# DrakesPurpur 26.2 - Performance, network and startup report

Scope: ver/26.2 engine, run on Nova (powerful) and Star VPS (8 cores, 6 GB, modest). Production stays on 1.21.11.
Symptoms investigated: high ping on the VPS test, slow startups. Facts below were checked against this repo
(`purpur-server/src/minecraft`, `paper-server`) and GitHub license metadata (2026-10-28). Not legal advice.

## 1. Findings in our own code (biggest wins)

| Finding | Effect | Status |
|---|---|---|
| `DrakesBackupManager` ran `7z a -mx=3 .` with all cores at normal priority, 1 h after boot, with stdout piped to nobody (buffer fills, 7z can block forever) | CPU/IO starvation of the tick and Netty threads on 8 cores = ping spikes; stuck backup process | Fixed (nice 19, ionice idle, `-mmt=2`, no pipe, timeout, deferral on high MSPT, fixed hour) |
| No in-engine measurement artifact for before/after tests | Cannot compare configs reproducibly | Added `drakes-data/perf-status.json` |
| No load response: view/simulation distance fixed under sustained overload | MSPT and outbound traffic stay high | Added optional load governor (off by default) |
| `/drakes gc` calls `System.gc()` (stop-the-world on G1) | Tick stall if used on a live server | Documented; avoid during play |
| `RyzenWorkerPool` parallelism = cores-2 (6 threads on 8 cores) competes with main/Netty on the VPS | Contention | Documented; keep usage off the tick path on the VPS |
| `settings.tps-catchup` defaults to true in Purpur | After a lag spike the server runs extra ticks back to back, amplifying jitter | Recommend `false` on VPS |

## 2. Candidate matrix

Engine license: Purpur is MIT, but the built engine incorporates Paper/Spigot (GPL-3.0, with LGPL/MIT parts), so the distributed
combination is effectively GPL-3.0. LGPL-3.0, MIT, Apache-2.0 code can be incorporated with attribution; GPL-3.0 patches
(Pufferfish, Folia, Canvas) are compatible with the combined work only with source availability (we publish) and headers kept.
Attribution goes in `NOTICE`. "In 26.2" was checked in the code unless marked (U = unverified).

| Candidate | License | Already in 26.2? | Applies to 26.2 | Expected gain | Risk | Decision |
|---|---|---|---|---|---|---|
| Lithium (CaffeineMC) | LGPL-3.0 | Partly (Moonrise/Paper cover chunk, entity lookups); the rest (block-entity, hopper, AI) not | Mod mixins, need manual port per system | MSPT medium | Medium-high (many small behaviour changes, Slimefun interplay) | Defer; port individual systems later with NOTICE |
| Starlight / ScalableLux | LGPL-3.0 / LGPL-3.0 | Starlight lighting already inside Moonrise (`StarLightInterface`) | Yes | Already realised | - | Rejected: duplicate |
| FerriteCore | MIT | No (client/server memory, blockstate dedupe) | Mixin port needed | RAM -10-40%, GC less | Medium (touches core registries) | Backlog; mostly memory |
| ModernFix | LGPL-3.0 | No | Mostly client/startup of modded; dynamic resources N/A | Startup of mod loaders only | - | Rejected: not applicable to Paper |
| Krypton (network) | LGPL-3.0 (U) | Yes: Netty `FlushConsolidationHandler` in `ServerConnectionListener` (`Paper.disableFlushConsolidate`), compression tuning exists | Yes | Already realised | - | Rejected: duplicate |
| VMP (Very Many Players) | MIT | Mostly (Moonrise per-player chunk send/tracking, `chunk-loading-basic`) | Partial | Small | Medium | Rejected: duplicate; tune config instead |
| C2ME | MIT (+ second license in monorepo) | Yes in effect (Moonrise async chunk IO/gen/worker threads) | Via `chunk-system.*` config | Chunk gen/load | Low | Config only |
| Pufferfish (DAB, async mob spawning, etc.) | GPL-3.0 | No: our `patches/unapplied-server` holds the old Pufferfish port, DAB absent | Needs NMS patch on current sources | MSPT high with many entities (DAB) | Medium-high (rebase, entity AI) | Backlog #1: rebase DAB as a patch, needs full patch build |
| Leaf | Inherits upstream (GPL-3.0) | No | Needs patch ports | Medium | Medium (async pathfinding/spawn are risky for Slimefun) | Rejected for now |
| Gale | Mixed (MIT build files, patches GPL-3/LGPL) | No | Patch ports | Small-medium | Medium | Cherry-pick ideas only |
| Petal | GPL-3.0, archived 2022 | No | Obsolete | - | High | Rejected: archived |
| Canvas | GPL-3.0 | No | Patch ports; tracks newer Paper | Medium | High (Folia-style regions) | Rejected |
| Folia | GPL-3.0 | No | Regionised threading breaks Bukkit plugins (Slimefun) | High on big hosts | Very high | Rejected |
| Paper recent patches / Mojira bug fixes | GPL-3.0/MIT | Come with the upstream rebase | Yes | Varies | Low | Keep rebasing on Paper; do not backport by hand |
| Network config: compression, `max-joins-per-tick`, packet limiter, native transport | n/a (config) | Yes (epoll only; no io_uring in 26.2 code) | Yes | Ping/join spikes | Low | Profiles below |
| Backup throttling / measurement / load governor | Original code | n/a | Yes | Ping spikes, MSPT | Low | Implemented |

