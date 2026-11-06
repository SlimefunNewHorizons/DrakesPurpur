# Compilar, probar y desplegar

## Compilar y verificar (JDK 25 + cargo)
```
HYBRID_JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 scripts/hybrid-check.sh --bench
cp native/drakes-hybrid/target/release/libdrakes_hybrid_ffi.so <servidor>/natives/
```
Sale con codigo != 0 si Rust y Java difieren en un solo bit.

## Servidor de pruebas (nunca produccion)
1. Copia de un mundo (no el original). 2. `perf.yml`: `native.hybrid.enabled: true`, activa **una** ruta (`bits`). 3. Arranca con `--enable-native-access=ALL-UNNAMED`; el log debe mostrar `[Hybrid] bits=true ...`. 4. Cambia a `false`, reinicia: el mundo debe abrir igual.

## Plan seguro
Fase 0 (hecha): kernels + paridad. Fase 1: parche de servidor que llame `HybridNative.packBits` desde la serializacion de secciones, detras de la config, con test de bytes identicos a vanilla. Fase 2: bots de carga (estilo `horizon-bots`) comparando Purpur / Drakes / Hibrido a igual semilla y hardware, p50/p95/p99 y MSPT. Fase 3: 2 semanas en servidor de pruebas con Slimefun + StarSuites. Solo entonces evaluar produccion. No fusionar sin esas evidencias.
