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
        # APAGADO por defecto: cambia lo que ven los jugadores; activalo en pruebas (VPS humilde).
        load-governor:
          enabled: false
          mspt-high: 45.0          # sobre esto durante sustain-seconds -> un paso abajo
          mspt-low: 30.0           # bajo esto durante recover-seconds -> un paso arriba
          sustain-seconds: 30
          recover-seconds: 180
          min-send-view-distance: 6
          min-simulation-distance: 4
          max-steps: 6
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
                + " load-governor=" + cfg.getBoolean("load-governor.enabled", false)
                + " backup-throttle=" + cfg.getBoolean("backup.throttle", true));
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[Perf] no se pudo iniciar: " + t.getMessage());
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
        if (cfg.getBoolean("load-governor.enabled", false)) safe(() -> govern(now));
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
