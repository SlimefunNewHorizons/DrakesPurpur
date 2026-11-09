# Resultados medidos (2026-11-06) — integracion real en PalettedContainer

Entorno: GitHub Actions `ubuntu-latest` (4 vCPU compartidas, Temurin 25.0.5), servidor DrakesPurpur 26.2 **compilado completo** desde esta rama (parche 0023 aplicado; `Build` y `Hybrid Engine CI` en verde, commit `46a060b`), semilla 12345, mundo nuevo, radio 8 (289 chunks, 6 936 secciones, 13 872 contenedores). Metodo: autotest dentro del servidor (`-Ddrakes.hybrid.selftest=8`), A/B alternado en el mismo proceso y sobre los mismos datos, mediana de 9 rondas tras 2 de calentamiento. Dos corridas independientes.

## Donde se integro (punto exacto)
`net.minecraft.world.level.chunk.PalettedContainer` (parche `0023`):
- `pack()` (guardado de secciones a region, via `Codec`): `new SimpleBitStorage(bits, n, int[]).getRaw()` -> `HybridChunkOps.packRaw`.
- `reencodeContents()` (guardado y carga con re-empaquetado): `storage.unpack(int[])` -> `HybridChunkOps.unpackInto`.
No se toca `Data.write/read` (envio por red): esa ruta copia los `long[]` ya empaquetados y no empaqueta nada.
Formato vanilla verificado en las fuentes de Mojang: `valuesPerLong = 64/bits`, valor `i` en el long `i/vpl`, desplazamiento `(i%vpl)*bits`, el constructor `int[]` **enmascara** (`& mask`). El kernel hace lo mismo.

## Paridad (dentro del servidor, datos reales)
| Corrida | Contenedores | Diferencias NBT Rust vs Java | Round-trip (decodificar con Rust, recodificar con Java) |
|---|---|---|---|
| 1 | 13 872 | **0** | **0** |
| 2 | 13 872 | **0** | **0** |

El NBT producido por los `Codec` reales de Mojang (la misma ruta que escribe la region) es identico con Rust encendido y apagado.

## Rendimiento: A/B Java vs Rust (mismo proceso)
| | Guardado (codec->NBT) | Carga (NBT->codec) |
|---|---|---|
| Corrida 1 | Java 287.8 us/chunk, Rust 278.7 -> **x1.033** | Java 141.7, Rust 134.8 -> **x1.052** |
| Corrida 2 | Java 371.5 us/chunk, Rust 350.9 -> **x1.059** | Java 140.2, Rust 139.9 -> **x1.002** |
| Ruido entre corridas OFF (control, mismo codigo Java) | 302.0 vs 371.3 us (+23 %) | 132.7 vs 148.6 us (+12 %) |

Desglose por etapa (corrida 2, ns por elemento): pack Java 0.90, pack Rust 0.86; unpack Java 0.30, unpack Rust 0.35.

**Veredicto: sin mejora demostrable.** La diferencia (0-6 %) esta por debajo del ruido entre corridas del propio control Java (12-23 %), y el empaquetado es una fraccion minuscula del coste: guardar un chunk cuesta ~0.3-0.4 ms frente a ~38-47 ms de media para generarlo (0.8 %). Segun la regla acordada **no se activa por defecto ni se presenta como mejora**; queda como interruptor experimental (`native.hybrid.bits`, apagado). El unpack nativo es, ademas, ligeramente mas lento por elemento.

## Instrumentacion: donde se pierde el tiempo (por chunk, hilo principal sincrono)
| Etapa | Corrida 1 | Corrida 2 |
|---|---|---|
| Generar/cargar (mundo nuevo) | media 38.7 ms, p50 20.7, p95 152, p99 270 | media 47.2, p50 25.5, p95 160, p99 303 |
| Guardar (codec->NBT) | 0.30 ms | 0.37 ms |
| Cargar desde NBT | 0.13 ms | 0.15 ms |
| Preparar paquete de envio (`ClientboundLevelChunkWithLightPacket`) | p50 44 us (Java) / 29 us (en caliente) | p50 49 / 31 us |

