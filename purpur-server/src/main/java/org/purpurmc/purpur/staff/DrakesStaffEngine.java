package org.purpurmc.purpur.staff;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

/**
 * DrakesStaffEngine — Sovereign Staff Suite embedded into Purpur Kernel.
 * Features:
 * - Native packet-level Vanish (/vani, /vanish).
 * - Instant Movement-level Freeze (/freeze).
 * - Alt & ISP Telemetry tracking in SQLite WAL.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesStaffEngine {
    private static final Logger LOGGER = Logger.getLogger("DrakesStaffEngine");
    private static final Set<UUID> VANISHED_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> FROZEN_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> SOVEREIGN_GOD_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<UUID, AltRecord> ALT_CACHE = new ConcurrentHashMap<>();

    public record AltRecord(String ipHash, String country, String isp, int altCount) {}

    private DrakesStaffEngine() {}

    public static void initialize() {
        LOGGER.info("[StaffEngine] Initialized kernel-embedded staff management, vanish/freeze matrix & sovereign godmode.");
    }

    public static boolean toggleVanish(Player player) {
        UUID uuid = player.getUniqueId();
        if (VANISHED_PLAYERS.contains(uuid)) {
            VANISHED_PLAYERS.remove(uuid);
            for (Player target : Bukkit.getOnlinePlayers()) {
                target.showPlayer(player);
            }
            player.setSleepingIgnored(false);
            player.setCanPickupItems(true);
            player.setCollidable(true);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aVanish &7desactivado&a. Volviste a ser visible."));
            return false;
        } else {
            VANISHED_PLAYERS.add(uuid);
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!target.hasPermission("odysseia.vanish.see") && !target.hasPermission("drakes.staff")) {
                    target.hidePlayer(player);
                }
            }
            player.setSleepingIgnored(true);
            player.setCanPickupItems(false);
            player.setCollidable(false);
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', "&aVanish &2activado&a. Quedaste oculto para jugadores sin permiso de staff."));
            return true;
        }
    }

    public static boolean toggleSovereignGod(Player player) {
        UUID uuid = player.getUniqueId();
        if (SOVEREIGN_GOD_PLAYERS.contains(uuid)) {
            SOVEREIGN_GOD_PLAYERS.remove(uuid);
            player.setInvulnerable(false);
            player.sendMessage(ChatColor.DARK_RED + "✦ [SovereignGod] Inmunidad absoluta DESACTIVADA.");
            return false;
        } else {
            SOVEREIGN_GOD_PLAYERS.add(uuid);
            player.setInvulnerable(true);
            player.sendMessage(ChatColor.GOLD + "✦ [SovereignGod] Inmunidad absoluta ACTIVADA (Invulnerable ante cualquier daño/void).");
            return true;
        }
    }

    public static boolean isSovereignGod(UUID uuid) {
        return SOVEREIGN_GOD_PLAYERS.contains(uuid);
    }

    public static boolean isVanished(UUID uuid) {
        return VANISHED_PLAYERS.contains(uuid);
    }

    public static boolean toggleFreeze(Player target, Player moderator) {
        UUID uuid = target.getUniqueId();
        if (FROZEN_PLAYERS.contains(uuid)) {
            FROZEN_PLAYERS.remove(uuid);
            target.sendMessage(ChatColor.GREEN + "[Staff] Has sido descongelado por el staff.");
            if (moderator != null) {
                moderator.sendMessage(ChatColor.GOLD + "[StaffEngine] " + ChatColor.GREEN + "Has descongelado a " + target.getName());
            }
            return false;
        } else {
            FROZEN_PLAYERS.add(uuid);
            target.sendMessage(ChatColor.RED + "====================================================");
            target.sendMessage(ChatColor.DARK_RED + "  ⚠️ HAS SIDO CONGELADO POR UN MIEMBRO DEL STAFF  ");
            target.sendMessage(ChatColor.YELLOW + "  No te desconectes o serás sancionado permanentemente.");
            target.sendMessage(ChatColor.RED + "====================================================");
            if (moderator != null) {
                moderator.sendMessage(ChatColor.GOLD + "[StaffEngine] " + ChatColor.RED + "Has congelado a " + target.getName());
            }
            return true;
        }
    }

    public static boolean isFrozen(UUID uuid) {
        return FROZEN_PLAYERS.contains(uuid);
    }

    public static void registerAlt(UUID uuid, String playerName, String ip, String country, String isp) {
        String ipClean = ip != null ? ip.replace("/", "").split(":")[0] : "127.0.0.1";
        org.purpurmc.purpur.threads.RyzenWorkerPool.runAsync(() -> {
            try {
                Class.forName("org.sqlite.JDBC");
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:drakes_staff_telemetry.db");
                     java.sql.Statement stmt = conn.createStatement()) {
                    stmt.execute("PRAGMA journal_mode = WAL;");
                    stmt.execute("CREATE TABLE IF NOT EXISTS player_ip_history ("
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL, ip TEXT NOT NULL, country TEXT, isp TEXT, last_seen INTEGER, "
                        + "PRIMARY KEY(uuid, ip));");
                }
                try (java.sql.Connection conn = java.sql.DriverManager.getConnection("jdbc:sqlite:drakes_staff_telemetry.db");
                     java.sql.PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO player_ip_history (uuid, name, ip, country, isp, last_seen) VALUES (?, ?, ?, ?, ?, ?) "
                         + "ON CONFLICT(uuid, ip) DO UPDATE SET last_seen = excluded.last_seen;");
                     java.sql.PreparedStatement countPs = conn.prepareStatement(
                         "SELECT COUNT(DISTINCT uuid) FROM player_ip_history WHERE ip = ?;")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, playerName);
                    ps.setString(3, ipClean);
                    ps.setString(4, country != null ? country : "CL");
                    ps.setString(5, isp != null ? isp : "ISP");
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();

                    countPs.setString(1, ipClean);
                    int alts = 1;
                    try (java.sql.ResultSet rs = countPs.executeQuery()) {
                        if (rs.next()) alts = rs.getInt(1);
                    }
                    ALT_CACHE.put(uuid, new AltRecord(ipClean, country != null ? country : "CL", isp != null ? isp : "ISP", alts));
                }
            } catch (Throwable t) {
                LOGGER.fine("[StaffEngine] Error registering player IP telemetry: " + t.getMessage());
            }
        });
    }

    public static String getStaffInspectionLine(Player target) {
        AltRecord record = getAltRecord(target.getUniqueId());
        return ChatColor.DARK_AQUA + "[" + target.getName() + "] "
            + ChatColor.GRAY + "Alts: " + (record.altCount() > 1 ? ChatColor.RED : ChatColor.GREEN) + record.altCount() + " "
            + ChatColor.GRAY + "| IP: " + ChatColor.YELLOW + record.ipHash() + " "
            + ChatColor.GRAY + "| Pais: " + ChatColor.AQUA + record.country() + " "
            + ChatColor.GRAY + "| Red: " + ChatColor.LIGHT_PURPLE + record.isp();
    }

    public static AltRecord getAltRecord(UUID uuid) {
        return ALT_CACHE.getOrDefault(uuid, new AltRecord("127.0.0.1", "CL", "Local/Fiber", 1));
    }

    public static Set<UUID> getVanishedPlayers() {
        return Collections.unmodifiableSet(VANISHED_PLAYERS);
    }

    public static Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(FROZEN_PLAYERS);
    }
}
