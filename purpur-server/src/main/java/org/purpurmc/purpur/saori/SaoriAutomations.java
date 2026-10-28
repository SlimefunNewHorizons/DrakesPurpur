package org.purpurmc.purpur.saori;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.apache.logging.log4j.Level;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Reglas automaticas (solo avisan a SAORI; nunca actuan, salvo el countdown de prepare-restart).
 * Todo corre en un hilo propio con lecturas baratas; cada aviso pasa por PrivacyGate y la cola comun.
 */
public final class SaoriAutomations {
    private static final Logger LOGGER = Logger.getLogger("SaoriLink");
    private static final long TICK_S = 5;

    private static volatile SaoriConfig cfg;
    private static YamlConfiguration y;
    private static ScheduledExecutorService ex;
    private static long ticks;
    private static final long BOOT_MS = System.currentTimeMillis();

    private static SaoriRules.Sustained lagTps, lagMspt, ping, heap;
    private static SaoriRules.Cooldown lagCd, pingCd, memCd, sfCd;
    private static SaoriRules.ErrorGrouper errors = new SaoriRules.ErrorGrouper(200);
    private static final SaoriRules.BootProfiler BOOT = new SaoriRules.BootProfiler();
    private static SaoriRules.FloodDetector flood;
    private static SaoriRules.GrowthDetector sfGrowth;
    private static final SaoriRules.DigestCounter DIGEST = new SaoriRules.DigestCounter();
    private static final List<String> RECENT_PUBLIC = Collections.synchronizedList(new ArrayList<>());
    private static final ArrayDeque<String> LAST_ALERTS = new ArrayDeque<>();
    private static long lastGcMs;
    private static long lastSfFailures = -1;
    private static boolean bootReported;
    private static volatile ScheduledFuture<?>[] restartTasks = new ScheduledFuture<?>[0];

    private SaoriAutomations() {}

    private static boolean on(String rule) {
        return y.getBoolean("automations." + rule + ".enabled", true);
    }

    private static long sec(String path, int def) {
        return y.getLong("automations." + path, def) * 1000L;
    }

