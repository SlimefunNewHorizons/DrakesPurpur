package org.purpurmc.purpur.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Prueba la migracion de los .db legacy (drakes_faults.db, drakes_storage_audit.db) al kernel.db (DrakesDb).
 * Con {@code -Ddrakes.test.legacyDir=<dir>} usa copias de bases reales (drakes_faults.db / drakes_storage_audit.db);
 * si no, genera datos sinteticos de volumen realista. Todo ocurre en un directorio temporal.
 */
class DrakesDbMigrationTest {

    private static final String STORAGE_DDL = "CREATE TABLE storage_actions (id INTEGER PRIMARY KEY AUTOINCREMENT, "
        + "timestamp INTEGER NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, world TEXT NOT NULL, "
        + "x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, block_type TEXT NOT NULL, action TEXT NOT NULL, "
        + "item_type TEXT, amount INTEGER NOT NULL DEFAULT 1)";
    private static final String FAULTS_DDL = "CREATE TABLE kernel_faults (id INTEGER PRIMARY KEY AUTOINCREMENT, "
        + "context TEXT NOT NULL, source_desc TEXT, error_message TEXT, stacktrace TEXT, timestamp INTEGER NOT NULL, "
        + "isolated INTEGER NOT NULL DEFAULT 1)";
    private static final int STORAGE_ROWS = Integer.getInteger("drakes.test.rows", 200_000);
    private static final int FAULT_ROWS = 5_000;

    private static Path root;
    private static String kernelPath;
    private Path work;

