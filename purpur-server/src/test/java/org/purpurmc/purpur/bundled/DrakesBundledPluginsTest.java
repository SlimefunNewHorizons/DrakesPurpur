package org.purpurmc.purpur.bundled;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DrakesBundledPluginsTest {
    private static byte[] plugin(String name) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(buffer)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("name: " + name + "\nversion: 1.0\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return buffer.toByteArray();
    }

    @Test
    void detectsDuplicateIdentityAcrossFilenames(@TempDir Path plugins) throws Exception {
        byte[] data = plugin("Slimefun");
        Files.write(plugins.resolve("Slimefun-current.jar"), data);
        assertEquals("Slimefun", DrakesBundledPlugins.embeddedPluginName(data));
        assertTrue(DrakesBundledPlugins.hasPluginIdentity(plugins.toFile(), "slimefun"));
        assertFalse(DrakesBundledPlugins.hasPluginIdentity(plugins.toFile(), "AnotherPlugin"));
    }
}
