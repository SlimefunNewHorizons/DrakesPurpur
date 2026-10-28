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

## Version compatibility

| Line | Branch | Minecraft | Java | Paper API (compile-time) | Status |
|---|---|---|---|---|---|
| DrakesPurpur modular | `ver/26.2` | 26.2 | 25 LTS | Paper `26.2` (`apiVersion = 26.2`, see `gradle.properties`) | test / staging (Season Two) |
| DrakesPurpur monolith | `monolito-local` (local only) | 26.2 | 25 LTS | Paper `26.2` | test (experimental R&D) |
| Production server | plain Paper 1.21.11 (not this fork) | 1.21.11 | 21 | `1.21.11-R0.1-SNAPSHOT` | production |

The suites and addons that run on this kernel come from the Universal line of the Slimefun core (`11.0-Universal-26.x-SNAPSHOT`), which is compiled against Paper API `1.21.11-R0.1-SNAPSHOT`, targets Java 21 bytecode and is the same codebase as the 1.21.11 production core. Addons for 26.x are obtained with `ai-hub/scripts/portar_addons.py`.

**Minecraft versions older than 1.21.11 are not supported and not tested.**

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

### 4b. DrakesDb: Single Shared Kernel Database
One SQLite WAL file, `drakes-data/kernel.db`, shared by every kernel subsystem (commit `fd9184e2f`, branch `ver/26.2`, also merged into `monolito-local`):
* Batched single-writer thread: callers never block the main thread on disk I/O.
* API `DrakesDb.get()` with `write(...)`, `read(...)` and a key-value store (`kv`) for suites and addons.
* Crash-guard failures and the audit trail were migrated into it; `/drakes db` reports its status.

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

---

## Dual-Branch Architecture: The Open-Source Future of Server Engineering

DrakesPurpur pioneers a dual-track architectural model addressing both production requirements and the long-term evolution of Minecraft server software:

```
                               +-------------------------------------+
                               |     DRAKESPURPUR SOVEREIGN CORE     |
                               +------------------+------------------+
                                                  |
                         +------------------------+------------------------+
                         |                                                 |
                         v                                                 v
         BRANCH: ver/26.2 (Modular Production)             BRANCH: monolito-local (Monolith)
  +----------------------------------------------+  +----------------------------------------------+
  | - Hardened Purpur Kernel (Panama FFM,        |  | - Complete Ingestion of Slimefun & Addons    |
  |   GrimGuard, ChatGuard, Crash Guard SQLite). |  |   directly into native Minecraft NMS.        |
  | - 9 StarSuites modular outside in plugins/.  |  | - Custom items & blocks as 1st-class objects |
  | - Decoupled modules/*.yml per addon.         |  |   (zero PDC serialization / compound tags).  |
  | - Hot-reloading via /drakessuites reload.    |  | - In-memory ticking in ServerLevel loop.     |
  | - Zero downtime, instant config tuning.      |  | - Single standalone executable JAR.          |
  +----------------------------------------------+  +----------------------------------------------+
```

Both branches share the same kernel (including `DrakesDb`, the `kernel.db` WAL database inspected with `/drakes db`); they differ only in how the suites are delivered:

* **`ver/26.2` (modular):** kernel only; suites are external JARs in `plugins/`.
* **`monolito-local` (monolith, local branch, never pushed):** suites are embedded in the server JAR from `purpur-server/bundled/drakes-bundled/`. Fill that folder with `scripts/empaquetar_monolito.sh [path-to-Drakes-Suites]`, then build.

**Modular vs monolith, artifact size:** the modular `purpur-paperclip` jar is about **68.8 MB**; the monolith jar with the bundled suites is about **82 MB**.

> **Build notes.** Without `BUILD_NUMBER` the version is `26.2.local-SNAPSHOT`, which breaks addons that parse `Bukkit.getBukkitVersion`; build with `BUILD_NUMBER=134 ./gradlew createPaperclipJar`. For the **modular** build, remove the `srcDir("bundled")` line from the generated `build.gradle.kts` so nothing is embedded.

### Branch Comparison: What Each Architecture Achieves

