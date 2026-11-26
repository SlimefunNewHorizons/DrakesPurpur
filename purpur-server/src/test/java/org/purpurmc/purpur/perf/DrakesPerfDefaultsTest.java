package org.purpurmc.purpur.perf;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DrakesPerfDefaultsTest {
    @Test
    void defaultConfigurationParsesAndPreservesKeyHierarchy() throws Exception {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(DrakesPerf.DEFAULT_YML);
        assertEquals(6, cfg.getInt("native.hybrid.prefetch.per-player-in-flight"));
        assertEquals(38.0, cfg.getDouble("native.hybrid.prefetch.throttle-mspt"));
        assertEquals(46.0, cfg.getDouble("native.hybrid.prefetch.pause-mspt"));
        assertEquals(1, cfg.getInt("maps.biome-preview-step"));
        assertEquals(50, cfg.getInt("maps.biome-preview-warn-ms"));
        assertTrue(cfg.isConfigurationSection("backup"));
    }
}
