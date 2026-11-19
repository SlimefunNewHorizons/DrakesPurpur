//! Pillar D: Parallel Anvil chunk batch decompression using scoped native worker threads.
use crate::compress::{gzip_decompress, zlib_decompress};
use crate::{ERR_ARG, ERR_FORMAT, ERR_RANGE};
use std::thread;

/// Decompresses a batch of Anvil-framed chunks (4-byte BE length + 1-byte type: 1=GZip, 2=Zlib, 3=Raw)
/// in parallel across up to `max_threads` worker threads.
pub fn decompress_batch(
    raw_region: &[u8],
    in_offsets: &[u32],
    in_lengths: &[u32],
    out_buf: &mut [u8],
    out_slot_cap: usize,
    out_lengths: &mut [i32],
    max_threads: usize,
) -> i32 {
    let count = in_offsets.len();
    if count != in_lengths.len() || count != out_lengths.len() || out_slot_cap == 0 {
        return ERR_ARG;
    }
    if out_buf.len() < count.saturating_mul(out_slot_cap) {
        return ERR_RANGE;
    }
    if count == 0 {
        return 0;
    }

    let workers = max_threads.clamp(1, 8).min(count);
    let chunks_per_worker = (count + workers - 1) / workers;

    let mut out_slices: Vec<&mut [u8]> = out_buf
        .chunks_mut(out_slot_cap)
        .take(count)
        .collect();
    let mut len_slices: Vec<&mut [i32]> = out_lengths
        .chunks_mut(chunks_per_worker)
        .collect();

    // Split out_slices into worker groups
    let mut out_groups: Vec<Vec<&mut [u8]>> = Vec::with_capacity(len_slices.len());
    let mut drain = out_slices.into_iter();
    for g in &len_slices {
        let mut v = Vec::with_capacity(g.len());
        for _ in 0..g.len() {
            if let Some(s) = drain.next() {
                v.push(s);
            }
        }
        out_groups.push(v);
    }

    let ok_count: usize = thread::scope(|s| {
        let mut handles = Vec::with_capacity(len_slices.len());
        for (w_idx, (lens_out, mut bufs_out)) in len_slices.into_iter().zip(out_groups.into_iter()).enumerate() {
            let start = w_idx * chunks_per_worker;
            handles.push(s.spawn(move || {
                let mut ok = 0usize;
                for local_i in 0..lens_out.len() {
                    let idx = start + local_i;
                    let off = in_offsets[idx] as usize;
                    let len = in_lengths[idx] as usize;
                    if off + len > raw_region.len() || len < 5 {
                        lens_out[local_i] = ERR_FORMAT;
                        continue;
                    }
                    let frame = &raw_region[off..off + len];
                    let comp_type = frame[4];
                    let payload = &frame[5..];
                    let dst = &mut bufs_out[local_i];
                    let rc = match comp_type {
                        1 => gzip_decompress(payload, dst),
                        2 => zlib_decompress(payload, dst),
                        3 => {
                            if payload.len() > dst.len() {
                                ERR_RANGE
                            } else {
                                dst[..payload.len()].copy_from_slice(payload);
                                payload.len() as i32
                            }
                        }
                        _ => ERR_FORMAT,
                    };
                    lens_out[local_i] = rc;
                    if rc >= 0 {
                        ok += 1;
                    }
                }
                ok
            }));
        }
        handles.into_iter().map(|h| h.join().unwrap_or(0)).sum()
    });

    ok_count as i32
}
