//! Horizon-inspired Piston Movement State Machine (vanilla 12-block limit, slime/honey rules).
//! Block kinds: 0=AIR, 1=NORMAL, 2=IMMOVABLE, 3=DESTROY, 4=SLIME, 5=HONEY.

use crate::ERR_ARG;

pub const P_AIR: u8 = 0;
pub const P_NORMAL: u8 = 1;
pub const P_IMMOVABLE: u8 = 2;
pub const P_DESTROY: u8 = 3;
pub const P_SLIME: u8 = 4;
pub const P_HONEY: u8 = 5;

pub const MAX_PUSH_BLOCKS: usize = 12;

fn is_sticky(k: u8) -> bool {
    k == P_SLIME || k == P_HONEY
}

fn can_stick(a: u8, b: u8) -> bool {
    if a == P_SLIME && b == P_HONEY { return false; }
    if a == P_HONEY && b == P_SLIME { return false; }
    is_sticky(a) || is_sticky(b)
}

/// Resolves a piston push starting at `(start_x, start_y, start_z)` (the block directly in front
/// of the piston head) in direction `(dx, dy, dz)` inside a local grid `(sx, sy, sz)`.
/// Returns:
///   `> 0`: number of blocks to move (written into `out_move` as flat indices),
///   `0`: push is blocked (immovable or >12 blocks) or nothing to move,
///   `< 0`: error code.
/// Also writes destroyed block flat indices into `out_destroy` and their count into `*out_destroy_count`.
pub fn resolve_push(
    sx: usize, sy: usize, sz: usize,
    grid: &[u8],
    start_x: i32, start_y: i32, start_z: i32,
    dx: i32, dy: i32, dz: i32,
    out_move: &mut [i32],
    out_destroy: &mut [i32],
    out_destroy_count: &mut i32,
) -> i32 {
    if grid.len() < sx * sy * sz || (dx.abs() + dy.abs() + dz.abs()) != 1 {
        return ERR_ARG;
    }
    *out_destroy_count = 0;
    let in_bounds = |x: i32, y: i32, z: i32| -> bool {
        x >= 0 && y >= 0 && z >= 0 && (x as usize) < sx && (y as usize) < sy && (z as usize) < sz
    };
    let idx = |x: i32, y: i32, z: i32| -> usize {
        ((y as usize) * sz + (z as usize)) * sx + (x as usize)
    };

    if !in_bounds(start_x, start_y, start_z) {
        return 0;
    }
    let first_kind = grid[idx(start_x, start_y, start_z)];
    if first_kind == P_AIR {
        return 0;
    }
    if first_kind == P_IMMOVABLE {
        return 0;
    }
    if first_kind == P_DESTROY {
        if !out_destroy.is_empty() {
            out_destroy[0] = idx(start_x, start_y, start_z) as i32;
            *out_destroy_count = 1;
        }
        return 0;
    }

    let mut to_move: Vec<(i32, i32, i32)> = Vec::with_capacity(MAX_PUSH_BLOCKS);
    let mut to_destroy: Vec<usize> = Vec::new();
    let mut queue: Vec<(i32, i32, i32)> = Vec::with_capacity(16);
    queue.push((start_x, start_y, start_z));

    let mut head = 0usize;
    while head < queue.len() {
        let (cx, cy, cz) = queue[head];
        head += 1;

        // Walk forward along push line from (cx, cy, cz)
        let mut lx = cx;
        let mut ly = cy;
        let mut lz = cz;
        loop {
            if !in_bounds(lx, ly, lz) {
                return 0; // hit region boundary -> blocked
            }
            let k = grid[idx(lx, ly, lz)];
            if k == P_AIR {
                break;
            }
            if k == P_IMMOVABLE {
                return 0;
            }
            if k == P_DESTROY {
                let di = idx(lx, ly, lz);
                if !to_destroy.contains(&di) {
                    to_destroy.push(di);
                }
                break;
            }
            if !to_move.contains(&(lx, ly, lz)) {
                if to_move.len() >= MAX_PUSH_BLOCKS {
                    return 0; // > 12 blocks -> blocked
                }
                to_move.push((lx, ly, lz));
                // Sticky side neighbors
                if is_sticky(k) {
                    for (nx, ny, nz) in [
                        (lx + 1, ly, lz), (lx - 1, ly, lz),
                        (lx, ly + 1, lz), (lx, ly - 1, lz),
                        (lx, ly, lz + 1), (lx, ly, lz - 1),
                    ] {
                        if in_bounds(nx, ny, nz) {
                            let nk = grid[idx(nx, ny, nz)];
                            if nk != P_AIR && nk != P_IMMOVABLE && nk != P_DESTROY && can_stick(k, nk) {
                                if !to_move.contains(&(nx, ny, nz)) && !queue.contains(&(nx, ny, nz)) {
                                    queue.push((nx, ny, nz));
                                }
                            }
                        }
                    }
                }
            }
            lx += dx;
            ly += dy;
            lz += dz;
        }
    }

    // Sort back-to-front along push direction so Java can move without overwriting
    to_move.sort_by_key(|&(x, y, z)| -(x * dx + y * dy + z * dz));

    let move_n = to_move.len().min(out_move.len());
    for i in 0..move_n {
        let (x, y, z) = to_move[i];
        out_move[i] = idx(x, y, z) as i32;
    }
    let dest_n = to_destroy.len().min(out_destroy.len());
    for i in 0..dest_n {
        out_destroy[i] = to_destroy[i] as i32;
    }
    *out_destroy_count = dest_n as i32;
    move_n as i32
}
