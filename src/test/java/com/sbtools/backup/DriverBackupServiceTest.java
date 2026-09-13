package com.sbtools.backup;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.settings.AppSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DriverBackupServiceTest {

    @TempDir
    Path temp;

    @Test
    void backupBeforeUpdate_nullDriver_throwsIOExceptionNotNpe() {
        DriverBackupService svc = new DriverBackupService();
        assertThrows(IOException.class, () -> svc.backupBeforeUpdate(null, AppSettings.defaults()));
    }

    @Test
    void backupSupportIssue_matrix() {
        assertNotNull(DriverBackupService.backupSupportIssue(null));
        InstalledDriver noInf = new InstalledDriver("id", "dev", "", "", "1", null, "", "OK", null, true);
        assertNotNull(DriverBackupService.backupSupportIssue(noInf));
        InstalledDriver badInf = new InstalledDriver("id", "dev", "", "", "1", "bad.inf name", "", "OK", null, true);
        assertNotNull(DriverBackupService.backupSupportIssue(badInf));
        InstalledDriver ok = new InstalledDriver("PCI\\VEN", "dev", "", "", "1.0", "oem42.inf", "", "OK", null, true);
        assertNull(DriverBackupService.backupSupportIssue(ok));
    }

    @Test
    void destructiveDelete_rejectsIndexedPathOutsideRoots(@TempDir Path root) throws Exception {
        Path backups = root.resolve("backups");
        Path device = backups.resolve("PCI_VEN_1");
        Path leaf = device.resolve("1700000000000_abcd1234");
        Files.createDirectories(leaf);
        Path outsider = root.resolve("projects").resolve("myapp").resolve("1700000000000_abcd1234");
        Files.createDirectories(outsider);
        Files.writeString(outsider.resolve("x.txt"), "data");

        DriverBackupEntry entry = new DriverBackupEntry(
                UUID.randomUUID().toString(),
                "PCI\\VEN_1",
                "Device",
                Instant.now(),
                outsider.toString(),
                "1.0",
                "oem1.inf");

        DriverBackupService svc = new DriverBackupService();
        assertThrows(IOException.class, () -> svc.removeBackupEntry(entry));
        assertTrue(Files.isDirectory(outsider));
    }

    @Test
    void countMatchingInf_exactlyOne(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("sub"));
        Files.writeString(folder.resolve("sub").resolve("test.inf"), ";");
        assertEquals(1, BackupHealth.countMatchingInfFiles(folder, "test.inf"));
        Files.writeString(folder.resolve("sub").resolve("other.inf"), ";");
        assertEquals(1, BackupHealth.countMatchingInfFiles(folder, "test.inf"));
    }

    @Test
    void sanitizeDeviceId_replacesUnsafeChars() {
        assertEquals("PCI_VEN_1", DriverBackupService.sanitizeDeviceId("PCI\\VEN_1"));
    }
}
