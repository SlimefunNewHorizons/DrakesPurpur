# Horizon vs Paper/Purpur: pipeline de chunks (analisis por componente)

Fuente Horizon: `horizon-rust/horizon` @ `41f822b` (MIT): `docs/server-next.md` (§1 baseline, §5 red/E/S), `docs/density-functions.md`, `crates/horizon-core/src/{noise,density,storage,paletted}`, `crates/horizon-world/src/compression.rs`, `crates/horizon-threads/src/jobs.rs`, `crates/horizon-protocol/src/{chunk,adapter}`. Paper/Purpur: fuentes reales del servidor 26.2 (volcadas en CI), perfil JFR y las mediciones de `RESULTADOS.md`. **No se copio codigo de Horizon.**

| Componente | Horizon | Paper/Purpur 26.2 (medido/leido) | Conclusion |
|---|---|---|---|
| Generacion de terreno | Grafo de density functions compilado en Rust, verificado por cientos de pruebas diferenciales contra vanilla; **su propia doc dice que el grafo completo y la generacion aun estan pendientes y que no hay mejora de juego completo medida** | Java (`NoiseChunk`/`DensityFunctions`); ruido ~48-53 % de la generacion (JFR) | `NormalNoise` nativo reproducido bit a bit, **sin ganancia de velocidad**. Portar el resto no esta justificado por datos |
| Noise (Perlin/Normal) | Implementacion Rust vanilla-exacta | Java; HotSpot ya compila bien el bucle | Empate (0.94-1.21x, en el ruido de medida) |
| Bit-packing de secciones | `PackedStorage` en Rust | `SimpleBitStorage` (Paper ya la optimizo) | Empate; ~1 % del coste de guardar un chunk |
| Hilos de generacion/luz | Pool de trabajo propio (`horizon-threads::jobs`), luz en el pool | Chunk system de Moonrise (pool propio, prioridades y dependencias) | **Descartado**: duplicaria trabajadores y reglas de concurrencia de Paper |
| Codificacion de secciones | En hilos auxiliares, una vez por chunk | Se construye `ClientboundLevelChunkWithLightPacket` por envio: **35-60 us/chunk** (medido) | Poco margen en CPU; el coste esta en otro sitio |
| **Cache del paquete de chunk** | Paquete codificado y comprimido se conserva hasta que cambia un bloque; re-entrar en un chunk cuesta un memcpy; compresion una vez para todos los jugadores (`SharedFrames`) | Se reconstruye y comprime por jugador y por envio | **Candidato real** para multijugador y re-entradas (Elytra de ida y vuelta, zonas concurridas). Requiere tocar la tuberia de Netty/compresion y la invalidacion por cambio de bloque: cambio grande, no hecho. Medir antes: tasa real de re-envios en produccion |
| Entidades como deltas | Registro de cambios por entidad, un paquete por cambio para todos los observadores | Tracker de Mojang (`ServerEntity`) con cadencia vanilla | Fuera del alcance de chunks |
| E/S de red | Pool fijo de hilos con `epoll`, cola acotada por jugador, backpressure | Netty (ya `epoll`), `ChunkBatchReceived` pacing vanilla | Ya equivalente en concepto; sin cambios |
| Region I/O / NBT | `RegionFile` propio en Rust, guardado en workers | `RegionFile` + chunk system de Paper (guardado async) | Guardar ~0.3-0.5 ms/chunk: no es cuello |
| Prefetch / LOD | LOD de **cliente** (terreno lejano muestreado localmente) | Sin equivalente de servidor | Ver `CLIENTE_HORIZON.md`; el servidor Paper no sirve LOD |

## Lo que si se integra aqui
- Instrumentacion por etapa (generar / guardar / cargar / preparar envio) con datos reales.
- Prefetch direccional experimental sobre la API publica de Paper (sin tocar el scheduler): **su efecto se mide en `RESULTADOS.md`; no se activa por defecto.**

## Siguiente paso con potencial real (no implementado)
Cache del paquete de chunk comprimido por (chunk, version de seccion) compartido entre jugadores, con invalidacion en `LevelChunk#setBlockState`. Antes de construirlo hay que medir en produccion cuantos paquetes de chunk son re-envios y cuanto CPU de Netty/compresion consumen; sin esa cifra no se puede justificar.
