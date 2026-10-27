package org.purpurmc.purpur.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.purpurmc.purpur.DrakesKernelBridge;
import org.purpurmc.purpur.slimefun.SlimefunFastPath;
import org.purpurmc.purpur.geyser.BedrockCompanion;
import org.purpurmc.purpur.saori.SaoriKernelSocket;
import org.purpurmc.purpur.security.DrakesGrimGuard;
import org.purpurmc.purpur.security.DrakesChatGuard;
import org.purpurmc.purpur.rust.SlimefunRustEngine;
import org.purpurmc.purpur.threads.RyzenWorkerPool;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * DrakesAdminCommand — CLI & in-game control panel for DrakesPurpur sovereign kernel
 * Command: /drakesadmin (or /drakes)
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesAdminCommand extends Command {

    public DrakesAdminCommand(String name) {
        super(name);
        this.description = "DrakesPurpur Sovereign Kernel Administrator & Diagnostics";
        this.usageMessage = "/drakes [status | test | security | threads | fastpath | gc | saori | credits | menu]";
        this.setAliases(Arrays.asList("drakesadmin", "dpurpur"));
        this.setPermission("bukkit.command.purpur");
    }

    @Override
    public boolean execute(CommandSender sender, String commandLabel, String[] args) {
        return handleCommand(sender, args);
    }

    public boolean handleCommand(CommandSender sender, String[] args) {
        if (!testPermission(sender)) {
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendKernelStatus(sender);
            return true;
        }

        // Vanish toggle (Kernel embedded)
        if (args[0].equalsIgnoreCase("vanish") || args[0].equalsIgnoreCase("vani")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores en el servidor.");
                return true;
            }
            org.purpurmc.purpur.staff.DrakesStaffEngine.toggleVanish(player);
            return true;
        }

        // Sovereign Godmode / Inmortalidad Administrativa (/ultragod)
        if (args[0].equalsIgnoreCase("ultragod") || args[0].equalsIgnoreCase("godmode")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores.");
                return true;
            }
            org.purpurmc.purpur.staff.DrakesStaffEngine.toggleSovereignGod(player);
            return true;
        }

        // Freeze toggle (Kernel embedded)
        if (args[0].equalsIgnoreCase("freeze")) {
            if (args.length < 2) {
                sender.sendMessage(ChatColor.RED + "Uso: /drakes freeze <jugador>");
                return true;
            }
            Player target = org.bukkit.Bukkit.getPlayer(args[1]);
            if (target == null) {
                sender.sendMessage(ChatColor.RED + "Jugador no encontrado: " + args[1]);
                return true;
            }
            Player moderator = sender instanceof Player p ? p : null;
            org.purpurmc.purpur.staff.DrakesStaffEngine.toggleFreeze(target, moderator);
            return true;
        }

        // FastPath toggle
        if (args[0].equalsIgnoreCase("fastpath")) {
            boolean current = SlimefunFastPath.isFastPathEnabled();
            SlimefunFastPath.setFastPathEnabled(!current);
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.YELLOW + "Slimefun FastPath ahora esta: "
                + (SlimefunFastPath.isFastPathEnabled() ? ChatColor.GREEN + "ACTIVADO" : ChatColor.RED + "DESACTIVADO"));
            return true;
        }

        // Garbage collection
        if (args[0].equalsIgnoreCase("gc")) {
            long before = Runtime.getRuntime().freeMemory();
            System.gc();
            long after = Runtime.getRuntime().freeMemory();
            long freedMb = (after - before) / (1024 * 1024);
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.GREEN + "Garbage Collection manual completado. "
                + ChatColor.AQUA + "RAM liberada: ~" + freedMb + " MB.");
            return true;
        }

        // Telemetry ping
        if (args[0].equalsIgnoreCase("saori")) {
            SaoriKernelSocket.dispatchTelemetry(20.0, 5.0, 1, Runtime.getRuntime().freeMemory() / (1024 * 1024));
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.GREEN + "Ping de telemetria despachado a SAORI exitosamente.");
            return true;
        }

        // Native profiler
        if (args[0].equalsIgnoreCase("threads") || args[0].equalsIgnoreCase("spark") || args[0].equalsIgnoreCase("profile")) {
            org.purpurmc.purpur.profiler.DrakesProfiler.ProfileSnapshot s = org.purpurmc.purpur.profiler.DrakesProfiler.takeSnapshot();
            java.io.File report = org.purpurmc.purpur.profiler.DrakesProfiler.exportMarkdownReport(s);

            sender.sendMessage(ChatColor.GOLD + "==================================================");
            sender.sendMessage(ChatColor.YELLOW + "  ⚡ DrakesPurpur Diagnostics (Spark Replacement)  ");
            sender.sendMessage(ChatColor.GOLD + "==================================================");
            sender.sendMessage(ChatColor.WHITE + "Hardware: " + ChatColor.AQUA + s.cores() + " Cores (" + s.cpuModel() + ")");
            sender.sendMessage(ChatColor.WHITE + "Memoria JVM: " + ChatColor.GREEN + s.usedRamMb() + " MB / " + s.maxRamMb() + " MB" + ChatColor.GRAY + " (GC Pauses: " + s.gcTimeMs() + " ms)");
            sender.sendMessage(ChatColor.WHITE + "Mundo & Carga: " + ChatColor.YELLOW + s.totalChunks() + " Chunks" + ChatColor.GRAY + " | " + ChatColor.AQUA + s.totalEntities() + " Entidades (" + s.totalLivingEntities() + " mobs)");
            sender.sendMessage(ChatColor.WHITE + "Reporte Markdown: " + ChatColor.GREEN + report.getPath());
            sender.sendMessage(ChatColor.GOLD + "--- Top 3 Hilos de CPU ---");
            for (int i = 0; i < Math.min(3, s.topThreads().size()); i++) {
                org.purpurmc.purpur.profiler.DrakesProfiler.ThreadStats st = s.topThreads().get(i);
                sender.sendMessage(ChatColor.GRAY + "#" + (i + 1) + " " + ChatColor.YELLOW + st.name() + ChatColor.WHITE + " - " + st.cpuTimeMs() + " ms (" + st.state() + ")");
            }
            sender.sendMessage(ChatColor.GOLD + "==================================================");
            return true;
        }

        // CLI Diagnostics & Synthetic Testing (Zero GUI needed)
        if (args[0].equalsIgnoreCase("test")) {
            String target = args.length > 1 ? args[1].toLowerCase() : "all";
            sender.sendMessage(ChatColor.GOLD + "[Kernel-Test] Ejecutando suite de pruebas sinteticas CLI: " + ChatColor.AQUA + target.toUpperCase());

            if (target.equals("all") || target.equals("anticheat") || target.equals("grim")) {
                String res = DrakesGrimGuard.runSyntheticTest();
                sender.sendMessage(ChatColor.GREEN + "  [GrimGuard] " + res);
            }

            if (target.equals("all") || target.equals("chat") || target.equals("security")) {
                String res = DrakesChatGuard.runSyntheticTest();
                sender.sendMessage(ChatColor.GREEN + "  [ChatGuard] " + res);
            }

            if (target.equals("all") || target.equals("rust") || target.equals("offheap")) {
                String res = SlimefunRustEngine.runSyntheticBenchmark(1000);
                for (String line : res.split("\\n")) {
                    sender.sendMessage(ChatColor.AQUA + "  " + line);
                }
            }

            if (target.equals("all") || target.equals("threads") || target.equals("ryzen")) {
                long start = System.currentTimeMillis();
                java.util.List<java.util.concurrent.CompletableFuture<Void>> futures = new java.util.ArrayList<>();
                for (int i = 0; i < 500; i++) {
                    futures.add(RyzenWorkerPool.runAsync(() -> {
                        double sum = 0;
                        for (int j = 0; j < 1000; j++) sum += Math.sqrt(j);
                    }));
                }
                java.util.concurrent.CompletableFuture.allOf(futures.toArray(new java.util.concurrent.CompletableFuture[0])).join();
                long elapsed = System.currentTimeMillis() - start;
                sender.sendMessage(ChatColor.GREEN + "  [RyzenWorkerPool] 500 tareas computadas en paralelo en " + elapsed + " ms (" + RyzenWorkerPool.getParallelism() + " hilos activos).");
            }

            sender.sendMessage(ChatColor.GOLD + "[Kernel-Test] Diagnostico sintético finalizado exitosamente.");
            return true;
        }

        // Security inspection
        if (args[0].equalsIgnoreCase("security")) {
            var grimStats = DrakesGrimGuard.getStats();
            var chatStats = DrakesChatGuard.getStats();
            sender.sendMessage(ChatColor.GOLD + "=== DrakesPurpur Kernel Security Metrics ===");
            sender.sendMessage(ChatColor.WHITE + "GrimGuard (Anticheat): " + (DrakesGrimGuard.isEnabled() ? ChatColor.GREEN + "ACTIVO" : ChatColor.RED + "DESACTIVADO"));
            sender.sendMessage(ChatColor.GRAY + "  - Bloqueos Reach: " + ChatColor.YELLOW + grimStats.reachViolations());
            sender.sendMessage(ChatColor.GRAY + "  - Bloqueos Speed: " + ChatColor.YELLOW + grimStats.speedViolations());
            sender.sendMessage(ChatColor.GRAY + "  - Bloqueos Click/CPS: " + ChatColor.YELLOW + grimStats.clickViolations());
            sender.sendMessage(ChatColor.GRAY + "  - Bloqueos Fly: " + ChatColor.YELLOW + grimStats.flyViolations());
            sender.sendMessage(ChatColor.WHITE + "ChatGuard (Filtros de Chat & Comandos): " + (DrakesChatGuard.isEnabled() ? ChatColor.GREEN + "ACTIVO" : ChatColor.RED + "DESACTIVADO"));
            sender.sendMessage(ChatColor.GRAY + "  - Mensajes Spam detenidos: " + ChatColor.YELLOW + chatStats.blockedSpam());
            sender.sendMessage(ChatColor.GRAY + "  - Toxicidad censurada: " + ChatColor.YELLOW + chatStats.blockedToxicity());
            sender.sendMessage(ChatColor.GRAY + "  - Intentos de inyeccion de comandos: " + ChatColor.YELLOW + chatStats.blockedExploits());
            return true;
        }

        // In-game GUI menu
        if (args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores en el servidor. Usa /drakes test para CLI.");
                return true;
            }
            org.purpurmc.purpur.gui.DrakesAdminMenu.openMenu(player);
            return true;
        }

        // Credits & Sovereign Canon Recognition
        if (args[0].equalsIgnoreCase("credits") || args[0].equalsIgnoreCase("autores")) {
            sender.sendMessage(ChatColor.GOLD + "==========================================================");
            sender.sendMessage(ChatColor.YELLOW + "  🏛️ DrakesPurpur 26.2: Creditos Canónicos & Autoría  ");
            sender.sendMessage(ChatColor.GOLD + "==========================================================");
            sender.sendMessage(ChatColor.WHITE + "👑 Arquitecto Jefe & Creador: " + ChatColor.LIGHT_PURPLE + "Jack (JackStar6677-1)");
            sender.sendMessage(ChatColor.GRAY + "   - Arquitectura DrakesPurpur, StarSuites, SAORI SRE, Kernel Bridges.");
            sender.sendMessage(ChatColor.WHITE + "🌟 Autor Soberano Suite Multiverse: " + ChatColor.AQUA + "Chagui68 (Chagui)");
            sender.sendMessage(ChatColor.GRAY + "   - Creador de MultiverseNets (Logística Cuántica 3x3 standalone)");
            sender.sendMessage(ChatColor.GRAY + "   - Creador de MultiverseCreatures (Bosses, Dimensiones y Rituales)");
            sender.sendMessage(ChatColor.GRAY + "   - MultiverseTinker, MultiverseProgramming y MultiverseGambling");
            sender.sendMessage(ChatColor.WHITE + "📦 Reconocimiento Upstream Slimefun: " + ChatColor.GREEN + "TheBusyBiscuit & Comunidad");
            sender.sendMessage(ChatColor.GRAY + "   - Proyecto base Slimefun4 (Modernizado y acelerado off-heap en StarSuites)");
            sender.sendMessage(ChatColor.GOLD + "==========================================================");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "=== DrakesPurpur Kernel Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/drakes test [all|anticheat|chat|rust|threads]" + ChatColor.GRAY + " - Pruebas sinteticas CLI sin GUI.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes security" + ChatColor.GRAY + " - Metricas de GrimGuard y ChatGuard.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes status" + ChatColor.GRAY + " - Estado del kernel, memoria y puentes.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes credits" + ChatColor.GRAY + " - Reconocimiento canonico de autores (JackStar & Chagui68).");
        sender.sendMessage(ChatColor.YELLOW + "/drakes threads" + ChatColor.GRAY + " - Profiler nativo de hilos (Reemplazo Spark).");
        sender.sendMessage(ChatColor.YELLOW + "/drakes fastpath" + ChatColor.GRAY + " - Alterna el bypass de eventos de Slimefun.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes gc" + ChatColor.GRAY + " - Forzar ciclo de limpieza de memoria.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes saori" + ChatColor.GRAY + " - Enviar ping de prueba a SAORI SRE.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes menu" + ChatColor.GRAY + " - Abre el panel interactivo visual (GUI).");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) throws IllegalArgumentException {
        if (args.length == 1) {
            return Arrays.asList("status", "test", "security", "credits", "threads", "fastpath", "gc", "saori", "menu")
                    .stream()
                    .filter(sub -> sub.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("test")) {
            return Arrays.asList("all", "anticheat", "chat", "rust", "threads")
                    .stream()
                    .filter(sub -> sub.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return java.util.Collections.emptyList();
    }

    private void sendKernelStatus(CommandSender sender) {
        long totalRam = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeRam = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedRam = totalRam - freeRam;

        sender.sendMessage(ChatColor.GOLD + "========================================");
        sender.sendMessage(ChatColor.YELLOW + "  ⚡ DrakesPurpur 26.2 Sovereign Kernel  ");
        sender.sendMessage(ChatColor.GRAY + "  Architecture: JackStar6677-1 | Star Systems");
        sender.sendMessage(ChatColor.GOLD + "========================================");
        sender.sendMessage(ChatColor.WHITE + "Rust FFM Off-Heap: " + (SlimefunRustEngine.isNativeLinked() ? ChatColor.GREEN + "CARGADO (libslimefun_ffi.so)" : ChatColor.YELLOW + "SIMULADO OFF-HEAP"));
        sender.sendMessage(ChatColor.WHITE + "GrimGuard (Kernel Anticheat): " + (DrakesGrimGuard.isEnabled() ? ChatColor.GREEN + "ACTIVO (Reemplazo GrimAC)" : ChatColor.RED + "INACTIVO"));
        sender.sendMessage(ChatColor.WHITE + "ChatGuard (Kernel AntiSpam): " + (DrakesChatGuard.isEnabled() ? ChatColor.GREEN + "ACTIVO" : ChatColor.RED + "INACTIVO"));
        sender.sendMessage(ChatColor.WHITE + "Slimefun FastPath: " + (SlimefunFastPath.isFastPathEnabled() ? ChatColor.GREEN + "ACTIVO (Bypass On)" : ChatColor.RED + "INACTIVO"));
        sender.sendMessage(ChatColor.WHITE + "Crash Guard Database: " + ChatColor.GREEN + "SQLite WAL (drakes_faults.db)");
        sender.sendMessage(ChatColor.WHITE + "Memoria JVM: " + ChatColor.AQUA + usedRam + " MB usados" + ChatColor.GRAY + " / " + totalRam + " MB total");
        sender.sendMessage(ChatColor.GOLD + "========================================");
    }
}