Perfil JFR de la generacion (radio 12, Hybrid OFF, 4 292 muestras; incluye los bucles del propio autotest, asi que la familia nbt/codec esta inflada): tiempo propio — `SimplexNoise.dot` 8.1 %, `ImprovedNoise.gradDot` 6.6 %, `NoiseInterpolator.updateForZ` 5.4 %, `ImprovedNoise.p` 3.4 %, `PerlinNoise.getValue` 3.2 %, `Climate$RTree.search` 3.1 %, `Aquifer.computeSubstance` 2.4 %. Familias (inclusivo): ruido/density functions **52.9 %**, features/estructuras 26.7 %, surface/carvers 7.7 %, luz 1.2 %.

## Lectura
1. El cuello de botella real del streaming de chunks nuevos es la **generacion de terreno por density functions/ruido** (>50 %), no el empaquetado, la serializacion ni el paquete.
2. Un kernel Rust de Perlin suelto tampoco ayuda (bench: 0.77-1.02x): la HotSpot ya compila bien el bucle; el coste esta en miles de llamadas virtuales por punto del grafo `DensityFunction`. Solo un **grafo de density functions completo en Rust** (el enfoque de Horizon, con paridad diferencial por semilla) podria cambiar esa cifra. Es el siguiente trabajo con potencial real y es grande.
3. Lo integrado aqui es una base correcta y verificada (build, patch, ABI, fallback, paridad, instrumentacion), no una aceleracion.

---

# Fase 3 — NormalNoise nativo (base de las density functions)

Commit `359c72d`; CI `Hybrid Engine CI` y `Build` en verde. Implementacion: `native/drakes-hybrid/src/density.rs` (ImprovedNoise -> PerlinNoise -> NormalNoise con el **mismo orden de operaciones** que la fuente de Mojang 26.2; f64 sin FMA; `Mth.floor` = cast saturante) + `hybrid/HybridDensity` (exporta las tablas de una instancia real de `NormalNoise`: Java conserva la semilla y los parametros).

## Paridad (dentro del servidor, instancias reales de Mojang, 200 000 puntos por ruido, incluyendo ±1e9 y la frontera del wrap 3.3554432E7)
| Ruido | Puntos | Diferencias bit a bit | Max ULP |
|---|---|---|---|
| temperature(-10; 1.5,0,1,0,0,0) | 200 000 | **0** | 0 |
| continentalness(-9; 9 octavas) | 200 000 | **0** | 0 |
| shift(-3; 4 octavas) | 200 000 | **0** | 0 |

Esto demuestra paridad bit a bit **solo para esta primitiva** (no para el grafo completo, ni para generacion de chunks).

## Rendimiento por punto (Java `NormalNoise.getValue` en bucle vs Rust por lotes via FFM, mediana de 9)
| Ruido | Lote 4096 | Lote 65536 |
|---|---|---|
| temperature | Java 218.8 / Rust 180.3 ns -> x1.21 | 194.7 / 180.5 -> x1.08 |
| continentalness (9 octavas) | 798.2 / 772.8 -> x1.03 | 793.3 / 774.2 -> x1.02 |
| shift (4 octavas) | 266.5 / 270.8 -> x0.98 | 267.6 / 271.9 -> x0.98 |

Ruido de la corrida en este runner: el control Java vario 12-23 % entre ejecuciones. **Las diferencias (0.98-1.21x) estan dentro del ruido: no hay mejora demostrable del ruido nativo.** El ruido no tiene un coste dominado por llamadas FFM (el lote lo amortiza): Rust y HotSpot ejecutan practicamente la misma aritmetica de doble precision.

