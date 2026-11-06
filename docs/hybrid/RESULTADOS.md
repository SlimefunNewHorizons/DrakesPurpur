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
