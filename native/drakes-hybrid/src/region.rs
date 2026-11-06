//! Read-only Anvil (.mca) header analysis: 1024 location entries (3-byte sector offset + 1-byte
//! sector count) then 1024 timestamps. This never writes; it is a diagnostics/safety check used before
//! any coordinated region work (docs/hybrid/ARQUITECTURA.md, "no two writers").
pub const HEADER_BYTES: usize = 8192;
pub const SECTOR: u64 = 4096;

/// -> `[present, corrupt, overlapping, max_sector_used, newest_timestamp]`
pub fn scan(h: &[u8], file_len: u64) -> [i64; 5] {
    let sectors_in_file = file_len.div_ceil(SECTOR);
    let (mut present, mut corrupt, mut overlap, mut max_s, mut newest) = (0i64, 0i64, 0i64, 0i64, 0i64);
    let mut owner = vec![false; sectors_in_file.max(2) as usize];
    owner[0] = true;
    owner[1] = true;
    for i in 0..1024 {
        let e = &h[i * 4..i * 4 + 4];
        let off = ((e[0] as u64) << 16) | ((e[1] as u64) << 8) | e[2] as u64;
        let cnt = e[3] as u64;
        if off == 0 && cnt == 0 { continue; }
        present += 1;
        if off < 2 || cnt == 0 || off + cnt > sectors_in_file {
            corrupt += 1;
            continue;
        }
        let mut bad = false;
        for s in off..off + cnt {
            if owner[s as usize] { bad = true; }
            owner[s as usize] = true;
        }
        if bad { overlap += 1; }
        max_s = max_s.max((off + cnt - 1) as i64);
        let t = &h[4096 + i * 4..4096 + i * 4 + 4];
        newest = newest.max(u32::from_be_bytes([t[0], t[1], t[2], t[3]]) as i64);
    }
    [present, corrupt, overlap, max_s, newest]
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn detects_corrupt_and_overlap() {
        let mut h = vec![0u8; HEADER_BYTES];
        let set = |h: &mut Vec<u8>, i: usize, off: u32, cnt: u8| {
            h[i * 4] = (off >> 16) as u8; h[i * 4 + 1] = (off >> 8) as u8; h[i * 4 + 2] = off as u8; h[i * 4 + 3] = cnt;
        };
        set(&mut h, 0, 2, 2); // ok: sectors 2..3
        set(&mut h, 1, 3, 1); // overlaps sector 3
        set(&mut h, 2, 100, 1); // beyond file
        set(&mut h, 3, 4, 1); // ok
        h[4096 + 12..4096 + 16].copy_from_slice(&777u32.to_be_bytes());
        let r = scan(&h, 5 * 4096);
        assert_eq!(r, [4, 1, 1, 4, 777]);
    }
}
