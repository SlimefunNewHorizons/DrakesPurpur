# DrakesPurpur 26.2 - The Sovereign Engine of DrakesCraft
> **Purpur 26.2 Kernel Fork (Paperweight + Java 25 LTS)**  
> **Chief Architect & Creator:** Jack (JackStar6677-1)  
> **Last updated:** October 2026  
> **Purpose:** Provide the fastest, most resilient and hardened runtime base for DrakesCraft Season Two and other high-performance technical networks.

---

## Official Infrastructure and Community

* **Connection IP**: `mc.drakescraft.cl` (Java 26.2 and Bedrock cross-play on port 25565)
* **Official Discord**: [discord.gg/drakescraft](https://discord.gg/rv3vtXZTk7)
* **Web Portal and Documentation**: [web.drakescraft.cl](https://web.drakescraft.cl)
* **Official Store**: [web.drakescraft.cl/store](https://web.drakescraft.cl/store.html)

DrakesPurpur is the sovereign core that powers DrakesCraft Season Two, handling 80+ Slimefun expansions, dynamic economies, multi-modal isolation, in-engine moderation tools, SQLite WAL storage auditing and a continuous 20.0 TPS.

---

## Mission and Engineering Philosophy

High-concurrency Minecraft servers running massive technical addons (such as Slimefun, automated digital logistics and multiblock machinery) have historically collapsed because of JVM Garbage Collector pauses and the overhead of millions of Bukkit events firing every second.

**DrakesPurpur** is a sovereign fork of [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) designed with one priority goal:  
**Turn Minecraft into an ultra-low-latency, self-healing technical engine, hardened against unhandled exceptions, natively synchronized with autonomous SRE orchestrators (SAORI) and optimized for mobile Bedrock players (Geyser).**

---

## DrakesPurpur Engineering Pillars

### 1. Kernel Crash Guard (Self-Healing, Zero Crashes)
* Intercepts unhandled exceptions (`NullPointerException`, `IndexOutOfBoundsException`, `ConcurrentModificationException`) directly in the main game loop (`ServerTick` and `BlockEntityTick`).
* **Dynamic Fault Isolation:** If an addon machine or an entity throws an unhandled error, the engine disables only the damaged component and logs the incident in an atomic SQLite WAL database (`drakes_faults.db`). The server never stops and the community keeps a stable 20.0 TPS.

### 2. Native Rust Off-Heap Preloading (Java 25 Panama FFM)
* **Minecraft's Real Bottleneck:** On massive technical servers the limit is rarely CPU processing; it is the creation of millions of transient Java objects per tick that saturate the Garbage Collector, causing critical Stop-The-World (STW) pauses.
* **Enterprise Hybrid Design:** Rewriting hundreds of Bukkit plugins in Rust or C from scratch is not viable. The sovereign architecture is hybrid: Java handles game logic, events and plugins, while Rust manages pure off-heap memory through Java 25's modern Panama FFM (Foreign Function & Memory) API, without the overhead of classic JNI.
* **Preloading at Startup:** The server boots by linking the native library `libslimefun_ffi.so`. Electrical networks, quantum transfers and machine ticking are computed outside the Java heap with SIMD vectorization (AVX-512 VNNI), keeping G1GC pauses under 15 milliseconds.

### 3. Sovereign Modality Engine (6 Canonical Modalities)
The kernel embeds an internal routing engine (`DrakesModalityEngine`) that splits the server into 6 canonical modalities within the same instance, removing the network latency and RAM overhead of external BungeeCord clusters:
* **`survival_sf`**: Main technical survival modality with Slimefun and all machinery suites active.
* **`survival_clasico`**: Pure Vanilla Survival modality. **This is the only modality that runs without Slimefun.**
* **`oneblock`**: Progression on an infinite regenerating block, integrated with BentoBox and technology.
* **`onechunk`**: Engineering and survival challenge confined to the limits of a single chunk.
* **`skyblock`**: Technical progression on floating islands in the void.
* **`lab`**: Technical testing environment and laboratory with periodic map reset.

**Hermetic Isolation Guarantees:**
* Independent inventory, armor and offhand snapshots per modality.
* Fully independent Ender Chests.
* Fully separate experience levels, potion effects, hunger and health.
* Dedicated game modes and permission profiles per modality.
* Asynchronous in-memory migration with no disk freezes on the main thread.

### 4. In-Engine Staff Tools (No Plugin Dependency)
Critical administration tools live directly in the Purpur kernel:
* **`/vani` / `/vanish`**: Sovereign ghost state. Intercepts entity packets at the network layer, mutes sounds, stops particles, blocks accidental item pickup and hides presence from the tab list invisibly.
* **`/ultragod`**: Kernel-level invulnerability superior to the classic `/god` command. Negates void damage, falling out of the world border, `/kill` commands, hunger, explosions and any environmental destruction vector.
* **`/freeze <player>`**: Immediate interactive lock of movement and interactions at the network-packet level for suspected exploiters.
* **`/drakes inspect <player>`**: Comprehensive administrative telemetry:
  - Account UUID, ping, client used and active modality.
  - Resolved IP address, geolocation (country) and network provider (ASN/ISP).
  - Multi-account (alts) detection: real-time and SQLite cross-tracking of connections sharing the same network signature.
  - Automatic display of informative prefixes above the head of staff members (`settings.drakes.staff.nametag-alt-display`).

### 4b. DrakesDb: Single Shared Kernel Database
One SQLite WAL file, `drakes-data/kernel.db`, shared by every kernel subsystem:
* Batched single-writer thread: callers never block the main thread on disk I/O.
* API `DrakesDb.get()` with `write(...)`, `read(...)` and a key-value store (`kv`) for suites and addons.
* Crash-guard failures and the audit trail live in it; `/drakes db` reports its status (queue, batches, size).

### 5. Storage and Action Auditing Subsystem (DrakesStorageAudit)
Embedded transactional engine based on SQLite WAL (`drakes_storage_audit.db`), designed as a zero-overhead alternative to external rollback plugins:
* Records block placement, destruction and physical block events.
* Monitors container transactions (chests, hoppers, shulkers, barrels), storing quantity, NBT data and the responsible player.
* Records item pickups and drops on the ground, as well as executed commands.
* Asynchronous writes through the multi-core `RyzenWorkerPool`, eliminating any main-thread freeze.

### 6. Automated Daily 7z Backup Engine (DrakesBackupScheduler)
Internal backup service with no performance degradation:
* Uses the LZMA2 compression algorithm in 7z format to achieve the highest size reduction on maps and configurations.
* Runs during low-concurrency hours on background threads.
* Applies a rotation policy with a strict 7-day retention window (`retention-days: 7`), automatically purging old files to protect the NVMe drives.

### 7. Geyser and Bedrock Companion Engine (Mobile and Console Optimization)
* **Redundant Packet Compensation:** Throttles updates of distant entities to save mobile bandwidth.
* **Preventive Void Recovery:** Prevents invisible deaths in the End and Skyblock void by repositioning the player before desynchronization (`teleport-if-outside-border`).
* **Decoupled Keepalive:** Asynchronous alternative handshakes that prevent false-timeout disconnects on high-latency cellular networks.

### 8. Native Link with SAORI (Direct IPC Socket)
* Native Unix Domain Socket connection between the engine and the autonomous orchestrator **SAORI** (`saori-ai-daemon` on port 8089).
* Eliminates heavy disk log reading (`latest.log`) and removes the need for external Discord bridges.
* Enables sub-millisecond bidirectional chat synchronization, real-time crash alerts and automatic incident resolution by AI agents.

### 9. DrakesProfiler and Ryzen Multi-Core Dispatcher
* **Direct Telemetry:** Reads CPU usage, JVM memory and thread usage directly from `ManagementFactory.getThreadMXBean()` and `OperatingSystemMXBean` with zero overhead.
* **Anti-Lag Diagnostics:** Instant breakdown of loaded chunks, living entity count per world and detection of saturated threads.
* **Local Markdown Reports:** Generates audits in `drakes_reports/` with comparative tables and hardware data, without exposing server data to external web viewers.
* **Ryzen Scheduler:** `RyzenWorkerPool` (ForkJoinPool) distributes asynchronous tasks and SQLite writes across all processor cores.

### 10. DrakesGrimGuard and DrakesChatGuard (Native Security)
* **In-Kernel Anti-Cheat (`DrakesGrimGuard`):** Packet-level validation of geometric reach (<3.20m), horizontal speed (<4.20 b/t), flight and clicks per second (<22 CPS) before the data reaches the Bukkit layer.
* **Chat Security (`DrakesChatGuard`):** Minimum cadence between messages (600ms), real-time bilingual censorship (`[censurado]`), protection against oversized packets (>256 characters) and command injection blocking (`/op`, `/calc`, `/execute`).
* **Synthetic Console Tests:** `/drakes test all` runs full security, chat and Rust engine tests directly from the terminal without connected players.

### 11. Native Slimefun Monolith Architecture (`feat/slimefun-native-engine`)
Parallel research and development branch exploring the full integration of Slimefun as direct Minecraft primitives:
* Slimefun items are evaluated as native `Block` and `Item` classes, removing PDC metadata and Bukkit wrappers.
* In-memory execution in the same cycle as vanilla furnaces and hoppers in `ServerLevel.tickBlockEntities()`.
* Produces the standalone artifact `DrakesPurpur-Server.jar` with support for 80+ factory-preinstalled expansions.

---

## Centralized Configuration (`purpur.yml`)

All kernel features are managed centrally in `purpur.yml` under the `settings.drakes.*` section:

```yaml
settings:
  drakes:
    crash-guard:
      enabled: true
      max-errors-per-minute: 60
      log-to-sqlite: true
    modalities:
      enabled: true
      worlds:
        survival_sf:
          - "world"
          - "world_nether"
          - "world_the_end"
        survival_clasico:
          - "clasico"
          - "clasico_nether"
          - "clasico_the_end"
        oneblock:
          - "oneblock_world"
        onechunk:
          - "onechunk_world"
        skyblock:
          - "bskyblock_world"
        lab:
          - "lab_world"
    staff:
      nametag-alt-display: true
      telemetry-database: "drakes_staff_telemetry.db"
    audit:
      storage-audit-enabled: true
      database: "drakes_storage_audit.db"
    backups:
      daily-7z-enabled: true
      retention-days: 7
      backup-hour-utc: 4
    anti-xray:
      engine-enhanced: true
    rust-engine:
      preload-native: true
      library-path: "natives/libslimefun_ffi.so"
    saori-bridge:
      enabled: true
      socket-path: "/var/run/saori.sock"
    geyser:
      packet-throttling: true
      void-teleport-fix: true
    motd:
      custom-gradient: true
    tab:
      smart-alignment: true
```

---

## CI/CD Pipeline and Sovereign Maven Distribution

The repository is automated with **GitHub Actions** (`.github/workflows/drakes_ci_cd.yml`):
1. **Cloud Compilation:** Every commit on `ver/**` branches builds the server's Paperclip binary on GitHub runners with **Java 25** and Paperweight.
2. **Automatic API Deployment:** Builds and deploys `DrakesPurpur-API` to the official repository at **`https://maven.drakescraft.cl`**.
3. **Webhook Distribution:** Sends the final artifact to staging with no CPU or memory usage on local workstations.

---

## Local Build Instructions

To build DrakesPurpur locally on Linux systems (Nova / workstations):

```bash
# Requires Java 25 LTS configured in JAVA_HOME
./gradlew applyAllPatches
./gradlew createPaperclipJar -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

The generated production binary will be available at:  
`purpur-server/build/libs/purpur-paperclip-26.2.local-SNAPSHOT.jar`.

---

## Acknowledgments and Upstream Attribution

DrakesPurpur is a sovereign fork designed and maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1) under **DrakesCraft Labs** and **Star Systems**.

We deeply thank the upstream projects and sovereign co-creators:
* **PurpurMC** ([github.com/PurpurMC/Purpur](https://github.com/PurpurMC/Purpur)): For the most configurable and flexible Minecraft server base.
* **PaperMC** ([github.com/PaperMC/Paper](https://github.com/PaperMC/Paper)): For leading server performance innovation, Paperweight and the Bukkit ecosystem.
* **Chagui68 (Chagui)**: Sovereign author of the **Multiverse Suite** (`MultiverseNets`, `MultiverseCreatures`, `MultiverseTinker`, `MultiverseProgramming` and `MultiverseGambling`) and innovator in **Pumpkin-launcher** (ClassLoader isolation, plugin sanitization and the PatchBukkit layer). Recognized in the kernel through `/drakes credits`.
* **GeyserMC** ([geysermc.org](https://geysermc.org/)): For unifying the Bedrock and Java communities.
* **TheBusyBiscuit** ([github.com/TheBusyBiscuit](https://github.com/TheBusyBiscuit)): For creating Slimefun4, the cornerstone of modern technical survival.
* **SpigotMC** and **Mojang Studios**: For the Minecraft universe.

---

<div align="center">

**DrakesCraft Labs · Star Systems** · Maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1)  
*Sovereign Software Architecture for Season Two*

</div>
