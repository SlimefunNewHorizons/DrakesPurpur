//! Vanilla `SimpleBitStorage` layout: `64 / bits` values per long, values never span longs,
//! value `i` lives in long `i / vpl` at bit offset `(i % vpl) * bits`. Used by chunk sections
//! (PalettedContainer) and heightmaps.
use crate::ERR_RANGE;

#[inline]
pub fn values_per_long(bits: u32) -> usize { (64 / bits) as usize }

#[inline]
pub fn long_count(bits: u32, count: usize) -> usize {
    let vpl = values_per_long(bits);
    count.div_ceil(vpl)
}

pub fn pack(bits: u32, values: &[u32], out: &mut [u64]) -> i32 {
    let need = long_count(bits, values.len());
    if out.len() < need { return ERR_RANGE; }
    let vpl = values_per_long(bits);
    let mask: u64 = if bits == 32 { 0xFFFF_FFFF } else { (1u64 << bits) - 1 };
    for (word, chunk) in out[..need].iter_mut().zip(values.chunks(vpl)) {
        let mut w = 0u64;
        let mut shift = 0u32;
        for &v in chunk {
            // Vanilla `SimpleBitStorage(bits, size, int[])` masks (`values[i] & mask`); it never rejects.
            w |= ((v as u64) & mask) << shift;
            shift += bits;
        }
        *word = w;
    }
    need as i32
}

pub fn unpack(bits: u32, longs: &[u64], out: &mut [u32]) -> i32 {
    let vpl = values_per_long(bits);
    if longs.len() < long_count(bits, out.len()) { return ERR_RANGE; }
    let mask: u64 = if bits == 32 { 0xFFFF_FFFF } else { (1u64 << bits) - 1 };
    for (chunk, &w) in out.chunks_mut(vpl).zip(longs) {
        let mut w = w;
        for slot in chunk {
            *slot = (w & mask) as u32;
            w >>= bits;
        }
    }
    out.len() as i32
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn roundtrip_all_widths() {
        for bits in 1..=32u32 {
            let max = if bits == 32 { u32::MAX } else { (1u32 << bits) - 1 };
            let vals: Vec<u32> = (0..4096u64).map(|i| ((i * 2654435761) as u32) & max).collect();
            let mut l = vec![0u64; long_count(bits, vals.len())];
            assert_eq!(pack(bits, &vals, &mut l), l.len() as i32);
            let mut back = vec![0u32; vals.len()];
            assert_eq!(unpack(bits, &l, &mut back), 4096);
            assert_eq!(vals, back, "bits={bits}");
        }
    }
    #[test]
    fn vanilla_layout_4bit() {
        // 4 bits -> 16 values per long, first value in the low nibble.
        let mut l = [0u64; 1];
        pack(4, &[1, 2, 3], &mut l);
        assert_eq!(l[0], 0x321);
    }
    #[test]
    fn masks_like_vanilla_and_rejects_short_buffer() {
        let mut l = [0u64; 1];
        assert_eq!(pack(4, &[0x1F, 0xFF3], &mut l), 1); // 0x1F -> 0xF, 0xFF3 -> 0x3
        assert_eq!(l[0], 0x3F);
        assert_eq!(pack(4, &[0; 17], &mut l), ERR_RANGE);
    }
}