## Que implica para el port del grafo completo (decision basada en datos)
- Perfil JFR de generacion (10 516 muestras): tiempo propio — `SimplexNoise.dot` 7.0 %, `PerlinNoise.getValue` 6.4 %, `Climate$RTree.search` 3.5 %, `NoiseChunk$NoiseInterpolator.updateForZ` 3.0 %, `Aquifer.computeSubstance` 2.4 %, `DensityFunctions$Ap2.compute` 2.0 %, `PureTransformer.compute` 1.6 %. Inclusivo: ruido/density 48.3 %, features/estructuras 24.3 %, superficie/carvers 7.9 %.
- Aunque el ruido fuese infinitamente rapido, el tiempo propio de las funciones de ruido (~14 %) acota la ganancia; el resto del grafo (interpoladores, caches, splines, aquifer, Climate) es codigo con muchas ramas y estado de `NoiseChunk` que Rust no acelera de forma evidente.
- Horizon documenta que su compilacion de density functions **no cubre aun el grafo completo** y que no ha medido mejora de juego completo (`docs/density-functions.md`).
- **Conclusion:** no hay evidencia de que portar el resto del grafo a Rust dé un beneficio a nivel de chunk que justifique el coste y el riesgo de paridad. No se enlaza `NormalNoise` nativo al pipeline de generacion (no se activa nada que no haya demostrado ganancia). El camino con mas potencial medible sigue siendo arquitectonico (paralelismo/colas), no el lenguaje del kernel.

## Fase 4/5 — volador sintetico y muestreo LOD (CI run 42321107189, commit 70c4942)

Fuente: logs del job "Full server build + in-server selftest" (runner compartido de 4 vCPU; **sin intervalos de confianza**, 2 corridas por modo, cada corrida usa un corredor distinto → la variabilidad del terreno es grande). Es un modelo de **carga** sobre el sistema de chunks real, no un cliente real.

| Velocidad (demanda) | Reactiva: columnas tarde / p95 | Prefetch lead=6: columnas tarde / p95 |
|---|---|---|
| 24 b/s (17 chunks/s) | 26 % / 2476 ms | 64 % / 8534 ms |
| 40 b/s (28 chunks/s) | 100 % / 8184 ms | 100 % / 12746 ms |
| 64 b/s (44 chunks/s) | 100 % / 13720 ms | 100 % / 15339 ms |

**Conclusion honesta:** el prefetch direccional **empeora** el resultado en las tres velocidades (compite con la carga urgente por el mismo generador). Por encima de ~17 chunks/s la generacion no da abasto en este runner, con o sin prefetch. Por la regla del proyecto (no activar lo que no demuestra beneficio) **`prefetch` queda desactivado por defecto y no se presenta como mejora**. Un prefetch util necesitaria prioridad de cola (urgente > anticipado) en el planificador de generacion, no probada.

### Autotest ON vs OFF (misma ejecucion)

- Paridad NBT real Rust == Java: 13 872 contenedores, 0 diferencias, 0 diferencias de ida y vuelta. Density (NormalNoise): 200 000 puntos × 3 ruidos, 0 diferencias bit a bit, max_ulp = 0.
- Guardado: x1.071 y carga: x1.042 a favor de Rust; density x1.03–1.10. **Todo dentro del ruido del runner (12–23 % de varianza de control)**: no se declara mejora.
- Bridge aislado (lotes de 4096): bits pack+unpack x3.2–4.1 y ruido mejorado x1.15 a favor de Rust, pero con lotes de 256 es x0.6–1.0; en el servidor el empaquetado es ~1 % del tiempo de un chunk, por eso no se traduce en mejora medible.
- Comandos `/drakes hybrid`: 8/8 subcomandos, `dispatchCommand`, tab-complete y GUI de 27 slots OK. Clics reales de jugador: **no probados**.
- Codec `horizon:lod` v1: 0 comprobaciones fallidas (vectores derivados de la especificacion, **no contrastados con un cliente/servidor Horizon real**).

### Muestreo LOD (sonda: altura `WORLD_SURFACE_WG` del generador, sin generar chunks)

| stride | ms por tile 16×16 | generar los chunks equivalentes (≈48 ms/chunk) | relacion |
|---|---|---|---|
| 1 | 441–455 | ~48 ms | **~9× mas caro que generar** |
| 2 | 443–457 | ~192 ms | ~2.3× mas caro |
| 4 | 462–458 | ~770 ms | ~1.7× mas barato |
| 8 | 461–468 | ~3078 ms | ~7× mas barato |
| 16 | 484–486 | ~12 313 ms | ~25× mas barato |

