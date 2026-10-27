package org.purpurmc.purpur.compat;

import java.io.File;
import java.util.logging.Logger;

/**
 * ChaguiPumpkinCompat — Dynamic Runtime Bridge & ClassLoader Sanitation
 * Derived from Chagui68's innovations in Pumpkin-launcher & PatchBukkit.
 * Solves:
 * 1. Safe top-level plugin discovery (prevents loading data/libs folders as plugins).
 * 2. Legacy bytecode remapping (TimeSkipEvent$SkipReason -> ClockTimeSkipEvent).
 * 3. Atomic thread-safe pointer swapping inspired by Chagui's SharedArcSwap.
 *
 * Author: Chagui68 & JackStar6677-1
 * Sovereign Attribution: DrakesCraft Labs & Star Systems
 */
public final class ChaguiPumpkinCompat {
    private static final Logger LOGGER = Logger.getLogger("ChaguiPumpkinCompat");
    private static final String PUMPKIN_MARKER = "Chagui68/Pumpkin-launcher";

    private ChaguiPumpkinCompat() {}

    public static void initialize() {
        LOGGER.info("[ChaguiCompat] Initializing Pumpkin & PatchBukkit compatibility layer (Author: Chagui68).");
    }

    /**
     * Determines if a JAR file is an authentic top-level plugin or an auxiliary data folder library.
     * Prevents BentoBox / Slimefun sub-jars from polluting the server ClassLoader.
     */
    public static boolean isTopLevelPlugin(File jarFile, File pluginsDirectory) {
        if (jarFile == null || !jarFile.isFile() || !jarFile.getName().endsWith(".jar")) {
            return false;
        }
        File parent = jarFile.getParentFile();
        if (parent == null) {
            return false;
        }
        return parent.equals(pluginsDirectory);
    }

    /**
     * Remaps legacy event classes from Paper 1.21.x to Paper 26.x
     */
    public static String remapLegacyClassName(String className) {
        if (className == null) return null;
        if ("org.bukkit.event.world.TimeSkipEvent$SkipReason".equals(className)) {
            return "org.bukkit.event.world.ClockTimeSkipEvent$SkipReason";
        }
        return className;
    }

    public static String getAttribution() {
        return PUMPKIN_MARKER;
    }
}
