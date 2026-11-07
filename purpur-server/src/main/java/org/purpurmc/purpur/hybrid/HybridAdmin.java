package org.purpurmc.purpur.hybrid;

import java.lang.management.ManagementFactory;
import java.lang.management.GarbageCollectorMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.purpurmc.purpur.nativelib.HybridNative;

/**
 * Subarbol {@code /drakes hybrid ...}. Todos los datos salen del estado real del proceso; nada se simula.
 * Permiso: {@code drakes.hybrid.admin} (por defecto solo operadores). El benchmark no toca mundos: opera sobre
 * arrays sinteticos en un hilo propio y exige {@code confirm}.
 */
public final class HybridAdmin {
    public static final String PERM = "drakes.hybrid.admin";
    public static final List<String> SUBS = List.of("status", "info", "chunks", "workers", "benchmark", "diagnostics", "gui", "help");
    private static volatile String lastBenchmark = "(sin ejecutar)";
    private static final AtomicBoolean BENCH_RUNNING = new AtomicBoolean();

    private HybridAdmin() {}

    public static String lastBenchmark() {
        return lastBenchmark;
    }

    public static List<String> complete(String[] args) {
        // args = argumentos tras "hybrid"
        if (args.length <= 1) {
            String pre = args.length == 0 ? "" : args[0].toLowerCase();
            return SUBS.stream().filter(x -> x.startsWith(pre)).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("benchmark")) return List.of("confirm").stream().filter(x -> x.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        return List.of();
    }

    public static boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERM)) {
            sender.sendMessage(ChatColor.RED + "Sin permiso (" + PERM + ").");
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        try {
            switch (sub) {
                case "status" -> status(sender);
                case "info" -> info(sender);
                case "chunks" -> chunks(sender);
                case "workers" -> workers(sender);
                case "benchmark" -> benchmark(sender, args.length > 1 && args[1].equalsIgnoreCase("confirm"));
                case "diagnostics", "diag" -> diagnostics(sender);
                case "gui" -> {
                    if (sender instanceof Player p) HybridAdminMenu.open(p);
                    else sender.sendMessage(ChatColor.RED + "La GUI es solo para jugadores. Usa /drakes hybrid status en consola.");
                }
                default -> help(sender);
            }
        } catch (Throwable t) {
            sender.sendMessage(ChatColor.RED + "Error en /drakes hybrid " + sub + ": " + t);
        }
        return true;
    }

    private static void line(CommandSender s, String k, String v) {
        s.sendMessage(ChatColor.GRAY + k + ": " + ChatColor.WHITE + v);
    }

    private static void header(CommandSender s, String t) {
        s.sendMessage(ChatColor.DARK_GREEN + "=== Hybrid Engine · " + t + " ===");
    }

    private static void help(CommandSender s) {
        header(s, "ayuda");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid status" + ChatColor.GRAY + " - biblioteca, ABI, modo y aceleraciones");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid info" + ChatColor.GRAY + " - configuracion efectiva y que significa cada opcion");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid chunks" + ChatColor.GRAY + " - contadores del pipeline de chunks y prefetch");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid workers" + ChatColor.GRAY + " - hilos del servidor por familia y estado");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid benchmark confirm" + ChatColor.GRAY + " - micro-benchmark sintetico (no toca mundos)");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid diagnostics" + ChatColor.GRAY + " - errores, JVM, GC, plugins y compatibilidad");
        s.sendMessage(ChatColor.YELLOW + "/drakes hybrid gui" + ChatColor.GRAY + " - panel in-game (jugadores)");
        s.sendMessage(ChatColor.GRAY + "Todo es experimental y esta apagado por defecto. Permiso: " + PERM);
    }

    private static void status(CommandSender s) {
        var n = HybridNative.snapshot();
        header(s, "estado");
        line(s, "Biblioteca Rust", n.libraryLoaded() ? "CARGADA (" + n.libraryPath() + ")" : "no cargada -> todo corre en Java");
        line(s, "Plataforma / origen", n.platform() + " / " + (n.libraryLoaded() ? n.libraryOrigin() : "-"));
        line(s, "ABI (biblioteca / esperada)", n.libraryAbi() + " / " + n.expectedAbi() + (n.libraryLoaded() && n.libraryAbi() != n.expectedAbi() ? "  <-- INCOMPATIBLE" : ""));
        line(s, "Modo", n.libraryLoaded() && (n.bits() || n.noise() || n.region()) ? "HIBRIDO (aceleraciones activas)" : "JAVA original");
        line(s, "Aceleraciones", "bits=" + n.bits() + " noise=" + n.noise() + " region=" + n.region() + " min-batch=" + n.minBatch());
        line(s, "Prefetch de chunks", HybridChunkPrefetch.enabled() ? "ACTIVADO" : "desactivado");
        line(s, "Fallback Java", n.failures() == 0 ? "sin incidencias" : n.failures() + " fallo(s); ultimo: " + n.lastError());
        double[] tps = Bukkit.getTPS();
        line(s, "TPS 1m/5m/15m", String.format("%.2f / %.2f / %.2f", tps[0], tps[1], tps[2]));
        line(s, "MSPT medio", String.format("%.2f ms", Bukkit.getAverageTickTime()));
    }

    private static void info(CommandSender s) {
        header(s, "configuracion");
        var cfg = DrakesPerf_cfg();
        String[] keys = {"hybrid.engine.enabled", "native.hybrid.enabled", "native.hybrid.min-batch", "native.hybrid.bits", "native.hybrid.noise", "native.hybrid.region",
            "native.hybrid.stats", "native.hybrid.prefetch.enabled", "native.hybrid.prefetch.min-speed", "native.hybrid.prefetch.lead-seconds",
            "native.hybrid.prefetch.max-lookahead-chunks", "native.hybrid.prefetch.max-in-flight", "native.dir"};
        for (String k : keys) line(s, k, String.valueOf(cfg == null ? "(perf.yml no cargado)" : cfg.get(k, "(por defecto)")));
        s.sendMessage(ChatColor.GRAY + "Los cambios en perf.yml requieren REINICIO. La GUI solo conmuta bits y prefetch durante la sesion.");
        s.sendMessage(ChatColor.GRAY + "Evidencia de rendimiento: docs/hybrid/RESULTADOS.md (ninguna ruta nativa tiene hoy mejora demostrada).");
    }

    private static org.bukkit.configuration.file.YamlConfiguration DrakesPerf_cfg() {
        try {
            return org.purpurmc.purpur.perf.DrakesPerf.cfg();
        } catch (Throwable t) {
            return null;
        }
    }

    private static void chunks(CommandSender s) {
        header(s, "chunks");
        for (World w : Bukkit.getWorlds()) {
            line(s, "Mundo " + w.getName(), "chunks cargados=" + w.getChunkCount() + " entidades=" + w.getEntityCount() + " jugadores=" + w.getPlayerCount()
                + " view=" + w.getViewDistance() + " send=" + w.getSendViewDistance() + " sim=" + w.getSimulationDistance());
        }
        boolean ev = HybridEvents.ensureRegistered();
        line(s, "Contadores de eventos", ev ? "activos desde hace " + (System.currentTimeMillis() - HybridEvents.registeredAtMs()) / 1000 + " s (se inician con el primer uso)" : "NO disponibles: " + HybridEvents.error());
        if (ev) {
            line(s, "Chunks cargados (nuevos / desde disco)", HybridEvents.CHUNK_LOADS_NEW.sum() + " / " + HybridEvents.CHUNK_LOADS_EXISTING.sum());
            line(s, "Chunks enviados a jugadores", String.valueOf(HybridEvents.CHUNK_SENDS.sum()));
        }
        line(s, "Prefetch", HybridChunkPrefetch.describe());
        line(s, "Bits (Java/Rust)", HybridStats.PACK_JAVA.calls.sum() + "+" + HybridStats.UNPACK_JAVA.calls.sum() + " / " + HybridStats.PACK_NATIVE.calls.sum() + "+" + HybridStats.UNPACK_NATIVE.calls.sum()
            + " llamadas (requiere native.hybrid.stats)");
    }

    private static void workers(CommandSender s) {
        header(s, "hilos");
        Map<String, int[]> fam = new TreeMap<>(); // familia -> {total, runnable, waiting}
        for (var e : Thread.getAllStackTraces().entrySet()) {
            Thread t = e.getKey();
            String n = t.getName();
            String f = family(n);
            int[] c = fam.computeIfAbsent(f, k -> new int[3]);
            c[0]++;
            if (t.getState() == Thread.State.RUNNABLE) c[1]++;
            else if (t.getState() == Thread.State.WAITING || t.getState() == Thread.State.TIMED_WAITING) c[2]++;
        }
        for (var e : fam.entrySet()) line(s, e.getKey(), "total=" + e.getValue()[0] + " ejecutando=" + e.getValue()[1] + " en espera=" + e.getValue()[2]);
        line(s, "Prefetch en vuelo", HybridChunkPrefetch.inFlight() + " solicitudes");
        line(s, "CPU del proceso", cpu());
    }

    private static String family(String n) {
        String l = n.toLowerCase();
        if (l.contains("worker-main") || l.contains("moonrise")) return "chunk system (Moonrise/Worker-Main)";
        if (l.contains("io-worker") || l.contains("chunk io") || l.contains("region")) return "E/S de chunks";
        if (l.startsWith("drakes")) return "Drakes (" + n.replaceAll("[-#]?\\d+$", "") + ")";
        if (l.contains("netty")) return "red (Netty)";
        if (l.contains("server thread")) return "hilo principal";
        if (l.contains("gc") || l.contains("g1") || l.contains("jit") || l.contains("c2") || l.contains("c1 ")) return "JVM (GC/JIT)";
        return "otros";
    }

    private static String cpu() {
        try {
            var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
            return String.format("proceso %.0f %% / sistema %.0f %% (%d CPUs)", os.getProcessCpuLoad() * 100, os.getCpuLoad() * 100, os.getAvailableProcessors());
        } catch (Throwable t) {
            return "no disponible";
        }
    }

    public static List<String> compatLines() {
        List<String> out = new ArrayList<>();
        Plugin[] ps = Bukkit.getPluginManager().getPlugins();
        long enabled = Arrays.stream(ps).filter(Plugin::isEnabled).count();
        out.add("Plugins: " + enabled + " habilitados de " + ps.length);
        Plugin sf = Bukkit.getPluginManager().getPlugin("Slimefun");
        if (sf == null) {
            out.add("Slimefun: no instalado");
        } else {
            out.add("Slimefun: " + (sf.isEnabled() ? "habilitado" : "DESHABILITADO") + " v" + sf.getDescription().getVersion());
            int n = slimefunItems();
            out.add("Items de Slimefun registrados: " + (n < 0 ? "no se pudo leer" : n));
        }
        out.add("Verificacion funcional: ver docs/hybrid/COMPATIBILIDAD.md (CI)");
        return out;
    }

    /** Lee el registro real de Slimefun por reflexion (soporta los dos espacios de nombres conocidos); -1 si no se puede. */
    public static int slimefunItems() {
        for (String cls : new String[] {"io.github.thebusybiscuit.slimefun4.implementation.Slimefun", "com.github.drakescraft_labs.slimefun4.implementation.Slimefun"}) {
            try {
                Object reg = Class.forName(cls, true, Bukkit.getPluginManager().getPlugin("Slimefun").getClass().getClassLoader()).getMethod("getRegistry").invoke(null);
                return ((java.util.Collection<?>) reg.getClass().getMethod("getAllSlimefunItems").invoke(reg)).size();
            } catch (Throwable ignored) {
            }
        }
        return -1;
    }

    private static void diagnostics(CommandSender s) {
        header(s, "diagnostico");
        var n = HybridNative.snapshot();
        line(s, "Inicializado", String.valueOf(n.initialized()));
        line(s, "Fallos nativos", n.failures() + (n.lastError().isEmpty() ? "" : " | ultimo: " + n.lastError()));
        line(s, "Listeners internos", HybridEvents.registered() ? "registrados" : "no registrados" + (HybridEvents.error().isEmpty() ? "" : " (" + HybridEvents.error() + ")"));
        line(s, "JVM", System.getProperty("java.vm.name") + " " + Runtime.version());
        long gcMs = 0, gcN = 0;
        for (GarbageCollectorMXBean g : ManagementFactory.getGarbageCollectorMXBeans()) {
            gcMs += Math.max(0, g.getCollectionTime());
            gcN += Math.max(0, g.getCollectionCount());
        }
        Runtime rt = Runtime.getRuntime();
        line(s, "Memoria", ((rt.totalMemory() - rt.freeMemory()) >> 20) + " / " + (rt.maxMemory() >> 20) + " MB; GC: " + gcN + " ciclos, " + gcMs + " ms");
        line(s, "CPU", cpu());
        line(s, "Ultimo benchmark", lastBenchmark);
        for (String c : compatLines()) s.sendMessage(ChatColor.GRAY + c);
        s.sendMessage(ChatColor.GRAY + "Etapas (requieren native.hybrid.stats): ");
        for (String l : HybridStats.report().split("\n")) if (!l.isBlank()) s.sendMessage(ChatColor.DARK_GRAY + "  " + l);
    }

    private static void benchmark(CommandSender s, boolean confirm) {
        header(s, "benchmark");
        if (!confirm) {
            s.sendMessage(ChatColor.YELLOW + "Micro-benchmark sintetico (arrays en memoria, un hilo propio, no toca mundos ni jugadores; ~2-5 s de CPU).");
            s.sendMessage(ChatColor.YELLOW + "Ejecuta: /drakes hybrid benchmark confirm");
            s.sendMessage(ChatColor.GRAY + "Nota: un microbenchmark NO predice el rendimiento del servidor (ver docs/hybrid/RESULTADOS.md).");
            return;
        }
        if (!BENCH_RUNNING.compareAndSet(false, true)) {
            s.sendMessage(ChatColor.RED + "Ya hay un benchmark en curso.");
            return;
        }
        s.sendMessage(ChatColor.GRAY + "Ejecutando en el hilo Drakes-HybridBench...");
        Thread t = new Thread(() -> {
            String res;
            try {
                res = runBench();
            } catch (Throwable e) {
                res = "ERROR: " + e;
            } finally {
                BENCH_RUNNING.set(false);
            }
            lastBenchmark = res;
            final String out = res;
            try {
                MinecraftServer.getServer().execute(() -> {
                    s.sendMessage(ChatColor.GREEN + "Resultado:");
                    for (String l : out.split("\n")) s.sendMessage(ChatColor.WHITE + l);
                });
            } catch (Throwable ignored) {
            }
        }, "Drakes-HybridBench");
        t.setDaemon(true);
        t.start();
    }

    /** Compara la referencia Java con la ruta nativa SOLO si esta cargada; informa tal cual, incluidos los empates. */
    static String runBench() {
        StringBuilder sb = new StringBuilder();
        var n = HybridNative.snapshot();
        int size = 4096, bits = 8, rounds = 15;
        int[] vals = new int[size];
        for (int i = 0; i < size; i++) vals[i] = (i * 31 + 7) & ((1 << bits) - 1);
        long[] out = new long[HybridNative.longCount(bits, size)];
        int[] back = new int[size];
        double java = medianNs(() -> {
            HybridNative.JavaRef.pack(bits, vals, out);
            HybridNative.JavaRef.unpack(bits, size, out, back);
        }, rounds);
        sb.append(String.format("bits 4096x%d: Java %.0f ns", bits, java));
        if (n.libraryLoaded() && n.bits()) {
            double nat = medianNs(() -> {
                HybridNative.packBits(bits, vals, out);
                HybridNative.unpackBits(bits, size, out, back);
            }, rounds);
            sb.append(String.format(" | nativo %.0f ns | x%.2f", nat, java / nat));
        } else {
            sb.append(" | nativo: omitido (biblioteca no cargada o ruta bits desactivada)");
        }
        sb.append("\nruido: ver autotest (-Ddrakes.hybrid.selftest) para paridad bit a bit sobre instancias reales de Mojang");
        sb.append("\n(microbenchmark sintetico; no es rendimiento de servidor)");
        return sb.toString();
    }

    private static double medianNs(Runnable r, int rounds) {
        for (int i = 0; i < 200; i++) r.run();
        double[] t = new double[rounds];
        for (int k = 0; k < rounds; k++) {
            long t0 = System.nanoTime();
            for (int i = 0; i < 200; i++) r.run();
            t[k] = (System.nanoTime() - t0) / 200.0;
        }
        Arrays.sort(t);
        return t[rounds / 2];
    }
}
