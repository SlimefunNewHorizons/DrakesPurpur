package org.purpurmc.purpur.rust;

import java.io.File;
import java.lang.invoke.MethodHandle;
import java.lang.foreign.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.purpurmc.purpur.DrakesKernelBridge;

/**
 * SlimefunRustEngine — High-performance Off-Heap Native Engine
 * Links Java 25 LTS to Rust (libslimefun_ffi.so) via Project Panama Foreign Function & Memory (FFM) API.
 * Thin, honest binding: no simulated results. If the library is missing or its ABI is not
 * supported the engine reports "not linked" and returns zeros. It does NOT eliminate GC; see
 * Slimefun-Rust/docs/RUST_NATIVO.md for measured numbers.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class SlimefunRustEngine {
    private static final Logger LOGGER = Logger.getLogger("SlimefunRustEngine");
    private static volatile boolean nativeLinked = false;
    private static MethodHandle tickCycleHandle;
    private static MethodHandle lastUnmetDemandHandle;
    private static MethodHandle lastWastedEnergyHandle;

    private static final AtomicLong TOTAL_NATIVE_TICKS = new AtomicLong(0);
    private static final AtomicLong TOTAL_NATIVE_TIME_NANOS = new AtomicLong(0);

    static {
        initPanamaBindings();
    }

    private SlimefunRustEngine() {}

    public static synchronized void initPanamaBindings() {
        if (nativeLinked) return;
        try {
            File nativeFile = new File("natives/libslimefun_ffi.so");
            if (!nativeFile.exists()) {
                // Check fallback in Slimefun-Rust repository target
                File repoBuild = new File("../Slimefun-Rust/target/release/libslimefun_ffi.so");
                if (repoBuild.exists()) {
                    nativeFile = repoBuild;
                }
            }

            if (nativeFile.exists()) {
                System.load(nativeFile.getAbsolutePath());
                SymbolLookup lookup = SymbolLookup.libraryLookup(nativeFile.getAbsolutePath(), Arena.global());
                Linker linker = Linker.nativeLinker();

                var abiOpt = lookup.find("slimefun_abi_version");
                if (abiOpt.isEmpty()) throw new IllegalStateException("sin slimefun_abi_version: biblioteca antigua");
                int abi = (int) linker.downcallHandle(abiOpt.get(), FunctionDescriptor.of(ValueLayout.JAVA_INT)).invokeExact();
                if (abi < 1 || abi > 2) throw new IllegalStateException("ABI " + abi + " no soportada (1-2)");

                var tickOpt = lookup.find("slimefun_execute_tick_cycle");
                if (tickOpt.isPresent()) {
                    tickCycleHandle = linker.downcallHandle(tickOpt.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
                }

                var demandOpt = lookup.find("slimefun_last_unmet_demand");
                if (demandOpt.isPresent()) {
                    lastUnmetDemandHandle = linker.downcallHandle(demandOpt.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
                }

                var wastedOpt = lookup.find("slimefun_last_wasted_energy");
                if (wastedOpt.isPresent()) {
                    lastWastedEnergyHandle = linker.downcallHandle(wastedOpt.get(), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
                }

                nativeLinked = tickCycleHandle != null;
                LOGGER.info("[RustEngine] Panama FFM Bindings inicializados exitosamente contra " + nativeFile.getName());
            } else {
                LOGGER.info("[RustEngine] libslimefun_ffi.so no detectado. Motor off-heap ausente o ABI incompatible; motor inactivo (sin simulación).");
            }
        } catch (Throwable t) {
            LOGGER.warning("[RustEngine] No se pudieron enlazar símbolos nativos de Rust: " + t.getMessage());
        }
    }

    public static boolean isNativeLinked() {
        return nativeLinked;
    }

    /**
     * Executes a complete Slimefun energy network and machine tick cycle off-heap.
     */
    public static RustCycleResult executeTick() {
        long start = System.nanoTime();
        long tickIndex = 0;
        long unmet = 0;
        long wasted = 0;

        if (nativeLinked && tickCycleHandle != null) {
            try {
                tickIndex = (long) tickCycleHandle.invokeExact();
                if (lastUnmetDemandHandle != null) unmet = (long) lastUnmetDemandHandle.invokeExact();
                if (lastWastedEnergyHandle != null) wasted = (long) lastWastedEnergyHandle.invokeExact();
            } catch (Throwable t) {
                LOGGER.warning("[RustEngine] Fallo en downcall nativo de tick: " + t.getMessage());
            }
        } else {
            // Sin biblioteca: no se inventan datos.
            tickIndex = TOTAL_NATIVE_TICKS.get() + 1;
        }

        long durationNanos = System.nanoTime() - start;
        TOTAL_NATIVE_TICKS.incrementAndGet();
        TOTAL_NATIVE_TIME_NANOS.addAndGet(durationNanos);

        return new RustCycleResult(tickIndex, unmet, wasted, durationNanos, nativeLinked);
    }

    /**
     * Runs a synthetic benchmark testing off-heap execution speed and memory allocations.
     */
    public static String runSyntheticBenchmark(int iterations) {
        long start = System.nanoTime();
        long totalNanos = 0;
        for (int i = 0; i < iterations; i++) {
            RustCycleResult r = executeTick();
            totalNanos += r.durationNanos();
        }
        long wallTimeMs = (System.nanoTime() - start) / 1_000_000;
        double avgMicros = (double) totalNanos / (iterations * 1_000.0);
        double opsPerSec = (double) iterations / Math.max(0.001, wallTimeMs / 1000.0);

        if (!nativeLinked) {
            return "Rust Engine: biblioteca no enlazada; nada que medir (sin simulacion).";
        }
        return String.format(
            "Rust FFM tick (%d ciclos, solver sin nodos registrados desde Java):\n"
            + "  - Costo medio por llamada: %.2f us (3 downcalls)\n"
            + "  - Tiempo total: %d ms\n"
            + "  - Aviso: mide solo el costo FFI; no demuestra ganancia frente a Java.",
            iterations, avgMicros, wallTimeMs);
    }

    public record RustCycleResult(long tickNumber, long unmetDemand, long wastedEnergy, long durationNanos, boolean isNative) {}
}
