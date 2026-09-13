package com.sbtools.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RegistryBackupSafetyTest {

    @TempDir
    Path temp;

    @Test
    void parseUtf8RegFile() throws Exception {
        Path reg = temp.resolve("run.reg");
        String content = "Windows Registry Editor Version 5.00\r\n\r\n"
                + "[HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Run]\r\n"
                + "\"Test\"=\"C:\\\\foo.exe\"\r\n";
        Files.writeString(reg, content, StandardCharsets.UTF_8);
        List<RegistryBackupSafety.RegSection> sections = RegistryBackupSafety.parseRegFile(reg);
        assertEquals(1, sections.size());
        assertTrue(RegistryBackupSafety.isAllowedHivePath(sections.get(0).hivePath()));
    }

    @Test
    void rejectsForbiddenHive() throws Exception {
        Path reg = temp.resolve("bad.reg");
        Files.writeString(reg, "[HKEY_LOCAL_MACHINE\\SOFTWARE\\EvilCorp]\r\n");
        var sections = RegistryBackupSafety.parseRegFile(reg);
        assertThrows(IOException.class, () -> RegistryBackupSafety.validateSections(sections, reg));
    }

    @Test
    void allowedPrefixMatchesChildKey() {
        assertTrue(RegistryBackupSafety.isAllowedHivePath(
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\\MyValue"));
    }

    @Test
    void runServicesKeyUsuallyAbsent_exportReportsMissing(@TempDir Path out) {
        String key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunServices";
        if (RegistryBackupSafety.keyExists(key)) {
            return;
        }
        assertEquals(RegistryBackupSafety.RegExportOutcome.MISSING,
                RegistryBackupSafety.exportRegKeyIfPresent(key, out.resolve("x.reg")));
    }
}
