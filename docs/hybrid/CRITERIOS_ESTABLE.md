# Criterios objetivos para promover un componente a estable (y activarlo por defecto)

Un componente deja de ser experimental **solo si cumple todos** los criterios que le aplican. `hybrid.engine.enabled` pasara a `true` por defecto unicamente cuando al menos un componente con aceleracion cumpla la columna "Mejora demostrada" **y** el motor completo cumpla "Estabilidad prolongada". Ninguna parte se declara estable por inercia.

| # | Criterio | Como se comprueba | Evidencia exigida |
|---|---|---|---|
| 1 | Build completo | `applyAllPatches`, `build`, `createPaperclipJar`, `scanJarForBadCalls` | CI en verde en el commit |
| 2 | Paridad | Autotest dentro del servidor: NBT/valores identicos Java vs Rust sobre datos reales | 0 diferencias en ≥ 10 000 contenedores / 200 000 puntos |
| 3 | Regresion | Tests automaticos del repo + suite de plugins | CI verde, sin tests omitidos |
| 4 | Concurrencia | Funciones puras sin estado global; prueba con multiples hilos (TSan o stress) | Informe sin carreras |
| 5 | Persistencia tras reinicio | Smoke de Slimefun/StarSuites con reinicio, y A/B de regiones ON/OFF | `PERSISTENCIA OK` + regiones equivalentes |
| 6 | Compatibilidad de plugins | Maquinas, redes, GUIs, bosses de Slimefun/StarSuites con jugadores simulados | Matriz sin FAIL (hoy solo smoke basico) |
| 7 | Benchmarks repetibles | Script versionado, ≥ 10 corridas, hardware dedicado | Mediana + IC 95 % |
| 8 | **Mejora demostrada** | Diferencia vs Java **fuera** del ruido de medida del control, en una carga representativa (chunks completos, no microbenchmark) | IC que no incluye 1.0 |
| 9 | Pruebas con jugadores | Sesion con jugadores/clientes reales (Java, Bedrock/Geyser, Horizon) | Registro de la sesion sin regresiones |
| 10 | Recuperacion ante fallos | Inyeccion de fallo nativo: la ruta cae a Java y el mundo no se corrompe | Prueba automatica |
| 11 | Rollback | `hybrid.engine.enabled: false` + reinicio, sin migracion | Verificado en un mundo real de prueba |
| 12 | Estabilidad prolongada | ≥ 72 h con carga en servidor de pruebas, sin fugas ni crashes | Metricas de RAM/GC/MSPT |

## Estado actual por componente (2026-11-07)
| Componente | 1 | 2 | 3 | 4 | 5 | 6 | 7 | **8** | 9 | 10 | 11 | 12 | Veredicto |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Puente FFM + fallback | ✔ | n/a | ✔ | ✔(sin estado) | ✔ | parcial | parcial | n/a | ✘ | parcial | ✔ | ✘ | **Infraestructura estable en codigo; sin activar** |
| Bit-packing en `PalettedContainer` | ✔ | ✔ | ✔ | ✔ | ✔ | parcial | parcial | **✘ (×1.00–1.07, ruido)** | ✘ | parcial | ✔ | ✘ | **Experimental: no se activa** |
| `NormalNoise` nativo | ✔ | ✔ (bit a bit) | ✔ | ✔ | n/a (no enlazado) | n/a | parcial | **✘ (×0.94–1.21, ruido)** | ✘ | n/a | ✔ | ✘ | **Experimental: no enlazado** |
| Prefetch direccional de chunks | ✔ | n/a | ✔ | parcial | n/a | parcial | **✘ (modelo sintetico, 1 hw)** | **✘ (peor en saturacion; ver RESULTADOS)** | ✘ | n/a | ✔ | ✘ | **Experimental: no se activa** |
| Instrumentacion / comandos / GUI | ✔ | n/a | ✔ | ✔ | n/a | n/a | n/a | n/a | ✘ (clics GUI sin jugador) | n/a | ✔ | ✘ | **Usable; GUI sin probar con jugadores** |
| DrakesLOD (`horizon:lod`) | codec ✔ | codec ✔ | codec ✔ | n/a | n/a | n/a | n/a | n/a | ✘ | n/a | n/a | n/a | **No conectado** |

**Conclusion honesta:** hoy **ningun componente de aceleracion cumple el criterio 8**, por lo que `hybrid.engine.enabled` se mantiene en `false` por defecto. Se activa automaticamente solo con `-Ddrakes.hybrid.engine.enabled=true` en entornos de prueba controlados (los workflows de CI lo hacen).
