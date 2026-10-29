package org.purpurmc.purpur.storage;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * DrakesDb: base de datos SQLite única (WAL) del kernel, compartida por el propio kernel (fallos, auditoría) y por
 * cualquier suite o plugin, que la obtiene con {@code DrakesDb.get()} (las clases del servidor son visibles para los plugins).
 * Sustituye a un {@code .db} por plugin y a abrir una conexión por inserción.
 *
 * <ul>
 *   <li>Escrituras: {@link #write(SqlWork)} las encola; un único hilo las agrupa en transacciones (cientos de inserciones por commit).</li>
 *   <li>Lecturas: {@link #read(SqlQuery)} usan una conexión aparte (WAL permite leer mientras se escribe).</li>
 *   <li>Almacén clave-valor por espacio de nombres: {@link #kv(String)}.</li>
 * </ul>
 * El mismo código sirve a la versión modular y al monolito: ambas arrancan el kernel, y las suites solo ven esta API.
 */
public final class DrakesDb {

    @FunctionalInterface
    public interface SqlWork {
        void run(Connection c) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlQuery<T> {
        T run(Connection c) throws SQLException;
    }

    private static final Logger LOGGER = Logger.getLogger("DrakesDb");
    /** Ruta de la base; {@code -Ddrakes.db.path} la sustituye (pruebas con datos temporales). */
    private static final String PATH = System.getProperty("drakes.db.path", "drakes-data/kernel.db");
    private static final int MAX_BATCH = 500;

    private static volatile DrakesDb instance;

    private final BlockingQueue<SqlWork> queue = new LinkedBlockingQueue<>();
    private final Connection writeConn;
    private final Connection readConn;
    private final Thread writer;
    private final AtomicLong committed = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean running = true;

    private DrakesDb() throws SQLException, ClassNotFoundException {
        Class.forName("org.sqlite.JDBC");
        File dir = new File(PATH).getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        writeConn = open();
        readConn = open();
        try (Statement st = writeConn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS kv (ns TEXT NOT NULL, k TEXT NOT NULL, v TEXT, updated INTEGER NOT NULL, PRIMARY KEY (ns, k))");
        }
        writer = new Thread(this::drainLoop, "DrakesDb-writer");
        writer.setDaemon(true);
        writer.start();
        Runtime.getRuntime().addShutdownHook(new Thread(this::close, "DrakesDb-shutdown"));
    }

    private static Connection open() throws SQLException {
        Connection c = DriverManager.getConnection("jdbc:sqlite:" + PATH);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL");
            st.execute("PRAGMA synchronous = NORMAL");
            st.execute("PRAGMA busy_timeout = 5000");
        }
        return c;
    }

    /** @return la instancia compartida, o {@code null} si no se pudo abrir (el llamador debe tolerarlo). */
    public static DrakesDb get() {
        return instance;
    }

    public static synchronized void initialize() {
        if (instance != null) {
            return;
        }
        try {
            instance = new DrakesDb();
            LOGGER.info("[DrakesDb] Base compartida activa (WAL): " + PATH);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[DrakesDb] No se pudo abrir " + PATH + ": " + t.getMessage());
        }
    }

    /** Encola una escritura asíncrona; no bloquea el hilo del servidor. */
    public void write(SqlWork work) {
        if (running) {
            queue.add(work);
        }
    }

    /** Lectura síncrona en una conexión propia (no usar desde el hilo del servidor con consultas pesadas). */
    public synchronized <T> T read(SqlQuery<T> query) throws SQLException {
        return query.run(readConn);
    }

    /** Ejecuta DDL/DML ya y espera (para crear tablas de un plugin al arrancar). */
    public void execNow(String sql) throws SQLException {
        synchronized (writeConn) {
            try (Statement st = writeConn.createStatement()) {
                st.execute(sql);
            }
        }
    }

    /** Importa una vez las filas de un .db antiguo (misma tabla y columnas) y lo renombra a .migrated. */
    public static void importLegacyTable(String legacyPath, String table) throws SQLException {
        DrakesDb db = instance;
        if (db != null) {
            DrakesDbMigration.importLegacy(db, legacyPath, table);
        }
    }

    public String stats() {
        return "cola=" + queue.size() + ", commit=" + committed.get() + ", fallos=" + failed.get();
    }

    private void drainLoop() {
        while (running || !queue.isEmpty()) {
            try {
                SqlWork first = queue.poll(500, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                synchronized (writeConn) {
                    writeConn.setAutoCommit(false);
                    int n = 0;
                    SqlWork w = first;
                    while (w != null && n < MAX_BATCH) {
                        try {
                            w.run(writeConn);
                            committed.incrementAndGet();
                        } catch (Throwable t) {
                            failed.incrementAndGet();
                            LOGGER.fine("[DrakesDb] Trabajo fallido: " + t.getMessage());
                        }
                        n++;
                        w = n < MAX_BATCH ? queue.poll() : null;
                    }
                    writeConn.commit();
                    writeConn.setAutoCommit(true);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "[DrakesDb] Error en el hilo escritor: " + t.getMessage());
            }
        }
    }

    private void close() {
        running = false;
        try {
            writer.join(5000);
            synchronized (writeConn) {
                writeConn.close();
            }
            readConn.close();
        } catch (Throwable ignored) {
            // cierre best-effort
        }
    }

    // ---- clave-valor ----

    public Kv kv(String namespace) {
        return new Kv(namespace);
    }

    public final class Kv {
        private final String ns;

        private Kv(String ns) {
            this.ns = ns;
        }

        public void put(String key, String value) {
            long now = System.currentTimeMillis();
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO kv (ns, k, v, updated) VALUES (?,?,?,?)")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.setString(3, value);
                    ps.setLong(4, now);
                    ps.executeUpdate();
                }
            });
        }

        public void remove(String key) {
            write(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kv WHERE ns=? AND k=?")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    ps.executeUpdate();
                }
            });
        }

        public String get(String key) throws SQLException {
            return read(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT v FROM kv WHERE ns=? AND k=?")) {
                    ps.setString(1, ns);
                    ps.setString(2, key);
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getString(1) : null;
                    }
                }
            });
        }

        public Map<String, String> all() throws SQLException {
            return read(c -> {
                Map<String, String> out = new LinkedHashMap<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT k, v FROM kv WHERE ns=? ORDER BY k")) {
                    ps.setString(1, ns);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            out.put(rs.getString(1), rs.getString(2));
                        }
                    }
                }
                return out;
            });
        }
    }
}
