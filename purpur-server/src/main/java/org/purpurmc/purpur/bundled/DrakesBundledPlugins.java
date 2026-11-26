package org.purpurmc.purpur.bundled;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.util.jar.JarInputStream;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Monolito: extrae a plugins/ los jars embebidos en el propio ejecutable (drakes-bundled/index.txt).
 * Preserves existing jars and avoids duplicate plugin identities; Paper loads the extracted jars normally.
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
            int escritos = 0, total = 0, off = 0, kept = 0;
            StringBuilder estado = new StringBuilder("# Generado en cada arranque por DrakesBundled. No editar.\n");
            for (String name : names) {
                Object modo = cfg.get("suites." + key(name), true);
                if ("keep".equalsIgnoreCase(String.valueOf(modo))) {
                    // jar gestionado a mano: el motor NO lo toca; solo deja constancia de si difiere del embebido
                    kept++;
                    File mine = new File(plugins, name);
                    try (InputStream in = cl.getResourceAsStream(BASE + name)) {
                        String emb = in == null ? "-" : sha(in.readAllBytes());
                        String dep = mine.isFile() ? sha(Files.readAllBytes(mine.toPath())) : "AUSENTE";
                        estado.append(key(name)).append(": {modo: keep, embebido: ").append(emb).append(", desplegado: ").append(dep).append("}\n");
                        LOGGER.warning("[DrakesBundled] " + name + " en modo keep: " + (dep.equals(emb) ? "igual al embebido" : mine.isFile() ? "jar manual distinto del embebido (se conserva)" : "NO existe en plugins/"));
                    }
                    continue;
                }
                if (!Boolean.parseBoolean(String.valueOf(modo))) {
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
                    if (dest.isFile() && sha(Files.readAllBytes(dest.toPath())).equals(sha(data))) {
                        estado.append(key(name)).append(": {mode: unchanged, embedded: ").append(sha(data)).append("}\n");
                        continue;
                    }
                    if (dest.isFile()) {
                        kept++;
                        estado.append(key(name)).append(": {mode: preserved, embedded: ").append(sha(data)).append(", deployed: ").append(sha(Files.readAllBytes(dest.toPath()))).append("}\n");
                        LOGGER.warning("[DrakesBundled] Preserving modified plugin " + name + "; embedded version was not installed.");
                        continue;
                    }
                    String identity = embeddedPluginName(data);
                    if (identity == null) {
                        LOGGER.warning("[DrakesBundled] Cannot determine plugin identity for " + name + "; skipping extraction.");
                        continue;
                    }
                    if (hasPluginIdentity(plugins, identity)) {
                        kept++;
                        LOGGER.warning("[DrakesBundled] Preserving existing plugin " + identity + " under another filename; skipping " + name);
                        continue;
                    }
                    estado.append(key(name)).append(": {mode: extracted, embedded: ").append(sha(data)).append("}\n");
                    File tmp = new File(plugins, name + ".tmp");
                    Files.write(tmp.toPath(), data);
                    Files.move(tmp.toPath(), dest.toPath());
                    escritos++;
                }
            }
            Files.writeString(new File("drakes-data/bundled-state.yml").toPath(), estado.toString(), StandardCharsets.UTF_8);
            LOGGER.info("[DrakesBundled] " + kept + " en keep; " + total + " plugins activos (" + off + " desactivados en " + SUITES + "), " + escritos + " extraídos/actualizados en plugins/");
        } catch (Exception e) {
            LOGGER.severe("[DrakesBundled] fallo extrayendo plugins embebidos: " + e);
        }
    }

    static String embeddedPluginName(byte[] data) throws Exception {
        try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(data))) {
            java.util.jar.JarEntry entry;
            while ((entry = jar.getNextJarEntry()) != null) {
                if (entry.getName().equals("plugin.yml") || entry.getName().equals("paper-plugin.yml")) {
                    return YamlConfiguration.loadConfiguration(new InputStreamReader(jar, StandardCharsets.UTF_8)).getString("name");
                }
            }
        }
        return null;
    }

    static boolean hasPluginIdentity(File plugins, String identity) throws Exception {
        File[] jars = plugins.listFiles((dir, name) -> name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"));
        if (jars == null) throw new java.io.IOException("Cannot list plugin directory");
        for (File file : jars) {
            try (JarFile jar = new JarFile(file)) {
                java.util.zip.ZipEntry descriptor = jar.getEntry("paper-plugin.yml");
                if (descriptor == null) descriptor = jar.getEntry("plugin.yml");
                if (descriptor == null) continue;
                try (InputStream in = jar.getInputStream(descriptor)) {
                    String name = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)).getString("name");
                    if (identity.equalsIgnoreCase(name)) return true;
                }
            }
        }
        return false;
    }

    /** drakes-data/suites.yml: una linea por jar embebido (nombre sin version). Falta o ausente = activo. */
    private static YamlConfiguration loadSuites(List<String> names) throws Exception {
        File f = new File(SUITES);
        if (!f.isFile()) {
            f.getParentFile().mkdirs();
            StringBuilder sb = new StringBuilder("""
                # Suites/addons del monolito (jars embebidos en el ejecutable).
                # true = se extrae a plugins/ y Paper lo carga; false = no se carga (y se borra el jar embebido ya extraido).
                # keep = el motor no toca ese jar (lo gestionas a mano; ver drakes-data/bundled-state.yml). Un jar que no aparezca aqui se considera activo. Se aplica al REINICIAR; no hace falta recompilar.
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
