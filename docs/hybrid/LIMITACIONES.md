# Limitaciones, bloqueos y registro de errores

1. (Resuelto en fase 2: la CI de GitHub compila el servidor completo; el sandbox local sigue sin alcanzar Paper/Mojang.) Antes: **build completo imposible en esta sesion**: `repo.papermc.io` y Gradle devuelven 403 desde el sandbox, y los parches de Paper requieren descargar su arbol. Solo se compilo/probo lo independiente (crate Rust + `HybridNative` con `javac`). Cualquier integracion en `PalettedContainer`/`NoiseChunk`/`RegionFile` queda **sin implementar**, no "pendiente de probar".
2. **Sin integracion en el pipeline de chunks**: los kernels existen y son correctos, pero ningun codigo del servidor los llama todavia. Habria que parchear `SimpleBitStorage` (Mojang) via parche de Purpur y medir dentro del servidor; la ganancia de 2x en el microbench puede desaparecer (el costo dominante del servidor esta en Bukkit/NBT/IO).
3. **Ruido nativo no aporta**: 0.77-0.92x. Se mantiene el interruptor solo como experimento.
4. **Benchmarks parciales**: 4 vCPU compartidas, JDK 25.0.4, mediana de 9 rondas, sin comparacion Purpur vs Drakes vs Hibrido ni Horizon Server (requiere servidor + bots). La metodologia de Horizon (`docs/server-next.md`, `horizon-bots`) es el modelo a seguir.
5. **Horizon es 26.3, DrakesPurpur 26.2**: sin mundo ni protocolo compartido; no se intento mezclar estado.
6. **No se probo** Horizon Client, Geyser, Slimefun ni StarSuites en ejecucion.
7. No se distribuye ningun binario; `.so` se compila localmente (`native/drakes-hybrid/target/` esta ignorado).
8. Errores encontrados y corregidos durante el desarrollo: tests con `1<<31`; `JAVA_HOME` del entorno apuntaba a JDK 21 (el script usa ahora `HYBRID_JAVA_HOME`).

## Fase 2 (2026-11-06)
9. **Slimefun y StarSuites NO se verificaron.** Sus jars no estan en este repo (`Slimefun4-Drake`, `Drakes-Suites` son repos aparte, y las ramas publicas estan orientadas a 1.21.11; la linea 26.2 esta en `Drakes-Suites` rama `26.x`). Construirlos y arrancarlos junto al servidor en CI requiere acceso a sus dependencias privadas/Maven y no se intento. El hook solo toca `PalettedContainer` y esta apagado por defecto, pero **eso no es una prueba funcional**.
10. El autotest corre con mundo nuevo y un solo hilo de peticion; no mide jugadores concurrentes, Elytra ni vuelo rapido, ni pregenerado vs nuevo por separado, ni TPS/MSPT bajo carga, ni Purpur original vs DrakesPurpur vs Hibrido (sin el parche, el control es la misma rama con Rust apagado).
11. Runners de CI compartidos: el control Java varia 12-23 % entre corridas; cualquier mejora menor que eso no es concluyente.
12. Horizon Client no se probo contra el servidor.
13. El autotest termina con `Runtime.halt` (mundo desechable): no usarlo en servidores con datos.

## Fase 3 (2026-11-06)
14. **Port del grafo completo de density functions: no realizado.** Se implemento y se demostro (bit a bit) la primitiva `NormalNoise` y se midio su velocidad: sin ganancia. Con el perfil JFR (ruido propio ~14 %, resto del grafo con estado de `NoiseChunk`) y la nota de Horizon de que su propia compilacion de density functions no cubre el grafo completo ni tiene mejora medida, no hay evidencia que justifique portar el resto. Si se retoma, el siguiente paso medible es un evaluador del grafo de `NoiseInterpolator.fillSlice` por lotes con paridad de bloques por semilla.
15. `HybridDensity` usa reflexion sobre campos privados de Mojang: debe sustituirse por accesores via parche antes de cualquier uso real.
16. **Smoke de Slimefun/StarSuites** cubre registro, identidad de items, `BlockStorage` y persistencia; **no** maquinas en ejecucion, GUIs, bosses, Geyser, SAORI/DrakesProfiler/RyzenWorkerPool ni jugadores.
17. `drakes-onechunk` (Drakes-Suites) se rechaza por falta de `plugin.yml`: problema preexistente en ese repo, no corregido aqui.
18. Benchmarks por chunk completo contra Purpur original, multiples jugadores, Elytra y Horizon Server **no se han hecho**.
19. Plataformas nativas: solo Linux x86_64 (CI). Windows/macOS/ARM sin construir ni probar.
20. Rollback: poner `native.hybrid.enabled: false` (o retirar `libdrakes_hybrid_ffi`) y reiniciar; el parche 0023 delega al codigo original de Mojang sin la biblioteca. No hay migracion de datos que revertir (formato de disco identico, verificado).
