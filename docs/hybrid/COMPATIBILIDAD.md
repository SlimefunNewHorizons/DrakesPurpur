# Matriz de compatibilidad (actualizada 2026-11-06, fase 3)

Leyenda: **OK** = verificado con ejecucion real en CI · **PARCIAL** · **NV** = no verificado · N/A

## Servidor de pruebas Slimefun + StarSuites (workflow `hybrid-compat.yml`, run 8, commit `8035a1c`)
Entorno: GitHub Actions, JDK 25, DrakesPurpur Hybrid construido desde esta rama + `libdrakes_hybrid_ffi.so`, `native.hybrid.enabled=true, bits=true`. Repos de terceros usados **solo en lectura** (checkout en CI): `SlimefunNewHorizons/Slimefun4-Drake@port-26x` (Maven, produce `Slimefun v11.0-Universal-26.x-SNAPSHOT`) y `SlimefunNewHorizons/Drakes-Suites@26.x` (Maven reactor, Java 25, MC 26.2). Ambos compilan sin tocar su codigo.

| Area | Estado | Evidencia |
|---|---|---|
| Compilar Slimefun4-Drake (port-26x) y Drakes-Suites (26.x) | **OK** | `mvn package`, exit 0 |
| Cargar Slimefun + DrakesCore/Combat/Tech/Utility/Multiverse/Server/Generators/Bio/Magic con Hybrid ON | **OK** | `Enabling ...` de las 10 piezas, `Done (9-15 s)`, sin excepciones |
| Registro de Slimefun | **OK** | 713 items registrados |
| Ida y vuelta `SlimefunItem.getById` y `getByItem(ItemStack)` (60 items) | **OK** | PASS=123, FAIL=0 (fase 2) |
| Colocar bloques Slimefun con `BlockStorage` y leerlos | **OK** | fase 1 |
| **Persistencia tras reinicio** (BlockStorage guardado y releido en un segundo arranque) | **OK** | "PERSISTENCIA tras reinicio: 17 bloques con datos de la fase 1" |
| Apagado normal (`Bukkit.shutdown`, guarda mundos) | **OK** | log de fase 2 |
| Maquinas en ejecucion (tickers, energia, cargo), GUIs/inventarios | **NV** | el smoke no simula un jugador |
| Recetas/Research/Custom Items en uso real, PersistentDataContainer de addons | **NV** | solo se prueba registro e identidad de items |
| Sistemas de bosses, progresion, SAORI, DrakesProfiler, RyzenWorkerPool, Geyser Companion | **NV** | cargan sin error, sin prueba funcional |
| Bedrock/Geyser, Horizon Client | **NV** | sin cliente |
| Modulo `drakes-onechunk` | **Problema preexistente** | su jar no contiene `plugin.yml`/`paper-plugin.yml`: Paper lo rechaza con ERROR (`Directory 'plugins/drakes-onechunk-...jar' does not contain a paper-plugin.yml or plugin.yml`). Es de Drakes-Suites; **no se modifico**. Separar y corregir en ese repo |

Nota: que los plugins carguen y pasen estas operaciones **no** prueba que el motor hibrido los beneficie ni que cada addon funcione; solo que no los rompe en estas rutas y que Rust no interviene en ellas (el unico enganche, `PalettedContainer`, es transparente y esta probado por paridad).

## Resto de la matriz
| Area | Estado | Nota |
|---|---|---|
| Motor hibrido OFF (defecto) | **OK** | servidor completo + autotest, control Java |
| Fallback a Java si falta la biblioteca | **OK** (logica) | `HybridNative.find()` nulo -> Java |
| Paridad de generacion/guardado de chunks | **PARCIAL** | NBT identico en 13 872 contenedores reales (bits); NormalNoise nativo bit a bit en 3 ruidos reales; **no** se ha demostrado paridad de chunks completos generados con un grafo nativo (no existe) |
| Bukkit/Paper/Purpur, eventos, scheduler | **OK** por construccion | Build Paper/Purpur en verde; el parche 0023 solo toca `PalettedContainer` |
| Mundos / activar-desactivar sin corrupcion | **PARCIAL** | persistencia de BlockStorage y mundos tras reinicio con Hybrid ON verificada; no se ha hecho A/B de regiones entre ON/OFF byte a byte |
| Java vanilla 26.2 | NV | sin cambios de protocolo |
| Horizon Client (26.3) -> servidor 26.2 | NV | el cliente trae adaptador 26.2; ver CLIENTE_HORIZON.md |
| Mods Forge/Fabric/NeoForge | N/A | no se promete |
| FPS / render | N/A | cliente |

## Monolito (run 42322308340, job "Monolito + Hybrid ON")

- Build correcto; las 9 suites embebidas se **extrajeron del JAR en el primer arranque** (`bundled-state.yml` con 9 entradas, modo activo) y arrancan sin que estén en `plugins/` de origen.
- Smoke fase 1: **156 PASS / 0 FAIL**; fase 2 tras reinicio: **123 PASS / 0 FAIL**, `PERSISTENCIA tras reinicio: 17 bloques con datos de la fase 1`, `RESULT OK`. 713 items de Slimefun, sin lineas SEVERE/ERROR. Arranque ~12 s.
- Biblioteca nativa: arranco con `bits=true` **sin `-Ddrakes.native.dir`** y la ruta resuelta cuelga de `run/drakes-d…` (coherente con la extraccion a `drakes-data/natives/embedded-<sha>`). **La ruta completa y el origen no se vieron** (el log estaba truncado): el CI ahora imprime la ruta completa y el contenido de `drakes-data/natives/`; hasta leerlo, la extraccion embebida se considera *probable, no confirmada*.
- Falla de instrumentacion: el contador "entradas drakes-bundled en el JAR" daba 0 pese a que la extraccion funciona (patron de busqueda erroneo); corregido.
- No cubre: GUIs de Slimefun con jugador real, maquinas/redes en carga, ni los 63 addons ausentes.
