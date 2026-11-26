package org.purpurmc.purpur.bundled;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Early-startup plugin staging promoter for DrakesPurpur.
 * <p>
 * Runs before {@link DrakesBundledPlugins#initialize()} and Paper's plugin scan so that
 * staged {@code *.jar} files placed in {@code staging/} replace existing plugins by their
 * real {@code plugin.yml} / {@code paper-plugin.yml} identity (not merely by filename),
 * backing up replaced JARs into {@code plugins/.rollback/} and preventing duplicates.
 */
public final class DrakesPluginStaging {
    private static final Logger LOGGER = Logger.getLogger("DrakesStaging");
    private static final String DEFAULT_README = """
        # DrakesPurpur Plugin Staging (`staging/`)

        Drop plugin `.jar` files here before restarting the server.

        ## How it works
        - On server startup (before Paper loads `plugins/`), DrakesPurpur inspects every `*.jar` in `staging/`.
        - Each staged JAR is matched against existing JARs in `plugins/` by its real `name` inside `plugin.yml` or `paper-plugin.yml` (so versioned filenames like `Networks-v1.jar` -> `Networks-v2.jar` never leave duplicates).
        - Any previous matching JAR in `plugins/` is moved cleanly to `plugins/.rollback/`.
        - Non-JAR files (`*.md`, `*.txt`, `*.tmp`) and subdirectories are ignored.

        ## Removing a plugin on next boot (`staging/remove.txt`)
        Create `staging/remove.txt` with one plugin name (or exact `.jar` filename) per line (`#` comments supported). On startup, matching JARs are moved to `plugins/.rollback/` and `remove.txt` is archived to `remove.last.txt`.
        """;

    private DrakesPluginStaging() {}

    public static void initialize() {
        if ("false".equalsIgnoreCase(System.getProperty("drakes.staging"))) return;
        process(Path.of("staging"), Path.of("plugins"));
    }

    /** Processes {@code stagingDir} into {@code pluginsDir}; package-private/public for unit tests. */
    public static int process(Path stagingDir, Path pluginsDir) {
        try {
            Files.createDirectories(stagingDir);
            Files.createDirectories(pluginsDir);
            ensureReadme(stagingDir.resolve("README.md"));
        } catch (IOException e) {
            LOGGER.warning("[DrakesStaging] Could not prepare staging directories: " + e.getMessage());
            return 0;
        }

        Path rollbackDir = pluginsDir.resolve(".rollback");
        int changes = 0;

        // 1. Optional removal list (staging/remove.txt)
        Path removeFile = stagingDir.resolve("remove.txt");
        if (Files.isRegularFile(removeFile)) {
            changes += processRemovals(removeFile, pluginsDir, rollbackDir);
        }

        // 2. Promote staged *.jar files
        List<Path> stagedJars = listJars(stagingDir);
        if (stagedJars.isEmpty()) {
            return changes;
        }

        Map<String, List<Path>> existingByIdentity = indexPluginsByIdentity(pluginsDir);
        for (Path staged : stagedJars) {
            String fileName = staged.getFileName().toString();
            String identity = readPluginName(staged);
            String key = (identity != null && !identity.isBlank())
                ? identity.toLowerCase(Locale.ROOT)
                : fileName.toLowerCase(Locale.ROOT);
            String displayName = (identity != null && !identity.isBlank()) ? identity : fileName;

            String hashShort = sha256Short(staged);
            List<String> backedUp = new ArrayList<>();
            List<Path> matches = existingByIdentity.getOrDefault(key, List.of());

            for (Path oldJar : matches) {
                if (!Files.isRegularFile(oldJar)) continue;
                try {
                    Files.createDirectories(rollbackDir);
                    Path rbTarget = rollbackDir.resolve(oldJar.getFileName().toString());
                    Files.move(oldJar, rbTarget, StandardCopyOption.REPLACE_EXISTING);
                    backedUp.add(oldJar.getFileName().toString());
                } catch (IOException e) {
                    LOGGER.warning("[DrakesStaging] Failed to move old jar " + oldJar.getFileName() + " to .rollback: " + e.getMessage());
                }
            }

            // Fallback: if no identity match, still back up exact same filename if present
            Path dest = pluginsDir.resolve(fileName);
            if (Files.isRegularFile(dest)) {
                try {
                    Files.createDirectories(rollbackDir);
                    Files.move(dest, rollbackDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                    backedUp.add(fileName);
                } catch (IOException e) {
                    LOGGER.warning("[DrakesStaging] Failed to back up " + fileName + ": " + e.getMessage());
                }
            }

            try {
                Files.move(staged, dest, StandardCopyOption.REPLACE_EXISTING);
                changes++;
                if (backedUp.isEmpty()) {
                    LOGGER.info("[DrakesStaging] Installed " + displayName + " (" + fileName + ", sha256=" + hashShort + ")");
                } else {
                    LOGGER.info("[DrakesStaging] Promoted " + displayName + " (" + String.join(", ", backedUp) + " -> " + fileName + ", sha256=" + hashShort + ")");
                }
            } catch (IOException e) {
                LOGGER.warning("[DrakesStaging] Failed to promote " + fileName + ": " + e.getMessage());
            }
        }
        return changes;
    }

    private static int processRemovals(Path removeFile, Path pluginsDir, Path rollbackDir) {
        int removed = 0;
        try {
            List<String> lines = Files.readAllLines(removeFile, StandardCharsets.UTF_8);
            Set<String> targets = new LinkedHashSet<>();
            for (String raw : lines) {
                String line = raw.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    targets.add(line.toLowerCase(Locale.ROOT));
                }
            }
            if (!targets.isEmpty()) {
                for (Path jar : listJars(pluginsDir)) {
                    String fname = jar.getFileName().toString().toLowerCase(Locale.ROOT);
                    String pname = readPluginName(jar);
                    String pkey = pname == null ? "" : pname.toLowerCase(Locale.ROOT);
                    if (targets.contains(fname) || (!pkey.isEmpty() && targets.contains(pkey))) {
                        Files.createDirectories(rollbackDir);
                        Files.move(jar, rollbackDir.resolve(jar.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                        LOGGER.info("[DrakesStaging] Removed " + (pname != null ? pname : fname) + " -> plugins/.rollback/" + jar.getFileName());
                        removed++;
                    }
                }
            }
            Files.move(removeFile, removeFile.resolveSibling("remove.last.txt"), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOGGER.warning("[DrakesStaging] Failed processing remove.txt: " + e.getMessage());
        }
        return removed;
    }

    private static void ensureReadme(Path readme) throws IOException {
        if (!Files.exists(readme)) {
            Files.writeString(readme, DEFAULT_README, StandardCharsets.UTF_8);
        }
    }

    private static List<Path> listJars(Path dir) {
        List<Path> jars = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .forEach(jars::add);
        } catch (IOException ignored) {}
        return jars;
    }

    private static Map<String, List<Path>> indexPluginsByIdentity(Path pluginsDir) {
        Map<String, List<Path>> map = new LinkedHashMap<>();
        for (Path jar : listJars(pluginsDir)) {
            String name = readPluginName(jar);
            if (name != null && !name.isBlank()) {
                map.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(jar);
            }
        }
        return map;
    }

    /** Extracts the declared plugin {@code name} from {@code plugin.yml} or {@code paper-plugin.yml}. */
    static String readPluginName(Path jarPath) {
        try (JarFile jf = new JarFile(jarPath.toFile())) {
            for (String descriptor : List.of("paper-plugin.yml", "plugin.yml")) {
                JarEntry entry = jf.getJarEntry(descriptor);
                if (entry == null) continue;
                try (InputStream in = jf.getInputStream(entry);
                     InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                    YamlConfiguration yml = YamlConfiguration.loadConfiguration(reader);
                    String name = yml.getString("name");
                    if (name != null && !name.isBlank()) {
                        return name.strip();
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static String sha256Short(Path path) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            return HexFormat.of().formatHex(digest).substring(0, 12);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
