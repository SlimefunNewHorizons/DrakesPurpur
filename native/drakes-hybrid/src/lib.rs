//! DrakesPurpur Horizon Hybrid Engine — native kernels, ABI v1.
//!
//! Design rules (docs/hybrid/ARQUITECTURA.md):
//! * Pure functions over caller-owned buffers. Rust never keeps pointers, never allocates memory the
//!   JVM must free, and never touches Minecraft objects. Java stays the only world authority.
//! * Every entry point returns `i32`: `>= 0` success (often a count), `< 0` an `ERR_*` code. Panics are
//!   caught (`catch_unwind`) and reported as `ERR_PANIC`; the Java side then falls back to Java.
//! * Semantics are vanilla-exact and cross-checked against a Java reference (`tools/hybrid-bench`).
//!
//! Algorithms are clean-room implementations of the vanilla-defined formats (SimpleBitStorage layout,
//! Ken Perlin's improved noise, Anvil region header). Horizon (MIT, horizon-rust/horizon) was used as a
//! design reference only; see docs/hybrid/HORIZON_COMPONENTS.md.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::slice;

pub mod bitstorage;
pub mod density;
pub mod noise;
pub mod region;
pub mod aabb;
pub mod anvil_async;
pub mod compress;
pub mod energy_net;

pub const ABI_VERSION: i32 = 1;

pub const ERR_NULL: i32 = -1;
pub const ERR_ARG: i32 = -2;
pub const ERR_RANGE: i32 = -3;
pub const ERR_FORMAT: i32 = -4;
pub const ERR_PANIC: i32 = -5;

fn guard(f: impl FnOnce() -> i32) -> i32 {
    catch_unwind(AssertUnwindSafe(f)).unwrap_or(ERR_PANIC)
}

/// # Safety
/// `p` must be null or valid for `n` reads of `T`.
unsafe fn rd<'a, T>(p: *const T, n: usize) -> Option<&'a [T]> {
    if p.is_null() { None } else { Some(slice::from_raw_parts(p, n)) }
}
/// # Safety
/// `p` must be null or valid for `n` writes of `T`, not aliased.
unsafe fn wr<'a, T>(p: *mut T, n: usize) -> Option<&'a mut [T]> {
    if p.is_null() { None } else { Some(slice::from_raw_parts_mut(p, n)) }
}

#[no_mangle]
pub extern "C" fn drakes_hybrid_abi_version() -> i32 {
    ABI_VERSION
}

/// Packs `count` values (one per u32) into SimpleBitStorage longs (no value spans two longs).
/// Returns the number of longs written, or an error. `out_longs` capacity must be >= `bitstorage::long_count`.
///
/// # Safety
/// Pointers must be valid for the given lengths.
#[no_mangle]
pub unsafe extern "C" fn drakes_bits_pack(bits: i32, count: i32, values: *const u32, out: *mut u64, out_cap: i32) -> i32 {
    guard(|| {
        if !(1..=32).contains(&bits) || count < 0 || out_cap < 0 { return ERR_ARG; }
        let (Some(v), Some(o)) = (rd(values, count as usize), wr(out, out_cap as usize)) else { return ERR_NULL };
        bitstorage::pack(bits as u32, v, o)
    })
}

/// Inverse of [`drakes_bits_pack`]. Returns `count` on success.
///
/// # Safety
/// Pointers must be valid for the given lengths.
#[no_mangle]
pub unsafe extern "C" fn drakes_bits_unpack(bits: i32, count: i32, longs: *const u64, longs_len: i32, out: *mut u32) -> i32 {
    guard(|| {
        if !(1..=32).contains(&bits) || count < 0 || longs_len < 0 { return ERR_ARG; }
        let (Some(l), Some(o)) = (rd(longs, longs_len as usize), wr(out, count as usize)) else { return ERR_NULL };
        bitstorage::unpack(bits as u32, l, o)
    })
}

/// Classic improved-noise sampling for `n` points. `perm` = 256 bytes (vanilla `ImprovedNoise.p`),
/// `xyz` = n*3 doubles (already multiplied by frequency, plus the noise origin offsets),
/// `out` = n doubles.
///
/// # Safety
/// Pointers must be valid for the given lengths.
#[no_mangle]
pub unsafe extern "C" fn drakes_noise_improved_batch(perm: *const u8, xyz: *const f64, n: i32, out: *mut f64) -> i32 {
    guard(|| {
        if n < 0 { return ERR_ARG; }
        let n = n as usize;
        let (Some(p), Some(c), Some(o)) = (rd(perm, 256), rd(xyz, n * 3), wr(out, n)) else { return ERR_NULL };
        noise::improved_batch(p, c, o);
        n as i32
    })
}

