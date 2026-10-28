package org.purpurmc.purpur.storage;

import java.io.File;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/** Importa una sola vez las filas de un {@code .db} antiguo (una tabla) a {@link DrakesDb} y lo renombra a {@code .migrated}. */
final class DrakesDbMigration {

    private static final Logger LOGGER = Logger.getLogger("DrakesDb");

    private DrakesDbMigration() {}

    static void importLegacy(DrakesDb db, String legacyPath, String table) throws SQLException {
        File legacy = new File(legacyPath);
        if (!legacy.isFile()) {
            return;
        }
        db.execNow("ATTACH DATABASE '" + legacy.getPath().replace("'", "''") + "' AS legacy");
        try {
            db.execNow("INSERT INTO main." + table + " SELECT * FROM legacy." + table);
        } finally {
            db.execNow("DETACH DATABASE legacy");
        }
        File done = new File(legacyPath + ".migrated");
        if (legacy.renameTo(done)) {
            LOGGER.info("[DrakesDb] Importada la tabla " + table + " desde " + legacyPath + " (renombrado a .migrated)");
        }
    }
}