| Feature & Objective | Branch `ver/26.2` (Production Modular) | Branch `monolito-local` (Monolith, local only) |
| :--- | :--- | :--- |
| **Architectural Model** | **Decoupled Hybrid:** Hardened kernel + external modular StarSuites JARs in `plugins/`. | **Native Monolith:** 100% of Slimefun core & 94 addons embedded into native server classes. |
| **Configuration Tuning** | **Instant & Granular:** Every addon has its own file in `plugins/Drakes<Suite>/modules/<addon>.yml`. | **Unified & Centralized:** Configured via `purpur.yml` and native kernel data registries. |
| **Recompilation Requirement** | **Zero Recompilation:** Edit YAMLs with `nano` and run `/drakessuites reload <suite> [module]`. | Requires kernel compilation (`./gradlew createPaperclipJar`) for core logic mutations. |
| **Execution Overhead** | Ultra-low: Off-heap Rust SIMD via Panama FFM + Spatial Ticker Engine sleeps idle chunks. | **Absolute Zero Wrapper Overhead:** Eliminates Bukkit Event Bus and PDC NBT serialization entirely. |
| **Deployment Target** | **Active Production Season Two (Live in Dallas & Staging).** | **Experimental Next-Gen R&D** for future unified server distribution. |

### Why Native Kernel Ingestion is the Future of Open-Source Plugins

For over 15 years (since Bukkit was created in 2011), the Minecraft server ecosystem has relied on **external wrapper layers** bolted onto an obfuscated game engine. While revolutionary for its time, this paradigm hits a hard mathematical wall when scaled to modern technical servers running 80+ addons:

1. **The Garbage Collector (GC) Crisis:**  
   Every interaction with a Bukkit plugin forces the JVM to instantiate thousands of short-lived wrapper objects (`ItemStack`, `BlockState`, `Event`) just to translate vanilla NMS primitives into Bukkit API types. Under heavy automation, this causes devastating Stop-The-World (STW) GC pauses.
2. **The PersistentDataContainer (PDC) Serialization Tax:**  
   Legacy addons simulate technical machines by writing String-based compound NBT tags to vanilla blocks on every tick. The CPU spends more time serializing and deserializing compound metadata than calculating electrical grids.
3. **The Event Bus Bottleneck:**  
   Firing millions of uncoordinated events across 70+ async task loops wastes massive processor cycles on cancellation checks and priority queues.

**The Sovereign Solution:**  
By absorbing community mechanics directly into the server kernel:
* Custom machines become **native `BlockEntity` primitives** ticking directly inside `ServerLevel.tickBlockEntities()`.
* Electrical grids and cargo logic calculate in **parallel off-heap native memory (Rust SIMD)** via Java 25 Panama FFM without touching the Java heap.
* The community transitions from a fragile web of 180 competing plugins into a **single, unified, self-healing server executable**. This is the next frontier of open-source Minecraft engineering: transforming external "plugin bloat" into first-class engine infrastructure.

---

## Sovereign Command Reference (Kernel, Staff & Suites)

All kernel features, moderation tools, and suite controls are exposed through high-performance commands:

### 1. Kernel Administration & Telemetry (`/drakes` / `/dpurpur`)
| Command | Permission | Description |
| :--- | :--- | :--- |
| `/drakes status` | `bukkit.command.purpur` | Displays real-time kernel status, JVM memory, Rust FFM state, and Crash Guard SQLite WAL metrics. |
| `/drakes test [all\|anticheat\|chat\|rust\|threads]` | `bukkit.command.purpur` | Executes automated synthetic CLI diagnostics validating anticheat, chat safety, and native throughput. |
| `/drakes security` | `bukkit.command.purpur` | Real-time violation counters for reach, velocity, flight, CPS, spam, and command injection attempts. |
| `/drakes threads` (or `/drakes spark`, `/drakes profile`) | `bukkit.command.purpur` | Built-in profiler (Spark replacement) exporting standalone Markdown reports to `drakes_reports/`. |
| `/drakes fastpath` | `bukkit.command.purpur` | Toggles zero-allocation SlimefunFastPath PDC event bypass. |
| `/drakes db` | `bukkit.command.purpur` | Status of the shared `DrakesDb` kernel database (queue, batches, size). |
| `/drakes gc` | `bukkit.command.purpur` | Triggers manual Garbage Collection and reports reclaimed RAM in megabytes. |
| `/drakes saori` | `bukkit.command.purpur` | Dispatches instant IPC test telemetry to the SAORI Autonomous SRE Daemon. |
| `/drakes menu` (or `/drakes gui`) | `bukkit.command.purpur` | Opens the 54-slot interactive chest GUI for real-time kernel tuning. |
| `/drakes credits` | `bukkit.command.purpur` | Canonical author attribution (JackStar6677-1 as Chief Architect, Chagui68 for Multiverse Suite). |