    @BeforeAll
    static void boot() throws Exception {
        root = Files.createTempDirectory("drakesdb-mig");
        kernelPath = root.resolve("drakes-data/kernel.db").toString();
        System.setProperty("drakes.db.path", kernelPath);
        DrakesDb.initialize();
        assertNotNull(DrakesDb.get(), "DrakesDb debe abrir");
        DrakesDb db = DrakesDb.get();
        db.execNow("CREATE TABLE IF NOT EXISTS " + STORAGE_DDL.substring("CREATE TABLE ".length()));
        db.execNow("CREATE TABLE IF NOT EXISTS " + FAULTS_DDL.substring("CREATE TABLE ".length()));
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (root != null) {
            try (Stream<Path> s = Files.walk(root)) {
                s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @BeforeEach
    void fresh() throws Exception {
        work = Files.createTempDirectory(root, "legacy");
        DrakesDb.get().execNow("DELETE FROM storage_actions");
        DrakesDb.get().execNow("DELETE FROM kernel_faults");
        DrakesDb.get().execNow("DELETE FROM sqlite_sequence");
    }

    // ---- utilidades ----

    private static Connection conn(Path p) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + p);
    }

    private static long count(Connection c, String table) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long kernelCount(String table) throws SQLException {
        return DrakesDb.get().read(c -> count(c, table));
    }

    private Path legacyStorage() throws Exception {
        Path p = work.resolve("drakes_storage_audit.db");
        String real = System.getProperty("drakes.test.legacyDir");
        if (real != null && Files.isRegularFile(Path.of(real, "drakes_storage_audit.db"))) {
            Files.copy(Path.of(real, "drakes_storage_audit.db"), p, StandardCopyOption.REPLACE_EXISTING);
            return p;
        }
        try (Connection c = conn(p)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(STORAGE_DDL);
                st.execute("CREATE INDEX idx_storage_coords ON storage_actions (world, x, y, z)");
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO storage_actions (timestamp, player_uuid, player_name, world, x, y, z, block_type, action, item_type, amount) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
                String[] worlds = {"world", "world_nether", "clasico", "oneblock_world", "bskyblock_world"};
                String[] names = {"JackStar6677", "Ñandú_77", "María", "Zoë", "x'; DROP TABLE kv;--", "日本語"};
                for (int i = 0; i < STORAGE_ROWS; i++) {
                    ps.setLong(1, 1_760_000_000_000L + i * 997L);
                    ps.setString(2, "00000000-0000-4000-8000-" + String.format("%012d", i % 300));
                    ps.setString(3, names[i % names.length]);
                    ps.setString(4, worlds[i % worlds.length]);
                    ps.setInt(5, (i * 31) % 20000 - 10000);
                    ps.setInt(6, (i % 320) - 64);
                    ps.setInt(7, (i * 17) % 20000 - 10000);
                    ps.setString(8, i % 7 == 0 ? "BARREL" : "CHEST");
                    ps.setString(9, i % 3 == 0 ? "TAKE" : "PUT");
                    if (i % 11 == 0) ps.setNull(10, java.sql.Types.VARCHAR); else ps.setString(10, "DIAMOND_ORE");
                    ps.setInt(11, 1 + i % 64);
                    ps.addBatch();
                    if (i % 5000 == 4999) ps.executeBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
        return p;
    }

    private Path legacyFaults() throws Exception {
        Path p = work.resolve("drakes_faults.db");
        String real = System.getProperty("drakes.test.legacyDir");
        if (real != null && Files.isRegularFile(Path.of(real, "drakes_faults.db"))) {
            Files.copy(Path.of(real, "drakes_faults.db"), p, StandardCopyOption.REPLACE_EXISTING);
            return p;
        }
        try (Connection c = conn(p)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.execute(FAULTS_DDL);
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kernel_faults (context, source_desc, error_message, stacktrace, timestamp, isolated) VALUES (?,?,?,?,?,?)")) {
                String trace = "java.lang.NullPointerException: x\n\tat org.purpurmc.purpur.Foo.bar(Foo.java:12)\n".repeat(60);
                for (int i = 0; i < FAULT_ROWS; i++) {
                    ps.setString(1, "tick");
                    if (i % 4 == 0) ps.setNull(2, java.sql.Types.VARCHAR); else ps.setString(2, "block@" + i);
                    ps.setString(3, "fallo " + i + " ñ");
                    ps.setString(4, trace);
                    ps.setLong(5, 1_760_000_000_000L + i);
                    ps.setInt(6, i % 2);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
        return p;
    }

    private static void assertIntegrity() throws SQLException {
        String r = DrakesDb.get().read(c -> {
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("PRAGMA integrity_check")) {
                rs.next();
                return rs.getString(1);
            }
        });
        assertEquals("ok", r, "integrity_check del kernel.db");
    }

    /** Filas del legacy que NO estan identicas en el kernel (0 = copia fiel, ids incluidos). */
    private static long missingFromKernel(Path legacyOrMigrated, String table) throws SQLException {
        return DrakesDb.get().read(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("ATTACH DATABASE '" + legacyOrMigrated.toString().replace("'", "''") + "' AS chk");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM (SELECT * FROM chk." + table + " EXCEPT SELECT * FROM main." + table + ")")) {
                    rs.next();
                    return rs.getLong(1);
                } finally {
                    st.execute("DETACH DATABASE chk");
                }
            }
        });
    }

    // ---- pruebas ----

    @Test
    void migraVolumenCompletoYRenombra() throws Exception {
        Path s = legacyStorage();
        Path f = legacyFaults();
        long expS;
        long expF;
        try (Connection c = conn(s)) { expS = count(c, "storage_actions"); }
        try (Connection c = conn(f)) { expF = count(c, "kernel_faults"); }

        long t0 = System.nanoTime();
        DrakesDbMigration.importLegacy(DrakesDb.get(), s.toString(), "storage_actions");
        DrakesDbMigration.importLegacy(DrakesDb.get(), f.toString(), "kernel_faults");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        System.out.println("[MIGRACION] storage_actions=" + expS + " kernel_faults=" + expF + " tiempo=" + ms + " ms, legacy="
            + (Files.size(Path.of(s + ".migrated")) + Files.size(Path.of(f + ".migrated"))) / 1024 + " KiB");

        assertEquals(expS, kernelCount("storage_actions"));
        assertEquals(expF, kernelCount("kernel_faults"));
        assertFalse(Files.exists(s), "original debe desaparecer");
        assertFalse(Files.exists(f));
        assertTrue(Files.exists(Path.of(s + ".migrated")));
        assertTrue(Files.exists(Path.of(f + ".migrated")));
        assertEquals(0, missingFromKernel(Path.of(s + ".migrated"), "storage_actions"), "contenido identico");
        assertEquals(0, missingFromKernel(Path.of(f + ".migrated"), "kernel_faults"), "contenido identico");
        assertIntegrity();
    }

    @Test
    void esIdempotenteSiElRenombradoFallo() throws Exception {
        Path s = legacyStorage();
        long exp;
        try (Connection c = conn(s)) { exp = count(c, "storage_actions"); }
        DrakesDbMigration.importLegacy(DrakesDb.get(), s.toString(), "storage_actions");
        // segunda pasada sin original: no-op
        DrakesDbMigration.importLegacy(DrakesDb.get(), s.toString(), "storage_actions");
        assertEquals(exp, kernelCount("storage_actions"));
        // simula caida entre INSERT y rename: el original reaparece
        Files.copy(Path.of(s + ".migrated"), s, StandardCopyOption.REPLACE_EXISTING);
        DrakesDbMigration.importLegacy(DrakesDb.get(), s.toString(), "storage_actions");
        assertEquals(exp, kernelCount("storage_actions"), "no debe duplicar ni lanzar");
        assertFalse(Files.exists(s), "debe quedar renombrado tras reintento");
        assertIntegrity();
    }

    @Test
    void noPierdeUnMigratedPrevio() throws Exception {
        Path s = legacyStorage();
        Path previo = Path.of(s + ".migrated");
        Files.writeString(previo, "respaldo-anterior");
        DrakesDbMigration.importLegacy(DrakesDb.get(), s.toString(), "storage_actions");
        assertEquals("respaldo-anterior", Files.readString(previo), ".migrated previo intacto");
        assertFalse(Files.exists(s));
        try (Stream<Path> l = Files.list(work)) {
            assertTrue(l.filter(p -> p.getFileName().toString().contains(".migrated")).count() >= 2);
        }
    }

    @Test
    void legacyConEsquemaAntiguoYSinTabla() throws Exception {
        Path old = work.resolve("viejo.db");
        try (Connection c = conn(old); Statement st = c.createStatement()) {
            // sin item_type ni amount
            st.execute("CREATE TABLE storage_actions (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, player_uuid TEXT NOT NULL, player_name TEXT NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, block_type TEXT NOT NULL, action TEXT NOT NULL)");
            st.execute("INSERT INTO storage_actions (timestamp, player_uuid, player_name, world, x, y, z, block_type, action) VALUES (1,'u','n','w',1,2,3,'CHEST','PUT'),(2,'u','n','w',1,2,3,'CHEST','TAKE')");
        }
        DrakesDbMigration.importLegacy(DrakesDb.get(), old.toString(), "storage_actions");
        assertEquals(2, kernelCount("storage_actions"), "esquema antiguo importa columnas comunes");
        assertFalse(Files.exists(old));

        Path vacio = work.resolve("vacio.db");
        try (Connection c = conn(vacio); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE otra (a)");
        }
        DrakesDbMigration.importLegacy(DrakesDb.get(), vacio.toString(), "storage_actions");
        assertEquals(2, kernelCount("storage_actions"));
        assertFalse(Files.exists(vacio), "sin la tabla no debe reintentarse eternamente");
    }

    @Test
    void legacyEnWalNoDejaSidecars() throws Exception {
        Path w = work.resolve("wal.db");
        try (Connection c = conn(w); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute(FAULTS_DDL);
            st.execute("INSERT INTO kernel_faults (context, timestamp) VALUES ('a',1),('b',2)");
        }
        DrakesDbMigration.importLegacy(DrakesDb.get(), w.toString(), "kernel_faults");
        assertEquals(2, kernelCount("kernel_faults"));
        assertFalse(Files.exists(Path.of(w + "-wal")), "sin -wal huerfano");
        assertFalse(Files.exists(Path.of(w + "-shm")), "sin -shm huerfano");
    }

    @Test
    void conflictoDeIdsNoBloqueaElArranque() throws Exception {
        // el kernel ya tiene la fila id=1 (p. ej. migracion previa a medias); el legacy trae id=1 y id=2
        DrakesDb.get().execNow("INSERT INTO kernel_faults (id, context, timestamp) VALUES (1,'a',1)");
        Path p = work.resolve("c.db");
        try (Connection c = conn(p); Statement st = c.createStatement()) {
            st.execute(FAULTS_DDL);
            st.execute("INSERT INTO kernel_faults (id, context, timestamp) VALUES (1,'a',1),(2,'b',2)");
        }
        DrakesDbMigration.importLegacy(DrakesDb.get(), p.toString(), "kernel_faults");
        assertEquals(2, kernelCount("kernel_faults"));
        assertFalse(Files.exists(p));
    }
}