El coste por tile es casi constante (~1.8 ms/columna) con independencia del stride: el LOD solo compensa a stride ≥ 4. **Limites de esta medicion:** la sonda mide solo la altura (sin spans, biomas, agua, luz) y es secuencial; el job OFF corrio a TPS ~5 (MSPT 177 ms) frente a ~20 en el ON, asi que el absoluto esta contaminado por carga del runner. El backend LOD **no esta conectado** a clientes y no se enviara a clientes sin negociar `horizon:lod`.

### Anomalias abiertas (no resueltas)

1. En el job ON, `/drakes hybrid status` mostro "Modo: JAVA original / bits=false" mientras la cabecera del autotest decia bits=true (el autotest fuerza el modo con `forceBits` durante la prueba; la linea `[stats paridad] llamadas nativas pack=0` convive con `bits.pack.rust calls=2617` del desglose). Hay que unificar lo que informa `status` con lo que el autotest fuerza, o documentar por que difieren.
2. `bits.unpack.java` informa ~20.8 ms por llamada en el job OFF (anomalo; probable contaminacion del temporizador por el bloqueo del runner). Sin explicar.
3. JFR (OFF): `worldgen:noise/density` 67.7 % de 13 282 muestras: el cuello real sigue siendo la generacion de ruido, no la serializacion.

## Correcciones tras la investigacion de colas (revision del A/B del volador)

La investigacion (`INVESTIGACION_COLAS_LOD.md`, verificada leyendo el codigo) mostro que **el A/B anterior no medía "anticipar vs no anticipar"**:

- Modo "reactivo" = todas las peticiones `urgent=true`; modo "prefetch" = casi todas `urgent=false` y **nunca promovidas** al vencer el plazo. Se comparaban dos prioridades, no dos estrategias.
- El volador usaba un prefetch propio sin tope ni carril; la clase de produccion `HybridChunkPrefetch` (tope 24 en vuelo, carril de 5) **no fue medida**. Por tanto "el prefetch empeora" **no aplica a la clase real**; sigue desactivada por la regla de no activar lo no demostrado.
- n=2 corridas por modo, corredores distintos, sin IC: los numeros 26 % vs 64 % son indicativos, no conclusiones.

Cambios aplicados: el volador mide ahora una matriz {R-urgent, R-normal, P-normal, P-promovido} en orden ABCD·DCBA, para separar el efecto de la prioridad del de la anticipacion.

**Anomalias — causas identificadas y RESUELTAS en CI run `42323576926` (commit `607f80be`):**
1. `bits=false` en `status`: el autotest dejaba `forceBits(false)` antes del smoke de comandos → corregido (restaura `initialBits` antes de `adminSmoke`; verificado en `selftest-on1.txt` y `selftest-on2.txt`: `Aceleraciones: bits=true`).
2. TPS ~5 (OFF) vs ~20 (ON): el OFF no ejecutaba flyer ni usaba las mismas banderas y leia el TPS justo tras generar ~289 chunks de forma sincrona en el hilo principal → artefacto de orden/ventana, no de Hybrid. En el run `42323576926` (diseño ABBA `off1`, `on1`, `on2`, `off2` con las mismas banderas):
   - `selftest-off1`: TPS 1m/5m/15m = `19.88 / 19.99 / 19.99`, MSPT medio = `1.02 ms`
   - `selftest-on1`: TPS 1m/5m/15m = `19.97 / 19.99 / 20.00`, MSPT medio = `1.36 ms`
   - `selftest-on2`: TPS 1m/5m/15m = `19.97 / 19.99 / 20.00`, MSPT medio = `1.54 ms`
   - `selftest-off2`: TPS 1m/5m/15m = `19.96 / 19.99 / 20.00`, MSPT medio = `1.36 ms`
