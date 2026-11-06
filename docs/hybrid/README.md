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

## Estado (2026-11-06, fase 3)

| Pieza | Estado | Evidencia |
|---|---|---|
| Servidor completo compilado con la rama | OK | CI `Build`, `Hybrid Engine CI`, `Hybrid compat` en verde |
| Kernel de bits integrado en `PalettedContainer` (parche 0023), con fallback Java | OK, **apagado por defecto** | [RESULTADOS.md](RESULTADOS.md) |
| NormalNoise nativo (base de density functions) | Implementado y con **paridad bit a bit**; **sin ganancia de velocidad** (0.98-1.21x, dentro del ruido). **No enlazado a la generacion** | RESULTADOS.md, fase 3 |
| Port del grafo completo de density functions | **NO realizado**: ver analisis de decision en RESULTADOS.md | - |
| Rendimiento Rust vs Java (bits) | Sin mejora demostrable | RESULTADOS.md |
| Instrumentacion (gen/guardar/cargar/envio, JFR, `perf-status.json`) | OK | RESULTADOS.md |
| Slimefun + 9 modulos StarSuites con Hybrid ON | **Cargan, 123/123 comprobaciones, persistencia tras reinicio OK** (solo smoke, no uso real) | [COMPATIBILIDAD.md](COMPATIBILIDAD.md) |
| Horizon Client / protocolo 776 vs 777 | Investigado, no probado con cliente | [CLIENTE_HORIZON.md](CLIENTE_HORIZON.md) |
| Listo para produccion | **NO** | LIMITACIONES.md |