Mojira "vanilla bug fixes": Paper carries the relevant ones upstream; hand-porting adds merge cost without measured gain.
io_uring: not present in `EventLoopGroupHolder` (epoll only); epoll is correct on Linux x86_64, no action.

## 3. Implemented (all switchable in `drakes-data/perf.yml`, created with safe defaults)

1. `perf.status`: JSON snapshot every 10 s (TPS, MSPT, ping avg/p50/p95/max, heap, GC time/count, load average, per-world distances/chunks/entities, governor step, backup flag). Read-only. Default on.
2. `backup.*`: backup under `nice -n 19` + `ionice -c3`, `-mmt=2`, stdout discarded, 180 min timeout, deferral while MSPT > 35 (up to 12 x 10 min), optional fixed hour (`start-hour`). Default on, same schedule as before unless `start-hour` is set.
3. `load-governor.*`: when MSPT > 45 for 30 s, lowers send-view and simulation distance one step (down to 6/4); restores one step after MSPT < 30 for 180 s. Hysteresis, every change logged. Default **off** (changes what players see; enable in tests).

All code is original (no third-party code copied); see `NOTICE` for the ideas credited.

## 4. Recommended profiles (documented, not applied)

### Star VPS (8 cores shared, 6 GB, one server)

JVM (Java 25; G1 stays the safe choice at <= 8 GB, ZGC generational only if pauses prove to be the cause):
```
-Xms5G -Xmx5G -XX:+AlwaysPreTouch -XX:+UseG1GC -XX:MaxGCPauseMillis=100 -XX:G1HeapRegionSize=8M
-XX:G1NewSizePercent=30 -XX:G1MaxNewSizePercent=40 -XX:G1ReservePercent=15 -XX:InitiatingHeapOccupancyPercent=20
-XX:+ParallelRefProcEnabled -XX:ParallelGCThreads=4 -XX:ConcGCThreads=2 -XX:+DisableExplicitGC
-XX:+PerfDisableSharedMem -XX:+UseStringDeduplication -Dio.netty.leakDetection.level=disabled -Dusing.aikars.flags=https://mcflags.emc.gs
```
(Leave ~1 GB for the OS/Netty off-heap; ParallelGCThreads=4 so GC does not take every core. Check the log for "Unrecognized/obsolete VM option" on the first boot.)
Faster starts: create an AOT cache once (`-XX:AOTCacheOutput=drakes.aot` on a training run, then `-XX:AOTCache=drakes.aot`, JEP 483/514), or on Java 21+ `-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=drakes.jsa`; run from a local SSD, not NFS/rclone.