3. `bits.unpack.java` ≈ 20 ms/llamada: **carrera de medicion** (`HybridStats.enabled` se activaba con una llamada en curso que empezo con t=0 y `add` sumaba el reloj monotono absoluto). Corregido leyendo el flag una sola vez por llamada, ignorando t=0 y reportando el maximo. Verificado en `42323576926`:
   - `selftest-off1`: `bits.unpack.java` `calls=1306 items=5349376 total=0.98ms avg=754ns/call max=0.033ms`
   - `selftest-on1`: `bits.unpack.java` `calls=1306 items=5349376 total=1.06ms avg=809ns/call max=0.287ms`

### Resultados de la matriz de prioridad del volador sintetico (VERIFICADOS contra los logs de CI)

> **Correccion (2026-11-09):** una version anterior de esta seccion y de `PROJECT_PURPLE_PROGRESS.md`/`ESTADO_Y_PENDIENTES.md` citaba cifras (R-urgent 69,3 % / P-promovido 77,5 %, y R-urgent 28 % / P-promovido 18 %) que **no aparecen en ningun log de CI** y se retiran. El run `42325872268` citado **no existe** en el repositorio. Las cifras siguientes son literales de los logs de `Hybrid Engine CI` (24 b/s, 2 corridas por politica en cada modo, orden ABCD·DCBA, runner de 4 vCPU compartido, sin intervalos de confianza).

Porcentaje de columnas tardias (resumen por politica de cada modo):

| Run (commit) | Modo | R-urgent | R-normal | P-normal | P-promovido |
|---|---|---|---|---|---|
| 42323576926 (607f80b) | off1 | 57 % | 100 % | 76 % | 62 % |
| | on1 (*) | 79 % | 100 % | 93 % | 100 % |
| | on2 | 55 % | 100 % | 76 % | 60 % |
| | off2 | 57 % | 100 % | 78 % | 59 % |
| 42337292514 (f3d23c6, prefetch de dos niveles) | off1 | 50 % | 100 % | 71 % | 55 % |
| | on1 | 55 % | 100 % | 76 % | 59 % |
| | on2 | 52 % | 100 % | 74 % | 59 % |
| | off2 | 55 % | 100 % | 76 % | 59 % |
| 42339791412 (ab5d46e, retencion de tickets) | off1 | 57 % | 100 % | 76 % | 60 % |
| | on1 | 55 % | 100 % | 76 % | 59 % |
| | on2 (*) | 84 % | 100 % | 97 % | 100 % |
| | off2 | 52 % | 100 % | 74 % | 55 % |

(*) Corridas con carga ajena del runner (loadavg y tiempos totales mas altos): no representativas.

**Conclusiones que SI respaldan los logs:**
1. La bandera `urgent` domina el resultado: `R-normal` (reactivo sin urgencia) llega tarde en el **100 %** de las columnas; `R-urgent` en ~50–57 %. La diferencia "26 % vs 64 %" de la primera medicion era sobre todo este efecto.
2. La **promocion mejora mucho a un prefetch sin promocion** (`P-promovido` ~55–62 % frente a `P-normal` ~71–78 %).
3. **`P-promovido` NO supera al reactivo urgente** (`R-urgent` ~50–57 %): son equivalentes dentro del ruido o peor. El cambio de prefetch de dos niveles (f3d23c6) casi no mueve la matriz. **No hay evidencia de que el prefetch mejore al comportamiento base de Paper.** Se mantiene desactivado por defecto.
4. Hay variacion grande entre corridas del mismo modo (p. ej. R-urgent individual de 34 % a 72 %) y outliers por carga del runner; sin IC no se puede afirmar diferencia entre OFF y ON.
5. En las corridas ON el log muestra `bitsConfigurado=false` durante el volador: el autotest apaga `forceBits` antes de esta fase, asi que el volador **no mide Hybrid ON**; la comparacion OFF/ON de esta fase no es informativa sobre los kernels nativos.
6. Anomalias de TPS y de `bits.unpack.java` (verificado en 42323576926): TPS 1m 19,88–19,98 en los cuatro modos, MSPT 0,90–1,36 ms; `bits.unpack.java` ya sin el valor anomalo.
