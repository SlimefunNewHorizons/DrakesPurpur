package org.purpurmc.purpur.backup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.purpurmc.purpur.perf.DrakesPerf;

/**
 * DrakesBackupManager — Autonomous 7z incremental backup scheduler in Purpur Kernel.
 * Runs on a configurable background thread every 24 hours, compresses server files into 7z,
 * and maintains a 7-day retention limit automatically purging older archives.
 *
 * Author: Jack (JackStar6677-1) - Star Systems & DrakesCraft Labs
 */
public final class DrakesBackupManager {
    private static final Logger LOGGER = Logger.getLogger("DrakesBackupManager");
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Drakes-Backup-Worker");
        t.setDaemon(true);
        return t;
    });

    private static final File BACKUP_DIR = new File("backups");
    private static final int RETENTION_DAYS = 7;

    private DrakesBackupManager() {}

    public static void initialize() {
        if (!BACKUP_DIR.exists()) {
            BACKUP_DIR.mkdirs();
        }
        long delayMin = 60;
        int hour = DrakesPerf.cfg().getInt("backup.start-hour", -1);
        if (hour >= 0 && hour <= 23) {
            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            java.time.LocalDateTime next = now.toLocalDate().atTime(hour, 0);
            if (!next.isAfter(now)) next = next.plusDays(1);
            delayMin = java.time.Duration.between(now, next).toMinutes();
        }
        SCHEDULER.scheduleAtFixedRate(() -> runScheduled(0), delayMin, 24 * 60, TimeUnit.MINUTES);
        LOGGER.info("[BackupManager] Daily 7z backup scheduler active (first run in " + delayMin + " min, retention: 7 days).");
    }

    /** Aplaza la copia (sin bloquear el hilo) mientras el servidor este con MSPT alto. */
    private static void runScheduled(int attempt) {
        double limit = DrakesPerf.cfg().getDouble("backup.defer-if-mspt-above", 35.0);
        int max = DrakesPerf.cfg().getInt("backup.max-defers", 12);
        if (DrakesPerf.enabled() && limit > 0 && attempt < max && DrakesPerf.msptNow() > limit) {
            long m = Math.max(1, DrakesPerf.cfg().getLong("backup.defer-minutes", 10));
            LOGGER.info("[BackupManager] MSPT alto; copia aplazada " + m + " min (" + (attempt + 1) + "/" + max + ")");
            SCHEDULER.schedule(() -> runScheduled(attempt + 1), m, TimeUnit.MINUTES);
            return;
        }
        performBackup();
    }

    public static void performBackup() {
        LOGGER.info("[BackupManager] Starting scheduled daily server archive...");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File targetArchive = new File(BACKUP_DIR, "drakes_backup_" + stamp + ".7z");

        DrakesPerf.setBackupRunning(true);
        try {
            List<String> cmd = new ArrayList<>();
            boolean throttle = DrakesPerf.enabled() && DrakesPerf.cfg().getBoolean("backup.throttle", true);
            if (throttle) {
                if (new File("/usr/bin/nice").canExecute()) cmd.addAll(List.of("/usr/bin/nice", "-n", "19"));
                if (new File("/usr/bin/ionice").canExecute()) cmd.addAll(List.of("/usr/bin/ionice", "-c", "3"));
            }
            cmd.addAll(List.of("7z", "a", "-t7z", "-mx=3", "-bso0", "-bsp0"));
            if (throttle) cmd.add("-mmt=" + Math.max(1, DrakesPerf.cfg().getInt("backup.threads", 2)));
            cmd.addAll(List.of("-xr!backups", "-xr!cache", "-xr!*.log", targetArchive.getAbsolutePath(), "."));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            // Antes la salida de 7z se mandaba a un pipe que nadie leia: con muchos archivos el buffer se llenaba y 7z se bloqueaba.
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            Process proc = pb.start();
            long timeout = Math.max(10, DrakesPerf.cfg().getLong("backup.timeout-minutes", 180));
            if (!proc.waitFor(timeout, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                targetArchive.delete();
                LOGGER.warning("[BackupManager] 7z superó " + timeout + " min; abortado.");
                return;
            }
            int code = proc.exitValue();

            if (code == 0) {
                LOGGER.info("[BackupManager] Daily backup created successfully: " + targetArchive.getName());
                cleanOldBackups();
            } else {
                LOGGER.warning("[BackupManager] 7z process exited with code " + code + ". Backup may be incomplete.");
            }
        } catch (Throwable t) {
            LOGGER.warning("[BackupManager] Unable to execute 7z backup (7z tool may be missing): " + t.getMessage());
        } finally {
            DrakesPerf.setBackupRunning(false);
        }
    }

    private static void cleanOldBackups() {
        File[] files = BACKUP_DIR.listFiles((d, name) -> name.startsWith("drakes_backup_") && name.endsWith(".7z"));
        if (files == null) return;

        long cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24L * 3600L * 1000L);
        for (File f : files) {
            if (f.lastModified() < cutoff) {
                if (f.delete()) {
                    LOGGER.info("[BackupManager] Purged expired backup: " + f.getName());
                }
            }
        }
    }
}
