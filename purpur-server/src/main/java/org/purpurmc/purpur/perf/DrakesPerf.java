package org.purpurmc.purpur.perf;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * drakes-data/perf.yml: interruptores de rendimiento del kernel (todos con defaults seguros).
 * - status: instantanea JSON (TPS, MSPT, ping, GC, mundos) para medir antes/despues; solo lectura.
 * - load-governor: baja send-view/simulation distance de forma gradual bajo carga sostenida y la restaura
 *   con histeresis (implementacion propia; ninguna linea copiada de otros proyectos).
 * - backup: ver DrakesBackupManager (nice/ionice/hilos, aplazar si hay lag).
 */
public final class DrakesPerf {
    private static final Logger LOGGER = Logger.getLogger("DrakesPerf");
    private static final String PATH = "drakes-data/perf.yml";
    static final String DEFAULT_YML = """
        # DrakesPurpur: rendimiento. Cada bloque se puede apagar. Ver docs/RENDIMIENTO_MOTOR.md
        enabled: true
        # Instantanea JSON periodica para scripts/medir_rendimiento.sh (solo lectura, 1 archivo pequeno).
        status:
          enabled: true
          file: drakes-data/perf-status.json
          interval-seconds: 10
        # Baja la distancia de envio/simulacion cuando MSPT queda alto de forma sostenida y la devuelve al bajar.
        # Dallas performance defaults; configurable per installation.
        load-governor:
          enabled: true
          mspt-high: 45.0          # sobre esto durante sustain-seconds -> un paso abajo
          mspt-low: 30.0           # bajo esto durante recover-seconds -> un paso arriba
          sustain-seconds: 30
          recover-seconds: 180
          min-send-view-distance: 6
          min-simulation-distance: 3
          max-steps: 6
        # Dallas hybrid performance defaults. Native paths retain Java fallback.
        # -Ddrakes.hybrid.engine.enabled=true lo fuerza (entornos de prueba controlados). Equivale a native.hybrid.enabled.
        hybrid:
          engine:
            enabled: true
        # Enable supported native libraries by default, retaining safe Java fallback.
        # Si falta el .so/.dll o la ABI no coincide se usa Java sin avisar a nadie mas que el log.
        native:
          enabled: true           # interruptor maestro
          dir: natives             # tambien se busca en drakes-data/natives, -Ddrakes.native.dir y $DRAKES_NATIVE_DIR
          ring-capacity: 64        # marcas de tiempo por bloque (>= limite de pulsos largo)
          max-entries: 8192        # bloques seguidos a la vez; si se llena se deja pasar
          odysseia:
            pulses: true          # contador de pulsos de redstone
            aura: true            # aura de jefe por lotes
          slimefun:
            fill: true            # reparto EnergyNet (fillSequential/storeRemaining)
            sum: true             # suma saturada
            price: true           # precio de DrakesSlimeMarket
            tick-engine: false     # SlimefunRustEngine (solo cuenta ticks; sin logica real)
          # Horizon Hybrid Engine (EXPERIMENTAL, docs/hybrid/). Java remains the fallback.
          hybrid:
            enabled: true          # interruptor maestro del puente libdrakes_hybrid_ffi (Rust permanente con fallback seguro a Java)
            min-batch: 2048        # bajo este tamano de lote se usa Java (el coste de la llamada FFM no compensa)
            bits: true            # empaquetado SimpleBitStorage por lotes
            noise: true           # ruido de Perlin por lotes
            region: true          # analisis (solo lectura) de cabeceras .mca
            stats: true           # contadores por etapa (pack/unpack Java vs Rust) en el log del autotest
            # Prefetch direccional: solicita (async, API publica de Paper) chunks por delante de quien se mueve rapido.
            # EXPERIMENTAL: ver docs/hybrid/RESULTADOS.md antes de activarlo.
            prefetch:
              enabled: true
              min-speed: 20.0        # bloques/s para considerar que se esta "volando"
              lead-seconds: 3.0      # anticipacion
              max-lookahead-chunks: 8
              half-width-chunks: 2
              max-in-flight: 24      # presupuesto de solicitudes simultaneas
              per-player-in-flight: 6   # multijugador: presupuesto efectivo = esto x jugadores en vuelo (tope max-in-flight), repartido a partes iguales
              throttle-mspt: 38.0    # MSPT medio desde el que se usa la mitad del presupuesto
              pause-mspt: 46.0       # MSPT medio desde el que se pausa el prefetch (el tick manda)
              interval-ms: 250
        # Hilos de generacion/carga de chunks (pool de trabajo de Moonrise) con gobernador adaptativo.
        # Paper deja por defecto nucleos/4 hilos (2 en 8 CPUs) y no los cambia. Con enabled=true el tope se mueve en caliente
        # entre min y max: sube cuando el servidor esta sano (varios jugadores explorando) y baja un hilo por paso cuando el
        # MSPT o la CPU del sistema pasan los umbrales (de golpe al minimo si el tick esta en peligro).
        # max-threads: 0 = automatico = CPUs detectadas - reserve-cores. Los reserve-cores quedan libres para el hilo principal,
        # Netty, Slimefun/async, base de datos y respaldos. Mide antes de subirlo: docs/hybrid/RESULTADOS.md (Fase 7 y 8).
        threads:
          chunk-gen:
            enabled: true
            min-threads: 2
            max-threads: 0
            reserve-cores: 2
            shrink-mspt: 38.0      # MSPT medio desde el que se baja un hilo por paso
            hard-mspt: 46.0        # desde aqui se baja directamente al minimo
            shrink-cpu: 0.88       # CPU total del sistema (0-1) desde la que se baja; hard = +0.07
            grow-seconds: 4        # espera entre subidas (de un hilo en un hilo)
            shrink-seconds: 1      # espera entre bajadas
        # Mapas de exploracion (cartografo y loot con ExplorationMapFunction). La vista previa de biomas muestrea 16384
            # posiciones en el hilo principal y colgo el servidor 10 s (ticket #435); por eso treasure-maps.enabled esta en false.
            # Con step 2 o 4 se muestrea 4x o 16x menos (solo cambia el contorno de agua dibujado); la estructura y el marcador
            # "+" son iguales. 0 = sin vista previa. Mide con /drakes hybrid workers antes de activar treasure-maps.
        maps:
          biome-preview-step: 4    # 1 vanilla | 2 | 4 | 8 | 0 sin vista previa
          biome-preview-warn-ms: 50
            # Copia de seguridad 7z: no compite con el tick ni con la red.
        backup:
          throttle: true           # nice 19 + ionice idle + hilos limitados
          threads: 2
          defer-if-mspt-above: 35.0
          defer-minutes: 10
          max-defers: 12
          start-hour: -1           # -1 = 1 h tras arrancar (legado); 0-23 = hora local fija (p.ej. 5)
          timeout-minutes: 180
        """;

