package org.purpurmc.purpur.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;
import java.util.logging.Logger;
import org.purpurmc.purpur.saori.SaoriKernelSocket;

/**
 * DrakesChatGuard — Kernel-level Chat & Command Security Guard
 * Eradicates external chat protection plugins by handling spam rate-limits,
 * repetition flood, toxic vocabulary masking, command injection and crash packets.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesChatGuard {
    private static final Logger LOGGER = Logger.getLogger("DrakesChatGuard");
    private static volatile boolean enabled = true;

    // Telemetry
    private static final AtomicLong BLOCKED_SPAM_COUNT = new AtomicLong(0);
    private static final AtomicLong BLOCKED_TOXIC_COUNT = new AtomicLong(0);
    private static final AtomicLong BLOCKED_COMMAND_EXPLOITS = new AtomicLong(0);

    // Spam tracking
    private static final Map<UUID, UserChatHistory> CHAT_HISTORY = new ConcurrentHashMap<>();
    private static final long MIN_MESSAGE_INTERVAL_MS = 600; // max ~1.6 msg/sec

    // Prohibited patterns & command exploits
    private static final Set<String> BLOCKED_COMMAND_PREFIXES = Set.of(
        "/op", "/deop", "/calc", "/solve", "/eval", "/pex", "/litebans:eval", "//calc",
        "/minecraft:execute", "/execute", "/bukkit:op", "/bukkit:eval"
    );

    // Vocabulary & Toxicity Regex (Bilingual ES/EN filters)
    private static final Pattern TOXICITY_PATTERN = Pattern.compile(
        "(?i)\\b(nigger|nigga|hitler|nazi|maricon|culiao|ctm|conchetumadre|chucha|puta|weon|aweonao|perra|bastardo)\\b"
    );

    private DrakesChatGuard() {}

    public static boolean isEnabled() {
        return enabled;
    }

    public static void setEnabled(boolean state) {
        enabled = state;
    }

    /**
     * Inspects and filters a chat message.
     * @return ChatValidationResult containing whether to cancel, and sanitized message.
     */
    public static ChatValidationResult processChatMessage(UUID playerUuid, String rawMessage) {
        if (!enabled || rawMessage == null) {
            return new ChatValidationResult(true, rawMessage, null);
        }

        long now = System.currentTimeMillis();
        UserChatHistory history = CHAT_HISTORY.computeIfAbsent(playerUuid, u -> new UserChatHistory());

        // 1. Rate-limit & Repetition Check (Anti-Spam)
        if (now - history.lastMessageTimeMs < MIN_MESSAGE_INTERVAL_MS) {
            BLOCKED_SPAM_COUNT.incrementAndGet();
            return new ChatValidationResult(false, null, "Estás escribiendo demasiado rápido. Espera un momento.");
        }

        if (rawMessage.equalsIgnoreCase(history.lastMessageContent) && (now - history.lastMessageTimeMs) < 3000) {
            BLOCKED_SPAM_COUNT.incrementAndGet();
            return new ChatValidationResult(false, null, "No repitas el mismo mensaje de forma consecutiva.");
        }

        // Update history
        history.lastMessageTimeMs = now;
        history.lastMessageContent = rawMessage;

        // 2. Vocabulary & Toxicity Filter (Masking)
        String sanitized = rawMessage;
        var matcher = TOXICITY_PATTERN.matcher(rawMessage);
        if (matcher.find()) {
            BLOCKED_TOXIC_COUNT.incrementAndGet();
            sanitized = matcher.replaceAll("§8[censurado]§r");
            SaoriKernelSocket.dispatchFault("ChatGuard-Toxicity", playerUuid.toString(), "Mensaje filtrado: " + rawMessage);
        }

        return new ChatValidationResult(true, sanitized, null);
    }

    /**
     * Inspects commands before execution to prevent syntax injection and server crashers.
     * @return true if command is safe, false if dropped.
     */
    public static boolean validateCommand(UUID playerUuid, String commandLine, boolean isOp) {
        if (!enabled || commandLine == null) return true;

        String lower = commandLine.trim().toLowerCase();

        // 1. Crash packet prevention (excessive length / control character bombs)
        if (commandLine.length() > 256) {
            BLOCKED_COMMAND_EXPLOITS.incrementAndGet();
            LOGGER.warning("[ChatGuard] Comando descartado por longitud excesiva (" + commandLine.length() + " chars) de " + playerUuid);
            return false;
        }

        // 2. Block prohibited syntax injection
        for (String blocked : BLOCKED_COMMAND_PREFIXES) {
            if (lower.startsWith(blocked + " ") || lower.equals(blocked)) {
                if (!isOp) {
                    BLOCKED_COMMAND_EXPLOITS.incrementAndGet();
                    LOGGER.warning("[ChatGuard] Bloqueado intento de comando peligroso '" + blocked + "' de " + playerUuid);
                    SaoriKernelSocket.dispatchFault("ChatGuard-CommandExploit", playerUuid.toString(), "blocked-prefix: " + blocked);
                    return false;
                }
            }
        }

        return true;
    }

    public static SecurityStats getStats() {
        return new SecurityStats(
            BLOCKED_SPAM_COUNT.get(),
            BLOCKED_TOXIC_COUNT.get(),
            BLOCKED_COMMAND_EXPLOITS.get()
        );
    }

    public static String runSyntheticTest() {
        UUID testUuid = UUID.fromString("00000000-0000-0000-0000-000000000002");
        int passed = 0;
        int failed = 0;

        // Test 1: Normal message
        ChatValidationResult r1 = processChatMessage(testUuid, "Hola a todos en DrakesCraft");
        if (r1.allowed() && r1.sanitizedMessage().equals("Hola a todos en DrakesCraft")) passed++; else failed++;

        // Test 2: Rapid spam (immediate second message)
        ChatValidationResult r2 = processChatMessage(testUuid, "Spam inmediato");
        if (!r2.allowed()) passed++; else failed++;

        // Test 3: Toxicity censorship
        try { Thread.sleep(650); } catch (InterruptedException ignored) {}
        ChatValidationResult r3 = processChatMessage(testUuid, "eres un weon tonto");
        if (r3.allowed() && r3.sanitizedMessage().contains("[censurado]")) passed++; else failed++;

        // Test 4: Command injection block
        boolean cmd1 = validateCommand(testUuid, "/op hacker", false);
        if (!cmd1) passed++; else failed++;

        // Test 5: Safe command
        boolean cmd2 = validateCommand(testUuid, "/spawn", false);
        if (cmd2) passed++; else failed++;

        CHAT_HISTORY.remove(testUuid);

        return "ChatGuard Test Suite: " + passed + "/5 pruebas superadas exitosamente. (Fallas: " + failed + ")";
    }

    public record ChatValidationResult(boolean allowed, String sanitizedMessage, String rejectionReason) {}
    public record SecurityStats(long blockedSpam, long blockedToxicity, long blockedExploits) {}

    private static final class UserChatHistory {
        long lastMessageTimeMs = 0;
        String lastMessageContent = "";
    }
}
