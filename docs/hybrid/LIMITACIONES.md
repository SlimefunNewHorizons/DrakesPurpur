# Limitaciones, bloqueos y registro de errores

1. **Build completo imposible en esta sesion**: `repo.papermc.io` y Gradle devuelven 403 desde el sandbox, y los parches de Paper requieren descargar su arbol. Solo se compilo/probo lo independiente (crate Rust + `HybridNative` con `javac`). Cualquier integracion en `PalettedContainer`/`NoiseChunk`/`RegionFile` queda **sin implementar**, no "pendiente de probar".
2. **Sin integracion en el pipeline de chunks**: los kernels existen y son correctos, pero ningun codigo del servidor los llama todavia. Habria que parchear `SimpleBitStorage` (Mojang) via parche de Purpur y medir dentro del servidor; la ganancia de 2x en el microbench puede desaparecer (el costo dominante del servidor esta en Bukkit/NBT/IO).
3. **Ruido nativo no aporta**: 0.77-0.92x. Se mantiene el interruptor solo como experimento.
4. **Benchmarks parciales**: 4 vCPU compartidas, JDK 25.0.4, mediana de 9 rondas, sin comparacion Purpur vs Drakes vs Hibrido ni Horizon Server (requiere servidor + bots). La metodologia de Horizon (`docs/server-next.md`, `horizon-bots`) es el modelo a seguir.
5. **Horizon es 26.3, DrakesPurpur 26.2**: sin mundo ni protocolo compartido; no se intento mezclar estado.
6. **No se probo** Horizon Client, Geyser, Slimefun ni StarSuites en ejecucion.
7. No se distribuye ningun binario; `.so` se compila localmente (`native/drakes-hybrid/target/` esta ignorado).
8. Errores encontrados y corregidos durante el desarrollo: tests con `1<<31`; `JAVA_HOME` del entorno apuntaba a JDK 21 (el script usa ahora `HYBRID_JAVA_HOME`).
