//! DrakesGuard — spatial entity hotspot & safe-mitigation analyzer (pure Rust, zero JVM state).
//!
//! Never marks protected entities or ItemFrames for deletion.

use std::collections::HashMap;

pub const KIND_OTHER: u8 = 0;
pub const KIND_MOB: u8 = 1;
pub const KIND_DISPOSABLE: u8 = 2;
pub const KIND_ITEM_FRAME: u8 = 3;

pub const ACTION_KEEP: u8 = 0;
pub const ACTION_THROTTLE_AI: u8 = 1;
pub const ACTION_CLEAN_DISPOSABLE: u8 = 2;

#[derive(Default)]
struct ChunkStats {
    total: i32,
    mobs: i32,
    disposables: i32,
    frames: i32,
    protected: i32,
}

/// Groups entities by `(chunk_x, chunk_z)`, marks per-entity mitigation actions in `out_actions`,
/// and writes up to `max_hotspots` 6-int records `[cx, cz, total, mobs, disposables, frames]`
/// sorted by severity descending into `out_hotspots`. Returns number of hotspots written.
#[allow(clippy::too_many_arguments)]
pub fn analyze_entities(
    chunk_x: &[i32],
    chunk_z: &[i32],
    kind: &[u8],
    protected: &[u8],
    mob_threshold: i32,
    item_threshold: i32,
    frame_threshold: i32,
    out_actions: &mut [u8],
    out_hotspots: &mut [i32],
) -> usize {
    let n = chunk_x.len();
    if n == 0
        || chunk_z.len() != n
        || kind.len() != n
        || protected.len() != n
        || out_actions.len() < n
    {
        return 0;
    }

    let mut chunks: HashMap<(i32, i32), ChunkStats> = HashMap::with_capacity((n / 8).max(16));
    for i in 0..n {
        let key = (chunk_x[i], chunk_z[i]);
        let st = chunks.entry(key).or_default();
        st.total += 1;
        if protected[i] != 0 {
            st.protected += 1;
        }
        match kind[i] {
            KIND_MOB => st.mobs += 1,
            KIND_DISPOSABLE => {
                if protected[i] == 0 {
                    st.disposables += 1;
                }
            }
            KIND_ITEM_FRAME => st.frames += 1,
            _ => {}
        }
    }

    // Track remaining throttle/clean budget per saturated chunk so we preserve baseline counts.
    let mut mob_seen: HashMap<(i32, i32), i32> = HashMap::new();
    for i in 0..n {
        out_actions[i] = ACTION_KEEP;
        if protected[i] != 0 {
            continue;
        }
        let key = (chunk_x[i], chunk_z[i]);
        let Some(st) = chunks.get(&key) else { continue };
        match kind[i] {
            KIND_MOB if mob_threshold > 0 && st.mobs > mob_threshold => {
                let seen = mob_seen.entry(key).or_insert(0);
                *seen += 1;
                if *seen > mob_threshold {
                    out_actions[i] = ACTION_THROTTLE_AI;
                }
            }
            KIND_DISPOSABLE if item_threshold > 0 && st.disposables > item_threshold => {
                out_actions[i] = ACTION_CLEAN_DISPOSABLE;
            }
            // ItemFrames are NEVER deleted; only reported via hotspot frames count.
            _ => {}
        }
    }

    let mut hotspots: Vec<((i32, i32), &ChunkStats, i32)> = chunks
        .iter()
        .filter_map(|(&key, st)| {
            let mob_over = if mob_threshold > 0 { (st.mobs - mob_threshold).max(0) } else { 0 };
            let item_over = if item_threshold > 0 { (st.disposables - item_threshold).max(0) } else { 0 };
            let frame_over = if frame_threshold > 0 { (st.frames - frame_threshold).max(0) } else { 0 };
            let score = mob_over * 3 + item_over * 2 + frame_over * 2;
            if score > 0 {
                Some((key, st, score))
            } else {
                None
            }
        })
        .collect();

    hotspots.sort_by(|a, b| b.2.cmp(&a.2).then_with(|| b.1.total.cmp(&a.1.total)));

    let max_records = out_hotspots.len() / 6;
    let written = hotspots.len().min(max_records);
    for (idx, ((cx, cz), st, _)) in hotspots.into_iter().take(written).enumerate() {
        let base = idx * 6;
        out_hotspots[base] = cx;
        out_hotspots[base + 1] = cz;
        out_hotspots[base + 2] = st.total;
        out_hotspots[base + 3] = st.mobs;
        out_hotspots[base + 4] = st.disposables;
        out_hotspots[base + 5] = st.frames;
    }
    written
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protected_entities_and_item_frames_are_never_deleted() {
        let cx = [1, 1, 1, 1, 1, 1];
        let cz = [2, 2, 2, 2, 2, 2];
        let kind = [KIND_MOB, KIND_MOB, KIND_MOB, KIND_DISPOSABLE, KIND_DISPOSABLE, KIND_ITEM_FRAME];
        // Entity 2 (mob) and entity 3 (disposable) are protected; entity 5 is an ItemFrame
        let prot = [0, 0, 1, 1, 0, 0];
        let mut actions = [0u8; 6];
        let mut hotspots = [0i32; 12];
        let h = analyze_entities(&cx, &cz, &kind, &prot, 1, 0, 0, &mut actions, &mut hotspots);
        assert_eq!(h, 1);
        assert_eq!(actions[0], ACTION_KEEP); // within baseline 1
        assert_eq!(actions[1], ACTION_THROTTLE_AI); // excess unprotected mob -> throttled, NOT deleted
        assert_eq!(actions[2], ACTION_KEEP); // protected mob -> untouched
        assert_eq!(actions[3], ACTION_KEEP); // protected item -> untouched
        assert_eq!(actions[5], ACTION_KEEP); // ItemFrame -> NEVER deleted
        assert_eq!(&hotspots[..6], &[1, 2, 6, 3, 1, 1]);
    }

    #[test]
    fn disposables_above_threshold_marked_clean_and_item_frames_flagged_as_hotspot() {
        let cx = [5, 5, 5, 9, 9];
        let cz = [5, 5, 5, 9, 9];
        let kind = [KIND_DISPOSABLE, KIND_DISPOSABLE, KIND_DISPOSABLE, KIND_ITEM_FRAME, KIND_ITEM_FRAME];
        let prot = [0, 0, 0, 0, 0];
        let mut actions = [0u8; 5];
        let mut hotspots = [0i32; 12];
        let h = analyze_entities(&cx, &cz, &kind, &prot, 40, 2, 1, &mut actions, &mut hotspots);
        assert_eq!(h, 2);
        assert_eq!(&actions[..3], &[ACTION_CLEAN_DISPOSABLE; 3]);
        assert_eq!(&actions[3..5], &[ACTION_KEEP, ACTION_KEEP]);
    }
}
