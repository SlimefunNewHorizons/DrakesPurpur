use drakes_hybrid_ffi::{aabb, anvil_async, compress, energy_net};
use std::hint::black_box;
use std::time::Instant;

fn main() {
    // 1. Pillar A: Zero-Copy Zlib-rs Chunk Packet Compression (16 KB chunk section x 200 iterations)
    let chunk_data = b"MinecraftChunkSectionPaletteBlockStates1.21.11Purpur26.2DrakesCraft".repeat(250);
    let mut comp = vec![0u8; chunk_data.len() + 128];
    let mut decomp = vec![0u8; chunk_data.len()];
    let t0 = Instant::now();
    let mut c_len = 0usize;
    for _ in 0..200 {
        c_len = compress::zlib_compress(black_box(&chunk_data), &mut comp, 4) as usize;
        black_box(compress::zlib_decompress(black_box(&comp[..c_len]), &mut decomp));
    }
    let dt_zlib_us = t0.elapsed().as_micros() as f64 / 200.0;

    // 2. Pillar B: 500 Slimefun Machines Energy Network Tick (10,000 ticks)
    let n = 500;
    let mut types = vec![0u8; n];
    let mut stored = vec![0i64; n];
    let mut cap = vec![0i64; n];
    let mut rate = vec![0i64; n];
    let mut nets = vec![0u32; n];
    for i in 0..n {
        nets[i] = (i / 50) as u32;
        match i % 5 {
            0 => { types[i] = energy_net::NODE_GENERATOR; rate[i] = 1000; }
            1 => { types[i] = energy_net::NODE_CAPACITOR; cap[i] = 50000; stored[i] = 5000; }
            _ => { types[i] = energy_net::NODE_CONSUMER; cap[i] = 10000; rate[i] = 300; }
        }
    }
    let t1 = Instant::now();
    let mut xfer = 0i64;
    for _ in 0..10_000 {
        xfer += energy_net::tick_energy_networks(&types, &mut stored, &cap, &rate, &nets);
    }
    let dt_energy_us = t1.elapsed().as_micros() as f64 / 10_000.0;

    // 3. Pillar C: 30 Moving Entities AABB Batch Intersections (against 1000 world boxes, 10,000 queries)
    let m = 1000;
    let min_x: Vec<f64> = (0..m).map(|i| (i % 50) as f64 * 1.5).collect();
    let min_y: Vec<f64> = (0..m).map(|i| ((i / 50) % 20) as f64).collect();
    let min_z: Vec<f64> = (0..m).map(|i| (i % 30) as f64 * 1.2).collect();
    let max_x: Vec<f64> = min_x.iter().map(|v| v + 1.0).collect();
    let max_y: Vec<f64> = min_y.iter().map(|v| v + 1.8).collect();
    let max_z: Vec<f64> = min_z.iter().map(|v| v + 1.0).collect();
    let mut out = vec![0i32; 128];
    let t2 = Instant::now();
    let mut hits = 0i64;
    for q in 0..10_000 {
        let off = (q % 30) as f64;
        hits += aabb::intersects_batch(off, 0.0, off * 0.5, off + 3.0, 4.0, off * 0.5 + 3.0,
            &min_x, &min_y, &min_z, &max_x, &max_y, &max_z, &mut out) as i64;
    }
    let dt_aabb_us = t2.elapsed().as_micros() as f64 / 10_000.0;

    // 4. Pillar D: Parallel Anvil Chunk Batch Decompression (32 chunks: 1 thread vs 4 threads)
    let mut raw = Vec::new();
    let mut offs = Vec::new();
    let mut lens = Vec::new();
    for _ in 0..32 {
        offs.push(raw.len() as u32);
        let fl = (c_len + 1) as u32;
        raw.extend_from_slice(&fl.to_be_bytes());
        raw.push(2u8);
        raw.extend_from_slice(&comp[..c_len]);
        lens.push((4 + 1 + c_len) as u32);
    }
    let slot = chunk_data.len() + 64;
    let mut out_buf = vec![0u8; 32 * slot];
    let mut out_lens = vec![0i32; 32];
    let t3a = Instant::now();
    for _ in 0..50 {
        black_box(anvil_async::decompress_batch(&raw, &offs, &lens, &mut out_buf, slot, &mut out_lens, 1));
    }
    let dt_anvil_1t_us = t3a.elapsed().as_micros() as f64 / 50.0;
    let t3b = Instant::now();
    for _ in 0..50 {
        black_box(anvil_async::decompress_batch(&raw, &offs, &lens, &mut out_buf, slot, &mut out_lens, 4));
    }
    let dt_anvil_4t_us = t3b.elapsed().as_micros() as f64 / 50.0;

    println!("ZLIB_ROUNDTRIP_US={:.2} (ratio={:.1}%)", dt_zlib_us, (c_len as f64 / chunk_data.len() as f64) * 100.0);
    println!("ENERGY_500_NODES_US={:.2} (xfer={})", dt_energy_us, xfer);
    println!("AABB_1000_BOXES_US={:.2} (hits={})", dt_aabb_us, hits);
    println!("ANVIL_32CHUNKS_1T_US={:.2}", dt_anvil_1t_us);
    println!("ANVIL_32CHUNKS_4T_US={:.2} (speedup={:.2}x)", dt_anvil_4t_us, dt_anvil_1t_us / dt_anvil_4t_us);
}
