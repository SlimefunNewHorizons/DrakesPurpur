<div align="center">

  <img src="banner.svg" alt="DrakesPurpur Banner" width="920" />

# DrakesPurpur 26.2

**High-Performance Sovereign Minecraft Server Engine for DrakesCraft Season Two & Technical Networks**

<p>
  <a href="https://github.com/SlimefunNewHorizons/DrakesPurpur"><img src="https://img.shields.io/badge/GitHub-DrakesPurpur-181717?style=for-the-badge&logo=github" alt="GitHub"/></a>
  <img src="https://img.shields.io/badge/Java-25%20LTS-F89820?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Minecraft-26.2-38BDF8?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 26.2"/>
  <img src="https://img.shields.io/badge/Rust-FFM%20Native-DEA584?style=for-the-badge&logo=rust&logoColor=white" alt="Rust FFM"/>
  <img src="https://img.shields.io/badge/Bedrock-Geyser%20Optimized-0ea5e9?style=for-the-badge&logo=android&logoColor=white" alt="Bedrock Optimized"/>
</p>

</div>

> ### Official DrakesCraft Community & Infrastructure
> 
> * **Server IP**: `mc.drakescraft.cl` (Java 26.2 & Bedrock Cross-play)
> * **Official Discord**: [discord.gg/drakescraft](https://discord.gg/rv3vtXZTk7)
> * **Web Portal & Documentation**: [web.drakescraft.cl](https://web.drakescraft.cl)
> * **Official Store**: [web.drakescraft.cl/store](https://web.drakescraft.cl/store.html)
> 
> DrakesPurpur is the sovereign kernel driving Season Two of DrakesCraft, powering 80+ Slimefun expansions, dynamic economies, multi-modal isolation, embedded staff suites, automated storage auditing, and rock-solid 20.0 TPS.

---

## Mission & Philosophy

High-concurrency Minecraft servers running extensive technical addons (such as Slimefun, automated logistics, and custom multiblock machines) historically collapse due to JVM Garbage Collection pauses and the overhead of millions of Bukkit events firing every second.

**DrakesPurpur** is a sovereign fork of [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) engineered with a singular objective:  
**Transform Minecraft into an ultra-low-latency, self-healing technical engine, hardened against unhandled exceptions, natively synchronized with autonomous SRE orchestrators (SAORI), and optimized for cross-play mobile Bedrock players (Geyser).**

---

## Core Architectural Pillars

### 1. Kernel Crash Guard & Self-Healing (Zero Server Crashes)
* Intercepts unhandled exceptions (`NullPointerException`, `IndexOutOfBoundsException`, `ConcurrentModificationException`) directly within the main tick loop (`ServerTick` and `BlockEntityTick`).
* **Dynamic Fault Isolation:** If an addon machine or entity throws an unhandled error, the engine safely disables only the faulty component and commits the incident into an **atomic SQLite WAL database (`drakes_faults.db`)**. The server process never halts, and players experience continuous 20.0 TPS without disconnects.

### 2. Native Rust Off-Heap Engine (Java 25 Panama FFM)
* **The True Bottleneck of Minecraft:** In heavily modded technical servers, the bottleneck is rarely raw CPU compute; it is the millions of transient Java objects generated per tick that overwhelm the Garbage Collector, resulting in disruptive Stop-The-World (STW) pauses.
* **Hybrid Enterprise Design:** Rewriting years of Bukkit/Paper plugins from scratch is infeasible and counterproductive. The pragmatic enterprise architecture is hybrid: Java executes game logic, events, and plugins, while Rust manages pure off-heap memory via the modern Java 25 Panama FFM (Foreign Function & Memory API) without legacy JNI overhead.
* **Kernel Preloading:** The server boots by preloading the acceleration binary `libslimefun_ffi.so`.
* Electrical grids, quantum transfers, and continuous machine ticking are calculated outside the Java Heap in parallel native memory with SIMD vectorization (AVX-512 VNNI), keeping G1GC pauses sub-15ms.

### 3. Sovereign Modality Engine (6 Canonical Modalities)
The kernel embeds an airtight multi-modal routing engine (`DrakesModalityEngine`) that segregates player state across 6 canonical game modes within a single server instance, eliminating the network latency and memory overhead of multi-proxy BungeeCord clusters:
* **`survival_sf`**: Primary technical Survival mode with Slimefun and full suite expansions enabled.
* **`survival_clasico`**: Pure Vanilla Survival mode. **This is the single modality with Slimefun completely disabled.**
* **`oneblock`**: Skyblock variant starting on an infinite renewing block, integrated with BentoBox and technical progressions.
* **`onechunk`**: High-density engineering challenge confined to a single chunk boundary.
* **`skyblock`**: Canonical technical void island progression.
* **`lab`**: Sandbox testing and staging laboratory with scheduled world resets.

**Airtight Isolation Guarantees:**
* Independent inventory snapshots, armor, and offhand slots.
* Independent Ender Chest inventories.
* Isolated experience levels, potion effects, hunger, and health pools.
* Modality-specific gamemodes and permission profiles.
* Hot-swapping handled asynchronously without main-thread disk stalls.

### 4. Embedded Staff Operations Suite (Zero Plugin Dependency)
Critical moderation tools have been moved directly into the server kernel, eliminating laggy third-party plugins:
* **`/vani` / `/vanish`**: Sovereign ghost state. Intercepts player entity packets at the network layer, suppresses sound packets, stops particle emission, prevents item pickup, and removes the player from the tab list silently.
* **`/ultragod`**: Kernel-level invulnerability superior to conventional `/god`. Cancels all damage vectors including void out-of-bounds damage, death commands, hunger depletion, entity explosions, and environmental hazards.
* **`/freeze <player>`**: Immediate packet-level motion cancellation and interact lock for suspected exploiters.
* **`/drakes inspect <player>`**: Comprehensive administrative telemetry:
  - Account UUID, ping, client brand, and current modality.
  - Resolved IP address, geolocation (country), and network provider (ASN/ISP).
  - Multi-account (Alts) detection: scans active connections and historical SQLite telemetry for matching IP and hardware signatures.
  - Automated staff nametag overlay display above player models (`settings.drakes.staff.nametag-alt-display`).

### 5. Storage & Action Auditing Subsystem (DrakesStorageAudit)
A high-throughput audit subsystem built on SQLite WAL (`drakes_storage_audit.db`), operating as a zero-overhead alternative to external rollbacks:
* Tracks block placements, destructions, and physics changes.
* Records container transactions (inventories, hoppers, shulkers, barrels) with item counts, NBT signatures, and player identifiers.
* Logs item pickups, drops, and administrative command executions.
* Commits asynchronously via the multi-core `RyzenWorkerPool`, eradicating main-thread I/O stalls and database deadlocks.

### 6. Automated 7z Backup Engine (DrakesBackupScheduler)
An embedded backup daemon executing automated daily snapshots without server performance degradation:
* Utilizes LZMA2 7z compression algorithms for maximum compression ratios on world saves and configuration files.
* Executes at off-peak hours via background worker threads.
* Implements an automated rolling retention window (`retention-days: 7`), purging backups older than 7 days to preserve NVMe storage health.

### 7. Geyser & Bedrock Companion Engine (Mobile & Console Tuning)
* **Packet Redundancy Compensation:** Throttles non-critical entity updates to conserve mobile bandwidth.
* **Predictive Void & Boundary Recovery:** Eliminates ghost void deaths in End and Skyblock environments via proactive boundary repositioning (`teleport-if-outside-border`).
* **Decoupled Keepalive Mechanism:** Uses asynchronous alternate keepalive handshakes to prevent false timeout disconnects for high-latency mobile and cellular connections.

### 8. SAORI Kernel Bridge (Direct Socket Telemetry)
* Native IPC / Unix Domain Socket connection linking the engine directly to the **SAORI Autonomous SRE Daemon** (`saori-ai-daemon` on port 8089).
* Replaces heavy external Discord bridge plugins and disk log scraping (`latest.log`).
* Enables sub-millisecond bidirectional chat bridging, real-time crash alerting, and automated incident triage by SRE AI agents.

### 9. DrakesProfiler & Ryzen Multi-Core Dispatcher
* **Direct Kernel Telemetry:** Reads CPU metrics, JVM memory, and thread utilization directly from `ManagementFactory.getThreadMXBean()` and `OperatingSystemMXBean` with zero overhead.
* **Root Cause Analysis (Anti-Lag):** Instant breakdown of total loaded chunks, entity counts, living mob density per world, and the top CPU-hogging threads.
* **Local Markdown Reports:** Generates standalone diagnostic files in `drakes_reports/` containing markdown tables and hardware metrics without uploading sensitive server data to third-party web viewers.
* **Multi-Core Ryzen Scheduler:** Utilizes `RyzenWorkerPool` (ForkJoinPool) to parallelize async lookups and SQLite WAL commits across all available processor cores.

### 10. DrakesGrimGuard & DrakesChatGuard (Zero-Lag Kernel Security)
* **Kernel-Level Anti-Cheat (`DrakesGrimGuard`):** Direct packet-level validation of geometric reach (<3.20m), horizontal velocity (<4.20 b/t), flight anomalies, and clicks per second (<22 CPS) before packets reach the Bukkit layer.
* **Integrated Chat Security (`DrakesChatGuard`):** Cadence-based anti-spam filter (600ms minimum interval), real-time bilingual censorship (`[censurado]`), packet crash prevention (>256 characters), and command injection sanitization (`/op`, `/calc`, `/execute`).
* **Synthetic CLI Testing:** `/drakes test all` runs automated console tests validating anticheat mechanics, chat safety, and the native Rust bridge without requiring connected game clients.

### 11. Slimefun Native Monolith Architecture (`feat/slimefun-native-engine`)
Parallel R&D branch exploring the complete ingestion of Slimefun core mechanics into native Minecraft classes:
* Slimefun custom items are evaluated as first-order `Block` and `Item` primitives, removing PDC metadata and wrapper overhead.
* Direct in-memory ticking alongside vanilla block entities in `ServerLevel.tickBlockEntities()`.
* Produces an all-in-one standalone `DrakesPurpur-Server.jar` that launches Minecraft with full technical automation out of the box.

---

## Centralized Configuration (`purpur.yml`)

All sovereign kernel features are configured centrally in `purpur.yml` under the `settings.drakes.*` namespace:

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

## CI/CD Pipeline & Sovereign Maven Distribution

DrakesPurpur is fully automated via **GitHub Actions** (`.github/workflows/drakes_ci_cd.yml`):
1. **Cloud Compilation:** Every push to `ver/**` builds the production Paperclip server binary on GitHub runners using **Java 25** and Paperweight.
2. **Automated API Deployment:** Builds and deploys `DrakesPurpur-API` directly to our sovereign repository at **`https://maven.drakescraft.cl`**.
3. **Webhook Ingestion:** Delivers binary artifacts to staging with zero local CPU or RAM overhead on developer workstations.

---

## Local Build Instructions

To build DrakesPurpur locally on Linux (Nova / Workstations):

```bash
# Requires Java 25 LTS set in JAVA_HOME
./gradlew applyAllPatches
./gradlew createPaperclipJar -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

The resulting production artifact will be generated at:  
`purpur-server/build/libs/purpur-paperclip-26.2.local-SNAPSHOT.jar`.

---

## Upstream Attribution & Acknowledgments

DrakesPurpur is a sovereign fork engineered and maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1) under **DrakesCraft Labs** and **Star Systems**.

We express our sincere gratitude to upstream projects and sovereign co-creators:
* **PurpurMC** ([github.com/PurpurMC/Purpur](https://github.com/PurpurMC/Purpur)): For the configurable and extensible Minecraft server software base.
* **PaperMC** ([github.com/PaperMC/Paper](https://github.com/PaperMC/Paper)): For pioneering modern server performance, Paperweight patching, and the Bukkit API ecosystem.
* **Chagui68 (Chagui)**: Sovereign author of the **Multiverse Suite** (`MultiverseNets`, `MultiverseCreatures`, `MultiverseTinker`, `MultiverseProgramming`, `MultiverseGambling`) and innovator in **Pumpkin-launcher** (ClassLoader compatibility, plugin sanitization, and PatchBukkit layer). Recognized in the kernel via `/drakes credits`.
* **GeyserMC** ([geysermc.org](https://geysermc.org/)): For bridging Bedrock and Java editions into a unified community.
* **TheBusyBiscuit** ([github.com/TheBusyBiscuit](https://github.com/TheBusyBiscuit)): For creating Slimefun4, the foundation of modern technical survival.
* **SpigotMC** & **Mojang Studios**: For the Minecraft sandbox universe.

---

<div align="center">

**DrakesCraft Labs · Star Systems** · Maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1)  
*Sovereign Software Architecture for Season Two*

</div>
