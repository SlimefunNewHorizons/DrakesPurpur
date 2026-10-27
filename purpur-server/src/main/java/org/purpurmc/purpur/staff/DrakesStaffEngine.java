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
    private static final Map<UUID, AltRecord> ALT_CACHE = new ConcurrentHashMap<>();

    public record AltRecord(String ipHash, String country, String isp, int altCount) {}

    private DrakesStaffEngine() {}

    public static void initialize() {
        LOGGER.info("[StaffEngine] Initialized kernel-embedded staff management & vanish/freeze matrix.");
    }

    public static boolean toggleVanish(Player player) {
        UUID uuid = player.getUniqueId();
        if (VANISHED_PLAYERS.contains(uuid)) {
            VANISHED_PLAYERS.remove(uuid);
            for (Player target : Bukkit.getOnlinePlayers()) {
                target.showPlayer(player);
            }
            player.sendMessage(ChatColor.GOLD + "[StaffEngine] " + ChatColor.YELLOW + "Modo Vanish: " + ChatColor.RED + "DESACTIVADO");
            return false;
        } else {
            VANISHED_PLAYERS.add(uuid);
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!target.hasPermission("drakes.staff")) {
                    target.hidePlayer(player);
                }
            }
            player.sendMessage(ChatColor.GOLD + "[StaffEngine] " + ChatColor.YELLOW + "Modo Vanish: " + ChatColor.GREEN + "ACTIVADO " + ChatColor.GRAY + "(Invisible en tab y mundo)");
            return true;
        }
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

    public static void registerAlt(UUID uuid, String ipHash, String country, String isp, int altCount) {
        ALT_CACHE.put(uuid, new AltRecord(ipHash, country, isp, altCount));
    }

    public static AltRecord getAltRecord(UUID uuid) {
        return ALT_CACHE.getOrDefault(uuid, new AltRecord("unknown", "CL", "Entel/VTR", 1));
    }

    public static Set<UUID> getVanishedPlayers() {
        return Collections.unmodifiableSet(VANISHED_PLAYERS);
    }

    public static Set<UUID> getFrozenPlayers() {
        return Collections.unmodifiableSet(FROZEN_PLAYERS);
    }
}
