package org.purpurmc.purpur.hybrid;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Prefetch direccional de chunks para jugadores que se desplazan rapido (Elytra, vuelo creativo, montura).
 * <p>
 * Estima la velocidad horizontal de cada jugador por la variacion de posicion, y si supera {@code min-speed} solicita de
 * forma asincrona (API publica de Paper {@code World#getChunkAtAsync}, prioridad normal, no urgente) los chunks en un
 * abanico por delante del radio de vision. No toca el estado del mundo, no bloquea el hilo principal, respeta el
 * presupuesto de solicitudes en vuelo y evita trabajo duplicado. Apagado por defecto ({@code native.hybrid.prefetch.enabled}).
 */
public final class HybridChunkPrefetch {
    private static final Logger LOG = Logger.getLogger("HybridPrefetch");

    private static volatile boolean enabled;
    private static volatile double minSpeed = 20.0;     // bloques/s
    private static volatile double leadSeconds = 3.0;   // segundos de anticipacion
    private static volatile int maxLookahead = 8;       // chunks por delante del radio de vision
    private static volatile int halfWidth = 2;          // chunks a cada lado del rumbo
    private static volatile int maxInFlight = 24;
    private static volatile long intervalMs = 250;
    private static ScheduledExecutorService ex;

    public static final LongAdder REQUESTED = new LongAdder(), SKIPPED_LOADED = new LongAdder(), SKIPPED_PENDING = new LongAdder(),
        DROPPED_BUDGET = new LongAdder(), COMPLETED = new LongAdder(), FAILED = new LongAdder(), LATENCY_NANOS = new LongAdder();
    private static final AtomicInteger IN_FLIGHT = new AtomicInteger();
    private record Key(UUID world, int x, int z) {}
    private static final Set<Key> PENDING = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private static final class State {
        World world;
        double x, z, vx, vz;
        long t;
    }

    private HybridChunkPrefetch() {}

    public static boolean enabled() {
        return enabled;
    }

    public static int inFlight() {
        return IN_FLIGHT.get();
    }

    public static String describe() {
        return String.format("enabled=%s min-speed=%.0f b/s lead=%.1fs lookahead=%d anchura=%d en-vuelo=%d/%d | solicitados=%d completados=%d fallidos=%d omitidos(cargado)=%d omitidos(duplicado)=%d descartados(presupuesto)=%d latencia-media=%.1f ms",
            enabled, minSpeed, leadSeconds, maxLookahead, halfWidth * 2 + 1, IN_FLIGHT.get(), maxInFlight, REQUESTED.sum(), COMPLETED.sum(),
            FAILED.sum(), SKIPPED_LOADED.sum(), SKIPPED_PENDING.sum(), DROPPED_BUDGET.sum(),
            COMPLETED.sum() == 0 ? 0.0 : LATENCY_NANOS.sum() / 1e6 / COMPLETED.sum());
    }

    public static synchronized void configure(boolean on, double minSpeedBps, double lead, int lookahead, int width, int inFlightMax, long periodMs) {
        minSpeed = minSpeedBps;
        leadSeconds = lead;
        maxLookahead = Math.max(1, lookahead);
        halfWidth = Math.max(0, width);
        maxInFlight = Math.max(1, inFlightMax);
        intervalMs = Math.max(50, periodMs);
        setEnabled(on);
    }

    public static synchronized void setEnabled(boolean on) {
        if (on == enabled) return;
        enabled = on;
        if (on) {
            ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Drakes-Prefetch");
                t.setDaemon(true);
                return t;
            });
            ex.scheduleWithFixedDelay(() -> {
                try {
                    MinecraftServer.getServer().execute(HybridChunkPrefetch::tick);
                } catch (Throwable t) {
                    LOG.log(Level.FINE, "prefetch: " + t);
                }
            }, 2000, intervalMs, TimeUnit.MILLISECONDS);
            LOG.info("[Hybrid] prefetch direccional de chunks ACTIVADO (experimental).");
        } else {
            if (ex != null) ex.shutdownNow();
            ex = null;
            STATES.clear();
            LOG.info("[Hybrid] prefetch direccional de chunks desactivado.");
        }
    }

    /** Hilo principal: lee posiciones, estima rumbo y solicita (async) los chunks por delante. */
    private static void tick() {
        if (!enabled) return;
        long now = System.nanoTime();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Location l = p.getLocation();
            World w = l.getWorld();
            State st = STATES.computeIfAbsent(p.getUniqueId(), k -> new State());
            if (st.world == w && st.t != 0) {
                double dt = (now - st.t) / 1e9;
                if (dt > 0.05) {
                    double ix = (l.getX() - st.x) / dt, iz = (l.getZ() - st.z) / dt;
                    st.vx = st.vx * 0.5 + ix * 0.5;   // media exponencial: suaviza paquetes de posicion irregulares
                    st.vz = st.vz * 0.5 + iz * 0.5;
                }
            } else {
                st.vx = 0;
                st.vz = 0;
            }
            st.world = w;
            st.x = l.getX();
            st.z = l.getZ();
            st.t = now;
            double speed = Math.hypot(st.vx, st.vz);
            if (speed < minSpeed || speed > 400) continue; // 400 b/s: salto/teletransporte, no desplazamiento
            double ux = st.vx / speed, uz = st.vz / speed;
            int viewR = w.getViewDistance();
            int look = Math.min(maxLookahead, (int) Math.ceil(speed * leadSeconds / 16.0));
            for (int step = viewR + 1; step <= viewR + look; step++) {
                for (int off = -halfWidth; off <= halfWidth; off++) {
                    double px = st.x + ux * step * 16 - uz * off * 16;
                    double pz = st.z + uz * step * 16 + ux * off * 16;
                    request(w, (int) Math.floor(px / 16.0), (int) Math.floor(pz / 16.0));
                }
            }
        }
        if (STATES.size() > 4 * Math.max(1, Bukkit.getOnlinePlayers().size())) {
            var online = new java.util.HashSet<UUID>();
            for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
            STATES.keySet().removeIf(id -> !online.contains(id));
        }
    }

    static void request(World w, int cx, int cz) {
        if (w.isChunkLoaded(cx, cz)) {
            SKIPPED_LOADED.increment();
            return;
        }
        Key key = new Key(w.getUID(), cx, cz);
        if (!PENDING.add(key)) {
            SKIPPED_PENDING.increment();
            return;
        }
        if (IN_FLIGHT.get() >= maxInFlight) {
            PENDING.remove(key);
            DROPPED_BUDGET.increment();
            return;
        }
        IN_FLIGHT.incrementAndGet();
        REQUESTED.increment();
        long t0 = System.nanoTime();
        w.getChunkAtAsync(cx, cz, true, false).whenComplete((chunk, err) -> {
            IN_FLIGHT.decrementAndGet();
            PENDING.remove(key);
            if (err == null && chunk != null) {
                COMPLETED.increment();
                LATENCY_NANOS.add(System.nanoTime() - t0);
            } else {
                FAILED.increment();
            }
        });
    }
}
