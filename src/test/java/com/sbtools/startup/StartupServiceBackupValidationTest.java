package com.sbtools.startup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class StartupServiceBackupValidationTest {

    @TempDir
    Path tempDir;

    @Test
    void rejectsBlankRegistryCommand() {
        StartupService.StartupBackupEntry entry = validRegistryEntry();
        entry.setCommand("   ");
        assertThrows(IOException.class, () -> StartupBackupValidation.validateEntryMetadata(entry));
    }

    @Test
    void rejectsInvalidBackupId() {
        assertFalse(StartupBackupValidation.isValidBackupId("../evil"));
        assertFalse(StartupBackupValidation.isValidBackupId("not-a-uuid"));
        assertTrue(StartupBackupValidation.isValidBackupId("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void resolveConfinedBackupFolder_rejectsTraversal() {
        StartupService.StartupBackupEntry entry = validRegistryEntry();
        entry.setId("11111111-1111-1111-1111-111111111111");
        Path root = tempDir.resolve("startup-backups");
        assertDoesNotThrow(() -> StartupBackupValidation.resolveConfinedBackupFolder(root, entry.getId()));
        entry.setId("00000000-0000-0000-0000-000000000002");
        assertThrows(IOException.class, () ->
                StartupBackupValidation.resolveConfinedBackupFolder(root, "../../outside"));
    }

    @Test
    void rejectsUnknownBackupType() {
        StartupService.StartupBackupEntry entry = validRegistryEntry();
        entry.setType("Unknown");
        assertThrows(IOException.class, () -> StartupBackupValidation.validateEntryMetadata(entry));
    }

    @Test
    void folderRestoreTargetAbsent_detectsExistingFile() throws IOException {
        Path file = tempDir.resolve("app.lnk");
        java.nio.file.Files.writeString(file, "x");
        assertThrows(IOException.class, () ->
                StartupBackupValidation.assertFolderRestoreTargetAbsent(file.toString()));
    }

    private static StartupService.StartupBackupEntry validRegistryEntry() {
        StartupService.StartupBackupEntry entry = new StartupService.StartupBackupEntry();
        entry.setId("11111111-1111-1111-1111-111111111111");
        entry.setName("App");
        entry.setType("Registry");
        entry.setHive("HKCU");
        entry.setKeyPath(StartupConstants.REG_RUN);
        entry.setValueName("App");
        entry.setCommand("C:\\app.exe");
        entry.setLocation("HKCU Run");
        entry.setBackupTime(System.currentTimeMillis());
        return entry;
    }
}
