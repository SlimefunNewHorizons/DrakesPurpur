package org.purpurmc.purpur.storage;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Importa una sola vez las filas de un {@code .db} antiguo (una tabla) a {@link DrakesDb} y lo renombra a {@code .migrated}.
 * Es idempotente: si el proceso cae entre el INSERT y el renombrado, el reintento ignora las filas ya importadas (misma PK).
 */
final class DrakesDbMigration {

    private static final Logger LOGGER = Logger.getLogger("DrakesDb");

    private DrakesDbMigration() {}

    private static String q(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private static List<String> columns(Connection c, String table) throws SQLException {
        List<String> cols = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA table_info(" + q(table) + ")")) {
            while (rs.next()) {
                cols.add(rs.getString("name"));
            }
        }
        return cols;
    }

    static void importLegacy(DrakesDb db, String legacyPath, String table) throws SQLException {
        File legacy = new File(legacyPath);
        if (!legacy.isFile()) {
            return;
        }
        // Columnas del legacy leidas con conexion propia (solo lectura de esquema); se importan las comunes.
        List<String> legacyCols;
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + legacy.getPath())) {
            legacyCols = columns(c, table);
        }
        if (legacyCols.isEmpty()) {
            LOGGER.warning("[DrakesDb] " + legacyPath + " no contiene la tabla " + table + "; se archiva sin importar");
        } else {
            Set<String> mainCols = new LinkedHashSet<>(db.read(c -> columns(c, table)));
            List<String> common = new ArrayList<>();
            for (String col : legacyCols) {
                if (mainCols.contains(col)) {
                    common.add(col);
                }
            }
            if (common.isEmpty()) {
                throw new SQLException("Sin columnas comunes entre " + legacyPath + " y " + table);
            }
            StringBuilder list = new StringBuilder();
            for (String col : common) {
                list.append(list.length() == 0 ? "" : ",").append(q(col));
            }
            db.execNow("ATTACH DATABASE '" + legacy.getPath().replace("'", "''") + "' AS legacy");
            try {
                // OR IGNORE: un reintento tras caida a medias no duplica ni se atasca en la PK.
                db.execNow("INSERT OR IGNORE INTO main." + q(table) + " (" + list + ") SELECT " + list + " FROM legacy." + q(table));
            } finally {
                db.execNow("DETACH DATABASE legacy");
            }
        }
        archive(legacy.toPath(), table, legacyPath);
    }

    /** Renombra a .migrated sin pisar un .migrated anterior (se le anade marca de tiempo). */
    private static void archive(Path legacy, String table, String legacyPath) {
        Path done = Path.of(legacyPath + ".migrated");
        if (Files.exists(done)) {
            done = Path.of(legacyPath + ".migrated." + System.currentTimeMillis());
        }
        try {
            Files.move(legacy, done);
            LOGGER.info("[DrakesDb] Importada la tabla " + table + " desde " + legacyPath + " (renombrado a " + done.getFileName() + ")");
        } catch (Exception e) {
            LOGGER.warning("[DrakesDb] Importada " + table + " pero no se pudo renombrar " + legacyPath + ": " + e
                + " (el proximo arranque lo reintenta sin duplicar)");
        }
    }
}
