//! Ken Perlin's improved noise (the algorithm behind vanilla `ImprovedNoise.noise`), double precision,
//! batch form. The permutation table is supplied by Java so seeds/datapacks stay authoritative there.

#[inline]
fn fade(t: f64) -> f64 { t * t * t * (t * (t * 6.0 - 15.0) + 10.0) }
#[inline]
fn lerp(t: f64, a: f64, b: f64) -> f64 { a + t * (b - a) }

/// Vanilla `SimplexNoise.GRADIENT` table (also used by ImprovedNoise.gradDot).
const GRAD: [[i32; 3]; 16] = [
    [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0],
    [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
    [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1],
    [1, 1, 0], [0, -1, 1], [-1, 1, 0], [0, -1, -1],
];

#[inline]
fn grad_dot(h: u8, x: f64, y: f64, z: f64) -> f64 {
    let g = GRAD[(h & 15) as usize];
    g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z
}

#[inline]
fn floor(v: f64) -> i32 {
    // Mth.floor semantics
    let i = v as i32;
    if v < i as f64 { i - 1 } else { i }
}

#[inline(always)]
pub fn improved(p: &[u8], x: f64, y: f64, z: f64) -> f64 {
    let xf = floor(x);
    let yf = floor(y);
    let zf = floor(z);
    let xr = x - xf as f64;
    let yr = y - yf as f64;
    let zr = z - zf as f64;

    let xi = (xf & 0xFF) as usize;
    let yi = (yf & 0xFF) as usize;
    let zi = (zf & 0xFF) as usize;

    let x0 = p[xi] as usize;
    let x1 = p[(xi + 1) & 0xFF] as usize;

    let aa = p[(x0 + yi) & 0xFF] as usize;
    let ab = p[(x0 + yi + 1) & 0xFF] as usize;
    let ba = p[(x1 + yi) & 0xFF] as usize;
    let bb = p[(x1 + yi + 1) & 0xFF] as usize;

    let xr1 = xr - 1.0;
    let yr1 = yr - 1.0;
    let zr1 = zr - 1.0;

    let d000 = grad_dot(p[(aa + zi) & 0xFF], xr, yr, zr);
    let d100 = grad_dot(p[(ba + zi) & 0xFF], xr1, yr, zr);
    let d010 = grad_dot(p[(ab + zi) & 0xFF], xr, yr1, zr);
    let d110 = grad_dot(p[(bb + zi) & 0xFF], xr1, yr1, zr);
    let d001 = grad_dot(p[(aa + zi + 1) & 0xFF], xr, yr, zr1);
    let d101 = grad_dot(p[(ba + zi + 1) & 0xFF], xr1, yr, zr1);
    let d011 = grad_dot(p[(ab + zi + 1) & 0xFF], xr, yr1, zr1);
    let d111 = grad_dot(p[(bb + zi + 1) & 0xFF], xr1, yr1, zr1);

    let fx = fade(xr);
    let fy = fade(yr);
    let fz = fade(zr);

    let x00 = lerp(fx, d000, d100);
    let x10 = lerp(fx, d010, d110);
    let x01 = lerp(fx, d001, d101);
    let x11 = lerp(fx, d011, d111);
    lerp(fz, lerp(fy, x00, x10), lerp(fy, x01, x11))
}

pub fn improved_batch(p: &[u8], xyz: &[f64], out: &mut [f64]) {
    for (o, c) in out.iter_mut().zip(xyz.chunks_exact(3)) {
        *o = improved(p, c[0], c[1], c[2]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn perm() -> Vec<u8> {
        let mut p: Vec<u8> = (0..=255).collect();
        let mut s = 12345u64;
        for i in (1..256).rev() {
            s = s.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
            p.swap(i, ((s >> 33) as usize) % (i + 1));
        }
        p
    }
    #[test]
    fn zero_at_lattice_points_and_bounded() {
        let p = perm();
        assert_eq!(improved(&p, 3.0, 4.0, 5.0), 0.0);
        for i in 0..2000 {
            let v = improved(&p, i as f64 * 0.37, i as f64 * 0.11, -(i as f64) * 0.23);
            assert!(v.abs() <= 1.0 + 1e-9, "{v}");
        }
    }
    #[test]
    fn batch_equals_scalar() {
        let p = perm();
        let pts: Vec<f64> = (0..300).map(|i| i as f64 * 0.173 - 20.0).collect();
        let mut out = vec![0.0; 100];
        improved_batch(&p, &pts, &mut out);
        for i in 0..100 {
            assert_eq!(out[i], improved(&p, pts[i * 3], pts[i * 3 + 1], pts[i * 3 + 2]));
        }
    }
}
