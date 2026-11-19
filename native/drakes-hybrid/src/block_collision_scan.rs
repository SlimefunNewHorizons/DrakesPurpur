//! Horizon-inspired flat-memory 3D block collision scanner.
//! Block flags: 0 = air/passable, 1 = full solid cube [0..1], 2 = lower slab [0..0.5].

use crate::{ERR_ARG, ERR_RANGE};

pub const BLOCK_AIR: u8 = 0;
pub const BLOCK_FULL: u8 = 1;
pub const BLOCK_SLAB_LOWER: u8 = 2;

/// Scans a flat 3D grid of dimensions (sx, sy, sz) anchored at (origin_x, origin_y, origin_z)
/// indexed as `(y * sz + z) * sx + x`. Writes colliding world coordinates (x, y, z) into
/// `out_coords` (3 i32s per hit) and returns the number of colliding blocks.
pub fn scan_collisions(
    origin_x: i32, origin_y: i32, origin_z: i32,
    sx: usize, sy: usize, sz: usize,
    blocks: &[u8],
    ent_min_x: f64, ent_min_y: f64, ent_min_z: f64,
    ent_max_x: f64, ent_max_y: f64, ent_max_z: f64,
    out_coords: &mut [i32],
) -> i32 {
    let expected = sx.saturating_mul(sy).saturating_mul(sz);
    if blocks.len() < expected {
        return ERR_ARG;
    }
    let max_hits = out_coords.len() / 3;
    if max_hits == 0 {
        return ERR_RANGE;
    }

    let ix0 = (ent_min_x.floor() as i32 - origin_x).clamp(0, sx as i32) as usize;
    let iy0 = (ent_min_y.floor() as i32 - origin_y).clamp(0, sy as i32) as usize;
    let iz0 = (ent_min_z.floor() as i32 - origin_z).clamp(0, sz as i32) as usize;
    let ix1 = (ent_max_x.ceil() as i32 - origin_x).clamp(0, sx as i32) as usize;
    let iy1 = (ent_max_y.ceil() as i32 - origin_y).clamp(0, sy as i32) as usize;
    let iz1 = (ent_max_z.ceil() as i32 - origin_z).clamp(0, sz as i32) as usize;

    let mut hits = 0usize;
    for ly in iy0..iy1 {
        let wy = (origin_y + ly as i32) as f64;
        let y_base = ly * sz;
        for lz in iz0..iz1 {
            let wz = (origin_z + lz as i32) as f64;
            let row_base = (y_base + lz) * sx;
            for lx in ix0..ix1 {
                let flag = blocks[row_base + lx];
                if flag == BLOCK_AIR {
                    continue;
                }
                let wx = (origin_x + lx as i32) as f64;
                let top_y = if flag == BLOCK_SLAB_LOWER { wy + 0.5 } else { wy + 1.0 };
                let hit = (wx < ent_max_x)
                    & (wx + 1.0 > ent_min_x)
                    & (wy < ent_max_y)
                    & (top_y > ent_min_y)
                    & (wz < ent_max_z)
                    & (wz + 1.0 > ent_min_z);
                if hit {
                    if hits < max_hits {
                        let base = hits * 3;
                        out_coords[base] = origin_x + lx as i32;
                        out_coords[base + 1] = origin_y + ly as i32;
                        out_coords[base + 2] = origin_z + lz as i32;
                        hits += 1;
                    } else {
                        return hits as i32;
                    }
                }
            }
        }
    }
    hits as i32
}
