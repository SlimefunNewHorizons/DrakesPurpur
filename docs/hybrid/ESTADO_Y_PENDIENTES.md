# Estado del proyecto y pendientes (cierre de sesión, 2026-11-07)

Documento de traspaso. Todo lo implementado está en la rama `experimental/horizon-rust-hybrid-engine` (PR #1, **borrador, sin fusionar, sin desplegar**). El launcher vive en su propio repositorio: https://github.com/SlimefunNewHorizons/DrakeStar-Launcher.

## 1. Hecho y verificado
- **Motor híbrido Java + Rust**: biblioteca nativa (`native/drakes-hybrid`) vía FFM con ABI versionado, fallback a Java idéntico, extracción embebida por plataforma verificada con SHA-256, interruptor maestro `hybrid.engine.enabled`. Paridad bit a bit: 13 872 contenedores NBT, 200 000 puntos × 3 ruidos, 0 diferencias.
- **Comandos `/drakes hybrid` y GUI de 27 slots**: 8/8 subcomandos probados en el servidor real. Clics de jugador real: sin probar.
- **Codec `horizon:lod` v1**: 0 fallos en las comprobaciones, derivado de la especificación; **no contrastado con un cliente/servidor Horizon real**. El backend LOD **no está conectado** a ningún cliente.
- **Compatibilidad (CI run 42322308340)**: monolito con 9 suites embebidas, smoke 156 + 123 PASS, persistencia tras reinicio OK, sin SEVERE/ERROR.
- **Catálogo real en CI**: 713 ítems (555 Slimefun + 158 Nanotech), 25 grupos, 257 investigaciones, 2 addons de 64. **713 no es el total del ecosistema.**
- **Inventario del ecosistema**: 190 repos (`docs/hybrid/ecosistema/manifest.json|md`, `gaps.md`); análisis reproducible del volcado de Slimefun-Knowledge-Drake (`analyze_dump.py`, `expected_by_addon.json`, `anomalies.json`, `suites_crosswalk.json`): 15 986 ítems, 64 addons, 274 grupos, 440 investigaciones confirmados; ColoredEnderChests = 51,2 % del total.
- **Herramientas de cobertura**: `tools/compat-smoke/compare_ids.py` (cobertura por conjunto de ids), `aliases.json`, registro de ids en el smoke, medición de rendimiento con ecosistema completo (`-Dsmoke.perf=true`).
- **CI de ecosistema completo**: `.github/workflows/hybrid-ecosystem.yml` + `tools/ecosystem/build_addons.py` (112 addons en 8 fragmentos, carga con reintentos, OFF/ON/ON/OFF). **Escrito, nunca ejecutado hasta el cierre.**

## 2. Resultados de rendimiento: qué se puede y qué no afirmar
- **No hay mejora de rendimiento demostrada.** Guardado x1.07, carga x1.04, density x1.03–1.10: dentro del ruido del runner (12–23 %). El empaquetado de bits gana x3–4 en el microbenchmark con lotes de 4096 y pierde con lotes de 256; en el servidor pesa ~1 % del tiempo de un chunk.
- **Prefetch direccional: desactivado.** El A/B anterior (26 % vs 64 % de columnas tardías a 24 b/s) comparaba prioridades, no estrategias (reactivo = todo `urgent`, prefetch = casi todo no urgente y nunca promovido), con n=2 y sin IC; además **no midió la clase de producción `HybridChunkPrefetch`**. Ver `INVESTIGACION_COLAS_LOD.md` y `RESULTADOS.md`.
- **Anomalías explicadas, no re-medidas**: `bits=false` (estado restaurado tras el autotest, corregido); TPS 5 vs 20 (OFF sin flyer y leído justo tras generación síncrona: artefacto de orden/ventana); `bits.unpack.java` ≈ 20 ms (carrera de `HybridStats.enabled`, corregida). El CI con condiciones equivalentes (OFF,ON,ON,OFF + matriz R-urgent/R-normal/P-normal/P-promovido) **no se llegó a leer**.
- **Muestreo LOD**: ~1,8 ms/columna casi constante; solo compensa a stride ≥ 4 (~9× más caro que generar a stride 1). Medición solo de altura y contaminada por carga del runner.

## 3. Pendiente (en orden de prioridad)
1. **Leer los CI del último push** (commit `e7e28fa`): Build (compila el smoke y los cambios de `HybridSelfTest`/`HybridStats`), Hybrid Engine CI, Hybrid compat (cobertura por ids), **Hybrid ecosystem** (primera ejecución: es probable que haya que ajustar regex de logs, JDK por addon y dependencias de repos Gradle/paperweight).
2. **Cobertura real del ecosistema**: de 64 addons solo 2 estaban cargados; el objetivo es los 63 restantes con sus JAR construidos desde fuente. Los módulos de Drakes-Suites son mayoritariamente stubs de configuración (49) o lógica sin ítems (45): **casi todos los addons necesitan su JAR independiente**. Equivalencia funcional suite↔addon sin probar.
3. **Pruebas funcionales profundas** (no hechas): máquinas concurrentes, redes de energía, GUIs/inventarios con jugador real, recetas en uso, jefes. Sin ellas **no se declara compatibilidad total**.
4. **Repetir benchmarks en hardware dedicado** con IC (≥ 6 corridas por celda, orden aleatorio), `-Xlog:gc`, MSPT por segundo; experimento E7 (medir la clase real `HybridChunkPrefetch`) y E0 (volcar fuentes de Paper para confirmar prioridades/hilos).
5. **DrakesLOD**: tabla de ids 26.2→26.3 desde ambos registros, enganche en fase de configuración + detección de brand, muestreador por rejilla de densidad con caché en disco, límites anti seed-cracking, prueba con cliente Horizon real. Nada de esto está implementado.
6. Caché de paquetes de chunk / compresión compartida (idea de Horizon); port del grafo de densidad completo (no justificado por los datos).
7. Pruebas con jugadores reales, cliente Horizon y Geyser; bibliotecas nativas de otras plataformas.
8. **Criterios para promover a estable**: `CRITERIOS_ESTABLE.md`. `hybrid.engine.enabled` **no debe pasar a `true` por defecto** hasta cumplirlos.

## 4. DrakeStar Launcher
Publicado en https://github.com/SlimefunNewHorizons/DrakeStar-Launcher (`main`). App Tauri 2 + Svelte 5 verificada en Linux (ventana real bajo Xvfb, proceso Java en streaming, 49 tests Rust, `npm run build` limpio). **Pendiente**: ejecución de su CI (nunca corrió hasta el cierre), prueba contra un JAR real de DrakesPurpur, Windows/macOS, instaladores, lanzar clientes DrakesJava/DrakesRust (no existe cliente Rust), loaders/modpacks/JRE gestionado, firma de manifiestos y rollback, mundos, preset Slimefun, accesibilidad. Autoría de los commits: `Claude <noreply@anthropic.com>` (reescribir antes de difundir si se desea otra). Detalle en el repo: `docs/ROADMAP.md`, `docs/PUBLICAR.md`.

## 5. Reglas vigentes
Trabajar solo en la rama experimental; PR #1 en borrador; no fusionar ni desplegar automáticamente; no modificar otros repos sin autorización (los fixes para addons se proponen como parche/PR); no declarar mejoras dentro del ruido ni compatibilidad total sin evidencia; tecnologías experimentales desactivadas por defecto; sin `Runtime.halt` fuera del autotest explícito.
