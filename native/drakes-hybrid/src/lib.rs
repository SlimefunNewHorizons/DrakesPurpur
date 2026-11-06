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
