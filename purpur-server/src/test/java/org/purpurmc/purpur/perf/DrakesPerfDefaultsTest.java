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
        assertEquals(4, cfg.getInt("maps.biome-preview-step"));
        assertTrue(cfg.getBoolean("native.enabled"));
        assertTrue(cfg.getBoolean("native.odysseia.pulses"));
        assertTrue(cfg.getBoolean("native.odysseia.aura"));
        assertTrue(cfg.getBoolean("native.slimefun.fill"));
        assertTrue(cfg.getBoolean("native.slimefun.sum"));
        assertTrue(cfg.getBoolean("native.slimefun.price"));
        assertTrue(cfg.getBoolean("native.hybrid.bits"));
        assertTrue(cfg.getBoolean("native.hybrid.noise"));
        assertTrue(cfg.getBoolean("native.hybrid.region"));
        assertTrue(cfg.getBoolean("load-governor.enabled"));
        assertEquals(3, cfg.getInt("load-governor.min-simulation-distance"));
        assertTrue(cfg.getBoolean("hybrid.engine.enabled"));
        assertTrue(cfg.getBoolean("native.hybrid.prefetch.enabled"));
        assertTrue(cfg.getBoolean("threads.chunk-gen.enabled"));
        assertFalse(cfg.getBoolean("native.slimefun.tick-engine"));
        assertEquals(50, cfg.getInt("maps.biome-preview-warn-ms"));
        assertTrue(cfg.isConfigurationSection("backup"));
    }
}
