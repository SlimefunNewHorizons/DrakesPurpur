package org.purpurmc.purpur.backup;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

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
        // Schedule daily backup task (initial delay 1 hour, repeat every 24 hours)
        SCHEDULER.scheduleAtFixedRate(DrakesBackupManager::performBackup, 1, 24, TimeUnit.HOURS);
        LOGGER.info("[BackupManager] Autonomous daily 7z backup scheduler active (Retention: 7 days).");
    }

    public static void performBackup() {
        LOGGER.info("[BackupManager] Starting scheduled daily server archive...");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File targetArchive = new File(BACKUP_DIR, "drakes_backup_" + stamp + ".7z");

        try {
            ProcessBuilder pb = new ProcessBuilder(
                "7z", "a", "-t7z", "-mx=3", "-xr!backups", "-xr!cache", "-xr!*.log",
                targetArchive.getAbsolutePath(), "."
            );
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int code = proc.waitFor();

            if (code == 0) {
                LOGGER.info("[BackupManager] Daily backup created successfully: " + targetArchive.getName());
                cleanOldBackups();
            } else {
                LOGGER.warning("[BackupManager] 7z process exited with code " + code + ". Backup may be incomplete.");
            }
        } catch (Throwable t) {
            LOGGER.warning("[BackupManager] Unable to execute 7z backup (7z tool may be missing): " + t.getMessage());
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