/// Validates an Anvil `.mca` header (8192 bytes) against `file_len` and fills `out_info`:
/// `[present_chunks, corrupt_entries, overlapping_entries, max_sector_used, newest_timestamp]` (5 x i64).
///
/// # Safety
/// Pointers must be valid for the given lengths.
#[no_mangle]
pub unsafe extern "C" fn drakes_region_scan_header(header: *const u8, header_len: i32, file_len: i64, out_info: *mut i64) -> i32 {
    guard(|| {
        if header_len < region::HEADER_BYTES as i32 || file_len < 0 { return ERR_ARG; }
        let (Some(h), Some(o)) = (rd(header, header_len as usize), wr(out_info, 5)) else { return ERR_NULL };
        let r = region::scan(h, file_len as u64);
        o.copy_from_slice(&r);
        r[0] as i32
    })
}

/// Crea un evaluador de NormalNoise a partir del volcado de Java (ver density.rs). Devuelve handle > 0 o un `ERR_*` (< 0).
///
/// # Safety
/// `buf` debe ser valido para `len` lecturas.
#[no_mangle]
pub unsafe extern "C" fn drakes_noise_create(buf: *const u8, len: i32) -> i64 {
    match catch_unwind(AssertUnwindSafe(|| {
        if len < 0 { return ERR_ARG as i64; }
        let Some(b) = rd(buf, len as usize) else { return ERR_NULL as i64 };
        density::create(b)
    })) { Ok(v) => v, Err(_) => ERR_PANIC as i64 }
}

#[no_mangle]
pub extern "C" fn drakes_noise_free(handle: i64) {
    let _ = catch_unwind(|| density::free(handle));
}

/// Evalua `n` puntos (xyz intercalados) del NormalNoise `handle`. Devuelve `n` o `ERR_*`.
///
/// # Safety
/// `xyz` valido para 3n lecturas y `out` para n escrituras.
#[no_mangle]
pub unsafe extern "C" fn drakes_normal_noise_batch(handle: i64, xyz: *const f64, n: i32, out: *mut f64) -> i32 {
    guard(|| {
        if n < 0 { return ERR_ARG; }
        let n = n as usize;
        let (Some(c), Some(o)) = (rd(xyz, n * 3), wr(out, n)) else { return ERR_NULL };
        let Some(noise) = density::get(handle) else { return ERR_ARG };
        noise.batch(c, o);
        n as i32
    })
}


// --- Horizon Hybrid Engine v0.2: 4 High-Gain Native Pillars (ABI v1 backwards-compatible) ---

#[no_mangle]
pub unsafe extern "C" fn drakes_zlib_compress(
    in_ptr: *const u8, in_len: i32, out_ptr: *mut u8, out_cap: i32, level: i32
) -> i32 {
    guard(|| {
        if in_len < 0 || out_cap < 0 || level < 0 { return ERR_ARG; }
        let (Some(i), Some(o)) = (rd(in_ptr, in_len as usize), wr(out_ptr, out_cap as usize)) else { return ERR_NULL };
        compress::zlib_compress(i, o, level as u32)
    })
}

#[no_mangle]
pub unsafe extern "C" fn drakes_zlib_decompress(
    in_ptr: *const u8, in_len: i32, out_ptr: *mut u8, out_cap: i32
) -> i32 {
    guard(|| {
        if in_len < 0 || out_cap < 0 { return ERR_ARG; }
        let (Some(i), Some(o)) = (rd(in_ptr, in_len as usize), wr(out_ptr, out_cap as usize)) else { return ERR_NULL };
        compress::zlib_decompress(i, o)
    })
}

#[no_mangle]
pub unsafe extern "C" fn drakes_energy_tick(
    count: i32,
    types: *const u8,
    stored: *mut i64,
    capacity: *const i64,
    rate: *const i64,
    net_ids: *const u32,
) -> i64 {
    if count <= 0 || types.is_null() || stored.is_null() || capacity.is_null() || rate.is_null() || net_ids.is_null() {
        return ERR_ARG as i64;
    }
    let n = count as usize;
    catch_unwind(AssertUnwindSafe(|| {
        let t = slice::from_raw_parts(types, n);
        let s = slice::from_raw_parts_mut(stored, n);
        let c = slice::from_raw_parts(capacity, n);
        let r = slice::from_raw_parts(rate, n);
        let ids = slice::from_raw_parts(net_ids, n);
        energy_net::tick_energy_networks(t, s, c, r, ids)
    })).unwrap_or(ERR_PANIC as i64)
}

#[no_mangle]
pub unsafe extern "C" fn drakes_aabb_intersects_batch(
    q_min_x: f64, q_min_y: f64, q_min_z: f64,
    q_max_x: f64, q_max_y: f64, q_max_z: f64,
    count: i32,
    min_x: *const f64, min_y: *const f64, min_z: *const f64,
    max_x: *const f64, max_y: *const f64, max_z: *const f64,
    out_idx: *mut i32, out_cap: i32,
) -> i32 {
    guard(|| {
        if count < 0 || out_cap < 0 { return ERR_ARG; }
        let n = count as usize;
        let (Some(x0), Some(y0), Some(z0), Some(x1), Some(y1), Some(z1), Some(out)) = (
            rd(min_x, n), rd(min_y, n), rd(min_z, n),
            rd(max_x, n), rd(max_y, n), rd(max_z, n),
            wr(out_idx, out_cap as usize)
        ) else { return ERR_NULL };
        aabb::intersects_batch(q_min_x, q_min_y, q_min_z, q_max_x, q_max_y, q_max_z, x0, y0, z0, x1, y1, z1, out)
    })
}