    private static volatile YamlConfiguration cfg = new YamlConfiguration();
    private static ScheduledExecutorService ex;
    private static volatile boolean backupRunning;

    // governor
    private static int step;
    private static long overSince = -1, underSince = -1;
    private static final Map<World, int[]> ORIGINAL = new IdentityHashMap<>(); // {send, sim}
    private static volatile double lastMspt;

    private DrakesPerf() {}

    public static YamlConfiguration cfg() {
        return cfg;
    }

    public static boolean enabled() {
        return cfg.getBoolean("enabled", true);
    }

    public static void setBackupRunning(boolean b) {
        backupRunning = b;
    }

    // ---- mapas de exploracion (cartografo / loot): vista previa de biomas acotada ----
        private static final java.util.concurrent.atomic.LongAdder MAP_PREVIEWS = new java.util.concurrent.atomic.LongAdder(), MAP_PREVIEW_MS = new java.util.concurrent.atomic.LongAdder();
        private static volatile long mapPreviewMaxMs;

        /** maps.biome-preview-step: 1 = vanilla (128x128 muestras), 2 = 4x menos, 4 = 16x menos, 0 = sin vista previa. */
        public static int mapPreviewStep() {
            int v = cfg.getInt("maps.biome-preview-step", 4);
            return v == 0 ? 0 : (v == 2 || v == 4 || v == 8 ? v : 1);
        }

