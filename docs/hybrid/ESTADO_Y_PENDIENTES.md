# Estado del proyecto y pendientes (actualizado en Nexus, 2026-11-07)

Documento de traspaso y estado continuo. Todo lo implementado en el servidor está en la rama `experimental/horizon-rust-hybrid-engine` (PR #1, **borrador, sin fusionar, sin desplegar**). El launcher vive en su propio repositorio: https://github.com/SlimefunNewHorizons/DrakeStar-Launcher.

## 1. Hecho y verificado
- **Motor híbrido Java + Rust**: biblioteca nativa (`native/drakes-hybrid`) vía FFM con ABI versionado, fallback a Java idéntico, extracción embebida por plataforma verificada con SHA-256, interruptor maestro `hybrid.engine.enabled`. Paridad bit a bit: 13 872 contenedores NBT, 200 000 puntos × 3 ruidos, 0 diferencias (verificado en CI; las cifras locales de Nexus no son comprobables desde aqui).
- **Comandos `/drakes hybrid` y GUI de 27 slots**: 8/8 subcomandos probados en el servidor real; ahora expone también métricas de promoción urgente en prefetch (`promovidos`, `urgentes`) y de la caché LRU de `DrakesLOD` (`HorizonLodCache`).
- **Codec `horizon:lod` v1 + Caché LRU con invalidación $O(1)$ (`HorizonLodCache`)**:
  - Codec de protocolo 0 fallos.
  - `HorizonLodCache` implementada y verificada en `HybridSelfTest.lodCodecTest`: caché LRU acotada por tile `(world, tileX, tileZ, stride)` con invalidación $O(1)$ a través de todas las escalas (`stride = 1..64`) al modificarse un chunk (`BlockBreakEvent` / `BlockPlaceEvent` registrados en `HybridEvents`).
- **Prefetch direccional de dos niveles (`P-promovido`) en `HybridChunkPrefetch`**: implementado (promocion a `urgent=true` en el borde de vision, presupuesto acotado). **Su beneficio NO esta demostrado**: en los logs de CI (runs 42323576926, 42337292514, 42339791412) `P-promovido` queda en ~55–62 % de columnas tardias, igual o peor que el reactivo urgente (~50–57 %) y mucho mejor solo que un prefetch sin promocion (~71–78 %). Las cifras 28 %→18 % que aparecian aqui **no estan respaldadas por ningun log** y se retiraron. Ver `RESULTADOS.md`.
- **Compatibilidad y matriz de 112 addons (`hybrid-ecosystem.yml` + `tools/ecosystem/build_addons.py`)**:
  - Corregido el bug de directorio de trabajo en el job `integrate` de `.github/workflows/hybrid-ecosystem.yml` (subshells en `eco_install`, `eco_props`, `eco_perf`, `eco_boot_load`), exclusión de `DrakesTech` duplicado frente a `Drakes-Suites` y filtrado de JARs de librería sin `plugin.yml`.
  - Pre-construcción de `drakes-slimefun-labs:11-SNAPSHOT` (`--parent-pom`) y librerías compartidas (`dough-core`, `drakes-labs-autoupdate`, `SefiLib`, `ExtraUtils-drake`, `SlimeHUD`, `InfinityExpansion`, `FoxyMachines`, `Gastronomicon`) en `m2-seed` para los 8 shards.
  - Normalización automática en el clon efímero (`normalize_addon_sources`) para compatibilidad con JDK 25 y el JAR universal de `Slimefun4-Drake` (`port-26x`), verificada localmente en Nexus construyendo con éxito `ChestTerminal`, `ElectricSpawners`, `GlobalWarming`, `SlimefunKnowledgeDump`, `Odysseia`, `Odysseia-Rust`, `SlimyTreeTaps`, `SlimefunNukes`, `HardcoreSlimefun`, `IDreamOfEasy` y `SlimefunAdvancements`.

## 2. Resultados de rendimiento: qué se puede y qué no afirmar
- **Kernels nativos pequeños (bits/noise puntual)**: guardado x1.05, carga x0.98, density x1.03–1.10; dentro del ruido del runner. El empaquetado de bits gana x3.97 en el microbenchmark con lotes de 4096 pero en el servidor representa ~1 % del coste de un chunk.
- **Prefetch direccional (`P-promovido`)**: sin mejora demostrada sobre el reactivo urgente (ver `RESULTADOS.md`); apagado por defecto.
- **Anomalías anteriores cerradas en los runs de CI (`42323576926`, `42337292514`)**:
  - `bits=false` al cierre del autotest: **resuelto** (`bits=true` preservado).
  - Asimetría de TPS entre modos OFF y ON: **resuelta** (`TPS 1m = 19,88–19,98` en las 4 fases `OFF1 -> ON1 -> ON2 -> OFF2`).

## 3. DrakeStar Launcher (`SlimefunNewHorizons/DrakeStar-Launcher`)
Publicado y actualizado en `main` (commit `c7db120`):
- **51/51 tests unitarios e integración en Rust** (`13` en `drakestar-core`, `6` en `drakestar-net`, `32` en `drakestar-app`) + `svelte-check` (0 errores/warnings) + `vite build` limpio en Windows y Linux.
- Implementado y probado:
  - Protocolo de estado de Minecraft (**Server List Ping `0x00`**, `mc_status_ping`) además de `tcp_ping`.
  - Copias de seguridad completas de mundos (`ServerDir::backup_world`) e instalador de preset Slimefun (`ServerDir::install_slimefun_preset`).
  - Pruebas del gestor de procesos tanto para Unix (`sh -c`) como para Windows (`cmd /C`), y `preserveSymlinks: true` en Vite para compilación desde junctions/symlinks en Windows.

## 3b. Ecosistema completo: lo que el CI en verde SI y NO prueba (run 42337292506, f3d23c6)
- **Construccion**: 100 de 112 addons construidos, 11 fallos (dependencias Maven no resueltas: SefiLib, Gastronomicon-drake, drakes-labs-autoupdate, NetworksV6-drake; errores de compilacion: UltimateGenerators2, BreweryX; 4 sin causa visible: BetterChests, LiteXpansion, Automation, CustomItemGenerators) y 1 inaccesible (Coronalis, privado).
- **Carga**: arranca (`Done`) con 139 jars; cargan 96, 9 fallan y se retiran (ChestTerminal, DiosesDrakes, DrakesSlimeMarket, FNAmplifications, Networks, Odysseia, SlimeFrame, SlimeTinker, SoundMuffler). Son plugins cargados, no addons con items verificados.
- **NO hay**: cobertura por ids (`compare_ids.py` fallo con `FileNotFoundError registered-ids.tsv` en las 4 corridas), ni veredicto smoke PASS/FAIL, ni persistencia, ni cifras de TPS/MSPT/CPU/heap del ecosistema. El job queda "success" porque su veredicto solo exige que el servidor arranque. **Hay que arreglar por que el plugin de prueba no escribe sus ficheros con el ecosistema cargado.**
- Siguen sin cargar `Odysseia-Rust.jar` (`Could not load plugin`).

## 4. Pendiente (en orden de prioridad)
1. Verificar el resultado completo de los 8 shards en la nueva ejecución de `.github/workflows/hybrid-ecosystem.yml` tras el push de esta sesión.
2. Pruebas funcionales profundas en vivo (máquinas concurrentes, redes de energía, GUIs/inventarios con jugador real, jefes).
3. Conectar `HorizonLodProtocol` + `HorizonLodCache` al canal de configuración/red cuando exista un cliente Horizon compatible con protocolo 26.2/26.3 y tabla de traducción de estados de bloque.
4. En `DrakeStar-Launcher`: lanzamiento de cliente Java/modpacks y prueba automática end-to-end contra el JAR completo de `DrakesPurpur`.

## 5. Reglas vigentes
Trabajar solo en la rama experimental; PR #1 en borrador; no fusionar ni desplegar automáticamente en producción (Dallas es estrictamente de solo lectura); no modificar repositorios secundarios de addons sin autorización; tecnologías experimentales desactivadas por defecto.
