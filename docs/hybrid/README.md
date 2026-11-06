# DrakesPurpur Horizon Hybrid Engine (EXPERIMENTAL)

Rama: `experimental/horizon-rust-hybrid-engine`. **Nada de esto esta activo por defecto** (`perf.yml -> native.hybrid.enabled: false`).
Java (DrakesPurpur) sigue siendo la unica autoridad del mundo; Rust solo calcula sobre buffers prestados durante una llamada.

| Documento | Contenido |
|---|---|
| [ARQUITECTURA.md](ARQUITECTURA.md) | Opciones A-D, decision con evidencia, ABI, reglas de seguridad |
| [HORIZON_COMPONENTS.md](HORIZON_COMPONENTS.md) | Que hay realmente en Horizon, licencia, que se reutiliza / descarta |
| [COMPATIBILIDAD.md](COMPATIBILIDAD.md) | Matriz: plugins, Slimefun, StarSuites, clientes Java/Horizon/Bedrock |
| [LIMITACIONES.md](LIMITACIONES.md) | Bloqueos, lo no verificado, lo que falta |
| [DESPLIEGUE.md](DESPLIEGUE.md) | Compilar, servidor de pruebas, plan de despliegue seguro |
| [bench_hybrid_2026-11-06.txt](bench_hybrid_2026-11-06.txt) | Salida cruda de paridad + benchmark |

## Estado honesto (2026-11-06)

| Pieza | Estado | Evidencia |
|---|---|---|
| Crate `native/drakes-hybrid` (ABI 1) | Compila, 6 tests Rust OK | `cargo test` |
| `HybridNative` (puente FFM + fallback Java) | Compila con JDK 25; paridad bit a bit con la referencia Java | `scripts/hybrid-check.sh` |
| Empaquetado `SimpleBitStorage` por lotes | ~2.1x a 4096 valores (una seccion), mas lento a 256 | bench; **no probado dentro del servidor** |
| Ruido de Perlin por lotes | **Sin ganancia** (0.77-0.92x). Se deja OFF; el JIT de Java iguala a Rust | bench |
| Analisis de cabeceras `.mca` (solo lectura) | Paridad con Java; utilidad diagnostica, no de rendimiento | test |
| Integracion en el pipeline real de chunks de Paper | **NO hecha**: ver LIMITACIONES.md | - |
| Build completo del servidor (Paper + parches) | **NO ejecutado**: el entorno no alcanza repo.papermc.io (403) | - |
| Horizon Client -> DrakesPurpur | **NO probado** (no hay cliente ni GPU aqui) | - |
