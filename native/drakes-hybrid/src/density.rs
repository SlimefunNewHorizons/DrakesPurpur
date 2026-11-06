//! Evaluador nativo de `NormalNoise` de Mojang (ImprovedNoise -> PerlinNoise -> NormalNoise), la base de los
//! nodos Noise/ShiftedNoise/Shift* del grafo de density functions.
//!
//! Replica el ORDEN de operaciones de la fuente de Mojang (26.2) para poder demostrar paridad bit a bit:
//! f64 sin FMA, `Mth.floor` = `(int)Math.floor` (cast saturante, igual que `as i32`), aritmetica entera con wrap.
//! Las tablas (permutacion, offsets, amplitudes) las construye Java con su propio RandomSource: la semilla y los
//! datapacks siguen siendo autoridad de Java; Rust solo evalua. Todo es inmutable tras `create` => seguro entre hilos.
//!
//! Formato de serializacion (little endian), producido por `HybridDensity.export`:
//!   i32 version(=1) | f64 valueFactor | PerlinBlock(first) | PerlinBlock(second)
//!   PerlinBlock = i32 octaves | f64 lowestFreqInputFactor | f64 lowestFreqValueFactor | octaves x Octave
//!   Octave = u8 present | (si present) f64 amplitude | f64 xo | f64 yo | f64 zo | 256 x u8 p
use std::sync::{Arc, Mutex};

const GRADIENT: [[i32; 3]; 16] = [
    [1, 1, 0], [-1, 1, 0], [1, -1, 0], [-1, -1, 0], [1, 0, 1], [-1, 0, 1], [1, 0, -1], [-1, 0, -1],
    [0, 1, 1], [0, -1, 1], [0, 1, -1], [0, -1, -1], [1, 1, 0], [0, -1, 1], [-1, 1, 0], [0, -1, -1],
];
const INPUT_FACTOR: f64 = 1.0181268882175227;

struct Improved { xo: f64, yo: f64, zo: f64, p: [u8; 256] }
struct Octave { amplitude: f64, noise: Improved }
struct Perlin { levels: Vec<Option<Octave>>, lowest_in: f64, lowest_val: f64 }
pub struct Normal { first: Perlin, second: Perlin, value_factor: f64 }

#[inline] fn floor_i(v: f64) -> i32 { v.floor() as i32 }           // (int)Math.floor(v)
#[inline] fn floor_l(v: f64) -> i64 { v.floor() as i64 }           // (long)Math.floor(v)
#[inline] fn smooth(x: f64) -> f64 { x * x * x * (x * (x * 6.0 - 15.0) + 10.0) }
#[inline] fn lerp(a: f64, p0: f64, p1: f64) -> f64 { p0 + a * (p1 - p0) }
#[inline] fn lerp2(a1: f64, a2: f64, x00: f64, x10: f64, x01: f64, x11: f64) -> f64 { lerp(a2, lerp(a1, x00, x10), lerp(a1, x01, x11)) }
#[inline]
#[allow(clippy::too_many_arguments)]
fn lerp3(a1: f64, a2: f64, a3: f64, x000: f64, x100: f64, x010: f64, x110: f64, x001: f64, x101: f64, x011: f64, x111: f64) -> f64 {
    lerp(a3, lerp2(a1, a2, x000, x100, x010, x110), lerp2(a1, a2, x001, x101, x011, x111))
}
#[inline] fn dot(g: &[i32; 3], x: f64, y: f64, z: f64) -> f64 { g[0] as f64 * x + g[1] as f64 * y + g[2] as f64 * z }
#[inline] fn wrap(x: f64) -> f64 { x - floor_l(x / 3.3554432E7 + 0.5) as f64 * 3.3554432E7 }

