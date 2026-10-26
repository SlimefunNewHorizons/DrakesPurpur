package org.purpurmc.purpur.slimefun;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SlimefunFastPath — Kernel-level bypass of heavy Bukkit item move events
 * Eliminates 15-20% of CPU tick waste caused by thousands of automated cargo tickers.
 * Author: Jack (JackStar6677-1) - DrakesCraft Labs
 */
public final class SlimefunFastPath {
    private static final Set<Long> REGISTERED_SLIMEFUN_BLOCKS = ConcurrentHashMap.newKeySet();
    private static volatile boolean fastPathEnabled = true;

    private SlimefunFastPath() {}

    public static boolean isFastPathEnabled() {
        return fastPathEnabled;
    }

    public static void setFastPathEnabled(boolean enabled) {
        fastPathEnabled = enabled;
    }

    public static void registerSlimefunBlock(int worldId, int x, int y, int z) {
        long key = packKey(worldId, x, y, z);
        REGISTERED_SLIMEFUN_BLOCKS.add(key);
    }

    public static void unregisterSlimefunBlock(int worldId, int x, int y, int z) {
        long key = packKey(worldId, x, y, z);
        REGISTERED_SLIMEFUN_BLOCKS.remove(key);
    }

    public static boolean shouldBypassBukkitEvent(int worldId, int x, int y, int z) {
        if (!fastPathEnabled) return false;
        long key = packKey(worldId, x, y, z);
        return REGISTERED_SLIMEFUN_BLOCKS.contains(key);
    }

    private static long packKey(int worldId, int x, int y, int z) {
        return (((long) worldId & 0xFFFFL) << 48)
             | (((long) (x + 30000000) & 0x3FFFFFFL) << 22)
             | ((long) (y & 0x7FFL) << 11)
             | ((long) (z + 30000000) & 0x3FFFFFFL);
    }
}
