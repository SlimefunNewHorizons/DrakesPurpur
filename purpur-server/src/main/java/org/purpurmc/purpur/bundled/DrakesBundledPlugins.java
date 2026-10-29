package org.purpurmc.purpur.bundled;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
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
 * Que suites se extraen se elige SIN recompilar en drakes-data/suites.yml (se crea la primera vez).
 */
public final class DrakesBundledPlugins {
    private static final Logger LOGGER = Logger.getLogger("DrakesBundled");
    private static final String BASE = "drakes-bundled/";
    private static final String SUITES = "drakes-data/suites.yml";

    private DrakesBundledPlugins() {}

    public static void initialize() {
        if ("false".equalsIgnoreCase(System.getProperty("drakes.bundled"))) return;
        ClassLoader cl = DrakesBundledPlugins.class.getClassLoader();
        try (InputStream idx = cl.getResourceAsStream(BASE + "index.txt")) {
            if (idx == null) return;
            File plugins = new File("plugins");
            plugins.mkdirs();
            List<String> names = new ArrayList<>();
            for (String name : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                name = name.strip();
                if (!name.isEmpty() && !name.startsWith("#")) names.add(name);
            }
            YamlConfiguration cfg = loadSuites(names);
            int escritos = 0, total = 0, off = 0;
            for (String name : names) {
                if (!cfg.getBoolean("suites." + key(name), true)) {
                    off++;
                    try (InputStream in = cl.getResourceAsStream(BASE + name)) {
                        File old = new File(plugins, name);
                        // solo borra el jar si es el embebido tal cual (no toca jars que Jack reemplazo a mano)
                        if (in != null && old.isFile() && sha(Files.readAllBytes(old.toPath())).equals(sha(in.readAllBytes()))) old.delete();
                    }
                    continue;
                }
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
            LOGGER.info("[DrakesBundled] " + total + " plugins activos (" + off + " desactivados en " + SUITES + "), " + escritos + " extraídos/actualizados en plugins/");
        } catch (Exception e) {
            LOGGER.severe("[DrakesBundled] fallo extrayendo plugins embebidos: " + e);
        }
    }

    /** drakes-data/suites.yml: una linea por jar embebido (nombre sin version). Falta o ausente = activo. */
    private static YamlConfiguration loadSuites(List<String> names) throws Exception {
        File f = new File(SUITES);
        if (!f.isFile()) {
            f.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder("""
                # Suites/addons del monolito (jars embebidos en el ejecutable).
                # true = se extrae a plugins/ y Paper lo carga; false = no se carga (y se borra el jar embebido ya extraido).
                # Un jar que no aparezca aqui se considera activo. Se aplica al REINICIAR; no hace falta recompilar.
                # Cada suite tiene ademas sus addons en plugins/<Suite>/modules/<addon>.yml (recarga: /drakessuites reload).
                # Drakes-Core es la base: si lo desactivas, el resto de suites no arrancara.
                suites:
                """);
            for (String n : names) sb.append("  ").append(key(n)).append(": true   # ").append(n).append('\n');
            Files.writeString(f.toPath(), sb.toString(), StandardCharsets.UTF_8);
        }
        return YamlConfiguration.loadConfiguration(f);
    }

    /** DrakesTech-v2.0.0-26.X-SNAPSHOT.jar -> DrakesTech ; drakes-multiverse.jar -> drakes-multiverse */
    static String key(String jar) {
        String b = jar.endsWith(".jar") ? jar.substring(0, jar.length() - 4) : jar;
        return b.replaceFirst("-v\\d.*$", "");
    }

    private static String sha(byte[] b) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
    }
}