impl Improved {
    #[inline] fn p(&self, i: i32) -> i32 { self.p[(i & 0xFF) as usize] as i32 }
    #[inline] fn grad(&self, hash: i32, x: f64, y: f64, z: f64) -> f64 { dot(&GRADIENT[(hash & 15) as usize], x, y, z) }
    fn noise(&self, ix: f64, iy: f64, iz: f64, y_scale: f64, y_fudge: f64) -> f64 {
        let x = ix + self.xo; let y = iy + self.yo; let z = iz + self.zo;
        let xf = floor_i(x); let yf = floor_i(y); let zf = floor_i(z);
        let xr = x - xf as f64; let yr = y - yf as f64; let zr = z - zf as f64;
        let yr_fudge = if y_scale != 0.0 {
            let limit = if y_fudge >= 0.0 && y_fudge < yr { y_fudge } else { yr };
            floor_i(limit / y_scale + 1.0E-7_f32 as f64) as f64 * y_scale
        } else { 0.0 };
        self.sample(xf, yf, zf, xr, yr - yr_fudge, zr, yr)
    }
    #[allow(clippy::too_many_arguments)]
    fn sample(&self, x: i32, y: i32, z: i32, xr: f64, yr: f64, zr: f64, yr_orig: f64) -> f64 {
        let x0 = self.p(x);
        let x1 = self.p(x.wrapping_add(1));
        let xy00 = self.p(x0.wrapping_add(y));
        let xy01 = self.p(x0.wrapping_add(y).wrapping_add(1));
        let xy10 = self.p(x1.wrapping_add(y));
        let xy11 = self.p(x1.wrapping_add(y).wrapping_add(1));
        let d000 = self.grad(self.p(xy00.wrapping_add(z)), xr, yr, zr);
        let d100 = self.grad(self.p(xy10.wrapping_add(z)), xr - 1.0, yr, zr);
        let d010 = self.grad(self.p(xy01.wrapping_add(z)), xr, yr - 1.0, zr);
        let d110 = self.grad(self.p(xy11.wrapping_add(z)), xr - 1.0, yr - 1.0, zr);
        let d001 = self.grad(self.p(xy00.wrapping_add(z).wrapping_add(1)), xr, yr, zr - 1.0);
        let d101 = self.grad(self.p(xy10.wrapping_add(z).wrapping_add(1)), xr - 1.0, yr, zr - 1.0);
        let d011 = self.grad(self.p(xy01.wrapping_add(z).wrapping_add(1)), xr, yr - 1.0, zr - 1.0);
        let d111 = self.grad(self.p(xy11.wrapping_add(z).wrapping_add(1)), xr - 1.0, yr - 1.0, zr - 1.0);
        let xa = smooth(xr); let ya = smooth(yr_orig); let za = smooth(zr);
        lerp3(xa, ya, za, d000, d100, d010, d110, d001, d101, d011, d111)
    }
}

impl Perlin {
    fn value(&self, x: f64, y: f64, z: f64, y_scale: f64, y_fudge: f64) -> f64 {
        let mut value = 0.0;
        let mut factor = self.lowest_in;
        let mut vf = self.lowest_val;
        for lvl in &self.levels {
            if let Some(o) = lvl {
                let nv = o.noise.noise(wrap(x * factor), wrap(y * factor), wrap(z * factor), y_scale * factor, y_fudge * factor);
                value += o.amplitude * nv * vf;
            }
            factor *= 2.0;
            vf /= 2.0;
        }
        value
    }
}

impl Normal {
    pub fn value(&self, x: f64, y: f64, z: f64) -> f64 {
        let x2 = x * INPUT_FACTOR; let y2 = y * INPUT_FACTOR; let z2 = z * INPUT_FACTOR;
        (self.first.value(x, y, z, 0.0, 0.0) + self.second.value(x2, y2, z2, 0.0, 0.0)) * self.value_factor
    }
    pub fn batch(&self, xyz: &[f64], out: &mut [f64]) {
        for (o, c) in out.iter_mut().zip(xyz.chunks_exact(3)) { *o = self.value(c[0], c[1], c[2]); }
    }

    pub fn parse(buf: &[u8]) -> Option<Normal> {
        let mut r = Reader { b: buf, i: 0 };
        if r.i32()? != 1 { return None; }
        let value_factor = r.f64()?;
        let first = r.perlin()?;
        let second = r.perlin()?;
        if r.i != buf.len() { return None; }
        Some(Normal { first, second, value_factor })
    }
}

