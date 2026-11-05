package org.purpurmc.purpur.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.purpurmc.purpur.perf.DrakesPerf;

class DrakesBackupManagerTest {

    @Test
    void returnsNullWhenNoSevenZipInPath(@TempDir Path emptyDir) {
        assertNull(DrakesBackupManager.findSevenZipBinary(emptyDir.toString()));
    }

    @Test
    void detectsExecutableSevenZipVariantsInPath(@TempDir Path binDir) throws Exception {
        Path fake7za = binDir.resolve("7za");
        Files.writeString(fake7za, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(fake7za, PosixFilePermissions.fromString("rwxr-xr-x"));

        String found = DrakesBackupManager.findSevenZipBinary(binDir.toString());
        assertEquals(fake7za.toAbsolutePath().toString(), found);
    }

    @Test
    void skipsBackupCleanlyWhenDisabledInConfig() {
        DrakesPerf.cfg().set("backup.enabled", false);
        try {
            DrakesBackupManager.performBackup();
            assertFalse(DrakesPerf.isBackupRunning(), "backupRunning flag must remain false when disabled");
        } finally {
            DrakesPerf.cfg().set("backup.enabled", true);
        }
    }

    @Test
    void handlesExit127WithoutLeavingIncompleteFilesOrStderrLog(@TempDir Path binDir) throws Exception {
        Path fake7z = binDir.resolve("7z");
        Files.writeString(fake7z, "#!/bin/sh\necho '7z: not found' >&2\nexit 127\n");
        Files.setPosixFilePermissions(fake7z, PosixFilePermissions.fromString("rwxr-xr-x"));

        Field f = DrakesBackupManager.class.getDeclaredField("sevenZipBinary");
        f.setAccessible(true);
        Object prev = f.get(null);
        DrakesPerf.cfg().set("backup.enabled", true);
        DrakesPerf.cfg().set("backup.throttle", false);
        File backupDir = new File("backups");
        backupDir.mkdirs();
        try {
            f.set(null, fake7z.toAbsolutePath().toString());
            DrakesBackupManager.performBackup();
            assertNull(f.get(null), "exit 127 must clear cached sevenZipBinary");
            assertFalse(new File(backupDir, "last_backup_stderr.log").exists(), "stderr log must be cleaned up");
            assertFalse(DrakesPerf.isBackupRunning(), "backupRunning must be reset in finally");
        } finally {
            f.set(null, prev);
        }
    }
}
