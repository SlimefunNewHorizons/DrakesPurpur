# DrakesLOD — terreno a distancias extremas (investigacion del canal `horizon:lod`)

Estado: **investigado y con codec implementado y probado; backend NO conectado**. Nada se envia a ningun cliente. LOD **no** es generacion de chunks completos ni sustituye la optimizacion del pipeline.

Fuente (MIT, `horizon-rust/horizon` @ `41f822b`): `crates/horizon-client/src/lod_net.rs`, `lod_net/{server,client}.rs`, `game/lan_far.rs`, `docs/graphics.md` (seccion *Distant terrain*), `docs/server-next.md`.

## 1. Como funciona en Horizon (hechos del codigo)
- **El LOD en Horizon es del cliente.** Contra un servidor vanilla/Paper el cliente solo dibuja terreno lejano con **lo que el servidor ya le envio** (lo cachea en `server-terrain/`). No hay LOD servido por Paper.
- Un **servidor Horizon** puede ofrecer LOD **muestreado por el servidor** por el canal `horizon:lod`, porque un cliente remoto no tiene la semilla ni el generador. **Apagado por defecto** (`horizon-lod-distance=0`): mostrar terreno lejano del generador ayuda a **descubrir la semilla** del mundo.
- **Negociacion.** El servidor envia un `Offer` en la **fase de configuracion**, **solo a clientes cuya marca (`brand`) es `"horizon"`**; un cliente vanilla "nunca se entera". El cliente solo se sirve de un servidor que hable su misma `VERSION`.
- **Version del protocolo: 1.**

## 2. Formato de cable (v1)
Convenciones de Minecraft: VarInt 7 bits LE, enteros big-endian, cadena = VarInt de longitud + UTF-8.

| Mensaje | Direccion | Campos |
|---|---|---|
| `Offer` | S→C (configuracion) | VarInt version(=1), VarInt distancia (chunks, ≤1024), VarInt solicitudes simultaneas (def. 8), VarInt tiles/s (def. 64). Se ignora si distancia/limites son 0 o la version difiere |
| `Request` | C→S | VarInt id, i32 x, i32 z (bloque, esquina del tile), VarInt stride (potencia de 2), bool aproximado |
| `Reply` | S→C | VarInt id, u8 estado (0 ok / 1 rechazo); si ok: VarInt n.º biomas + cadenas, VarInt columnas (=256), y por columna: bool superficie {i32 altura, VarInt estado de bloque, bool agua {i32 altura, VarInt estado}, VarInt bioma}, VarInt n.º spans, spans {i16 min, i16 max, VarInt estado, VarInt bioma, u16 luz} |

Tile = 16×16 columnas desde (x,z), cada una de `stride` bloques. Reglas del servidor (`Request::allowed`): tile **alineado** a `stride*16`; stride potencia de 2 **no mayor** que el tile mas grueso a esa distancia; punto mas cercano dentro de `(distancia+8)*16` bloques. Limites: **≤16 384 spans por tile, ≤512 KiB por respuesta, ≤256 biomas, alturas −2048..4096**; spans ordenados y sin solaparse. El servidor de Horizon muestrea en **un hilo de fondo** para todos los jugadores y aplica un cubo de fichas por jugador.

## 3. Implementado aqui (`hybrid/lod/HorizonLodProtocol`)
Codec completo de `Offer`/`Request`/`Reply` + `allowed` + cubo de fichas, con las mismas validaciones que el cliente de Horizon. El autotest lo prueba (ida y vuelta, rechazos, limites, vectores de bytes). **Los vectores salen de la especificacion leida; no se han contrastado con el binario de Horizon** (no hay cliente en el entorno).

## 4. Que falta para un backend LOD funcional (bloqueos reales)
1. **Traduccion de ids de estado de bloque 26.2 → 26.3.** El `Reply` usa los ids del registro de Horizon (26.3); DrakesPurpur es 26.2 (en el adaptador de Horizon, un estado vale `13330` en 26.2 y `15177` en 26.3). Hace falta una tabla generada de ambos registros (Horizon la genera con `tools/generate_packet_id_adapter.py`).
2. **Oferta en la fase de configuracion y deteccion de `brand=="horizon"`** en Paper/Purpur: requiere un enganche en `ServerConfigurationPacketListenerImpl` (no hay evento publico verificado). Parche de servidor nuevo, con pruebas.
3. **Muestreador de columnas** (spans + bioma + agua + luz) sin generar chunks: la base es `ChunkGenerator#getBaseColumn/getBaseHeight` de Mojang. El autotest mide el coste de `getBaseHeight` por tile (seccion *LOD sampler* de `RESULTADOS.md`); un tile completo costara mas que eso.
4. **Cache persistente** de tiles por (mundo, nivel, x, z) con invalidacion al modificarse bloques (region + version).
5. **Prueba con un cliente Horizon real** (o `horizon-bots`) para demostrar la negociacion y el renderizado. Sin esto **no se puede declarar compatibilidad**.
6. **Seguridad:** exponer terreno lejano revela informacion de la semilla; debe ser opt-in por el administrador (como en Horizon: `horizon-lod-distance`, 0 por defecto).

## 5. Politica
- LOD solo para clientes que hayan negociado soporte (`brand=="horizon"` y `Offer` aceptada); los clientes Java tradicionales conservan el protocolo vanilla sin cambios.
- Desactivado por defecto; ningun envio hasta que el punto 5 de arriba se cumpla.
