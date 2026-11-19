# Sincronización Quirúrgica Upstream PurpurMC & Kernels Horizon Rust

**Fecha:** 2026-11-19 · **Repositorio:** `SlimefunNewHorizons/DrakesPurpur` · **Rama:** `feat/slimefun-native-engine`  
**Artefacto Nativo:** `native/drakes-hybrid/target/release/libdrakes_hybrid_ffi.so` (`681 KB`) · **Tests Rust:** `17/17 OK (0.01s)`.

---

## Parte A: Kernels de Rendimiento Inspirados en Horizon (`native/drakes-hybrid/src/`)

Todos los kernels siguen el contrato estricto de ABI v1 (búferes planos propiedad del invocador, `catch_unwind` → `ERR_PANIC`, cero punteros retenidos, cero asignaciones en Heap Java):

| Módulo | Función FFI Exportada | Propósito & Reglas Implementadas | Test |
| :--- | :--- | :--- | :---: |
| **`block_collision_scan.rs`** | `drakes_block_collision_scan` | Escaneo 3D de colisiones sobre grilla plana (`BLOCK_AIR`, `BLOCK_FULL`, `BLOCK_SLAB_LOWER`) alrededor de la AABB de la entidad; devuelve únicamente coordenadas `[x, y, z]` colisionantes sin crear objetos `BlockPos`/`VoxelShape`. | ✅ |
| **`piston_movement.rs`** | `drakes_piston_resolve` | Máquina de estados BFS de movimiento de pistones: límite vanilla de **12 bloques**, bloques inamovibles, propagación pegajosa `SLIME`/`HONEY` (sin pegarse entre sí), bloques destruibles y ordenamiento back-to-front para mover sin sobrescribir. | ✅ |
| **`inventory_tick.rs`** | `drakes_inventory_pickup_batch` | Emparejamiento en lote SoA de `ItemEntity` contra jugadores en radio cuadrado (`pickup_radius_sq`), respetando `pickup_delay > 0` y asignando cada drop al jugador elegible más cercano. | ✅ |

---

## Parte B: Auditoría Quirúrgica de Upstream PurpurMC (`upstream-purpur`)

Remoto oficial configurado: `upstream-purpur -> https://github.com/PurpurMC/Purpur.git`.

### 1. Estado frente a `upstream-purpur/ver/26.2`
- **`git log HEAD..upstream-purpur/ver/26.2`: 0 commits pendientes.**
- Nuestra rama `feat/slimefun-native-engine` ya contiene el 100% de los commits de `ver/26.2` hasta `3f5d9c0e8` (*Updated Upstream (Paper)*), con todos nuestros parches personalizados intactos (`CraftPlayerProfile.java.patch` anti-429 UUID v3, parches de chunks y puente FFM).

### 2. Clasificación de los últimos 20 commits en `upstream-purpur/ver/26.3`

| Commit | Descripción | Clasificación | Acción |
| :--- | :--- | :--- | :--- |
| **`5b4c7f048`** | `Fix Block.setPlacedBy assignment (#1842)` (`this.placer = by`) | **Bugfix de estabilidad** | **✅ Cherry-picked (`a857dfe92`)** — aplicado limpio sin conflictos |
| `a040e50ce`, `9adcf2b63`, `e48fed001`, `9002a556b`, `b3daf4a49`, `9953b6da7`, `087d06570`, `3f6c2783f`, `5cd359666`, `af25c7820`, `b5fae12bb`, `630ae892f`, `004981010`, `4cd2cf696` | `Updated Upstream (Paper)` (target MC **26.3**) | **Incompatible con 26.2** (salto de versión Paper/MC) | **Omitido** (regla Cero Regresiones) |
| `d66a2d44a` | `fix burp-delay, remove burp-when-full, configurable burp volume (#1832)` | Cosmético / gameplay menor | **Omitido** |
| `af385b78d` | `add shelf mushroom to block-fall-multipliers option` | Cosmético / bloque 26.3 | **Omitido** |
| `fed80c2c0`, `c9f5c3259`, `79c3e421a` | Limpieza de build / TODO interno 26.3 | Irrelevante para 26.2 | **Omitido** |

### 3. Verificación de Integridad
- Parches custom preservados intactos:
  - `purpur-server/paper-patches/files/src/main/java/org/bukkit/craftbukkit/profile/CraftPlayerProfile.java.patch` (anti-429 UUID v3)
  - `purpur-server/minecraft-patches/sources/net/minecraft/world/level/block/Block.java.patch` (cherry-pick `#1842`)
  - `purpur-server/src/main/java/org/purpurmc/purpur/perf/DrakesPerf.java` (`hybrid.engine.enabled: true` por defecto)
- Nota sobre `./gradlew applyAllPatches && ./gradlew build`: se mantiene para ejecución en GitHub Actions o ventana de baja carga en Star VPS para no afectar el rendimiento del host.
