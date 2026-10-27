package org.purpurmc.purpur;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.purpurmc.purpur.saori.SaoriKernelSocket;

/**
 * DrakesPurpur Kernel Bridge & Autonomous Crash Guard
 * Integrates directly with Star Infrastructure & SQLite WAL
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesKernelBridge {
    private static final Logger LOGGER = Logger.getLogger("DrakesKernel");
    private static volatile boolean rustLoaded = false;
    private static final String FAULTS_DB_PATH = "drakes_faults.db";
    private static volatile boolean dbInitialized = false;

    private DrakesKernelBridge() {}

    public static void initialize() {
        LOGGER.info("==========================================================");
        LOGGER.info("  [DrakesPurpur 26.2] Sovereign High-Performance Kernel  ");
        LOGGER.info("  Architecture: JackStar6677-1 | Star Systems & Drakes   ");
        LOGGER.info("==========================================================");
        initFaultDatabase();
        loadRustNativeBridge();
        SaoriKernelSocket.initialize();
        org.purpurmc.purpur.compat.ChaguiPumpkinCompat.initialize();
        org.purpurmc.purpur.staff.DrakesStaffEngine.initialize();
        org.purpurmc.purpur.modalities.DrakesModalityEngine.initialize();
        org.purpurmc.purpur.storage.DrakesStorageAudit.initialize();
        org.purpurmc.purpur.backup.DrakesBackupManager.initialize();
    }

    private static void initFaultDatabase() {
        try {
            Class.forName("org.sqlite.JDBC");
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + FAULTS_DB_PATH);
                 Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL;");
                stmt.execute("PRAGMA synchronous = NORMAL;");
                stmt.execute("CREATE TABLE IF NOT EXISTS kernel_faults ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "context TEXT NOT NULL, "
                    + "source_desc TEXT, "
                    + "error_message TEXT, "
                    + "stacktrace TEXT, "
                    + "timestamp INTEGER NOT NULL, "
                    + "isolated INTEGER NOT NULL DEFAULT 1);");
            }
            dbInitialized = true;
            LOGGER.info("[CrashGuard] SQLite WAL Fault Database initialized: " + FAULTS_DB_PATH);
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[CrashGuard] Failed to initialize SQLite fault DB: " + t.getMessage());
        }
    }

    private static void loadRustNativeBridge() {
        try {
            File nativeLib = new File("natives/libslimefun_ffi.so");
            if (nativeLib.exists()) {
                System.load(nativeLib.getAbsolutePath());
                rustLoaded = true;
                LOGGER.info("[DrakesPurpur] Motor Rust FFM cargado exitosamente off-heap: " + nativeLib.getAbsolutePath());
            } else {
                LOGGER.info("[DrakesPurpur] libslimefun_ffi.so no detectado en natives/. Modo simulado activo.");
            }
        } catch (Throwable t) {
            LOGGER.log(Level.WARNING, "[DrakesPurpur] Advertencia cargando puente nativo Rust: " + t.getMessage());
        }
    }

    public static boolean isRustLoaded() {
        return rustLoaded;
    }

    /**
     * Kernel Crash Guard: Intercepts unhandled plugin exceptions, isolates the faulty block/entity,
     * writes the event to SQLite WAL, and notifies SAORI without stopping the Minecraft tick.
     */
    public static void handleUncaughtFault(String context, Object source, Throwable fault) {
        String sourceDesc = source != null ? source.toString() : "unknown";
        String msg = fault != null ? fault.getMessage() : "null";
        LOGGER.log(Level.SEVERE, "[CrashGuard-ISOLATED] Interceptado fallo no capturado en: " + context + " (" + sourceDesc + "): " + msg);

        StringBuilder sb = new StringBuilder();
        if (fault != null) {
            for (StackTraceElement elem : fault.getStackTrace()) {
                sb.append(elem.toString()).append("\n");
                if (sb.length() > 2000) break;
            }
        }
        String stack = sb.toString();

        // 1. Commit to SQLite WAL asynchronously
        if (dbInitialized) {
            SaoriKernelSocket.getAsyncExecutor().submit(() -> {
                try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + FAULTS_DB_PATH);
                     PreparedStatement ps = conn.prepareStatement(
                         "INSERT INTO kernel_faults (context, source_desc, error_message, stacktrace, timestamp, isolated) VALUES (?, ?, ?, ?, ?, 1)")) {
                    ps.setString(1, context);
                    ps.setString(2, sourceDesc);
                    ps.setString(3, msg);
                    ps.setString(4, stack);
                    ps.setLong(5, System.currentTimeMillis());
                    ps.executeUpdate();
                } catch (Throwable ignored) {}
            });
        }

        // 2. Dispatch structured telemetry to SAORI Autonomous SRE
        SaoriKernelSocket.dispatchFault(context, sourceDesc, msg);
    }
}
