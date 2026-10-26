package org.purpurmc.purpur.geyser;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BedrockCompanion — Kernel-level network optimizations for Geyser / Floodgate mobile clients
 * Author: Jack (JackStar6677-1) - DrakesCraft Labs
 */
public final class BedrockCompanion {
    private static final Set<UUID> BEDROCK_PLAYERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean adaptiveEntityTracking = true;

    private BedrockCompanion() {}

    public static void markBedrockPlayer(UUID playerUuid) {
        BEDROCK_PLAYERS.add(playerUuid);
    }

    public static void unmarkBedrockPlayer(UUID playerUuid) {
        BEDROCK_PLAYERS.remove(playerUuid);
    }

    public static boolean isBedrockPlayer(UUID playerUuid) {
        return BEDROCK_PLAYERS.contains(playerUuid);
    }

    public static boolean shouldThrottleUpdate(UUID playerUuid, int entityAgeTicks) {
        if (!adaptiveEntityTracking || !isBedrockPlayer(playerUuid)) {
            return false;
        }
        return (entityAgeTicks % 2) != 0;
    }

    public static void setAdaptiveEntityTracking(boolean enabled) {
        adaptiveEntityTracking = enabled;
    }
}
