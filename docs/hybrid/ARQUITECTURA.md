# Arquitectura

## Hechos verificados
- DrakesPurpur: Purpur/Paper **26.2**, Java 25, puente Panama ya existente (`nativelib/DrakesNative`, `docs/NATIVO.md`: en su benchmark Java gano o empato en todo; nativo OFF).
- Horizon (`horizon-rust/horizon`, MIT): **reescritura completa de Minecraft Java 26.3 en Rust**, ~190k lineas solo en `horizon-world`; no es una libreria embebible ni un backend para Bukkit. `horizon-server` (repo de distribucion) **no contiene codigo**: el motor es `horizon-client --dedicated` + supervisor `crates/horizon-server`.
- Horizon tiene un adaptador de protocolo hacia servidores antiguos (`horizon-protocol/src/adapter`, v26.2 incluido): un cliente Horizon *puede* hablar con un servidor 26.2. Esto es lo que hay que probar primero (ver COMPATIBILIDAD.md); no se ha podido ejecutar aqui.

## Alternativas
| | Descripcion | Veredicto |
|---|---|---|
| A | DrakesPurpur autoritativo + kernels Rust (bibliotecas) | **ELEGIDA** |
| B | Horizon como backend, JVM con Bukkit | Rechazada: Horizon no expone estado de mundo/entidades a un tercero; habria que implementar Bukkit/Paper API sobre Rust (proyecto de anos) |
| C | Dos motores con subsistemas delimitados | Rechazada: exige sincronizar estado de bloques/entidades; riesgo directo de dos escritores |
| D | Integracion profunda / mundo compartido | Rechazada: viola la restriccion de un unico escritor; 26.2 vs 26.3 ademas difieren en formatos y registros |

Evidencia: A es la unica que conserva Bukkit/Paper/Slimefun/StarSuites sin tocar y permite fallback inmediato. Un runtime Bukkit sobre Horizon queda fuera de alcance (proyecto separado, sin tocar DrakesPurpur).

## Reglas
1. **Un solo escritor**: Rust nunca abre ni escribe archivos de region. `drakes_region_scan_header` recibe bytes ya leidos por Java y es de solo lectura.
2. Rust no guarda punteros ni reserva memoria que Java deba liberar; Java presta segmentos de `Arena.ofConfined()` por llamada.
3. Funciones puras, sin estado global => seguras desde cualquier hilo; no hay objetos de Minecraft en la frontera (solo `int[]`, `long[]`, `double[]`, `byte[]`).
4. Retorno `i32`: `>=0` ok, `<0` codigo (`-1` null, `-2` arg, `-3` rango, `-4` formato, `-5` panic capturado).
5. Fallback: motor apagado / sin biblioteca / ABI distinta / lote < `min-batch` / error nativo => referencia Java con semantica identica; un fallo en runtime apaga esa ruta hasta reiniciar.
6. ABI versionada: `drakes_hybrid_abi_version()` debe ser exactamente `HybridNative.ABI`.

## ABI v1
`drakes_bits_pack`, `drakes_bits_unpack`, `drakes_noise_improved_batch`, `drakes_region_scan_header` (firmas en `native/drakes-hybrid/src/lib.rs`).

## Configuracion (`drakes-data/perf.yml`, todo `false` por defecto)
```
native.hybrid.enabled | min-batch (2048) | bits | noise | region
```
Se reutiliza `native.dir` y la busqueda de biblioteca de `DrakesNative` (`-Ddrakes.native.dir`, `$DRAKES_NATIVE_DIR`, `natives/`, `drakes-data/natives/`).
