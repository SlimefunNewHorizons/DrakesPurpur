<div align="center">

  <img src="banner.svg" alt="DrakesPurpur Banner" width="920" />

# ⚡ DrakesPurpur 26.2

**High-Performance Sovereign Minecraft Server Engine for DrakesCraft Season Two & Technical Networks**

<p>
  <a href="https://github.com/SlimefunNewHorizons/DrakesPurpur"><img src="https://img.shields.io/badge/GitHub-DrakesPurpur-181717?style=for-the-badge&logo=github" alt="GitHub"/></a>
  <img src="https://img.shields.io/badge/Java-25%20LTS-F89820?style=for-the-badge&logo=openjdk&logoColor=white" alt="Java 25"/>
  <img src="https://img.shields.io/badge/Minecraft-26.2-38BDF8?style=for-the-badge&logo=minecraft&logoColor=white" alt="Minecraft 26.2"/>
  <img src="https://img.shields.io/badge/Rust-FFM%20Native-DEA584?style=for-the-badge&logo=rust&logoColor=white" alt="Rust FFM"/>
  <img src="https://img.shields.io/badge/Bedrock-Geyser%20Optimized-0ea5e9?style=for-the-badge&logo=android&logoColor=white" alt="Bedrock Optimized"/>
</p>

</div>

