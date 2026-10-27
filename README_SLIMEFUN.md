# DrakesPurpur: Slimefun Native Engine Monolith
### Architecture and Design Specification: Fusing Slimefun into the Minecraft Kernel Primitives

---

## 1. Vision: Slimefun as First-Order Minecraft Mechanics

Modded technical Minecraft servers historically face an insurmountable architectural compromise:
1. Bukkit/Paper Plugin Model: Unbeatable compatibility and ecosystem variety, but constrained by PluginClassLoader indirection, PersistentDataContainer (PDC) string serialization/deserialization overhead, and redundant Bukkit event dispatch queues on every tick.
2. Full Re-implementations (Rust/C): Raw speed improvements, but completely alienated from the Bukkit ecosystem, breaking hundreds of existing community plugins, permission systems, and game integrations.

The Slimefun Native Engine is the engineering breakthrough conceived by Jack (JackStar6677-1):
Integrating Slimefun core mechanics directly into the Minecraft server kernel (purpur-server).

Instead of simulating electrical cables, solar generators, and genetic incubators as vanilla blocks with custom PDC metadata tags, the server kernel treats Slimefun as first-order Minecraft mechanics (comparable to native Redstone, Campfires, or Furnaces).

---

## 2. Technical Roadmap and Architectural Differences

```
Legacy Slimefun Plugin Model:
[Minecraft World Tick]
       |
[Bukkit Event Queue]
       |
[PluginClassLoader]
       |
[Slimefun Ticker Listener]
       |
[PDC String Deserialization]
       |
[Block Modification]

Slimefun Native Engine Monolith:
[Minecraft World Tick]
       |
[ServerLevel.tickBlockEntities]
       |
[Native SlimefunBlockEntity]
       |
[Rust FFM SIMD Compute Off-Heap]
       |
[Direct In-Memory State Mutation]
```

### Direct Kernel Primitives
* Native Block Entities: Slimefun machines extend net.minecraft.world.level.block.entity.BlockEntity directly.
* Zero PDC Overhead: Eradicates the requirement to serialize, parse, and string-match slimefun:slimefun_item compound tags on every chunk load and block tick.
* In-Memory Ticking: Machine cycles tick alongside native Minecraft furnaces and hoppers in ServerLevel.tickBlockEntities(), avoiding Bukkit event dispatch costs entirely.

### Embedded Toolchain and Upstream Migration
This architecture incorporates the sovereign porting toolchain in tools/porting/portar_drakespurpur.py:
* Automatically remaps legacy namespaces (io.github.thebusybiscuit and me.mrCookieSlime) to internal NMS classes.
* Strips external plugin wrappers and links machine states directly to native Minecraft data registries.

### All-in-One Standalone Distribution
* Produces a single, sovereign executable: DrakesPurpur-Slimefun-26.2.jar.
* Drop-in Deployment: Any server administrator can launch this single JAR and instantly have a fully automated technical Minecraft server with over 80 expansions of machines, networks, and magic enabled out of the box with zero plugin configuration.

---

## 3. Interaction with StarSuites and Season 2 Production Strategy

To protect data integrity across all environments, DrakesCraft operates under strict segregation:
1. Active Production (Dallas, Paper 1.21.11): Runs individual validated standalone JARs to guarantee zero mid-season disruption or inventory risk.
2. Staging / Season 2 (Purpur 26.2): Deploys the modular DrakesPurpur kernel alongside StarSuites (drakes-core to drakes-server + drakes-multiverse by Chagui68).
3. Native Engine Monolith (feat/slimefun-native-engine): Parallel R&D track proving out the zero-overhead kernel architecture for future deployment.

---

## 4. Local Build Instructions

To build the Slimefun Native Monolith binary:

```bash
# Checkout the R&D branch
git checkout feat/slimefun-native-engine

# Build with Java 25
./gradlew applyAllPatches
./gradlew createReobfPaperclipJar -Dorg.gradle.parallel=false
```

The resulting standalone JAR will be compiled at:
purpur-server/build/libs/purpur-paperclip-26.2.local-SNAPSHOT.jar

---

DrakesCraft Labs - Star Systems - Engineered by JackStar6677-1
Pushing the Boundaries of Minecraft Server Performance