`server.properties`: `view-distance=7`, `simulation-distance=5`, `network-compression-threshold=256`, `use-native-transport=true`, `sync-chunk-writes=false`, `max-tick-time=-1`, `entity-broadcast-range-percentage=75`, `rate-limit=0` (packet limiter is in paper-global).

`config/paper-global.yml`:
```
chunk-system: {io-threads: 2, worker-threads: 3}
chunk-loading-basic: {player-max-chunk-send-rate: 40.0}
# max-joins-per-tick: 3 (find the key in the generated file; only change values, keep the rest)
packet-limiter: {all-packets: {interval: 7.0, max-packet-rate: 500.0, action: KICK}}
```
`config/paper-world-defaults.yml`: `entities.spawning.per-player-mob-spawns: true`, `chunks.delay-chunk-unloads-by: 10s`,
`chunks.max-auto-save-chunks-per-tick: 8`, `chunks.auto-save-interval: 6000`, `environment.optimize-explosions: true`,
`collisions.max-entity-collisions: 2`, `hopper.disable-move-event: true` (only if no plugin needs it; Slimefun cargo does not),
`misc.redstone-implementation: ALTERNATE_CURRENT`, `entities.spawning.despawn-ranges` soft 28 / hard 96 for monsters, `entities.behavior.nerf-pigmen-from-nether-portals: true`.

`spigot.yml`: `entity-activation-range: {animals: 16, monsters: 24, raiders: 48, misc: 8, water: 12, villagers: 16, flying-monsters: 32}`,
`tick-inactive-villagers: false`, `entity-tracking-range: {players: 48, animals: 32, monsters: 40, misc: 24, other: 48}`, `mob-spawn-range: 6`, `merge-radius: {item: 3.5, exp: 4.0}`, `nerf-spawner-mobs: true`.

`purpur.yml`: `settings.tps-catchup: false`, `settings.use-alternate-keepalive: true`, `settings.lagging-threshold: 19.0`.

`drakes-data/perf.yml`: `load-governor.enabled: true`, `backup.start-hour: 5`.

### Nova (powerful, several cores free)

Same files, with: heap `-Xms8G -Xmx8G` (more if RAM allows), `-XX:ParallelGCThreads=8 -XX:ConcGCThreads=3`; `view-distance=10`, `simulation-distance=7`;
`chunk-system: {io-threads: 3, worker-threads: 6}`, `player-max-chunk-send-rate: 75.0`; entity ranges at Paper defaults;
`load-governor.enabled: false`. ZGC option for A/B: `-XX:+UseZGC -XX:+ZGenerational -XX:SoftMaxHeapSize=7G` (drop all G1 flags; Java 25 already generational) - measure GC pause in `perf-status.json` (`gc_total_ms`).

Test order for the VPS: baseline -> `tps-catchup:false` + alt keepalive -> view/sim/ranges -> governor -> JVM flags. One change per run.

## 5. Measurement procedure

Script (read-only): `~/ai-hub/scripts/medir_rendimiento.sh <server_dir|host:dir> [segundos] [etiqueta]`.
1. Same world, same player count/bots, same plugin set; 2 min warm-up, 10 min sample.
2. Script samples `drakes-data/perf-status.json` every 10 s: median/p95 MSPT, min TPS, ping avg/p95, heap, GC ms delta, load average, backup running; plus host `ss -ti` RTT/retrans to the game port and CPU steal (`/proc/stat`).
3. Optional in-game: `/spark profiler start --timeout 600` then compare reports; `/tps`, `/mspt`, `/drakes saori status` (check `drakes-data/saori.yml` lag/ping rules).
4. Output: the script prints one summary line per run (TSV); paste lines in a table and compare by label.