> ### 🏰 Official DrakesCraft Community & Infrastructure
> 
> * 🎮 **Server IP**: `mc.drakescraft.cl` *(Java 26.2 & Bedrock Cross-play)*
> * 💬 **Official Discord**: [discord.gg/drakescraft](https://discord.gg/rv3vtXZTk7)
> * 🌐 **Web Portal & Documentation**: [web.drakescraft.cl](https://web.drakescraft.cl) — 🛒 **Official Store**: [web.drakescraft.cl/store](https://web.drakescraft.cl/store.html)
> 
> *DrakesPurpur is the sovereign kernel driving Season Two of DrakesCraft, powering 80+ Slimefun expansions, dynamic economies, multi-modal isolation, and rock-solid 20.0 TPS.*

---

## 🎯 Mission & Philosophy

High-concurrency Minecraft servers running massive technical addons (such as Slimefun, automated logistics, and custom multiblock machines) historically collapse due to **JVM Garbage Collection pauses** and the overhead of millions of Bukkit events firing every second.

**DrakesPurpur** is a sovereign fork of [PurpurMC/Purpur](https://github.com/PurpurMC/Purpur) engineered with a singular objective:  
**Transform Minecraft into an ultra-low-latency, self-healing technical engine, hardened against unhandled exceptions and natively synchronized with autonomous SRE orchestrators (SAORI) and mobile Bedrock players (Geyser).**

---

## ⚡ Core Architectural Pillars

### 1. 🛡️ Kernel Crash Guard & Self-Healing (ZERO Server Crashes)
- Intercepts unhandled exceptions (`NullPointerException`, `IndexOutOfBoundsException`, `ConcurrentModificationException`) directly within the main tick loop (`ServerTick` and `BlockEntityTick`).
- **Dynamic Fault Isolation:** If an addon machine or entity throws an unhandled error, the engine safely disables only the faulty component and commits the incident into an **atomic SQLite WAL database (`drakes_faults.db`)**. The server never halts, and players experience continuous 20.0 TPS without disconnects.

### 2. 🦀 Native Rust Off-Heap Engine (Java 25 Panama FFM)
- **The True Bottleneck of Minecraft:** In heavily modded technical servers, the bottleneck is rarely raw CPU compute; it is the **millions of transient Java objects** generated per tick that overwhelm the Garbage Collector, resulting in disruptive *Stop-The-World (STW) pauses*.
- **Why NOT rewrite 200 repositories to Rust/C:** Rewriting years of Bukkit/Paper plugins from scratch is infeasible and counterproductive. The pragmatic enterprise design is **hybrid**: Java executes game logic, events, and plugins, while **Rust manages pure off-heap memory** via the modern **Panama FFM (Foreign Function & Memory API)** without legacy JNI overhead.
- **Kernel Preloading:** The server boots by preloading the acceleration binary [`libslimefun_ffi.so`](natives/libslimefun_ffi.so).
- Electrical grids, quantum transfers, and continuous machine ticking are calculated outside the Java Heap in parallel native memory with SIMD vectorization (AVX-512 VNNI), keeping G1GC pauses sub-15ms.

### 3. 📱 Geyser & Bedrock Companion Engine (Mobile & Console Tuning)
- **Packet Redundancy Compensation:** Throttles non-critical entity updates to conserve mobile bandwidth.
- **Predictive Void & Boundary Recovery:** Eliminates ghost void deaths in End/Skyblock environments via proactive boundary repositioning (`teleport-if-outside-border`).
- **Decoupled Keepalive Mechanism:** Uses asynchronous alternate keepalive handshakes to prevent false timeout disconnects for high-latency mobile and cellular connections.

### 4. 🤖 SAORI Kernel Bridge (Direct Socket Telemetry)
- Native IPC / Unix Domain Socket connection linking the engine directly to the **SAORI Autonomous SRE Daemon** (`saori-ai-daemon` on port 8089).
- Replaces heavy external Discord bridge plugins and disk log scraping (`latest.log`).
- Enables sub-millisecond bidirectional chat bridging, real-time crash alerting, and automated incident triage by SRE AI agents.

### 5. 📊 DrakesProfiler & Ryzen Multi-Core Dispatcher (Zero-Dependency Spark Replacement)
- **Direct Kernel Telemetry:** Reads CPU metrics, JVM memory, and thread utilization directly from `ManagementFactory.getThreadMXBean()` and `OperatingSystemMXBean` with zero overhead.
- **Root Cause Analysis (Anti-Lag):** Instant breakdown of total loaded chunks, entity counts, living mob density per world, and the top CPU-hogging threads.
- **Local Markdown (.md) Reports:** Generates standalone diagnostic files in `drakes_reports/` containing markdown tables and hardware metrics without uploading sensitive server data to third-party web viewers.
- **Multi-Core Ryzen 9 Scheduler:** Utilizes `RyzenWorkerPool` (ForkJoinPool) to parallelize async lookups and SQLite WAL commits across all available processor cores.

### 6. 🛡️ DrakesGrimGuard & DrakesChatGuard (Zero-Lag Kernel Security)
- **Prescindiendo de GrimAC:** Validación directa en el kernel (`DrakesGrimGuard`) de Reach geométrico (<3.20m), velocidad horizontal (<4.20 b/t), fly y clicks por segundo (<22 CPS) antes de enrutar paquetes a la capa Bukkit.
- **Prescindiendo de Chat Plugins Externos:** `DrakesChatGuard` incorpora filtro anti-spam (600ms de cadencia), censura bilingüe en tiempo real (`§8[censurado]§r`), prevención de paquetes crash (>256 caracteres) e intercepción de exploits de inyección de comandos (`/op`, `/calc`, `/execute`).
- **Pruebas Sintéticas CLI Sin GUI:** `/drakes test all` ejecuta pruebas automáticas en consola validando el anticheat, chat y el motor Rust sin requerir clientes conectados.

### 7. 🔬 Slimefun Native Monolith (Experimental Branch: `feat/slimefun-native-engine`)
- Parallel R&D branch exploring the complete ingestion of Slimefun core mechanics into native Minecraft classes:
  - Slimefun custom items are evaluated as first-order `Block` and `Item` primitives, removing PDC metadata and wrapper overhead.
  - Produces an all-in-one standalone `DrakesPurpur-Server.jar` that launches Minecraft with full technical automation out of the box.

---

## ☁️ CI/CD Pipeline & Sovereign Maven Distribution

DrakesPurpur is fully automated via **GitHub Actions** ([`.github/workflows/drakes_ci_cd.yml`](.github/workflows/drakes_ci_cd.yml)):
1. **Cloud Compilation:** Every push to `ver/**` builds the production Paperclip server binary on GitHub runners using **Java 25** and Paperweight.
2. **Automated API Deployment:** Builds and deploys `DrakesPurpur-API` directly to our sovereign repository at **[`https://maven.drakescraft.cl`](https://maven.drakescraft.cl)**.
3. **Webhook Ingestion:** Delivers binary artifacts to staging with zero local CPU or RAM overhead on developer workstations.

---

## 📜 Local Build Instructions

To build DrakesPurpur locally on Linux (Nova / Workstations):

```bash
# Requires Java 25 LTS set in JAVA_HOME
./gradlew applyAllPatches
./gradlew createPaperclipJar -Dorg.gradle.parallel=false -Dorg.gradle.jvmargs="-Xmx3584m -XX:+UseParallelGC"
```

The resulting production artifact will be generated at:  
`purpur-server/build/libs/purpur-paperclip-26.2.local-SNAPSHOT.jar`.

---

## 🙏 Upstream Attribution & Acknowledgments

DrakesPurpur is a sovereign fork engineered and maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1) under **DrakesCraft Labs** and **Star Systems**.

We express our sincere gratitude to the upstream projects and sovereign co-creators that make this platform possible:
- **[PurpurMC](https://github.com/PurpurMC/Purpur):** For the most configurable and fun Minecraft server software.
- **[PaperMC](https://github.com/PaperMC/Paper):** For pioneering modern server performance, Paperweight patching, and the Bukkit API.
- **Chagui68 (Chagui):** Autor soberano de la Suite **Multiverse** (`MultiverseNets`, `MultiverseCreatures`, `MultiverseTinker`, `MultiverseProgramming` y `MultiverseGambling`). Reconocido en el kernel `/drakes credits`.
- **[GeyserMC](https://geysermc.org/):** For bridging Bedrock and Java editions into a unified community.
- **[TheBusyBiscuit](https://github.com/TheBusyBiscuit):** For creating Slimefun4, the foundation of modern technical survival.
- **[SpigotMC](https://www.spigotmc.org/) & [Mojang Studios](https://www.minecraft.net/):** For the Minecraft sandbox universe.

---

<div align="center">

**DrakesCraft Labs · Star Systems** · Maintained by [**JackStar6677-1**](https://github.com/JackStar6677-1)  
*Sovereign Software Architecture for Season Two*

</div>
