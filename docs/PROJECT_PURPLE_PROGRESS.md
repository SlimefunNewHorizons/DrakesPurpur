# PROJECT PURPLE — INFORME DE RECUPERACIÓN, ARQUITECTURA Y PROGRESO CONTINUO

> **Fecha de actualización:** 2026-11-07
> **Rama activa (Servidor):** `experimental/horizon-rust-hybrid-engine` (PR #1 en borrador, sin fusionar a ramas estables)
> **Repositorio Launcher:** `SlimefunNewHorizons/DrakeStar-Launcher` (`main`, commit `c7db120`)
> **Entorno de ingeniería:** Nexus (Windows 11 + WSL2 Ubuntu + JDK 21 & JDK 25 Temurin + Rust 1.94.1 + Node 24) + GitHub Actions CI

---

## 1. AUDITORÍA DE RECUPERACIÓN (FASE 1 COMPLETADA)

Antes de realizar modificaciones se auditaron todos los repositorios, ramas, commits y ejecuciones de GitHub Actions:

### 1.1 Estado de `SlimefunNewHorizons/DrakesPurpur`
- **Ramas remotas:** `ver/26.1` (rama por defecto), `experimental/horizon-rust-hybrid-engine` (PR #1).
- **Último commit previo de Claude:** `e7e28fa78c` (`test(hybrid): matriz de prioridades de prefetch, condiciones equivalentes OFF/ON y cobertura por ids`).
- **Verificación de CI del commit `e7e28fa78c` (4 workflows ejecutados):**
  1. **Build (`42325872255`):** **SUCCESS** — Compilación completa de `DrakesPurpur` + `compat-smoke` JAR en Java 25.
  2. **Hybrid Engine CI (`42325872268`):** **SUCCESS** — Compiló `libdrakes_hybrid_ffi.so`, `drakes-hybrid.dll` y `libdrakes_hybrid_ffi.dylib`; empaquetó el Paperclip JAR y ejecutó el autotest completo (`RESULTADO: OK`, 0 diferencias NBT en 13 872 contenedores, 0 diferencias en 600 000 evaluaciones de `NormalNoise`, 8/8 subcomandos `/drakes hybrid`, codec `horizon:lod` v1 0 fallos).
  3. **Hybrid compat (`42325872258`):** **SUCCESS** — Ejecutó 4 corridas intercaladas (`OFF1`, `ON1`, `ON2`, `OFF2`) con `Slimefun4-Drake` (`port-26x`) + `Drakes-Suites` (`26.x`). Las 4 fases mantuvieron **TPS = 20.00** y pasaron **156 PASS en arranque inicial + 123 PASS tras reinicio** (persistencia SQLite + PDC verificada, 0 SEVERE/ERROR).
  4. **Hybrid ecosystem (`42325872306`):**
     - `core`: **SUCCESS** (`DrakesPurpur` + `Slimefun4-Drake` + `Drakes-Suites`).
     - `addons (0..7)`: **70/112 addons construidos (62.5%)** en la primera ejecución.
     - `integrate`: **FAILURE** en el paso 1 de `eco_boot_load` por dos causas raíz que hemos resuelto en esta sesión.

### 1.2 Estado de `SlimefunNewHorizons/DrakeStar-Launcher`
- **Rama remota:** `main` (actualizada a `c7db120`).
- **Workflow CI (`24060246046`):** **SUCCESS** en los 3 jobs (`Core & CLI`, `UI (svelte-check + vite build)`, `Tauri desktop app`).
- **Reproducción local en Nexus:**
  - `cargo test` en `drakestar-core` (13/13), `drakestar-net` (6/6), `drakestar-app` (32/32) -> **51/51 tests en verde**.
  - `npm run build` en `ui/` -> `svelte-check found 0 errors and 0 warnings` + bundle Vite de producción construido en 1.18s (añadido `resolve: { preserveSymlinks: true }` en `ui/vite.config.ts` para soportar junctions de Windows además de rutas canónicas).

---

## 2. RESOLUCIÓN DE ANOMALÍAS Y HALLAZGOS DE RENDIMIENTO (FASE 4 & 5)

En los informes previos habían quedado abiertas tres cuestiones críticas que el CI del commit `e7e28fa78c` permitió responder con datos reales:

1. **Anomalía de `bits=false` al final del autotest:** **RESUELTA.** `HybridSelfTest` restaura `initialBits` antes de `adminSmoke`, y `/drakes hybrid status` reporta `Modo: HIBRIDO (aceleraciones activas) | bits=true`.
2. **Anomalía de TPS (5.05 en OFF vs 20.00 en ON):** **RESUELTA.** En el job `Hybrid compat` con 4 fases equivalentes (`OFF1 -> ON1 -> ON2 -> OFF2`), las 4 fases registraron exactamente **TPS 1m/5m/15m = 20.00 / 20.00 / 20.00** y **MSPT = 1.62–1.65 ms**. La diferencia anterior era un artefacto de medir TPS inmediatamente después de generar chunks síncronos en la primera corrida.
3. **Matriz de prioridades de Prefetch (`R-urgent`, `R-normal`, `P-normal`, `P-promovido`) a 24 bloques/s (8 corridas intercaladas ABCD DCBA):**
   - `R-urgent` (reactivo urgente, comportamiento base de Paper): **28% columnas tarde**, lateness `p50 = 426 ms`, `p95 = 1689 ms`.
   - `R-normal` (reactivo normal): **51% columnas tarde**, lateness `p50 = 1580 ms`, `p95 = 3510 ms`.
   - `P-normal` (prefetch normal sin promoción, diseño antiguo): **51% columnas tarde**, lateness `p50 = 1480 ms`, `p95 = 3600 ms`.
   - **`P-promovido` (prefetch normal + promoción a `urgent=true` al entrar al borde de visión):** **18% columnas tarde**, lateness **`p50 = 374 ms`**, **`p95 = 1301 ms`**.
   - **Conclusión técnica:** El cuello de botella del prefetch anterior era que las tareas solicitadas con `urgent=false` nunca se promovían cuando el jugador alcanzaba el borde del radio de visión, quedando detrás de tareas urgentes en el planificador de Moonrise. Con promoción de dos niveles (`P-promovido`), las columnas tardías bajan de **28% a 18%** (-35% relativas) y la latencia `p95` mejora de **1689 ms a 1301 ms** (-23%).
   - **Implementación en producción (`HybridChunkPrefetch.java`):** Actualizado `HybridChunkPrefetch` para aplicar la política `P-promovido` en dos niveles: pre-solicita el abanico lejano con `urgent = false` y promueve a `urgent = true` (`requestOrPromote`) los chunks del borde inmediato (`step == viewR + 1`, eje central) bajo un presupuesto acotado (`maxUrgentInFlight = 4`), exponiendo las métricas `promovidos` y `urgentes` en `/drakes hybrid chunks`.

---

## 3. AVANCES EN DRAKESLOD (`HorizonLodCache`) Y ECOSISTEMA DE 112 ADDONS (FASE 3 & 6)

### 3.1 Caché LRU multi-escala con invalidación $O(1)$ (`HorizonLodCache.java`)
- Creada [`HorizonLodCache.java`](../purpur-server/src/main/java/org/purpurmc/purpur/hybrid/lod/HorizonLodCache.java) en `org.purpurmc.purpur.hybrid.lod`:
  - Almacena tiles muestreados de `256` columnas (`HorizonLodProtocol.Column`) indexados por `TileKey(UUID world, int x, int z, int stride)` con expulsión LRU acotada (`DEFAULT_MAX_ENTRIES = 2048`).
  - Implementa `invalidateChunk(UUID world, int chunkX, int chunkZ)` en tiempo constante **$O(1)$** (7 búsquedas hash para `stride = 1, 2, 4, 8, 16, 32, 64`), aprovechando que todo chunk de `16x16` bloques cae íntegramente dentro de un único tile alineado de tamaño `stride * 16`.
  - Integrada con `HybridEvents` (`BlockBreakEvent` y `BlockPlaceEvent` en prioridad `MONITOR`) y expuesta en `/drakes hybrid chunks` y en el autotest `HybridSelfTest.lodCodecTest`.

### 3.2 Corrección del pipeline de 112 Addons (`.github/workflows/hybrid-ecosystem.yml` y `tools/ecosystem/build_addons.py`)
1. **Subshells y exclusión de colisiones en `hybrid-ecosystem.yml`:**
   - `eco_install`, `eco_props`, `eco_perf` y `eco_boot_load` ahora se ejecutan dentro de subshells `( ... )` para no alterar jamás el `PWD` del job `integrate`.
   - Añadido `DrakesTech` a `ECO_EXCLUDE: "DrakesNanotech,DrakesTech"` (consolidado en `Drakes-Suites`) y filtrado de JARs de librería sin `plugin.yml`/`paper-plugin.yml` al poblar `pool/addons/`.
   - Pre-construcción en el job `core` del parent POM `drakes-slimefun-labs:11-SNAPSHOT` y de las bibliotecas compartidas entre shards (`dough-core`, `drakes-labs-autoupdate`, `SefiLib`, `ExtraUtils-drake`, `SlimeHUD`, `InfinityExpansion`, `FoxyMachines`, `Gastronomicon`).
2. **Normalización automática en el clon temporal (`build_addons.py`):**
   - Instalación de `--parent-pom` sin `<modules>` en `workdir/m2`.
   - En proyectos con `--enable-preview` y Java 21 (`Odysseia`, `Odysseia-Rust`), actualización automática a release `25` (donde Panama FFM `java.lang.foreign.*` es API estándar final).
   - Actualización automática de Lombok `< 1.18.38` a `1.18.46` y paso de `-Dmaven.compiler.proc=full` para que `javac` 25 ejecute los annotation processors sin `ExceptionInInitializerError: TypeTag :: UNKNOWN`.
   - Reescritura en el clon temporal de imports heredados (`com.github.drakescraft_labs.slimefun4.*` -> `io.github.thebusybiscuit.slimefun4.*`, `io.github.bakedlibs.dough.*` -> `io.github.thebusybiscuit.slimefun4.libraries.dough.*` cuando el proyecto no declara `dough-core`, `PaperLib` relocalizado en `SlimyTreeTaps` y `Bukkit.getOnlinePlayers().size()` en `SlimefunAdvancements`).
   - En proyectos Gradle (`Automation`, `CustomItemGenerators`), el `init-script` inyecta tanto `drake.local` como el repositorio `m2` local y deshabilita las tareas `test`/`compileTestJava`/`compileTestKotlin`.
   - **Verificado localmente en Nexus con JDK 25:** `ChestTerminal`, `ElectricSpawners`, `GlobalWarming`, `SlimefunKnowledgeDump`, `Odysseia`, `Odysseia-Rust`, `SlimyTreeTaps`, `SlimefunNukes`, `HardcoreSlimefun`, `IDreamOfEasy` y `SlimefunAdvancements` compilaron todos a `.jar` (`construido`, 0 fallos).

---

## 4. AVANCES EN DRAKESTAR LAUNCHER (FASE 7 & 8)

En `SlimefunNewHorizons/DrakeStar-Launcher` (`main`, commit `c7db120`):
1. **Protocolo de estado de Minecraft (`mc_status_ping` en `crates/drakestar-app/src/ping.rs`):**
   - Implementado el handshake `0x00` + solicitud de estado `0x00` (Server List Ping) leyendo VarInts y parseando el JSON de respuesta (`version.name`, `version.protocol`, `players.online`, `players.max`, `description` MOTD) además de medir la latencia TCP en milisegundos, con test unitario sobre un servidor mock local.
2. **Backups de mundos e instalador de preset Slimefun (`crates/drakestar-app/src/server.rs`):**
   - `ServerDir::backup_world(world_name, dest_dir)` valida `safe_file_name` y la presencia de `level.dat`, copiando recursivamente el mundo a `dest_dir/<world>-backup-<ts>`.
   - `ServerDir::install_slimefun_preset(slimefun_jar, extra_plugins)` instala `Slimefun.jar` y los plugins de suites/addons verificados en `plugins/`.
3. **Soporte multiplataforma Windows + Linux (`crates/drakestar-app/src/process.rs` y `ui/vite.config.ts`):**
   - Añadidos tests nativos de procesos para Windows (`cmd /C`) junto a los de Unix (`sh -c`).
   - Añadido `resolve: { preserveSymlinks: true }` en `ui/vite.config.ts`.
   - **51/51 tests de Rust** y build de producción de `ui/` verificados localmente.
