# Native libraries (Rust) / Bibliotecas nativas

## English

**Status: everything native is OFF by default. In the benchmark Java won (or tied) on every path.**

### What exists
| Library | Repo | ABI | Bridge in the engine |
|---|---|---|---|
| `libodysseia_ffi.so` / `odysseia_ffi.dll` | Odysseia-Rust | 1 | `nativelib.DrakesNative`: pulse counter (redstone clock guard), batched boss aura |
| `libslimefun_ffi.so` / `slimefun_ffi.dll` | Slimefun-Rust | 2 | `DrakesNative`: saturating sum, `fillSequential`, `storeRemaining`, market price. `SlimefunRustEngine` (tick counter) |

Not bridged (stubs, no real logic in Rust either): Slimefun cargo routing, `TickerEngine` (only counts ticks), `slimefun_load/save_sqlite_db` (BlockStorage import/export), the energy graph solver (Java never registers nodes), Odysseia chat filter / troll / TPS message / legacy `evaluate_clock`. No C code exists in the stack; none is proposed because no hot spot with measurable gain was found.

### How it works
- Loaded with Java 22+ FFM (Panama), **no JNI**. `perf.yml -> native.enabled: false` is the master switch; each path has its own switch (`native.odysseia.pulses|aura`, `native.slimefun.fill|sum|price|tick-engine`).
- Search order for the file: `-Ddrakes.native.dir`, `$DRAKES_NATIVE_DIR`, `native.dir` (default `natives/`), `drakes-data/natives/`, `natives/`.
- ABI is checked (Odysseia exactly 1, Slimefun 2). Missing file, old library, wrong ABI, missing symbol or any failing downcall => automatic fallback to the Java implementation (same semantics, cross-checked in the benchmark) plus a log line. A runtime failure disables that path until restart.
- Plugins (Odysseia, Slimefun) call static methods: `DrakesNative.pulses()` (always returns a working counter; Java ring buffer, zero allocation, when native is off), `auraBatch`, `fillSequential`, `storeRemaining`, `sumSaturating`, `marketPrice`. Main-thread use; the Java `PulseCounter` is not thread-safe.

### Build and install
```
# Odysseia-Rust and Slimefun-Rust (cargo >= 1.75)
nice -n 10 cargo build --release -p odysseia-ffi -j4      # target/release/libodysseia_ffi.so
nice -n 10 cargo build --release -p slimefun-ffi -j4      # target/release/libslimefun_ffi.so
# Windows: cargo build --release  -> target\release\odysseia_ffi.dll / slimefun_ffi.dll
mkdir -p natives && cp target/release/lib*_ffi.so natives/   # next to the server jar
# C (gcc) would go here as: gcc -O2 -shared -fPIC -o natives/libx.so x.c  (nothing needs it today)
```
Then set `native.enabled: true` plus the path switch in `drakes-data/perf.yml` and restart. Add `--enable-native-access=ALL-UNNAMED` to the JVM flags to silence the FFM warning.

### Benchmark (reproducible)
`scripts/native-bench.sh <dir-with-.so>` (compiles `nativelib` + `tools/native-bench/NativeBench.java` with javac, G1, 256 MB heap, median of 7 runs, bytes/op via `ThreadMXBean`). Full output: `docs/bench_nativo_2026-10-29.txt` (Nova, JDK 25.0.4, machine not idle).

| Path | Java | Rust/Panama | Verdict |
|---|---|---|---|
| Redstone pulse, 512 blocks | naive HashMap+Deque 50.6 ns, 56 B/op; ring buffer 35.0 ns, **0 B/op** | 79.6 ns, 0 B/op | Java ring wins; native OFF |
| Boss aura, 100 players / 1000 | 136 ns / 1.44 us | 261 ns / 2.46 us, 64 B/op | Java wins |
| Saturating sum n=16 / 1024 | 10 / 523 ns | 19 / 1384 ns | Java wins |
| fillSequential n=16 / 1024 / 8192 | 31 / 1377 / 8994 ns | 44 / 1560 / 11632 ns | Java wins |
| storeRemaining n=16 / 1024 | 13.5 / 200 ns | 18 / 360 ns | Java wins |
| Market price | 45.9 ns | 34.3 ns | tie (12 ns, called rarely); OFF |

Conclusion: the only real GC win is the **allocation-free Java** `PulseCounter` (56 B/op -> 0), which is active without any native library. Native code does not pay for the FFM transition at these sizes, and the dominant cost of EnergyNet is Bukkit/BlockStorage access, which cannot move out of Java. Revisit only with an off-heap data model (data living in Rust, no gather/scatter).

## Espanol (breve)

**Todo lo nativo esta APAGADO por defecto: en el benchmark Java gano o empato en todas las rutas.**

- Carga por Panama (Java 22+), sin JNI. Maestro: `drakes-data/perf.yml -> native.enabled` (false) + un interruptor por ruta.
- Busca la biblioteca en `-Ddrakes.native.dir`, `$DRAKES_NATIVE_DIR`, `native.dir` (`natives/`), `drakes-data/natives/`. Comprueba la ABI (Odysseia 1, Slimefun 2). Si falta o falla, cae a Java solo, con log.
- Compilar: `cargo build --release -p odysseia-ffi` y `-p slimefun-ffi` (usar `nice -n 10 ... -j4`), copiar `target/release/lib*_ffi.so` a `natives/`. En Windows salen `.dll`. No hay codigo C en el stack ni se propone ninguno sin ganancia medida.
- Sin puente (stubs): ruteo de cargo, `TickerEngine`, import/export SQLite de BlockStorage, filtro de chat/troll de Odysseia.
- Medir: `scripts/native-bench.sh <carpeta-con-.so>`; resultados en `docs/bench_nativo_2026-10-29.txt`.
- Lo unico que reduce GC de verdad: el `PulseCounter` Java sin asignaciones (56 B/op -> 0), que no necesita biblioteca nativa.
