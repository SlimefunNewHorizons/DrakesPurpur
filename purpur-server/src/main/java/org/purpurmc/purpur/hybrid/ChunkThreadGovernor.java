package org.purpurmc.purpur.hybrid;

import ca.spottedleaf.moonrise.common.util.MoonriseCommon;
import java.lang.management.ManagementFactory;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;

/**
 * Gobernador adaptativo de los hilos de generacion/carga de chunks (pool de trabajo de Moonrise).
 * <p>
 * Paper fija el numero de hilos al arrancar (automatico = nucleos/4: 2 hilos en 8 CPUs). Aqui el tope se mueve en caliente
 * ({@code WORKER_POOL.adjustThreadCount}) entre {@code min} y {@code max}: sube hasta {@code max} cuando el servidor esta sano
 * y baja un hilo por paso cuando el MSPT o la CPU del sistema superan los umbrales (y de golpe a {@code min} si el tick esta
 * en peligro). {@code max} = nucleos detectados - {@code reserve-cores}: esos nucleos quedan libres para el hilo principal,
 * Netty, Slimefun/async, base de datos y respaldos. Un hilo ocioso no consume CPU; el riesgo solo existe cuando todos estan
 * ocupados a la vez, y es justo cuando el gobernador reduce.
 */
public final class ChunkThreadGovernor {
    private static final Logger LOG = Logger.getLogger("ChunkThreadGovernor");

    /** Parametros de decision (puros, probables sin servidor). */
    public record Params(int min, int max, double shrinkMspt, double hardMspt, double shrinkCpu, double hardCpu,
                         long growEveryMs, long shrinkEveryMs) {}

    /** Resultado: nuevo numero de hilos (igual a {@code current} si no hay cambio). */
    static int decide(int current, Params p, double mspt, double cpu, long msSinceChange) {
        int lo = Math.max(1, p.min()), hi = Math.max(lo, p.max());
        if (current < 0) {                                   // primera decision: sano -> tope, si no -> minimo
            return (mspt >= p.shrinkMspt() || cpu >= p.shrinkCpu()) ? lo : hi;
        }
        current = Math.min(hi, Math.max(lo, current));
        if (mspt >= p.hardMspt() || cpu >= p.hardCpu()) return lo;                       // peligro: minimo ya
        if (mspt >= p.shrinkMspt() || cpu >= p.shrinkCpu()) {                           // carga: un hilo menos por paso
            return msSinceChange >= p.shrinkEveryMs() ? Math.max(lo, current - 1) : current;
        }
        boolean calm = mspt < p.shrinkMspt() * 0.85 && cpu < p.shrinkCpu() - 0.10;      // histeresis para volver a subir
        if (calm && current < hi && msSinceChange >= p.growEveryMs()) return current + 1;
        return current;
    }

    /** Tope automatico: nucleos detectados menos los reservados, nunca menos que {@code min}. */
    static int autoMax(int cores, int reserve, int min) {
        return Math.max(Math.max(1, min), cores - Math.max(0, reserve));
    }

    private static ScheduledExecutorService ex;
    private static volatile boolean enabled;
    private static volatile Params params;
    private static volatile int current = -1;
    private static volatile long lastChange = System.nanoTime();
    private static volatile double lastMspt, lastCpu;
    private static final AtomicLong CHANGES = new AtomicLong(), SHRINKS = new AtomicLong(), HARD = new AtomicLong();

    private ChunkThreadGovernor() {}

    public static boolean enabled() {
        return enabled;
    }

    public static String describe() {
        Params p = params;
        if (!enabled || p == null) return "gobernador de hilos de chunks: apagado (la cantidad la fija chunk-system.worker-threads)";
        return String.format("gobernador de hilos de chunks: activo=%d (rango %d-%d, %d CPUs detectadas) mspt=%.1f cpu-sistema=%.0f%% cambios=%d bajadas=%d emergencias=%d",
            current, p.min(), p.max(), Runtime.getRuntime().availableProcessors(), lastMspt, lastCpu * 100,
            CHANGES.get(), SHRINKS.get(), HARD.get());
    }

    public static synchronized void configure(boolean on, int min, int max, int reserveCores, double shrinkMspt,
                                              double hardMspt, double shrinkCpu, long growSeconds, long shrinkSeconds) {
        int cores = Runtime.getRuntime().availableProcessors();
        int lo = Math.max(1, min);
        int hi = max > 0 ? Math.max(lo, max) : autoMax(cores, reserveCores, lo);
        params = new Params(lo, hi, shrinkMspt, Math.max(shrinkMspt, hardMspt), shrinkCpu, Math.min(0.99, shrinkCpu + 0.07),
            Math.max(1, growSeconds) * 1000L, Math.max(1, shrinkSeconds) * 1000L);
        if (on == enabled) return;
        enabled = on;
        if (on) {
            ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Drakes-ChunkGovernor");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleWithFixedDelay(ChunkThreadGovernor::tick, 5, 1, TimeUnit.SECONDS);
            LOG.info("[Hybrid] gobernador de hilos de chunks ACTIVADO: " + describe());
        } else {
            if (ex != null) ex.shutdownNow();
            ex = null;
            current = -1;
            LOG.info("[Hybrid] gobernador de hilos de chunks desactivado.");
        }
    }

    private static double systemCpu() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            double v = os.getCpuLoad();
            return v < 0 ? 0 : v;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void tick() {
        if (!enabled) return;
        try {
            Params p = params;
            double mspt = Bukkit.getAverageTickTime(), cpu = systemCpu();
            lastMspt = mspt;
            lastCpu = cpu;
            long sinceMs = (System.nanoTime() - lastChange) / 1_000_000L;
            int next = decide(current, p, mspt, cpu, sinceMs);
            if (next != current) {
                boolean emergency = current >= 0 && next == p.min() && next < current - 1;
                if (next < current) SHRINKS.incrementAndGet();
                if (emergency) HARD.incrementAndGet();
                MoonriseCommon.WORKER_POOL.adjustThreadCount(next);
                LOG.info("[Hybrid] hilos de chunks " + current + " -> " + next + " (mspt=" + String.format("%.1f", mspt)
                    + ", cpu=" + String.format("%.0f%%", cpu * 100) + ")");
                current = next;
                lastChange = System.nanoTime();
                CHANGES.incrementAndGet();
            }
        } catch (Throwable t) {
            LOG.log(Level.FINE, "gobernador: " + t);
        }
    }
}