### 2. Embedded Staff Operations Suite (Zero Plugin Dependency)
| Command | Permission | Description |
| :--- | :--- | :--- |
| `/vani` / `/vanish` (or `/drakes vanish`) | `odysseia.vanish` | Ghost state: cancels entity packets, suppresses sounds, particles, and hides player from tablist. |
| `/ultragod` (or `/drakes ultragod`) | `drakes.staff` | Kernel-level invulnerability cancelling void damage, death commands, explosions, and hunger. |
| `/freeze <player>` (or `/drakes freeze`) | `drakes.staff` | Immediate packet-level motion cancellation and interact lock for suspected exploiters. |
| `/drakes inspect <player>` (or `/drakes alts`) | `drakes.staff` | Displays player telemetry: UUID, ping, modality, IP/ASN, and active alt-accounts. |

### 3. Native Purpur Utility Commands
| Command | Permission | Description |
| :--- | :--- | :--- |
| `/tps` / `/tpsbar` | `purpur.tps` | Real-time server tick performance (20.0 TPS target) with optional visual bossbar overlay. |
| `/ram` / `/rambar` | `purpur.ram` | Real-time JVM memory consumption metrics and visual bossbar. |
| `/uptime` | `purpur.uptime` | Displays total continuous server runtime without restarts. |
| `/ping` | None | Displays precise network round-trip latency for the executing player. |
| `/purpur reload` | `bukkit.command.purpur` | Hot-reloads all engine configuration values from `purpur.yml`. |

### 4. StarSuites & Gameplay Management Commands
| Command | Permission | Description |
| :--- | :--- | :--- |
| `/drakessuites` | `drakessuites.admin` | Displays active suites, 94 absorbed modules, and chunk-spatial ticker telemetry. |
| `/drakessuites reload <suite> [module]` | `drakessuites.admin` | Hot-reloads modular YAML configuration on the fly without server restarts. |
| `/hardcoresf <toggle\|on\|off\|reload>` (or `/hsf`) | `hardcoreslimefun.admin` | Operator toggle for HardcoreSlimefun research loss & machine hazards (default: `false`). |
| `/gw` | `globalwarming.admin` | Inspects carbon emissions and controls climate catastrophe events (default: `false`). |
| `/irp restore <player> <id>` | `irp.admin` | Restores inventory snapshots safely via SQLite WAL audit. |
| `/sm` | None | Dynamic macroeconomic player market with automated moving average dampening. |
| `/marry` & `/pat` | None | Social interaction commands (MarriageMaster & VanillaPatPat). |

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

## Standalone Season Two Stack & Operational Deployment (Port 25588)

DrakesPurpur 26.2 acts as the monolithic host kernel powering the **Season Two** operational stack. In this architectural model, **all 94 Slimefun addons are fully encapsulated within the StarSuites monorepo (9 Mega-Suites)**, while core server infrastructure plugins (e.g., LuckPerms, BentoBox, WorldEdit, EssentialsX, MarriageMaster) remain modular plugins.

### 1. Hardware & System Prerequisites
* **Operating System**: Linux x86_64 (glibc 2.35+, Ubuntu 22.04+ or Debian 12+)
* **Java Runtime**: **OpenJDK / Eclipse Temurin Java 25 LTS**
* **Memory Allocation**: 8 GB minimum, 12-16 GB recommended for multi-modal operation
* **Native Rust Acceleration**: `plugins/Slimefun/native/libslimefun_ffi.so` compiled for target architecture (Tiger Lake / AVX-512 VNNI compatible)
* **Ports**: Game Port `25588` (TCP/UDP), RCON Port `25589` (TCP)

### 2. Recommended Production Startup Script (`run.sh`)
```bash
#!/usr/bin/env bash
JAVA_BIN="/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
JAR_FILE="drakespurpur-standalone.jar"

exec "$JAVA_BIN" \
  -Xms8G -Xmx12G \
  -XX:+UseG1GC \
  -XX:+ParallelRefProcEnabled \
  -XX:MaxGCPauseMillis=20 \
  -XX:+UnlockExperimentalVMOptions \
  -XX:+DisableExplicitGC \
  -XX:+AlwaysPreTouch \
  -XX:G1NewSizePercent=30 \
  -XX:G1MaxNewSizePercent=40 \
  -XX:G1ReservePercent=15 \
  -XX:G1HeapWastePercent=5 \
  -XX:G1MixedGCCountTarget=4 \
  -XX:InitiatingHeapOccupancyPercent=15 \
  -XX:G1MixedGCLiveThresholdPercent=90 \
  -XX:G1RSetUpdatingPauseTimePercent=5 \
  -XX:SurvivorRatio=32 \
  -XX:+PerfDisableSharedMem \
  -XX:MaxTenuringThreshold=1 \
  --enable-native-access=ALL-UNNAMED \
  -Dusing.aikars.flags=https://mcflags.emc.gs \
  -Daikars.new.flags=true \
  -jar "$JAR_FILE" --nogui
```

