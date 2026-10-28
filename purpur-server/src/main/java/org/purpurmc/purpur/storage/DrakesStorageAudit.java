package org.purpurmc.purpur.storage;

import java.sql.PreparedStatement;
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
        DrakesDb db = DrakesDb.get();
        if (db == null) {
            LOGGER.log(Level.WARNING, "[StorageAudit] DrakesDb no disponible; auditoria desactivada");
            return;
        }
        try {
            db.execNow("CREATE TABLE IF NOT EXISTS storage_actions ("
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
                + "amount INTEGER NOT NULL DEFAULT 1)");
            db.execNow("CREATE INDEX IF NOT EXISTS idx_storage_coords ON storage_actions (world, x, y, z)");
            DrakesDbMigration.importLegacy(db, DB_PATH, "storage_actions");
            initialized = true;
            LOGGER.info("[StorageAudit] Kernel Storage Forensics active (tabla storage_actions en DrakesDb)");
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[StorageAudit] Failed to initialize audit table: " + t.getMessage());
        }
    }

    public static void logAction(UUID playerUuid, String playerName, String world, int x, int y, int z,
                                 String blockType, String action, String itemType, int amount) {
        if (!initialized) return;
        long now = System.currentTimeMillis();
        String name = playerName != null ? playerName : "unknown";
        String item = itemType != null ? itemType : "AIR";
        DrakesDb.get().write(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO storage_actions (timestamp, player_uuid, player_name, world, x, y, z, block_type, action, item_type, amount) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                ps.setLong(1, now);
                ps.setString(2, playerUuid.toString());
                ps.setString(3, name);
                ps.setString(4, world);
                ps.setInt(5, x);
                ps.setInt(6, y);
                ps.setInt(7, z);
                ps.setString(8, blockType);
                ps.setString(9, action);
                ps.setString(10, item);
                ps.setInt(11, amount);
                ps.executeUpdate();
            }
        });
    }
}
