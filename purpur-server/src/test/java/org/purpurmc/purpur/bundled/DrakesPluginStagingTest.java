package org.purpurmc.purpur.bundled;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class DrakesPluginStagingTest {

    private static void writeFakePluginJar(Path target, String pluginName) throws IOException {
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(target))) {
            jos.putNextEntry(new JarEntry("plugin.yml"));
            String yml = "name: " + pluginName + "\nversion: 1.0\nmain: example.Main\n";
            jos.write(yml.getBytes(StandardCharsets.UTF_8));
            jos.closeEntry();
        }
    }

    @Test
    void promotesVersionedPluginByIdentityAndMovesOldToRollback(@TempDir Path tmp) throws IOException {
        Path staging = tmp.resolve("staging");
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(staging);
        Files.createDirectories(plugins);

        Path oldJar = plugins.resolve("Networks-26.2-v1.jar");
        Path newJar = staging.resolve("Networks-26.2-v2-ecde418.jar");
        Path docFile = staging.resolve("notes.txt");
        writeFakePluginJar(oldJar, "Networks");
        writeFakePluginJar(newJar, "Networks");
        Files.writeString(docFile, "ignore me", StandardCharsets.UTF_8);

        int changed = DrakesPluginStaging.process(staging, plugins);

        assertEquals(1, changed);
        assertFalse(Files.exists(oldJar), "old versioned jar must be moved out of plugins/");
        assertTrue(Files.exists(plugins.resolve(".rollback/Networks-26.2-v1.jar")), "old jar must be backed up in .rollback");
        assertTrue(Files.exists(plugins.resolve("Networks-26.2-v2-ecde418.jar")), "new jar must be promoted to plugins/");
        assertFalse(Files.exists(newJar), "staged jar must be moved out of staging/");
        assertTrue(Files.exists(staging.resolve("README.md")), "staging/README.md must be auto-created");
        assertTrue(Files.exists(docFile), "non-jar files must be ignored");
    }

    @Test
    void removeTxtMovesListedPluginToRollbackAndArchivesList(@TempDir Path tmp) throws IOException {
        Path staging = tmp.resolve("staging");
        Path plugins = tmp.resolve("plugins");
        Files.createDirectories(staging);
        Files.createDirectories(plugins);

        Path unwanted = plugins.resolve("OldAddon-1.0.jar");
        writeFakePluginJar(unwanted, "OldAddon");
        Files.writeString(staging.resolve("remove.txt"), "# remove old addon\nOldAddon\n", StandardCharsets.UTF_8);

        int changed = DrakesPluginStaging.process(staging, plugins);

        assertEquals(1, changed);
        assertFalse(Files.exists(unwanted));
        assertTrue(Files.exists(plugins.resolve(".rollback/OldAddon-1.0.jar")));
        assertFalse(Files.exists(staging.resolve("remove.txt")));
        assertTrue(Files.exists(staging.resolve("remove.last.txt")));
    }
}