struct Reader<'a> { b: &'a [u8], i: usize }
impl Reader<'_> {
    fn take(&mut self, n: usize) -> Option<&[u8]> { let s = self.b.get(self.i..self.i.checked_add(n)?)?; self.i += n; Some(s) }
    fn i32(&mut self) -> Option<i32> { Some(i32::from_le_bytes(self.take(4)?.try_into().ok()?)) }
    fn f64(&mut self) -> Option<f64> { Some(f64::from_le_bytes(self.take(8)?.try_into().ok()?)) }
    fn perlin(&mut self) -> Option<Perlin> {
        let n = self.i32()?;
        if !(1..=64).contains(&n) { return None; }
        let lowest_in = self.f64()?; let lowest_val = self.f64()?;
        let mut levels = Vec::with_capacity(n as usize);
        for _ in 0..n {
            if self.take(1)?[0] == 0 { levels.push(None); continue; }
            let amplitude = self.f64()?; let xo = self.f64()?; let yo = self.f64()?; let zo = self.f64()?;
            let mut p = [0u8; 256]; p.copy_from_slice(self.take(256)?);
            levels.push(Some(Octave { amplitude, noise: Improved { xo, yo, zo, p } }));
        }
        Some(Perlin { levels, lowest_in, lowest_val })
    }
}

// ---- registro de manejadores (inmutables, compartidos entre hilos) ----
static REGISTRY: Mutex<Vec<Option<Arc<Normal>>>> = Mutex::new(Vec::new());

pub fn create(buf: &[u8]) -> i64 {
    let Some(n) = Normal::parse(buf) else { return crate::ERR_FORMAT as i64 };
    let mut reg = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
    reg.push(Some(Arc::new(n)));
    reg.len() as i64 // handle = indice + 1
}
pub fn get(h: i64) -> Option<Arc<Normal>> {
    let reg = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
    reg.get((h as usize).checked_sub(1)?)?.clone()
}
pub fn free(h: i64) {
    let mut reg = REGISTRY.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(slot) = (h as usize).checked_sub(1).and_then(|i| reg.get_mut(i)) { *slot = None; }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn blob(octs: usize) -> Vec<u8> {
        let mut v = Vec::new();
        v.extend(1i32.to_le_bytes()); v.extend(0.5f64.to_le_bytes());
        for _ in 0..2 {
            v.extend((octs as i32).to_le_bytes()); v.extend(1.0f64.to_le_bytes()); v.extend(0.6f64.to_le_bytes());
            for o in 0..octs {
                v.push(1); v.extend(1.0f64.to_le_bytes());
                for k in 0..3 { v.extend((17.25f64 + o as f64 + k as f64).to_le_bytes()); }
                let mut p: Vec<u8> = (0..=255).collect(); p.rotate_left(o * 7 + 3); v.extend(p);
            }
        }
        v
    }
    #[test] fn parse_rejects_garbage_and_truncation() {
        let b = blob(2); assert!(Normal::parse(&b).is_some());
        assert!(Normal::parse(&b[..b.len() - 1]).is_none());
        let mut bad = b.clone(); bad[0] = 9; assert!(Normal::parse(&bad).is_none());
        let mut extra = b.clone(); extra.push(0); assert!(Normal::parse(&extra).is_none());
    }
    #[test] fn deterministic_finite_and_batch_equals_scalar() {
        let n = Normal::parse(&blob(3)).unwrap();
        let pts: Vec<f64> = (0..900).map(|i| (i as f64) * 3.7 - 1500.0).collect();
        let mut out = vec![0.0; 300]; n.batch(&pts, &mut out);
        for i in 0..300 { let s = n.value(pts[i*3], pts[i*3+1], pts[i*3+2]); assert_eq!(out[i].to_bits(), s.to_bits()); assert!(s.is_finite()); }
    }
    #[test] fn handles_extreme_inputs_without_panic() {
        let n = Normal::parse(&blob(2)).unwrap();
        for v in [f64::NAN, f64::INFINITY, f64::NEG_INFINITY, 1e300, -1e300, 3.3554432E7, -0.0] { let _ = n.value(v, v, v); }
    }
    #[test] fn handle_lifecycle() {
        let h = create(&blob(1)); assert!(h > 0 && get(h).is_some());
        free(h); assert!(get(h).is_none()); assert!(get(0).is_none() && get(-5).is_none());
        assert!(create(&[1, 2, 3]) < 0);
    }
}
