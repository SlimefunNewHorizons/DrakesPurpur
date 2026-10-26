package org.purpurmc.purpur.command;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.purpurmc.purpur.DrakesKernelBridge;
import org.purpurmc.purpur.slimefun.SlimefunFastPath;
import org.purpurmc.purpur.geyser.BedrockCompanion;
import org.purpurmc.purpur.saori.SaoriKernelSocket;

/**
 * DrakesAdminCommand — In-game control panel for DrakesPurpur sovereign kernel
 * Command: /drakesadmin (or /drakes)
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesAdminCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("drakes.admin")) {
            sender.sendMessage(ChatColor.RED + "No tienes permiso para acceder al Kernel de DrakesPurpur.");
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendKernelStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("fastpath")) {
            boolean current = SlimefunFastPath.isFastPathEnabled();
            SlimefunFastPath.setFastPathEnabled(!current);
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.YELLOW + "Slimefun FastPath ahora esta: "
                + (SlimefunFastPath.isFastPathEnabled() ? ChatColor.GREEN + "ACTIVADO" : ChatColor.RED + "DESACTIVADO"));
            return true;
        }

        if (args[0].equalsIgnoreCase("gc")) {
            long before = Runtime.getRuntime().freeMemory();
            System.gc();
            long after = Runtime.getRuntime().freeMemory();
            long freedMb = (after - before) / (1024 * 1024);
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.GREEN + "Garbage Collection manual completado. "
                + ChatColor.AQUA + "RAM liberada: ~" + freedMb + " MB.");
            return true;
        }

        if (args[0].equalsIgnoreCase("saori")) {
            SaoriKernelSocket.dispatchTelemetry(20.0, 5.0, 1, Runtime.getRuntime().freeMemory() / (1024 * 1024));
            sender.sendMessage(ChatColor.GOLD + "[DrakesPurpur] " + ChatColor.GREEN + "Ping de telemetria despachado a SAORI exitosamente.");
            return true;
        }

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

                if (args[0].equalsIgnoreCase("menu") || args[0].equalsIgnoreCase("gui")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Este comando solo puede ser ejecutado por jugadores en el servidor.");
                return true;
            }
            org.purpurmc.purpur.gui.DrakesAdminMenu.openMenu(player);
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "=== DrakesPurpur Kernel Commands ===");
        sender.sendMessage(ChatColor.YELLOW + "/drakes menu" + ChatColor.GRAY + " - Abre el panel interactivo visual (GUI).");
        sender.sendMessage(ChatColor.YELLOW + "/drakes status" + ChatColor.GRAY + " - Muestra el estado del kernel y puentes.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes fastpath" + ChatColor.GRAY + " - Alterna el bypass de eventos de Slimefun.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes gc" + ChatColor.GRAY + " - Forzar ciclo de limpieza de memoria.");
        sender.sendMessage(ChatColor.YELLOW + "/drakes threads" + ChatColor.GRAY + " - Profiler nativo de hilos (Reemplazo Spark).");
        sender.sendMessage(ChatColor.YELLOW + "/drakes saori" + ChatColor.GRAY + " - Enviar ping de prueba a SAORI SRE.");
        return true;
    }

    private void sendKernelStatus(CommandSender sender) {
        long totalRam = Runtime.getRuntime().totalMemory() / (1024 * 1024);
        long freeRam = Runtime.getRuntime().freeMemory() / (1024 * 1024);
        long usedRam = totalRam - freeRam;

        sender.sendMessage(ChatColor.GOLD + "========================================");
        sender.sendMessage(ChatColor.YELLOW + "  ⚡ DrakesPurpur 26.2 Sovereign Kernel  ");
        sender.sendMessage(ChatColor.GRAY + "  Architecture: JackStar6677-1 | Star Systems");
        sender.sendMessage(ChatColor.GOLD + "========================================");
        sender.sendMessage(ChatColor.WHITE + "Rust FFM Off-Heap: " + (DrakesKernelBridge.isRustLoaded() ? ChatColor.GREEN + "CARGADO (libslimefun_ffi.so)" : ChatColor.YELLOW + "SIMULADO"));
        sender.sendMessage(ChatColor.WHITE + "Slimefun FastPath: " + (SlimefunFastPath.isFastPathEnabled() ? ChatColor.GREEN + "ACTIVO (Bypass On)" : ChatColor.RED + "INACTIVO"));
        sender.sendMessage(ChatColor.WHITE + "Crash Guard Database: " + ChatColor.GREEN + "SQLite WAL (drakes_faults.db)");
        sender.sendMessage(ChatColor.WHITE + "Memoria JVM: " + ChatColor.AQUA + usedRam + " MB usados" + ChatColor.GRAY + " / " + totalRam + " MB total");
        sender.sendMessage(ChatColor.GOLD + "========================================");
    }
}
