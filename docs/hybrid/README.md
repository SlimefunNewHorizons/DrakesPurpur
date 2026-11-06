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

## Estado (2026-11-06, segunda fase)

| Pieza | Estado | Evidencia |
|---|---|---|
| Servidor completo compilado con la rama (parche 0023) | OK | CI `Build` + `Hybrid Engine CI` en verde |
| Kernel de bits **integrado** en `PalettedContainer.pack()/reencodeContents()` con fallback Java | OK, **apagado por defecto** | [RESULTADOS.md](RESULTADOS.md) |
| Paridad dentro del servidor (13 872 contenedores reales x2 corridas) | 0 diferencias | autotest |
| Rendimiento Rust vs Java | **Sin mejora demostrable** (0-6 %, bajo el ruido) | RESULTADOS.md |
| Ruido de Perlin nativo | Sin ganancia (0.77-1.02x); no integrado | bench |
| Instrumentacion (gen/guardar/cargar/envio, JFR, `perf-status.json`) | OK | RESULTADOS.md |
| Investigacion Horizon Client / protocolo 776 vs 777 | Documentada, **no probada con cliente real** | [CLIENTE_HORIZON.md](CLIENTE_HORIZON.md) |
| Slimefun / StarSuites ejecutando sobre la rama | **NO verificado** (ver LIMITACIONES.md) | - |
