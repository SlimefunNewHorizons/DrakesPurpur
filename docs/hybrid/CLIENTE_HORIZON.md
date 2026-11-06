# Horizon Client, protocolo 26.2 vs 26.3 y streaming/generacion

Fuente: `horizon-rust/horizon` (MIT) leido el 2026-11-06 — `crates/horizon-protocol/src/adapter/*`, `docs/graphics.md`, `docs/server-next.md`.
Nada de esto se ha podido **ejecutar** (no hay cliente Horizon ni GPU en el entorno); son hechos de codigo, no pruebas de conexion.

## 1. Versiones
| | Protocolo | Minecraft |
|---|---|---|
| DrakesPurpur (esta rama) | 776 | 26.2 |
| Horizon (cliente y servidor) | 777 | 26.3 |

Horizon **no** exige servidor 26.3: su cliente incluye un adaptador "older-servers" (`adapter/v26_2.rs`, `v26_1_2.rs`, `v1_21_11.rs`, `v1_21_10.rs`) que renumera ids de paquetes por estado y traduce los paquetes cuyo layout cambio, una version cada vez (26.1 -> 26.2 -> 26.3). Por tanto el camino de menor riesgo para "Horizon Client en DrakesPurpur" es **no tocar el servidor**: que el cliente traduzca.

## 2. Cambios 26.2 -> 26.3 que el adaptador traduce (los que importan al servidor de chunks)
Extraidos de `translate.rs` (comentarios del propio codigo de Horizon):
- **Chunk (`LevelChunkWithLight`)**: los estados de bloque de una seccion en paleta global se renumeran y re-empaquetan con mas bits (el registro de estados de 26.3 es mayor); los biomas se dejan tal cual (registro del servidor).
- **Luz**: 26.2 envia las 4 mascaras como `BitSet.toLongArray` (VarInt de longs); 26.3 como `toByteArray` (bytes little-endian sin ceros finales). Mismos bits, otra codificacion.
- **Tipos de block entity** eliminados en la version pinned (p. ej. la cama de 26.1) se descartan.
- Movimiento de entidades: 26.2 manda deltas `Short` (1/4096) + on-ground; 26.3 empaqueta on-ground con el numero de pasos y lo manda antes de los angulos.
- `swing` (26.2) pasa a `punch` (26.3); acciones de picar: 26.3 inserta `CHANGE_DESTROY_DIRECTION` en 1; teletransporte incluye destino; explosion anade flag de sonido; particulas reordenadas.
- Perfil/sesion: 26.2 anade id de sesion y flag de comprobacion de cuentas.

**Implicacion**: nada de esto exige cambios en `PalettedContainer` del servidor; la traduccion de chunks ocurre en el cliente. El kernel Rust de `SimpleBitStorage` de esta rama NO participa en ese camino (el envio usa los `long[]` ya empaquetados).

## 3. Que optimizaciones de Horizon son de cliente y cuales de servidor
| Area | Donde vive | Aplica a DrakesPurpur |
|---|---|---|
| FPS: wgpu, GPU culling, cave culling, HDR, shaders, frame generation | Cliente | Solo si el jugador usa Horizon Client; el servidor no puede prometerlo |
| Terreno lejano / LOD (hasta 1024+ chunks) | Cliente: muestrea su propio generador local; contra servidores Paper/vanilla usa solo lo que el servidor envio, cacheado en `server-terrain/` | El servidor Paper **no** sirve LOD; no hay equivalente en el protocolo vanilla |
| Generacion de chunks nativa | Servidor Horizon (`horizon-engine` headless) y cliente single-player | No reutilizable en Paper sin portar el pipeline de worldgen |
| Codificacion de secciones en hilos auxiliares (#3036), E/S por hilos, cache de chunks, compresion | Servidor Horizon | Concepto aplicable; ver sec. 4 |

## 4. Streaming/generacion: lo que dicen nuestras medidas
En el servidor real (autotest, 289 chunks, runner de 4 vCPU): generar/cargar un chunk cuesta **~38 ms de media (p99 ~210-270 ms)**; serializar a NBT ~0.3 ms; preparar el paquete de envio ~0.03-0.06 ms. La tuberia **no esta limitada por empaquetado ni por el paquete**, sino por la generacion de terreno. Las ideas de Horizon que atacan eso son: (a) generacion con density functions nativas/vanilla-exactas, (b) mover trabajo fuera del hilo de tick (ya hecho en gran parte por el chunk system de Paper), (c) LOD en cliente. (a) es el unico candidato con potencial real y requiere un port de `NoiseChunk`/`DensityFunction` con pruebas diferenciales contra vanilla por semilla (Horizon tiene 324 harnesses para eso). Ver perfil JFR en `bench_hybrid_*`.

## 5. Slimefun en Horizon Client (no verificado)
Slimefun depende de resource packs/modelos personalizados, items con `CustomModelData`, GUIs de inventario y paquetes de servidor estandar; todo viaja por protocolo vanilla, asi que en principio llega al cliente. Lo que no se sabe: si Horizon renderiza los modelos/shaders del pack igual que vanilla. **Prueba pendiente** con un cliente real en un servidor de pruebas.

## 6. Plan de prueba de conexion (antes de escribir protocolo nuevo)
1. Servidor de pruebas DrakesPurpur 26.2 con `online-mode=false`.
2. Horizon Client (launcher oficial) -> conectar; registrar errores de traduccion en su log.
3. Probar: unirse, moverse, volar rapido (chunks), abrir GUI de Slimefun, resource pack del servidor, Elytra.
4. Solo si falla algo concreto: abrir issue en `horizon-rust/horizon` (la traduccion es suya) en vez de parchear el servidor.