        public static void noteMapPreview(long ms) {
            MAP_PREVIEWS.increment();
            MAP_PREVIEW_MS.add(ms);
            if (ms > mapPreviewMaxMs) mapPreviewMaxMs = ms;
            if (ms >= cfg.getLong("maps.biome-preview-warn-ms", 50)) {
                LOGGER.warning("[Perf] vista previa de mapa tardo " + ms + " ms en el hilo principal (maps.biome-preview-step=" + mapPreviewStep() + ")");
            }
        }

        public static String mapPreviewStats() {
            long n = MAP_PREVIEWS.sum();
            return "mapas=" + n + " media=" + (n == 0 ? 0 : MAP_PREVIEW_MS.sum() / n) + " ms max=" + mapPreviewMaxMs + " ms step=" + mapPreviewStep();
        }

        public static double msptNow() {
        try {
            return Bukkit.getAverageTickTime();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Carga perf.yml y arranca los muestreadores. Idempotente y a prueba de fallos. */
    public static void initialize() {
        try {
            File f = new File(PATH);
            if (!f.exists()) {
                f.getParentFile().mkdirs();
                Files.writeString(f.toPath(), DEFAULT_YML, StandardCharsets.UTF_8);
            }
            cfg = YamlConfiguration.loadConfiguration(f);
            initNative();
            if (!enabled()) {
                LOGGER.info("[Perf] desactivado en perf.yml");
                return;
            }
            ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Drakes-Perf");
                t.setDaemon(true);
                return t;
            });
            int every = Math.max(2, cfg.getInt("status.interval-seconds", 10));
            // el muestreo de gobernador usa el mismo reloj; arranca tras 2 min para no medir el arranque
            ex.scheduleAtFixedRate(() -> safe(() -> onMain(DrakesPerf::sample)), 120, every, java.util.concurrent.TimeUnit.SECONDS);
            LOGGER.info("[Perf] status=" + cfg.getBoolean("status.enabled", true)
                + " load-governor=" + cfg.getBoolean("load-governor.enabled", true)
                + " backup-throttle=" + cfg.getBoolean("backup.throttle", true));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Perf] no se pudo iniciar: " + t.getMessage());
        }
    }

    /** Interruptor unico: propiedad del sistema > hybrid.engine.enabled > native.hybrid.enabled (compatibilidad). */
    private static boolean engineEnabled() {
        String p = System.getProperty("drakes.hybrid.engine.enabled");
        if (p != null) return Boolean.parseBoolean(p);
        return cfg.getBoolean("hybrid.engine.enabled", cfg.getBoolean("native.hybrid.enabled", true));
    }

    private static void initNative() {
        try {
            org.purpurmc.purpur.nativelib.DrakesNative.init(new org.purpurmc.purpur.nativelib.DrakesNative.Settings(
                cfg.getBoolean("native.enabled", true), cfg.getString("native.dir", "natives"),
                cfg.getBoolean("native.odysseia.pulses", true), cfg.getBoolean("native.odysseia.aura", true),
                cfg.getBoolean("native.slimefun.fill", true), cfg.getBoolean("native.slimefun.sum", true),
                cfg.getBoolean("native.slimefun.price", true), cfg.getBoolean("native.slimefun.tick-engine", false),
                cfg.getInt("native.ring-capacity", 64), cfg.getInt("native.max-entries", 8192)));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Native] init fallo (" + t + "); todo en Java.");
        }
        try {
            org.purpurmc.purpur.nativelib.HybridNative.init(new org.purpurmc.purpur.nativelib.HybridNative.Settings(
                engineEnabled(), cfg.getString("native.dir", "natives"),
                cfg.getBoolean("native.hybrid.bits", true), cfg.getBoolean("native.hybrid.noise", true),
                cfg.getBoolean("native.hybrid.region", true), cfg.getInt("native.hybrid.min-batch", 2048)));
            org.purpurmc.purpur.hybrid.HybridStats.enabled = cfg.getBoolean("native.hybrid.stats", true);
            org.purpurmc.purpur.hybrid.HybridChunkPrefetch.configure(cfg.getBoolean("native.hybrid.prefetch.enabled", true),
                cfg.getDouble("native.hybrid.prefetch.min-speed", 20.0), cfg.getDouble("native.hybrid.prefetch.lead-seconds", 3.0),
                cfg.getInt("native.hybrid.prefetch.max-lookahead-chunks", 8), cfg.getInt("native.hybrid.prefetch.half-width-chunks", 2),
                cfg.getInt("native.hybrid.prefetch.max-in-flight", 24), cfg.getLong("native.hybrid.prefetch.interval-ms", 250));
            org.purpurmc.purpur.hybrid.HybridChunkPrefetch.tune(cfg.getInt("native.hybrid.prefetch.per-player-in-flight", 6),
                cfg.getDouble("native.hybrid.prefetch.throttle-mspt", 38.0), cfg.getDouble("native.hybrid.prefetch.pause-mspt", 46.0));
            org.purpurmc.purpur.hybrid.ChunkThreadGovernor.configure(cfg.getBoolean("threads.chunk-gen.enabled", true),
                cfg.getInt("threads.chunk-gen.min-threads", 2), cfg.getInt("threads.chunk-gen.max-threads", 0),
                cfg.getInt("threads.chunk-gen.reserve-cores", 2), cfg.getDouble("threads.chunk-gen.shrink-mspt", 38.0),
                cfg.getDouble("threads.chunk-gen.hard-mspt", 46.0), cfg.getDouble("threads.chunk-gen.shrink-cpu", 0.88),
                cfg.getLong("threads.chunk-gen.grow-seconds", 4), cfg.getLong("threads.chunk-gen.shrink-seconds", 1));
            org.purpurmc.purpur.hybrid.HybridSelfTest.maybeStart(); // solo con -Ddrakes.hybrid.selftest=<radio>
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Hybrid] init fallo (" + t + "); todo en Java.");
        }
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable t) {
            LOGGER.log(Level.FINE, "[Perf] " + t);
        }
    }

    private static void onMain(Runnable r) {
        MinecraftServer.getServer().execute(r);
    }

    // ---- muestreo (hilo principal) ---------------------------------------------------------

    private static void sample() {
        final long now = System.currentTimeMillis();
        lastMspt = msptNow();
        if (cfg.getBoolean("load-governor.enabled", true)) safe(() -> govern(now));
        safe(() -> DrakesEntityGuard.tick(now, lastMspt, cfg));
        if (cfg.getBoolean("status.enabled", true)) {
            final String json = snapshot();
            ex.execute(() -> write(json));
        }
    }

    private static void govern(long now) {
        double high = cfg.getDouble("load-governor.mspt-high", 45.0);
        double low = cfg.getDouble("load-governor.mspt-low", 30.0);
        long sustain = cfg.getLong("load-governor.sustain-seconds", 30) * 1000L;
        long recover = cfg.getLong("load-governor.recover-seconds", 180) * 1000L;
        int maxSteps = cfg.getInt("load-governor.max-steps", 6);
        if (lastMspt > high) {
            underSince = -1;
            if (overSince < 0) overSince = now;
            if (now - overSince >= sustain && step < maxSteps && apply(step + 1)) {
                step++;
                overSince = now; // nueva ventana completa antes del siguiente paso
            }
        } else if (lastMspt < low && step > 0) {
            overSince = -1;
            if (underSince < 0) underSince = now;
            if (now - underSince >= recover) {
                apply(step - 1);
                step--;
                underSince = now;
            }
        } else {
            overSince = -1;
            underSince = -1;
        }
    }

    /** Aplica el nivel (0 = originales). Devuelve false si ya no hay margen para bajar. */
    private static boolean apply(int level) {
        int minSend = cfg.getInt("load-governor.min-send-view-distance", 6);
        int minSim = cfg.getInt("load-governor.min-simulation-distance", 4);
        boolean changed = false;
        for (World w : Bukkit.getWorlds()) {
            int[] o = ORIGINAL.computeIfAbsent(w, k -> new int[] {k.getSendViewDistance(), k.getSimulationDistance()});
            int send = level == 0 ? o[0] : Math.max(Math.min(minSend, o[0]), o[0] - level);
            int sim = level == 0 ? o[1] : Math.max(Math.min(minSim, o[1]), o[1] - level);
            if (w.getSendViewDistance() != send) {
                w.setSendViewDistance(send);
                changed = true;
            }
            if (w.getSimulationDistance() != sim) {
                w.setSimulationDistance(sim);
                changed = true;
            }
        }
        if (changed) LOGGER.info("[Perf] load-governor nivel " + level + " (MSPT " + Math.round(lastMspt * 10) / 10.0 + ")");
        return changed || level == 0;
    }

    private static String snapshot() {
        JsonObject o = new JsonObject();
        o.addProperty("ts", System.currentTimeMillis() / 1000);
        double[] tps = Bukkit.getTPS();
        JsonArray t = new JsonArray();
        for (double v : tps) t.add(Math.round(v * 100) / 100.0);
        o.add("tps", t);
        o.addProperty("mspt", Math.round(lastMspt * 100) / 100.0);
        List<Integer> pings = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) pings.add(p.getPing());
        Collections.sort(pings);
        JsonObject pg = new JsonObject();
        pg.addProperty("players", pings.size());
        if (!pings.isEmpty()) {
            pg.addProperty("avg", Math.round(pings.stream().mapToInt(i -> i).average().orElse(0)));
            pg.addProperty("p50", pings.get(pings.size() / 2));
            pg.addProperty("p95", pings.get(Math.min(pings.size() - 1, (int) Math.ceil(pings.size() * 0.95) - 1)));
            pg.addProperty("max", pings.get(pings.size() - 1));
        }
        o.add("ping", pg);
        Runtime rt = Runtime.getRuntime();
        o.addProperty("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        o.addProperty("heap_max_mb", rt.maxMemory() >> 20);
        long gcMs = 0, gcN = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcMs += Math.max(0, g.getCollectionTime());
            gcN += Math.max(0, g.getCollectionCount());
        }
        o.addProperty("gc_total_ms", gcMs);
        o.addProperty("gc_count", gcN);
        o.addProperty("uptime_s", ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
        o.addProperty("load_avg", Math.round(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage() * 100) / 100.0);
        o.addProperty("governor_step", step);
        o.addProperty("backup_running", backupRunning);
        JsonArray ws = new JsonArray();
        for (World w : Bukkit.getWorlds()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", w.getName());
            j.addProperty("view", w.getViewDistance());
            j.addProperty("send_view", w.getSendViewDistance());
            j.addProperty("simulation", w.getSimulationDistance());
            j.addProperty("chunks", w.getChunkCount());
            j.addProperty("entities", w.getEntityCount());
            j.addProperty("players", w.getPlayerCount());
            ws.add(j);
        }
        o.add("worlds", ws);
        JsonObject hy = new JsonObject(); // Horizon Hybrid Engine: estado + contadores por etapa (si native.hybrid.stats)
        hy.addProperty("status", org.purpurmc.purpur.nativelib.HybridNative.status());
        hy.addProperty("library_loaded", org.purpurmc.purpur.nativelib.HybridNative.libraryLoaded());
        for (org.purpurmc.purpur.hybrid.HybridStats.Stage st : org.purpurmc.purpur.hybrid.HybridStats.ALL) {
            JsonObject sj = new JsonObject();
            sj.addProperty("calls", st.calls.sum());
            sj.addProperty("items", st.items.sum());
            sj.addProperty("ms", st.nanos.sum() / 1e6);
            hy.add(st.name, sj);
        }
        o.add("hybrid", hy);
        return new GsonBuilder().create().toJson(o);
    }

    private static void write(String json) {
        try {
        Path p = new File(cfg.getString("status.file", "drakes-data/perf-status.json")).toPath();
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.io.IOException e) {
            LOGGER.log(Level.FINE, "[Perf] no se pudo escribir el status: " + e);
        }
    }
}