    public static synchronized void init(SaoriConfig c) {
        cfg = c;
        y = c.yml;
        lagTps = new SaoriRules.Sustained(y.getDouble("automations.lag.tps-below", 17.0), false, sec("lag.sustain-seconds", 30));
        lagMspt = new SaoriRules.Sustained(y.getDouble("automations.lag.mspt-above", 50.0), true, sec("lag.sustain-seconds", 30));
        lagCd = new SaoriRules.Cooldown(sec("lag.cooldown-seconds", 600));
        ping = new SaoriRules.Sustained(0.5, true, sec("ping.sustain-seconds", 60));
        pingCd = new SaoriRules.Cooldown(sec("ping.cooldown-seconds", 600));
        heap = new SaoriRules.Sustained(y.getDouble("automations.memory.heap-percent", 90) / 100.0, true, sec("memory.sustain-seconds", 60));
        memCd = new SaoriRules.Cooldown(sec("memory.cooldown-seconds", 900));
        sfCd = new SaoriRules.Cooldown(sec("slimefun-storage.cooldown-seconds", 900));
        sfGrowth = new SaoriRules.GrowthDetector(y.getInt("automations.slimefun-storage.queue-growth-samples", 4),
            y.getLong("automations.slimefun-storage.min-queue", 500));
        flood = new SaoriRules.FloodDetector(y.getInt("automations.chat-flood.messages", 6),
            sec("chat-flood.window-seconds", 8), sec("chat-flood.cooldown-seconds", 120));
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "Saori-Automations");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(() -> safe(SaoriAutomations::tick), TICK_S, TICK_S, TimeUnit.SECONDS);
        long errWin = Math.max(10, y.getLong("automations.errors.window-seconds", 60));
        if (on("errors")) ex.scheduleWithFixedDelay(() -> safe(SaoriAutomations::flushErrors), errWin, errWin, TimeUnit.SECONDS);
        long dig = y.getLong("automations.digest.minutes", 30);
        if (on("digest") && dig > 0) ex.scheduleWithFixedDelay(() -> safe(SaoriAutomations::digest), dig, dig, TimeUnit.MINUTES);
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) lastGcMs += Math.max(0, g.getCollectionTime());
    }

    private static void safe(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
            // las reglas jamas deben afectar al servidor
        }
    }

    // ---- emision -------------------------------------------------------------------------

    private static void alert(String rule, JsonObject body, String text) {
        body.addProperty("rule", rule);
        if (SaoriKernelSocket.emit("automation", text == null ? rule : text, body)) {
            synchronized (LAST_ALERTS) {
                LAST_ALERTS.addFirst(rule + " @" + java.time.LocalTime.now().withNano(0));
                while (LAST_ALERTS.size() > 10) LAST_ALERTS.removeLast();
            }
        }
    }

    // ---- hooks ---------------------------------------------------------------------------

    /** Desde el appender. @return false si es un error repetido que no debe reenviarse suelto. */
    public static boolean onLog(String logger, Level lvl, String msg, Throwable thrown) {
        if (ex == null) return true;
        try {
            long now = System.currentTimeMillis();
            if (on("boot") && msg.startsWith("Enabling ") && msg.contains(" v")) {
                BOOT.start(msg.substring(9, msg.indexOf(' ', 9) > 0 ? msg.indexOf(' ', 9) : msg.length()), now);
            } else if (on("boot") && msg.startsWith("Done (") && !bootReported) {
                bootReported = true;
                ex.execute(() -> safe(SaoriAutomations::bootReport));
            }
            boolean isErr = lvl.isMoreSpecificThan(Level.ERROR)
                || (lvl.isMoreSpecificThan(Level.WARN) && (msg.contains("Error occurred while enabling") || msg.contains("Error occurred while disabling")));
            if (isErr && on("errors")) {
                String sig = SaoriRules.ErrorGrouper.signature(logger, msg, thrown == null ? null : thrown.getStackTrace());
                return errors.record(sig, now) == 1;
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    public static void onPluginEnabled(String plugin) {
        if (ex == null || !on("boot")) return;
        long d = BOOT.end(plugin, System.currentTimeMillis());
        if (d > sec("boot.slow-plugin-seconds", 30)) {
            JsonObject o = new JsonObject();
            o.addProperty("plugin", plugin);
            o.addProperty("enable_ms", d);
            alert("boot_slow_plugin", o, "plugin " + plugin + " tardo " + d + " ms en arrancar");
        }
    }

    public static void onChat(String playerName) {
        if (ex == null || !on("chat-flood")) return;
        if (flood.record(playerName, System.currentTimeMillis())) {
            JsonObject o = new JsonObject();
            o.addProperty("player", playerName);
            o.addProperty("messages", y.getInt("automations.chat-flood.messages", 6));
            alert("chat_flood", o, "rafaga de chat de " + playerName); // sin contenido
        }
    }

    public static void recordPublic(String kind, String text) {
        DIGEST.add(kind);
        synchronized (RECENT_PUBLIC) {
            RECENT_PUBLIC.add(kind + ": " + (text.length() > 120 ? text.substring(0, 120) : text));
            while (RECENT_PUBLIC.size() > 20) RECENT_PUBLIC.remove(0);
        }
    }

    // ---- reglas periodicas ---------------------------------------------------------------

    private static void tick() {
        ticks++;
        long now = System.currentTimeMillis();
        double[] tpsArr = Bukkit.getTPS();
        double tps = tpsArr.length > 0 ? tpsArr[0] : 20;
        double mspt = Bukkit.getAverageTickTime();

        if (on("lag")) {
            boolean a = lagTps.update(now, tps);
            boolean b = lagMspt.update(now, mspt);
            if ((a || b) && lagCd.tryFire(now)) {
                JsonObject o = health();
                o.addProperty("sustained_s", y.getLong("automations.lag.sustain-seconds", 30));
                o.addProperty("suggest_profile", Bukkit.getPluginManager().getPlugin("spark") != null
                    ? "spark: /spark profiler start --timeout 120" : "/drakes threads");
                alert("lag", o, "TPS " + tps + " MSPT " + mspt);
            }
        }
        if (on("ping")) {
            List<Integer> pings = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) pings.add(p.getPing());
            if (!pings.isEmpty()) {
                Collections.sort(pings);
                int high = y.getInt("automations.ping.high-ping-ms", 200);
                long nHigh = pings.stream().filter(v -> v > high).count();
                boolean bad = nHigh >= y.getInt("automations.ping.min-players", 2);
                if (ping.update(now, bad ? 1 : 0) && pingCd.tryFire(now)) {
                    JsonObject o = new JsonObject();
                    o.addProperty("avg_ping_ms", (int) pings.stream().mapToInt(i -> i).average().orElse(0));
                    o.addProperty("p95_ping_ms", pings.get(Math.min(pings.size() - 1, (int) Math.ceil(pings.size() * 0.95) - 1)));
                    o.addProperty("players_high_ping", nHigh);
                    o.addProperty("players", pings.size());
                    o.addProperty("tps", Math.round(tps * 100) / 100.0);
                    o.addProperty("correlated_with_lag", tps < y.getDouble("automations.lag.tps-below", 17.0));
                    alert("ping", o, "ping alto sostenido");
                }
            }
        }
        if (on("memory")) {
            Runtime rt = Runtime.getRuntime();
            double ratio = (rt.totalMemory() - rt.freeMemory()) / (double) rt.maxMemory();
            long gc = 0;
            for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) gc += Math.max(0, g.getCollectionTime());
            long gcDelta = gc - lastGcMs;
            lastGcMs = gc;
            boolean longGc = gcDelta > y.getLong("automations.memory.gc-pause-ms-per-interval", 2000);
            if ((heap.update(now, ratio) || longGc) && memCd.tryFire(now)) {
                JsonObject o = health();
                o.addProperty("heap_ratio", Math.round(ratio * 1000) / 1000.0);
                o.addProperty("gc_ms_last_interval", gcDelta);
                alert("memory", o, "heap " + Math.round(ratio * 100) + "% gc " + gcDelta + "ms");
            }
        }
        if (on("slimefun-storage") && ticks % 6 == 0) safe(() -> slimefunStorage(now));
    }

    private static void slimefunStorage(long now) {
        var sf = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (sf == null) return;
        try {
            ClassLoader cl = sf.getClass().getClassLoader();
            Object store = cl.loadClass("io.github.thebusybiscuit.slimefun4.storage.backend.SfStores").getMethod("get").invoke(null);
            Object st = store.getClass().getMethod("stats").invoke(store);
            long queued = ((Number) st.getClass().getMethod("queued").invoke(st)).longValue();
            long pending = ((Number) st.getClass().getMethod("pending").invoke(st)).longValue();
            long failures = ((Number) st.getClass().getMethod("failures").invoke(st)).longValue();
            boolean grow = sfGrowth.update(queued + pending);
            boolean newFail = lastSfFailures >= 0 && failures > lastSfFailures;
            lastSfFailures = failures;
            if ((grow || newFail) && sfCd.tryFire(now)) {
                JsonObject o = new JsonObject();
                o.addProperty("queued", queued);
                o.addProperty("pending", pending);
                o.addProperty("failures", failures);
                o.addProperty("growing", grow);
                alert("slimefun_storage", o, "cola slimefun " + queued + "/" + pending + " fallos " + failures);
            }
        } catch (ReflectiveOperationException | ClassCastException e) {
            // Slimefun sin CachedSfStore (otra version): sin datos, sin ruido
        }
    }

    private static void bootReport() {
        JsonObject o = new JsonObject();
        o.addProperty("boot_seconds_since_kernel", (System.currentTimeMillis() - BOOT_MS) / 1000);
        o.add("slowest", pairs(BOOT.slowest((int) y.getLong("automations.boot.top", 5))));
        o.add("over_threshold", pairs(BOOT.overThreshold(sec("boot.slow-plugin-seconds", 30))));
        alert("boot_report", o, "arranque completado");
    }

    private static JsonArray pairs(List<java.util.Map.Entry<String, Long>> l) {
        JsonArray a = new JsonArray();
        for (var e : l) {
            JsonObject p = new JsonObject();
            p.addProperty("plugin", e.getKey());
            p.addProperty("enable_ms", e.getValue());
            a.add(p);
        }
        return a;
    }

    private static void flushErrors() {
        var groups = errors.flush();
        if (groups.isEmpty()) return;
        JsonArray a = new JsonArray();
        StringBuilder text = new StringBuilder();
        for (var g : groups.subList(0, Math.min(10, groups.size()))) {
            JsonObject p = new JsonObject();
            p.addProperty("signature", g.signature().length() > 400 ? g.signature().substring(0, 400) : g.signature());
            p.addProperty("count", g.count());
            a.add(p);
            text.append(g.signature()).append(' ');
        }
        JsonObject o = new JsonObject();
        o.add("repeated", a);
        alert("error_summary", o, text.toString());
    }

    private static void digest() {
        JsonObject o = health();
        JsonObject c = new JsonObject();
        DIGEST.takeAndReset().forEach(c::addProperty);
        o.add("public_counts", c);
        JsonArray recent = new JsonArray();
        synchronized (RECENT_PUBLIC) {
            RECENT_PUBLIC.forEach(recent::add);
            RECENT_PUBLIC.clear();
        }
        o.add("recent_public", recent);
        o.addProperty("rule", "digest");
        SaoriKernelSocket.emit("digest", "digest", o);
    }

    // ---- salud ---------------------------------------------------------------------------

    public static JsonObject health() {
        JsonObject o = new JsonObject();
        double[] t = Bukkit.getTPS();
        o.addProperty("tps", Math.round((t.length > 0 ? t[0] : 20) * 100) / 100.0);
        o.addProperty("mspt", Math.round(Bukkit.getAverageTickTime() * 100) / 100.0);
        o.addProperty("players", Bukkit.getOnlinePlayers().size());
        Runtime rt = Runtime.getRuntime();
        o.addProperty("heap_used_mb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        o.addProperty("heap_max_mb", rt.maxMemory() >> 20);
        o.addProperty("uptime_s", (System.currentTimeMillis() - BOOT_MS) / 1000);
        List<World> worlds = new ArrayList<>(Bukkit.getWorlds());
        worlds.sort(Comparator.comparingInt(World::getEntityCount).reversed());
        JsonArray w = new JsonArray();
        for (World world : worlds.subList(0, Math.min(3, worlds.size()))) {
            JsonObject j = new JsonObject();
            j.addProperty("world", world.getName());
            j.addProperty("entities", world.getEntityCount());
            j.addProperty("chunks", world.getChunkCount());
            w.add(j);
        }
        o.add("top_worlds", w);
        return o;
    }

    public static String healthText() {
        JsonObject h = health();
        StringBuilder sb = new StringBuilder("TPS " + h.get("tps").getAsString() + " | MSPT " + h.get("mspt").getAsString()
            + " | jugadores " + h.get("players").getAsString()
            + " | heap " + h.get("heap_used_mb").getAsString() + "/" + h.get("heap_max_mb").getAsString() + " MB");
        synchronized (LAST_ALERTS) {
            if (!LAST_ALERTS.isEmpty()) sb.append(" | ultimos avisos: ").append(String.join(", ", LAST_ALERTS));
        }
        return sb.toString();
    }

    public static JsonObject healthJson() {
        JsonObject h = health();
        h.addProperty("link", SaoriKernelSocket.stats());
        JsonArray a = new JsonArray();
        synchronized (LAST_ALERTS) {
            LAST_ALERTS.forEach(a::add);
        }
        h.add("last_alerts", a);
        return h;
    }

    // ---- pre-reinicio coordinado (NUNCA para el servidor) -----------------------------------

    public static synchronized String prepareRestart(int seconds) {
        if (ex == null) return "automations desactivadas";
        seconds = Math.max(10, Math.min(600, seconds));
        cancelRestart();
        List<ScheduledFuture<?>> l = new ArrayList<>();
        final int total = seconds;
        l.add(ex.schedule(() -> SaoriLink.broadcast("Reinicio programado en " + total + " s. Guarda tu progreso."), 0, TimeUnit.SECONDS));
        for (int mark : new int[] {60, 30, 10, 5, 3, 2, 1}) {
            if (mark < total) {
                l.add(ex.schedule(() -> SaoriLink.broadcast("Reinicio en " + mark + " s."), total - mark, TimeUnit.SECONDS));
            }
        }
        l.add(ex.schedule(() -> {
            SaoriLink.runOnMain(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "save-all"));
            JsonObject o = new JsonObject();
            o.addProperty("saved", true);
            alert("restart_ready", o, "listo para reiniciar");
        }, total, TimeUnit.SECONDS));
        restartTasks = l.toArray(new ScheduledFuture<?>[0]);
        return "countdown " + total + " s iniciado (solo anuncia y guarda; no reinicia)";
    }

    public static synchronized String cancelRestart() {
        int n = 0;
        for (ScheduledFuture<?> f : restartTasks) {
            if (!f.isDone() && f.cancel(false)) n++;
        }
        restartTasks = new ScheduledFuture<?>[0];
        return n > 0 ? "countdown cancelado" : "no habia countdown";
    }
}
