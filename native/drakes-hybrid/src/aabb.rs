//! Pillar C: Auto-vectorized SIMD (AVX2/NEON) batch AABB intersection queries over SoA buffers.

/// Checks `query` AABB against `n` SoA bounding boxes, writing matching indices into `out_idx`.
/// Returns number of hits written (clamped to `out_idx.len()`).
#[inline(never)]
pub fn intersects_batch(
    q_min_x: f64, q_min_y: f64, q_min_z: f64,
    q_max_x: f64, q_max_y: f64, q_max_z: f64,
    min_x: &[f64], min_y: &[f64], min_z: &[f64],
    max_x: &[f64], max_y: &[f64], max_z: &[f64],
    out_idx: &mut [i32],
) -> i32 {
    let n = min_x.len();
    let cap = out_idx.len();
    let mut hits = 0usize;

    // Branchless-friendly SoA loop for LLVM AVX2/NEON auto-vectorization
    for i in 0..n {
        let overlap = (min_x[i] < q_max_x)
            & (max_x[i] > q_min_x)
            & (min_y[i] < q_max_y)
            & (max_y[i] > q_min_y)
            & (min_z[i] < q_max_z)
            & (max_z[i] > q_min_z);
        if overlap && hits < cap {
            out_idx[hits] = i as i32;
            hits += 1;
        }
    }
    hits as i32
}
