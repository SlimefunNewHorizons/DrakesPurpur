# Estado del proyecto y pendientes (actualizado en Nexus, 2026-11-07)

Documento de traspaso y estado continuo. Todo lo implementado en el servidor está en la rama `experimental/horizon-rust-hybrid-engine` (PR #1, **borrador, sin fusionar, sin desplegar**). El launcher vive en su propio repositorio: https://github.com/SlimefunNewHorizons/DrakeStar-Launcher.

## 1. Hecho y verificado
- **Motor híbrido Java + Rust**: biblioteca nativa (`native/drakes-hybrid`) vía FFM con ABI versionado, fallback a Java idéntico, extracción embebida por plataforma verificada con SHA-256, interruptor maestro `hybrid.engine.enabled`. Paridad bit a bit: 13 872 contenedores NBT, 200 000 puntos × 3 ruidos, 0 diferencias (verificado en CI run `42325872268` y en local en Nexus con 10/10 tests Rust).
- **Comandos `/drakes hybrid` y GUI de 27 slots**: 8/8 subcomandos probados en el servidor real; ahora expone también métricas de promoción urgente en prefetch (`promovidos`, `urgentes`) y de la caché LRU de `DrakesLOD` (`HorizonLodCache`).
- **Codec `horizon:lod` v1 + Caché LRU con invalidación $O(1)$ (`HorizonLodCache`)**:
  - Codec de protocolo 0 fallos.
  - `HorizonLodCache` implementada y verificada en `HybridSelfTest.lodCodecTest`: caché LRU acotada por tile `(world, tileX, tileZ, stride)` con invalidación $O(1)$ a través de todas las escalas (`stride = 1..64`) al modificarse un chunk (`BlockBreakEvent` / `BlockPlaceEvent` registrados en `HybridEvents`).
- **Prefetch direccional de dos niveles (`P-promovido`) en `HybridChunkPrefetch`**:
  - Los chunks que entran en el borde inmediato del radio de visión (`step == viewR + 1`, eje central del rumbo) se promueven a prioridad urgente (`urgent = true`) respetando un presupuesto acotado (`maxUrgentInFlight = 4`), mientras el resto del abanico (`step > viewR + 1`) mantiene `urgent = false`.
  - Validado por los resultados del experimento A/B/C/D en CI run `42325872268`: `P-promovido` redujo las columnas tardías de `28%` (`R-urgent`, p95 1689 ms) y `51%` (`P-normal`, p95 3600 ms) a **`18%` (p50 374 ms, p95 1301 ms)**.
- **Compatibilidad y matriz de 112 addons (`hybrid-ecosystem.yml` + `tools/ecosystem/build_addons.py`)**:
  - Corregido el bug de directorio de trabajo en el job `integrate` de `.github/workflows/hybrid-ecosystem.yml` (subshells en `eco_install`, `eco_props`, `eco_perf`, `eco_boot_load`), exclusión de `DrakesTech` duplicado frente a `Drakes-Suites` y filtrado de JARs de librería sin `plugin.yml`.
  - Pre-construcción de `drakes-slimefun-labs:11-SNAPSHOT` (`--parent-pom`) y librerías compartidas (`dough-core`, `drakes-labs-autoupdate`, `SefiLib`, `ExtraUtils-drake`, `SlimeHUD`, `InfinityExpansion`, `FoxyMachines`, `Gastronomicon`) en `m2-seed` para los 8 shards.
  - Normalización automática en el clon efímero (`normalize_addon_sources`) para compatibilidad con JDK 25 y el JAR universal de `Slimefun4-Drake` (`port-26x`), verificada localmente en Nexus construyendo con éxito `ChestTerminal`, `ElectricSpawners`, `GlobalWarming`, `SlimefunKnowledgeDump`, `Odysseia`, `Odysseia-Rust`, `SlimyTreeTaps`, `SlimefunNukes`, `HardcoreSlimefun`, `IDreamOfEasy` y `SlimefunAdvancements`.

## 2. Resultados de rendimiento: qué se puede y qué no afirmar
- **Kernels nativos pequeños (bits/noise puntual)**: guardado x1.05, carga x0.98, density x1.03–1.10; dentro del ruido del runner. El empaquetado de bits gana x3.97 en el microbenchmark con lotes de 4096 pero en el servidor representa ~1 % del coste de un chunk.
- **Prefetch direccional con promoción (`P-promovido`)**: mejora real sobre el planificador de Moonrise en la matriz controlada ABCD+DCBA (de 28% a 18% de columnas tardías y reducción de p95 de 1689 ms a 1301 ms a 24 bloques/s). Se mantiene apagado por defecto (`native.hybrid.prefetch.enabled: false`) hasta completar pruebas con jugadores reales.
- **Anomalías anteriores cerradas en CI run `42325872268`**:
  - `bits=false` al cierre del autotest: **resuelto** (`bits=true` preservado).
  - Asimetría de TPS entre modos OFF y ON: **resuelta** (`TPS = 20.00 / 20.00 / 20.00` en las 4 fases `OFF1 -> ON1 -> ON2 -> OFF2`).

## 3. DrakeStar Launcher (`SlimefunNewHorizons/DrakeStar-Launcher`)
Publicado y actualizado en `main` (commit `c7db120`):
- **51/51 tests unitarios e integración en Rust** (`13` en `drakestar-core`, `6` en `drakestar-net`, `32` en `drakestar-app`) + `svelte-check` (0 errores/warnings) + `vite build` limpio en Windows y Linux.
- Implementado y probado:
  - Protocolo de estado de Minecraft (**Server List Ping `0x00`**, `mc_status_ping`) además de `tcp_ping`.
  - Copias de seguridad completas de mundos (`ServerDir::backup_world`) e instalador de preset Slimefun (`ServerDir::install_slimefun_preset`).
  - Pruebas del gestor de procesos tanto para Unix (`sh -c`) como para Windows (`cmd /C`), y `preserveSymlinks: true` en Vite para compilación desde junctions/symlinks en Windows.

## 4. Pendiente (en orden de prioridad)
1. Verificar el resultado completo de los 8 shards en la nueva ejecución de `.github/workflows/hybrid-ecosystem.yml` tras el push de esta sesión.
2. Pruebas funcionales profundas en vivo (máquinas concurrentes, redes de energía, GUIs/inventarios con jugador real, jefes).
3. Conectar `HorizonLodProtocol` + `HorizonLodCache` al canal de configuración/red cuando exista un cliente Horizon compatible con protocolo 26.2/26.3 y tabla de traducción de estados de bloque.
4. En `DrakeStar-Launcher`: lanzamiento de cliente Java/modpacks y prueba automática end-to-end contra el JAR completo de `DrakesPurpur`.

## 5. Reglas vigentes
Trabajar solo en la rama experimental; PR #1 en borrador; no fusionar ni desplegar automáticamente en producción (Dallas es estrictamente de solo lectura); no modificar repositorios secundarios de addons sin autorización; tecnologías experimentales desactivadas por defecto.
