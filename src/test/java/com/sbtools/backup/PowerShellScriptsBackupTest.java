package com.sbtools.backup;

import com.sbtools.util.PowerShellScripts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class PowerShellScriptsBackupTest {

    @Test
    void restoreScriptRequiresRecordedInfName() throws Exception {
        Path script = PowerShellScripts.resolve("pnputil-restore.ps1");
        String text = Files.readString(script);
        assertTrue(text.contains("RecordedInfName is required"));
        assertTrue(text.contains("Parameter(Mandatory = $true)][string]$RecordedInfName"));
    }

    @Test
    void packagedScriptsExist() throws Exception {
        assertNotNull(PowerShellScripts.resolve("pnputil-backup.ps1"));
        assertNotNull(PowerShellScripts.resolve("pnputil-restore.ps1"));
        assertNotNull(PowerShellScripts.resolve("checkpoint-restore.ps1"));
        assertNotNull(PowerShellScripts.resolve("list-restore-points.ps1"));
    }
}
