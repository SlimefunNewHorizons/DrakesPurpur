//! Pillar B: Flat-memory Slimefun / StarSuites energy network graph ticker.
//! Node types: 0 = Generator, 1 = Capacitor, 2 = Consumer.

pub const NODE_GENERATOR: u8 = 0;
pub const NODE_CAPACITOR: u8 = 1;
pub const NODE_CONSUMER: u8 = 2;

/// Ticks a flat contiguous array of energy nodes grouped by `net_ids`.
/// Updates `stored` in-place and returns total energy transferred across all networks.
pub fn tick_energy_networks(
    types: &[u8],
    stored: &mut [i64],
    capacity: &[i64],
    rate: &[i64],
    net_ids: &[u32],
) -> i64 {
    let n = types.len();
    if n == 0 {
        return 0;
    }
    let max_net = net_ids.iter().copied().max().unwrap_or(0) as usize;
    let mut supply = vec![0i64; max_net + 1];

    // Pass 1: Harvest generation + available capacitor charge into flat network pool
    for i in 0..n {
        let net = net_ids[i] as usize;
        match types[i] {
            NODE_GENERATOR => {
                supply[net] = supply[net].saturating_add(rate[i].max(0));
            }
            NODE_CAPACITOR => {
                let avail = stored[i].max(0);
                supply[net] = supply[net].saturating_add(avail);
                stored[i] = 0;
            }
            _ => {}
        }
    }

    let mut total_transferred = 0i64;

    // Pass 2: Satisfy consumers up to their capacity & rate
    for i in 0..n {
        if types[i] == NODE_CONSUMER {
            let net = net_ids[i] as usize;
            let need = (capacity[i] - stored[i]).max(0).min(rate[i].max(0));
            let give = need.min(supply[net]);
            if give > 0 {
                stored[i] += give;
                supply[net] -= give;
                total_transferred += give;
            }
        }
    }

    // Pass 3: Store surplus back into capacitors
    for i in 0..n {
        if types[i] == NODE_CAPACITOR {
            let net = net_ids[i] as usize;
            let room = (capacity[i] - stored[i]).max(0);
            let put = room.min(supply[net]);
            if put > 0 {
                stored[i] += put;
                supply[net] -= put;
            }
        }
    }

    total_transferred
}
