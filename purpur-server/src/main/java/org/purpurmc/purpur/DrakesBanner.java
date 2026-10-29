package org.purpurmc.purpur;

import io.papermc.paper.ServerBuildInfo;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Banner de arranque de DrakesPurpur. Se imprime una vez por la consola desde el kernel.
 * Apagar: -Ddrakes.banner=false. Sin colores: variable NO_COLOR o -Ddrakes.banner.color=false.
 * Los colores son secuencias ANSI en el mensaje; el appender de logs/latest.log usa %stripAnsi, asi que el archivo queda limpio.
 * Todo ASCII a proposito: la consola de Pterodactyl o un locale POSIX no siempre soportan UTF-8.
 */
public final class DrakesBanner {
    private static final Map<Character, String[]> FONT = Map.ofEntries(
        Map.entry('D', new String[]{"#### ", "#   #", "#   #", "#   #", "#### "}),
        Map.entry('R', new String[]{"#### ", "#   #", "#### ", "#  # ", "#   #"}),
        Map.entry('A', new String[]{" ### ", "#   #", "#####", "#   #", "#   #"}),
        Map.entry('K', new String[]{"#  # ", "# #  ", "##   ", "# #  ", "#  # "}),
        Map.entry('E', new String[]{"#####", "#    ", "#### ", "#    ", "#####"}),
        Map.entry('S', new String[]{" ####", "#    ", " ### ", "    #", "#### "}),
        Map.entry('P', new String[]{"#### ", "#   #", "#### ", "#    ", "#    "}),
        Map.entry('U', new String[]{"#   #", "#   #", "#   #", "#   #", " ### "}),
        Map.entry('2', new String[]{" ### ", "#   #", "  ## ", " #   ", "#####"}),
        Map.entry('6', new String[]{" ### ", "#    ", "#### ", "#   #", " ### "}),
        Map.entry('.', new String[]{"  ", "  ", "  ", "  ", "# "}),
        Map.entry(' ', new String[]{"   ", "   ", "   ", "   ", "   "}));

    private static boolean printed;

    private DrakesBanner() {}

    private static String[] render(String text) {
        String[] rows = new String[5];
        java.util.Arrays.fill(rows, "");
        for (char c : text.toCharArray()) {
            String[] g = FONT.getOrDefault(c, FONT.get(' '));
            for (int i = 0; i < 5; i++) rows[i] += g[i] + " ";
        }
        return rows;
    }

    private static int embeddedSuites() {
        if ("false".equalsIgnoreCase(System.getProperty("drakes.bundled"))) return 0;
        try (InputStream in = DrakesBanner.class.getClassLoader().getResourceAsStream("drakes-bundled/index.txt")) {
            if (in == null) return 0;
            int n = 0;
            for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                l = l.strip();
                if (!l.isEmpty() && !l.startsWith("#")) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    public static synchronized void print(Logger log) {
        if (printed || "false".equalsIgnoreCase(System.getProperty("drakes.banner"))) return;
        printed = true;
        boolean color = !"false".equalsIgnoreCase(System.getProperty("drakes.banner.color"))
            && System.getenv("NO_COLOR") == null;
        String r = color ? "\u001B[0m" : "";
        String bar = color ? "\u001B[38;5;99m" : "";
        String t1 = color ? "\u001B[1;38;5;141m" : "";
        String t2 = color ? "\u001B[1;38;5;51m" : "";
        String key = color ? "\u001B[38;5;245m" : "";
        String val = color ? "\u001B[1;37m" : "";
        String link = color ? "\u001B[4;38;5;81m" : "";
        String dim = color ? "\u001B[2;37m" : "";

        ServerBuildInfo bi = ServerBuildInfo.buildInfo();
        String build = bi.buildNumber().isPresent() ? String.valueOf(bi.buildNumber().getAsInt()) : "DEV";
        int suites = embeddedSuites();
        String mode = suites > 0 ? "MONOLITO (" + suites + " suites embebidas)" : "MODULAR (suites externas en plugins/)";
        String sep = bar + "=".repeat(64) + r;

        log.info(sep);
        for (String s : render("DRAKES")) log.info(t1 + s + r);
        String[] a = render("PURPUR");
        String[] b = render("26.2");
        for (int i = 0; i < 5; i++) log.info(t2 + a[i] + r + t1 + b[i] + r);
        log.info(sep);
        line(log, key, val, r, "Version", "DrakesPurpur 26.2 build " + build);
        line(log, key, val, r, "Minecraft", bi.minecraftVersionId());
        line(log, key, val, r, "Java", Runtime.version() + " (" + System.getProperty("java.vm.name", "?") + ")");
        line(log, key, val, r, "Modo", mode);
        log.info(key + "Edited by " + val + "JackStar6677-1 (JackStar Systems)" + key + " - DrakesCraft Season Two" + r);
        link(log, key, link, r, "GitHub", "https://github.com/SlimefunNewHorizons/DrakesPurpur");
        link(log, key, link, r, "Perfil", "https://github.com/JackStar6677-1");
        link(log, key, link, r, "Maven", "https://maven.drakescraft.cl");
        link(log, key, link, r, "Web", "https://web.drakescraft.cl");
        link(log, key, link, r, "Discord", "https://discord.gg/rv3vtXZTk7");
        log.info(dim + "Based on Purpur (PurpurMC) and Paper (PaperMC); licensed per upstream; modifications by JackStar6677-1" + r);
        log.info(sep);
    }

    private static void line(Logger log, String key, String val, String r, String k, String v) {
        log.info(key + String.format("%-10s", k) + r + val + v + r);
    }

    private static void link(Logger log, String key, String link, String r, String k, String v) {
        log.info(key + String.format("%-10s", k) + r + link + v + r);
    }
}
