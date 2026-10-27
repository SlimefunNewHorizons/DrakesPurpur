package org.purpurmc.purpur.storage;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DrakesStorageAudit — Lightweight CoreProtect replacement embedded in Purpur Kernel.
 * Tracks interactions with Chests, Barrels, Slimefun Cargo Nodes, and Multiverse storages.
 * Commits audit logs asynchronously to SQLite WAL without causing block-break lag.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesStorageAudit {
    private static final Logger LOGGER = Logger.getLogger("DrakesStorageAudit");
    private static final String DB_PATH = "drakes_storage_audit.db";
    private static volatile boolean initialized = false;

    private DrakesStorageAudit() {}

    public static void initialize() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL;");
                stmt.execute("PRAGMA synchronous = NORMAL;");
                stmt.execute("CREATE TABLE IF NOT EXISTS storage_actions ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "timestamp INTEGER NOT NULL, "
                    + "player_uuid TEXT NOT NULL, "
                    + "player_name TEXT NOT NULL, "
                    + "world TEXT NOT NULL, "
                    + "x INTEGER NOT NULL, "
                    + "y INTEGER NOT NULL, "
                    + "z INTEGER NOT NULL, "
                    + "block_type TEXT NOT NULL, "
                    + "action TEXT NOT NULL, "
                    + "item_type TEXT, "
                    + "amount INTEGER NOT NULL DEFAULT 1);");
                stmt.execute("CREATE INDEX IF NOT EXISTS idx_storage_coords ON storage_actions (world, x, y, z);");
            }
            initialized = true;
            LOGGER.info("[StorageAudit] Kernel Storage Forensics active. SQLite DB: " + DB_PATH);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[StorageAudit] Failed to initialize SQLite audit DB: " + t.getMessage());
        }
    }

    public static void logAction(UUID playerUuid, String playerName, String world, int x, int y, int z,
                                 String blockType, String action, String itemType, int amount) {
        if (!initialized) return;

        org.purpurmc.purpur.threads.RyzenWorkerPool.runAsync(() -> {
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
                 PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO storage_actions (timestamp, player_uuid, player_name, world, x, y, z, block_type, action, item_type, amount) "
                     + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setString(2, playerUuid.toString());
                ps.setString(3, playerName != null ? playerName : "unknown");
                ps.setString(4, world);
                ps.setInt(5, x);
                ps.setInt(6, y);
                ps.setInt(7, z);
                ps.setString(8, blockType);
                ps.setString(9, action);
                ps.setString(10, itemType != null ? itemType : "AIR");
                ps.setInt(11, amount);
                ps.executeUpdate();
            } catch (Throwable t) {
                LOGGER.fine("[StorageAudit] Error recording storage action: " + t.getMessage());
            }
        });
    }
}