### 3. Architecture & Plugin Demarcation
To ensure maximum stability and zero class collisions, Season Two enforces strict domain separation:

* **Inside the Suites (`plugins/Drakes*.jar`):**  
  All 94 technical Slimefun addons are distributed across 9 compiled suites:
  * `DrakesCore.jar`: Kernel, SQLite WAL, Dough-core, and Spatial Ticker Engine.
  * `DrakesTech.jar`: Networks, Infinity, DynaTech, Supreme, FluffyMachines, Nanotech, EMCTech, Quaptics, etc.
  * `DrakesBio.jar`: Genetic Chickens, Cultivation, SlimyBees, MobCapturer, TreeTaps, Gastronomicon, GlobalWarming, Drugfun.
  * `DrakesMagic.jar`: AlchimiaVitae, Crystamae, RelicsOfCthonia, SoulJars, Netheopoiesis, TranscEndence, SpiritsUnchained, etc.
  * `DrakesGenerators.jar`: UltimateGenerators, LiteXpansion, SMG, EcoPower, OreChunks, BetterReactors, Liquid.
  * `DrakesUtility.jar`: Backpacks, ColoredEnderChests, ChestTerminal, SFCalc, SlimeHUD, SoundMuffler, ExtraTools, PatPat, etc.
  * `DrakesCombat.jar`: SlimeTinker, Warfare, ExtraGear, ObsidianExpansion, SlimefunNukes, Galactifun, HardcoreSlimefun, etc.
  * `DrakesServer.jar`: Star Engine, Dynamic Market, InvSwitcher (6 modalities), PlayerVaultZ, AxGraves, BreweryX.
  * `DrakesMultiverse.jar`: Sovereign Suite by Chagui68 (MultiverseNets & MultiverseCreatures).
* **Outside the Suites (Modular Infrastructure in `plugins/`):**  
  Network and administration plugins: `LuckPerms`, `Vault`, `WorldEdit`, `WorldGuard`, `EssentialsX`, `nLogin`, `nAntiBot`, `TAB`, `DiscordSRV`, `Geyser`, `ViaVersion`, `BentoBox` (BSkyBlock, AOneBlock, OneChunk), `MarriageMaster`, `Multiverse-Core`, `DecentHolograms`, `ProtocolLib`.

### 4. Dimensions & Datapack Layout
Modern Minecraft (26.2/Paper) stores auxiliary multi-world dimensions under `world/dimensions/minecraft/`:
* **Dimensions Path**: `world/dimensions/minecraft/<dimension_name>` (32 dimensions loaded, including `clasico`, `clasico_nether`, `clasico_the_end`, `laboratorio`, `oneblock_world`, `onechunk_world`, `bskyblock_world`, `galactifun_*`).
* **Datapacks Path**: `world/datapacks/`
  * Includes `Terralith_26.2_v2.6.4.zip` for vanilla world generation in `survival_clasico`.
  * Includes `Continents_v1.1.13.zip`, `Geophilic`, and `Dungeons and Taverns`.

### 5. Gameplay & Environmental Controls
* **HardcoreSlimefun (`enabled: false` by default):**
  * Research loss on death and hazardous machine failures are completely disabled by default to prevent unfair player deaths.
  * Toggleable dynamically in-game by operators:
    * `/hardcoresf toggle` (or `/hsf toggle`)
    * `/hardcoresf on` / `/hardcoresf off`
    * `/hardcoresf reload`
* **GlobalWarming (`eventos-climaticos.enabled: false` by default):**
  * Catastrophic environmental destruction (sea level rise, fire storms) is suppressed by default in `plugins/DrakesBio/modules/globalwarming.yml`.
  * Managed via administrative command: `/gw`.

### 6. Verification & Operational Health Check
To verify server operational status via RCON or console:
```bash
# Check loaded plugins and suites
plugins

# Verify tick performance (target: 20.0 TPS)
tps

# Verify loaded multi-world dimensions
mv list

# Execute synthetic kernel anticheat and crash-guard tests
/drakes test all

# Inspect StarSuites active modules
/drakessuites
```

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
