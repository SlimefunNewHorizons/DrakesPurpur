//! Horizon-inspired batch ItemEntity pickup & drop-merge resolver.

use crate::ERR_ARG;

/// Matches dropped items (SoA positions + pickup_delay) against players (SoA positions)
/// within `pickup_radius_sq`. Writes `(item_idx, player_idx)` pairs into `out_pairs` (2 i32s per match).
/// Each item is claimed by at most one player (nearest eligible).
pub fn resolve_pickups(
    item_x: &[f64], item_y: &[f64], item_z: &[f64], item_delay: &[i32],
    player_x: &[f64], player_y: &[f64], player_z: &[f64],
    pickup_radius_sq: f64,
    out_pairs: &mut [i32],
) -> i32 {
    let n_items = item_x.len();
    let n_players = player_x.len();
    if n_items != item_y.len() || n_items != item_z.len() || n_items != item_delay.len()
        || n_players != player_y.len() || n_players != player_z.len()
    {
        return ERR_ARG;
    }
    let max_pairs = out_pairs.len() / 2;
    let mut matches = 0usize;

    for i in 0..n_items {
        if item_delay[i] > 0 {
            continue;
        }
        let ix = item_x[i];
        let iy = item_y[i];
        let iz = item_z[i];
        let mut best_p: i32 = -1;
        let mut best_d2 = pickup_radius_sq;

        for p in 0..n_players {
            let dx = ix - player_x[p];
            let dy = iy - player_y[p];
            let dz = iz - player_z[p];
            let d2 = dx * dx + dy * dy + dz * dz;
            if d2 <= best_d2 {
                best_d2 = d2;
                best_p = p as i32;
            }
        }
        if best_p >= 0 && matches < max_pairs {
            out_pairs[matches * 2] = i as i32;
            out_pairs[matches * 2 + 1] = best_p;
            matches += 1;
        }
    }
    matches as i32
}
