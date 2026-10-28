package org.purpurmc.purpur.bundled;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Monolito: extrae a plugins/ los jars embebidos en el propio ejecutable (drakes-bundled/index.txt).
 * Solo reescribe un jar si cambia su SHA-256; el plugin-loader de Paper los carga como cualquier otro.
 * Desactivable con -Ddrakes.bundled=false (p. ej. para probar la rama modular con el mismo jar).
 */
public final class DrakesBundledPlugins {
    private static final Logger LOGGER = Logger.getLogger("DrakesBundled");
    private static final String BASE = "drakes-bundled/";

    private DrakesBundledPlugins() {}

    public static void initialize() {
        if ("false".equalsIgnoreCase(System.getProperty("drakes.bundled"))) return;
        ClassLoader cl = DrakesBundledPlugins.class.getClassLoader();
        try (InputStream idx = cl.getResourceAsStream(BASE + "index.txt")) {
            if (idx == null) return;
            File plugins = new File("plugins");
            plugins.mkdirs();
            int escritos = 0, total = 0;
            for (String name : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                name = name.strip();
                if (name.isEmpty() || name.startsWith("#")) continue;
                total++;
                try (InputStream in = cl.getResourceAsStream(BASE + name)) {
                    if (in == null) { LOGGER.warning("Falta en el jar: " + name); continue; }
                    byte[] data = in.readAllBytes();
                    File dest = new File(plugins, name);
                    if (dest.isFile() && sha(Files.readAllBytes(dest.toPath())).equals(sha(data))) continue;
                    File tmp = new File(plugins, name + ".tmp");
                    Files.write(tmp.toPath(), data);
                    Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    escritos++;
                }
            }
            LOGGER.info("[DrakesBundled] " + total + " plugins embebidos, " + escritos + " extraídos/actualizados en plugins/");
        } catch (Exception e) {
            LOGGER.severe("[DrakesBundled] fallo extrayendo plugins embebidos: " + e);
        }
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
