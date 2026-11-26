//! Batched Slimefun / Networks item-stack hashing and cargo route matching.
//! Processes an entire tick batch in one FFM call to avoid per-item JNI/FFM overhead.

pub const FLAG_IGNORE_AMOUNT: u8 = 1 << 0;
pub const FLAG_IGNORE_META: u8 = 1 << 1;

const FNV_OFFSET: u64 = 0xcbf2_9ce4_8422_2325;
const FNV_PRIME: u64 = 0x0100_0000_01b3;

#[inline(always)]
fn mix_u64(mut h: u64, v: u64) -> u64 {
    h ^= v;
    h = h.wrapping_mul(FNV_PRIME);
    h ^= h >> 33;
    h = h.wrapping_mul(0xff51_afd7_ed55_8ccd);
    h ^ (h >> 33)
}

/// Computes deterministic 64-bit composite keys for `n` item stacks in one pass.
pub fn batch_hash_items(
    material_ids: &[i32],
    amounts: &[i32],
    nbt_hashes: &[u64],
    flags: &[u8],
    out_hashes: &mut [u64],
) -> usize {
    let n = material_ids
        .len()
        .min(amounts.len())
        .min(nbt_hashes.len())
        .min(flags.len())
        .min(out_hashes.len());

    for i in 0..n {
        let f = flags[i];
        let mut h = mix_u64(FNV_OFFSET, material_ids[i] as u32 as u64);
        if (f & FLAG_IGNORE_META) == 0 {
            h = mix_u64(h, nbt_hashes[i]);
        }
        if (f & FLAG_IGNORE_AMOUNT) == 0 {
            h = mix_u64(h, amounts[i].max(0) as u64);
        }
        out_hashes[i] = h;
    }
    n
}

/// Matches source item stacks against destination filters ordered by priority (descending).
/// Writes `(src_idx, dst_idx, move_amount)` triples into `out_src`, `out_dst`, `out_amt`
/// up to `out_cap` routes, updating remaining capacities locally during the pass.
/// Returns the number of matched routes written.
pub fn batch_match_cargo_routes(
    src_keys: &[u64],
    src_amounts: &[i32],
    dst_keys: &[u64],
    dst_caps: &[i32],
    dst_priorities: &[i32],
    out_src: &mut [i32],
    out_dst: &mut [i32],
    out_amt: &mut [i32],
) -> usize {
    let ns = src_keys.len().min(src_amounts.len());
    let nd = dst_keys.len().min(dst_caps.len()).min(dst_priorities.len());
    let cap = out_src.len().min(out_dst.len()).min(out_amt.len());
    if ns == 0 || nd == 0 || cap == 0 {
        return 0;
    }

    // Sort destination indices by priority descending (stable on index for determinism).
    let mut order: Vec<usize> = (0..nd).collect();
    order.sort_by(|&a, &b| dst_priorities[b].cmp(&dst_priorities[a]).then_with(|| a.cmp(&b)));

    let mut rem_src: Vec<i32> = src_amounts[..ns].iter().map(|&a| a.max(0)).collect();
    let mut rem_dst: Vec<i32> = dst_caps[..nd].iter().map(|&c| c.max(0)).collect();
    let mut written = 0usize;

    'outer: for si in 0..ns {
        let mut avail = rem_src[si];
        if avail <= 0 {
            continue;
        }
        let key = src_keys[si];
        for &di in &order {
            if dst_keys[di] != key {
                continue;
            }
            let room = rem_dst[di];
            if room <= 0 {
                continue;
            }
            let moved = avail.min(room);
            out_src[written] = si as i32;
            out_dst[written] = di as i32;
            out_amt[written] = moved;
            written += 1;

            avail -= moved;
            rem_src[si] = avail;
            rem_dst[di] = room - moved;

            if written >= cap {
                break 'outer;
            }
            if avail <= 0 {
                break;
            }
        }
    }

    written
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn batch_hash_deterministic_and_respects_flags() {
        let mats = [10, 10, 10, 11];
        let amts = [1, 64, 64, 1];
        let nbts = [0xABCDu64, 0xABCDu64, 0x9999u64, 0xABCDu64];
        let flags = [FLAG_IGNORE_AMOUNT, FLAG_IGNORE_AMOUNT, FLAG_IGNORE_AMOUNT, FLAG_IGNORE_AMOUNT];
        let mut out = [0u64; 4];

        let n = batch_hash_items(&mats, &amts, &nbts, &flags, &mut out);
        assert_eq!(n, 4);
        assert_eq!(out[0], out[1], "ignore_amount must match across stack sizes");
        assert_ne!(out[0], out[2], "different NBT hash must differ");
        assert_ne!(out[0], out[3], "different material must differ");

        // Ignore both amount and meta
        let flags_no_meta = [FLAG_IGNORE_AMOUNT | FLAG_IGNORE_META; 4];
        let mut out2 = [0u64; 4];
        batch_hash_items(&mats, &amts, &nbts, &flags_no_meta, &mut out2);
        assert_eq!(out2[0], out2[2]);
    }

    #[test]
    fn batch_route_respects_priority_and_capacity() {
        let src_keys = [100u64, 200u64];
        let src_amts = [50, 10];
        // dst 0: key 100, cap 20, prio 1; dst 1: key 100, cap 40, prio 10; dst 2: key 200, cap 5, prio 5
        let dst_keys = [100u64, 100u64, 200u64];
        let dst_caps = [20, 40, 5];
        let dst_prio = [1, 10, 5];

        let mut os = [0i32; 8];
        let mut od = [0i32; 8];
        let mut oa = [0i32; 8];

        let count = batch_match_cargo_routes(
            &src_keys, &src_amts, &dst_keys, &dst_caps, &dst_prio, &mut os, &mut od, &mut oa,
        );
        assert_eq!(count, 3);
        // src 0 (50) -> dst 1 (prio 10, cap 40) = 40, then dst 0 (prio 1, cap 20) = 10
        assert_eq!((os[0], od[0], oa[0]), (0, 1, 40));
        assert_eq!((os[1], od[1], oa[1]), (0, 0, 10));
        // src 1 (10) -> dst 2 (cap 5) = 5
        assert_eq!((os[2], od[2], oa[2]), (1, 2, 5));
    }
}
