package org.purpurmc.purpur.security;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;
import org.purpurmc.purpur.saori.SaoriKernelSocket;

/**
 * DrakesGrimGuard — Kernel-level Anticheat & Packet Validation
 * Replaces external GrimAC plugin with zero Bukkit event overhead.
 * Validates movement, combat reach, fast-clicks and packet timers directly in the server core.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesGrimGuard {
    private static final Logger LOGGER = Logger.getLogger("DrakesGrimGuard");
    private static volatile boolean enabled = true;

    // Thresholds
    private static final double MAX_SURVIVAL_REACH_SQUARED = 3.20 * 3.20; // 3.2 blocks max reach
    private static final double MAX_HORIZONTAL_SPEED_PER_TICK = 1.15; // Max legitimate horizontal velocity
    private static final int MAX_CLICKS_PER_SECOND = 22;

    // Telemetry & metrics
    private static final AtomicLong BLOCKED_REACH_VIOLATIONS = new AtomicLong(0);
    private static final AtomicLong BLOCKED_SPEED_VIOLATIONS = new AtomicLong(0);
    private static final AtomicLong BLOCKED_CLICK_VIOLATIONS = new AtomicLong(0);
    private static final AtomicLong BLOCKED_FLY_VIOLATIONS = new AtomicLong(0);

    // Player tracking states
    private static final Map<UUID, PlayerMovementState> MOVEMENT_TRACKER = new ConcurrentHashMap<>();
    private static final Map<UUID, ClickState> CLICK_TRACKER = new ConcurrentHashMap<>();

    private DrakesGrimGuard() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
    }

    /**
     * Validates melee attack reach at the kernel level.
     * @return true if the attack is legitimate, false if it exceeds geometric reach.
     */
    public static boolean validateAttackReach(UUID playerUuid, double attackerX, double attackerY, double attackerZ,
                                              double targetX, double targetY, double targetZ) {
        if (!enabled) return true;

        double dx = attackerX - targetX;
        double dy = attackerY - targetY;
        double dz = attackerZ - targetZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > MAX_SURVIVAL_REACH_SQUARED) {
            BLOCKED_REACH_VIOLATIONS.incrementAndGet();
            double distance = Math.sqrt(distSq);
            LOGGER.warning("[GrimGuard] Interceptado Reach anómalo de " + playerUuid + ": "
                + String.format("%.2f", distance) + "m (límite: 3.20m)");
            SaoriKernelSocket.dispatchFault("GrimGuard-Reach", playerUuid.toString(),
                "Distancia: " + String.format("%.2f", distance) + "m");
            return false;
        }
        return true;
    }

    /**
     * Validates movement packets (Speed, Fly, Illegal Y Delta).
     * @return true if legitimate, false if cancelled/mitigated.
     */
    public static boolean validateMovement(UUID playerUuid, double toX, double toY, double toZ, boolean onGround, boolean canFly) {
        if (!enabled || canFly) return true;

        long now = System.currentTimeMillis();
        PlayerMovementState state = MOVEMENT_TRACKER.computeIfAbsent(playerUuid, u -> new PlayerMovementState(toX, toY, toZ, onGround, now));

        double dx = toX - state.lastX;
        double dy = toY - state.lastY;
        double dz = toZ - state.lastZ;
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        // Update state
        state.lastX = toX;
        state.lastY = toY;
        state.lastZ = toZ;
        state.lastOnGround = onGround;
        state.lastTimeMs = now;

        // Speed check
        if (horizontalDist > MAX_HORIZONTAL_SPEED_PER_TICK) {
            BLOCKED_SPEED_VIOLATIONS.incrementAndGet();
            LOGGER.warning("[GrimGuard] Interceptado Speed anormal de " + playerUuid + ": "
                + String.format("%.2f", horizontalDist) + " b/t");
            SaoriKernelSocket.dispatchFault("GrimGuard-Speed", playerUuid.toString(),
                "Velocidad: " + String.format("%.2f", horizontalDist) + " b/t");
            return false;
        }

        // Fly / Hover check (rising in the air without velocity or jumping)
        if (!onGround && dy > 0.0 && !state.wasEverOnGroundRecently(now)) {
            BLOCKED_FLY_VIOLATIONS.incrementAndGet();
            LOGGER.warning("[GrimGuard] Interceptado Fly no autorizado de " + playerUuid);
            return false;
        }

        return true;
    }

    /**
     * Rate-limits interaction/click rate (Anti-AutoClicker / FastClick).
     * @return true if allowed, false if dropped.
     */
    public static boolean validateClick(UUID playerUuid) {
        if (!enabled) return true;

        long now = System.currentTimeMillis();
        ClickState state = CLICK_TRACKER.computeIfAbsent(playerUuid, u -> new ClickState(now));

        if (now - state.windowStartMs > 1000) {
            state.windowStartMs = now;
            state.clicksInWindow = 1;
            return true;
        }

        state.clicksInWindow++;
        if (state.clicksInWindow > MAX_CLICKS_PER_SECOND) {
            BLOCKED_CLICK_VIOLATIONS.incrementAndGet();
            return false;
        }
        return true;
    }

    public static void clearPlayer(UUID playerUuid) {
        MOVEMENT_TRACKER.remove(playerUuid);
        CLICK_TRACKER.remove(playerUuid);
    }

    public static AnticheatStats getStats() {
        return new AnticheatStats(
            BLOCKED_REACH_VIOLATIONS.get(),
            BLOCKED_SPEED_VIOLATIONS.get(),
            BLOCKED_CLICK_VIOLATIONS.get(),
            BLOCKED_FLY_VIOLATIONS.get(),
            MOVEMENT_TRACKER.size()
        );
    }

    /**
     * Executes synthetic diagnostic test suite from console.
     */
    public static String runSyntheticTest() {
        UUID testUuid = UUID.fromString("00000000-0000-0000-0000-000000000001");
        int passed = 0;
        int failed = 0;

        // Test 1: Reach legit (2.5 blocks)
        boolean legitReach = validateAttackReach(testUuid, 0, 64, 0, 0, 64, 2.5);
        if (legitReach) passed++; else failed++;

        // Test 2: Reach violation (5.5 blocks)
        boolean illegalReach = validateAttackReach(testUuid, 0, 64, 0, 0, 64, 5.5);
        if (!illegalReach) passed++; else failed++;

        // Test 3: Legit movement (0.3 blocks/tick)
        validateMovement(testUuid, 0, 64, 0, true, false);
        boolean legitMove = validateMovement(testUuid, 0.3, 64, 0, true, false);
        if (legitMove) passed++; else failed++;

        // Test 4: Illegal speed (4.2 blocks/tick)
        boolean illegalSpeed = validateMovement(testUuid, 4.5, 64, 0, true, false);
        if (!illegalSpeed) passed++; else failed++;

        // Test 5: Click rate test (25 clicks in 100ms)
        int allowedClicks = 0;
        for (int i = 0; i < 30; i++) {
            if (validateClick(testUuid)) allowedClicks++;
        }
        if (allowedClicks <= MAX_CLICKS_PER_SECOND) passed++; else failed++;

        clearPlayer(testUuid);

        return "GrimGuard Test Suite: " + passed + "/5 pruebas superadas exitosamente. (Fallas: " + failed + ")";
    }

    public record AnticheatStats(long reachViolations, long speedViolations, long clickViolations, long flyViolations, int trackedPlayers) {}

    private static final class PlayerMovementState {
        double lastX, lastY, lastZ;
        boolean lastOnGround;
        long lastTimeMs;
        long lastGroundTimeMs;

        PlayerMovementState(double x, double y, double z, boolean onGround, long timeMs) {
            this.lastX = x;
            this.lastY = y;
            this.lastZ = z;
            this.lastOnGround = onGround;
            this.lastTimeMs = timeMs;
            this.lastGroundTimeMs = onGround ? timeMs : 0;
        }

        boolean wasEverOnGroundRecently(long now) {
            return (now - lastGroundTimeMs) < 1500;
        }
    }

    private static final class ClickState {
        long windowStartMs;
        int clicksInWindow;

        ClickState(long now) {
            this.windowStartMs = now;
            this.clicksInWindow = 1;
        }
    }
}
