package org.purpurmc.purpur.backup;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
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

    private static volatile String sevenZipBinary;

    private DrakesBackupManager() {}

    static String findSevenZipBinary() {
        return findSevenZipBinary(System.getenv("PATH"));
    }

    static String findSevenZipBinary(String pathEnv) {
        String[] candidates = {"7z", "7za", "7zr"};
        String[] dirs = (pathEnv == null || pathEnv.isBlank())
            ? new String[] {"/usr/bin", "/usr/local/bin", "/bin"}
            : pathEnv.split(File.pathSeparator);
        for (String bin : candidates) {
            for (String dir : dirs) {
                if (dir == null || dir.isBlank()) continue;
                File f = new File(dir, bin);
                if (f.isFile() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public static void initialize() {
        if (!DrakesPerf.cfg().getBoolean("backup.enabled", true)) {
            LOGGER.info("[BackupManager] Disabled via drakes-data/perf.yml (backup.enabled=false).");
            return;
        }
        sevenZipBinary = findSevenZipBinary();
        if (sevenZipBinary == null) {
            LOGGER.info("[BackupManager] 7z binary not found in PATH; internal 7z backup scheduler disabled (external backups used).");
            return;
        }
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
        LOGGER.info("[BackupManager] Daily 7z backup scheduler active using " + sevenZipBinary + " (first run in " + delayMin + " min, retention: 7 days).");
    }

    /** Aplaza la copia (sin bloquear el hilo) mientras el servidor este con MSPT alto. */
    private static void runScheduled(int attempt) {
        if (!DrakesPerf.cfg().getBoolean("backup.enabled", true)) {
            return;
        }
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
        if (!DrakesPerf.cfg().getBoolean("backup.enabled", true)) {
            return;
        }
        String bin = sevenZipBinary != null ? sevenZipBinary : findSevenZipBinary();
        if (bin == null) {
            LOGGER.info("[BackupManager] Skipping backup: 7z binary not available in container.");
            return;
        }
        LOGGER.info("[BackupManager] Starting scheduled daily server archive...");
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File targetArchive = new File(BACKUP_DIR, "drakes_backup_" + stamp + ".7z");
        File stderrLog = new File(BACKUP_DIR, "last_backup_stderr.log");

        DrakesPerf.setBackupRunning(true);
        try {
            List<String> cmd = new ArrayList<>();
            boolean throttle = DrakesPerf.enabled() && DrakesPerf.cfg().getBoolean("backup.throttle", true);
            if (throttle) {
                if (new File("/usr/bin/nice").canExecute()) cmd.addAll(List.of("/usr/bin/nice", "-n", "19"));
                if (new File("/usr/bin/ionice").canExecute()) cmd.addAll(List.of("/usr/bin/ionice", "-c", "3"));
            }
            cmd.addAll(List.of(bin, "a", "-t7z", "-mx=3", "-bso0", "-bsp0"));
            if (throttle) cmd.add("-mmt=" + Math.max(1, DrakesPerf.cfg().getInt("backup.threads", 2)));
            // Exclude temporary/locked runtime files (session.lock, sockets, active db locks) to avoid non-fatal warnings
            cmd.addAll(List.of(
                "-xr!backups",
                "-xr!cache",
                "-xr!*.log",
                "-xr!session.lock",
                "-xr!*.lck",
                "-xr!*.sock",
                "-xr!*.tmp",
                targetArchive.getAbsolutePath(),
                "."
            ));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            pb.redirectError(stderrLog);
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
            } else if (code == 1) {
                // In 7-Zip, code 1 is a non-fatal warning (some locked runtime files were skipped, archive is valid)
                String warnSummary = readFirstLine(stderrLog);
                LOGGER.warning("[BackupManager] Daily backup created with non-fatal warnings (exit code 1, locked/temp files skipped): "
                    + targetArchive.getName() + (warnSummary != null ? " | " + warnSummary : ""));
                cleanOldBackups();
            } else {
                String errSummary = readFirstLine(stderrLog);
                LOGGER.severe("[BackupManager] 7z process failed with fatal exit code " + code + ". Purging incomplete archive."
                    + (errSummary != null ? " Details: " + errSummary : ""));
                targetArchive.delete();
            }
        } catch (Throwable t) {
            LOGGER.warning("[BackupManager] Unable to execute 7z backup (7z tool may be missing): " + t.getMessage());
        } finally {
            DrakesPerf.setBackupRunning(false);
        }
    }

    private static String readFirstLine(File file) {
        if (file == null || !file.exists() || file.length() == 0) return null;
        try (BufferedReader br = new BufferedReader(new FileReader(file, StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) return line;
            }
        } catch (Throwable ignored) {}
        return null;
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
