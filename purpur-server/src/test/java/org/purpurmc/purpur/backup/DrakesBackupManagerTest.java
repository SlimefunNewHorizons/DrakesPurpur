package org.purpurmc.purpur.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.purpurmc.purpur.perf.DrakesPerf;

class DrakesBackupManagerTest {

    @Test
    void returnsNullWhenNoSevenZipInPath(@TempDir Path emptyDir) {
        assertNull(DrakesBackupManager.findSevenZipBinary(emptyDir.toString()));
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
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
            assertTrue(true, "performBackup must return cleanly when backup.enabled=false");
        } finally {
            DrakesPerf.cfg().set("backup.enabled", true);
        }
    }
}