#[no_mangle]
pub unsafe extern "C" fn drakes_anvil_decompress_batch(
    raw_ptr: *const u8, raw_len: i32,
    chunk_count: i32, in_offsets: *const u32, in_lengths: *const u32,
    out_buf: *mut u8, out_buf_len: i32, out_slot_cap: i32,
    out_lengths: *mut i32, max_threads: i32,
) -> i32 {
    guard(|| {
        if raw_len < 0 || chunk_count < 0 || out_buf_len < 0 || out_slot_cap <= 0 || max_threads <= 0 {
            return ERR_ARG;
        }
        let c = chunk_count as usize;
        let (Some(raw), Some(offs), Some(lens), Some(obuf), Some(olens)) = (
            rd(raw_ptr, raw_len as usize),
            rd(in_offsets, c), rd(in_lengths, c),
            wr(out_buf, out_buf_len as usize), wr(out_lengths, c)
        ) else { return ERR_NULL };
        anvil_async::decompress_batch(raw, offs, lens, obuf, out_slot_cap as usize, olens, max_threads as usize)
    })
}

#[cfg(test)]
mod hybrid_v2_tests {
    use super::*;

    #[test]
    fn zlib_roundtrip_zero_copy() {
        let payload = b"DrakesCraft Chunk Section Data ".repeat(256);
        let mut comp = vec![0u8; payload.len() + 128];
        let c_len = compress::zlib_compress(&payload, &mut comp, 4);
        assert!(c_len > 0 && (c_len as usize) < payload.len());
        let mut decomp = vec![0u8; payload.len()];
        let d_len = compress::zlib_decompress(&comp[..c_len as usize], &mut decomp);
        assert_eq!(d_len as usize, payload.len());
        assert_eq!(&decomp[..], &payload[..]);
    }

    #[test]
    fn energy_network_500_nodes_conservation() {
        let n = 500;
        let mut types = vec![0u8; n];
        let mut stored = vec![0i64; n];
        let mut cap = vec![0i64; n];
        let mut rate = vec![0i64; n];
        let mut nets = vec![0u32; n];
        for i in 0..n {
            nets[i] = (i / 50) as u32;
            match i % 5 {
                0 => { types[i] = energy_net::NODE_GENERATOR; rate[i] = 1000; cap[i] = 0; }
                1 => { types[i] = energy_net::NODE_CAPACITOR; cap[i] = 50000; stored[i] = 2000; }
                _ => { types[i] = energy_net::NODE_CONSUMER; cap[i] = 10000; rate[i] = 300; }
            }
        }
        let transferred = energy_net::tick_energy_networks(&types, &mut stored, &cap, &rate, &nets);
        assert_eq!(transferred, 300 * 300); // 300 consumers * 300 J each
    }

    #[test]
    fn aabb_simd_batch_hits() {
        let min_x = [0.0, 5.0, 1.5, -2.0];
        let min_y = [0.0, 5.0, 1.5, -2.0];
        let min_z = [0.0, 5.0, 1.5, -2.0];
        let max_x = [2.0, 6.0, 3.0, -1.0];
        let max_y = [2.0, 6.0, 3.0, -1.0];
        let max_z = [2.0, 6.0, 3.0, -1.0];
        let mut out = [-1i32; 4];
        let hits = aabb::intersects_batch(1.0, 1.0, 1.0, 2.5, 2.5, 2.5, &min_x, &min_y, &min_z, &max_x, &max_y, &max_z, &mut out);
        assert_eq!(hits, 2);
        assert_eq!(&out[..2], &[0, 2]);
    }

    #[test]
    fn anvil_parallel_decompress_batch() {
        let mut raw = Vec::new();
        let mut offs = Vec::new();
        let mut lens = Vec::new();
        let payload = b"AnvilChunkNBTBlockStatesSection".repeat(64);
        let mut comp = vec![0u8; payload.len() + 64];
        let c_len = compress::zlib_compress(&payload, &mut comp, 4) as usize;
        for _ in 0..8 {
            offs.push(raw.len() as u32);
            let frame_len = (c_len + 1) as u32;
            raw.extend_from_slice(&frame_len.to_be_bytes());
            raw.push(2u8); // Zlib
            raw.extend_from_slice(&comp[..c_len]);
            lens.push((4 + 1 + c_len) as u32);
        }
        let slot = payload.len() + 64;
        let mut out_buf = vec![0u8; 8 * slot];
        let mut out_lens = vec![0i32; 8];
        let ok = anvil_async::decompress_batch(&raw, &offs, &lens, &mut out_buf, slot, &mut out_lens, 4);
        assert_eq!(ok, 8);
        for i in 0..8 {
            assert_eq!(out_lens[i] as usize, payload.len());
            assert_eq!(&out_buf[i * slot..i * slot + payload.len()], &payload[..]);
        }
    }
}
