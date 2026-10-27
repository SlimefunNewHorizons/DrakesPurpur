<div align="center">

  <img src="banner.svg" alt="Slimefun Native Engine Banner" width="920" />

# DrakesPurpur: Slimefun Native Engine Monolith
### `feat/slimefun-native-engine` - Experimental Next-Gen R&D Branch

<p>
  <img src="https://img.shields.io/badge/Architecture-Native%20Monolith-purple?style=for-the-badge&logo=rust&logoColor=white" alt="Native Monolith"/>
  <img src="https://img.shields.io/badge/Java-25%20LTS-F89820?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Minecraft-26.2-38BDF8?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 26.2"/>
  <img src="https://img.shields.io/badge/Status-Experimental%20R%26D-F43F5E?style=for-the-badge" alt="Experimental R&D"/>
</p>

</div>

---

## Vision: The World's First Native Slimefun Server Engine

Historically, technical modded Minecraft servers are fractured into two conflicting paradigms:
1. **Bukkit Plugin Layers:** High compatibility, but plagued by `PluginClassLoader` indirection, PDC serialization overhead, and redundant Bukkit event queues.
2. **Total Re-implementations (Rust/C):** Impressive raw speeds, but incompatible with 200+ existing Java plugins, Spigot APIs, and community addons.

The **Slimefun Native Engine (`feat/slimefun-native-engine`)** is an ambitious R&D project by **Jack (JackStar6677-1)**:  
**Fusing Slimefun's complete item, machine, and energy mechanics directly into Minecraft's native server kernel (`purpur-server`).**

Instead of simulating electrical cables, solar generators, and genetic incubators as vanilla blocks with metadata tags, the server kernel treats **Slimefun as first-order Minecraft mechanics** (comparable to native Redstone, Campfires, or Furnaces).

---

## Technical Roadmap & Architectural Differences

```mermaid
graph TD
    subgraph "Legacy Slimefun Plugin Model"
        A[Minecraft World Tick] --> B[Bukkit Event Queue]
        B --> C[PluginClassLoader]
        C --> D[Slimefun Ticker Listener]
        D --> E[PDC String Deserialization]
        E --> F[Block Modification]
    end

    subgraph "Slimefun Native Engine Monolith"
        G[Minecraft World Tick] --> H[ServerLevel.tickBlockEntities]
        H --> I[Native SlimefunBlockEntity]
        I --> J[Rust FFM SIMD Compute Off-Heap]
        J --> K[Direct In-Memory State Mutation]
    end
```

### 1. Direct Kernel Primitives
- **Native Block Entities:** Slimefun machines extend `net.minecraft.world.level.block.entity.BlockEntity` directly.
- **Zero PDC Overhead:** Eliminates the need to serialize and parse `slimefun:slimefun_item` compound tags on every chunk load.
- **In-Memory Ticking:** Machine cycles tick alongside native Minecraft furnaces and hoppers in `ServerLevel.tickBlockEntities()`, avoiding Bukkit event dispatch costs entirely.

### 2. Embedded Toolchain & Upstream Migration
This branch incorporates the sovereign porting toolchain in [`tools/porting/portar_drakespurpur.py`](tools/porting/portar_drakespurpur.py):
- Automatically remaps legacy namespaces (`io.github.thebusybiscuit` and `me.mrCookieSlime`) to internal NMS classes.
- Strips external plugin wrappers and links machine states directly to native Minecraft data registries.

### 3. All-in-One Standalone Distribution
- Produces a single, sovereign executable: `DrakesPurpur-Slimefun-26.2.jar`.
- **Drop-in Deployment:** Any server administrator can launch this single JAR and instantly have a fully automated technical Minecraft server with over 80 expansions of machines, networks, and magic enabled out of the box with zero plugin configuration.

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
         BRANCH: ver/26.2 (Modular Production)             BRANCH: feat/slimefun-native-engine (Monolith)
  +----------------------------------------------+  +----------------------------------------------+
  | - Hardened Purpur Kernel (Panama FFM,        |  | - Complete Ingestion of Slimefun & Addons    |
  |   GrimGuard, ChatGuard, Crash Guard SQLite). |  |   directly into native Minecraft NMS.        |
  | - 9 StarSuites modular outside in plugins/.  |  | - Custom items & blocks as 1st-class objects |
  | - Decoupled modules/*.yml per addon.         |  |   (zero PDC serialization / compound tags).  |
  | - Hot-reloading via /drakessuites reload.    |  | - In-memory ticking in ServerLevel loop.     |
  | - Zero downtime, instant config tuning.      |  | - Single standalone executable JAR.          |
  +----------------------------------------------+  +----------------------------------------------+
```

### Branch Comparison: What Each Architecture Achieves

| Feature & Objective | Branch `ver/26.2` (Production Modular) | Branch `feat/slimefun-native-engine` (Monolith) |
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

---

## Branch Status & Production Notice

> [!WARNING]
> **EXPERIMENTAL R&D BRANCH:** This branch (`feat/slimefun-native-engine`) is actively undergoing heavy architectural refactoring and is NOT intended for immediate live deployment on Dallas.
>
> The verified, rock-solid production release for **Season Two** lives on the **[`ver/26.2`](https://github.com/SlimefunNewHorizons/DrakesPurpur/tree/ver/26.2)** branch, utilizing the **DrakesPurpur Modular Kernel + StarSuites**.

---

## Building the Experimental Monolith

```bash
# Checkout this branch
git checkout feat/slimefun-native-engine

# Build with Java 25
./gradlew applyAllPatches
./gradlew createReobfPaperclipJar -Dorg.gradle.parallel=false
```

---

<div align="center">

**DrakesCraft Labs · Star Systems** · Engineered by [**JackStar6677-1**](https://github.com/JackStar6677-1)  
*Pushing the Boundaries of Minecraft Server Performance*

</div>
