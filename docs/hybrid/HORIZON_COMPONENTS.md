# Registro de componentes Horizon

Licencia de Horizon: **MIT** (c) 2026 Ludovic Marie and the Horizon contributors; reutilizar es legal si se conserva el aviso. **No se copio codigo de Horizon** en esta rama: los kernels son implementaciones propias de formatos definidos por vanilla (SimpleBitStorage, ruido de Perlin de Ken Perlin, cabecera Anvil). Si se porta codigo de Horizon en el futuro, anadir aviso MIT en `NOTICE` y una fila aqui.

Revisado: `horizon-core` (noise/, density/, biome, paletted, storage, random), `horizon-world` (chunk, compression: zlib-rs/lz4, datafix), `horizon-threads` (jobs), `horizon-protocol` (adapter, chunk), `docs/server-next.md`, `docs/density-functions.md`, `docs/heightmaps.md`.

| Componente Horizon | Que resuelve | Paper/Purpur hoy | Decision |
|---|---|---|---|
| `horizon-core::storage` (PackedStorage) | Bit-packing de secciones/heightmaps | `SimpleBitStorage` Java | **Adaptado (propio)** -> `drakes_bits_*`; ~2x a 4096 valores |
| `horizon-core::noise::perlin` | Ruido de terreno (f32, con volumenes/derivadas, acoplado a su RandomSource) | `ImprovedNoise` Java (double) | **Probado y descartado**: version propia batch no gana (0.77-0.92x) |
| `density/*` (grafo de density functions, interpolacion, cache) | Worldgen 26.x | `NoiseChunk`/`DensityFunction` | **Pendiente**: candidato real, pero exige reemplazar el calculo de NoiseChunk de Paper con datapacks y paridad por semilla; no viable sin build de servidor |
| `horizon-world::compression` (zlib-rs, lz4) | Codecs de region | Deflater/LZ4 Java | Pendiente de medir; riesgo medio (region writer) |
| `horizon-protocol::chunk`, `adapter` | Serializacion y traduccion 26.2->26.3 | Netty/FriendlyByteBuf | Descartado para servidor (es lado cliente Horizon); el adaptador sirve al cliente |
| `horizon-threads::jobs` | Pool de trabajos | Pool propio de Paper | Descartado: duplicaria trabajadores contra Paper |
| Render, shaders, wgpu, HDR | FPS del cliente | N/A | Cliente: el servidor hibrido **no** promete FPS |
