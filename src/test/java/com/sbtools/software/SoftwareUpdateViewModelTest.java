package com.sbtools.software;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareUpdateViewModelTest {

    @Test
    void zoom1603Underlying1612FromLogIsManualRepair() throws Exception {
        java.nio.file.Path diag = java.nio.file.Files.createTempDirectory("wz-diag");
        java.nio.file.Path log = diag.resolve("z.log");
        java.nio.file.Files.writeString(log,
                "RemoveExistingProducts\nError 1714 cannot be removed\nSystem Error 1612\n");
        SoftwareUpdateEntry entry = new SoftwareUpdateEntry(
                "Zoom.Zoom", "Zoom Workplace (64-bit)", "7.0", "7.1", "winget", null, 0);
        String raw = "Installer failed with exit code: 1603\nInstaller log is available at:\n" + log + "\n";
        var res = new com.sbtools.util.ProcessResult(1, raw, "");
        SoftwareInstallFailure.Result failure = SoftwareInstallFailure.classify(entry, res, diag);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SOURCE_MISSING, failure.kind());
        assertFalse(failure.immediateRetryUseful());
        String history = SoftwareInstallFailure.historyPayload(failure);
        assertTrue(history.contains("Technical details"));
        assertTrue(history.contains("1603"));
        java.nio.file.Files.deleteIfExists(log);
        java.nio.file.Files.deleteIfExists(diag);
    }

    @Test
    void classifierMarks1612NonRetryableForBatchAndSingle() {
        SoftwareUpdateEntry entry = new SoftwareUpdateEntry(
                "Oracle.JavaRuntimeEnvironment", "Java 8", "8", "8u401", "winget", null, 0);
        var res = new com.sbtools.util.ProcessResult(1,
                "Uninstall failed with exit code: 1612\n", "");
        SoftwareInstallFailure.Result failure = SoftwareInstallFailure.classify(entry, res);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SOURCE_MISSING, failure.kind());
        assertFalse(failure.immediateRetryUseful());
        assertEquals(1, SoftwareInstallFailure.countInstallerExitMentions(failure.formattedUserMessage(), 1612));
        assertFalse(failure.immediateRetryUseful());
    }

    @Test
    void dedupeKeepsFirstWingetStyleEntry() {
        SoftwareUpdateEntry winget = new SoftwareUpdateEntry("abc", "A", "1", "2", "winget", null, 0);
        SoftwareUpdateEntry wu = new SoftwareUpdateEntry("abc", "A WU", "", "KB1", "WindowsUpdate", "guid", 0);
        List<SoftwareUpdateEntry> out = SoftwareUpdateViewModel.dedupeById(List.of(winget, wu));
        assertEquals(1, out.size());
        assertEquals("winget", out.get(0).source());
    }
}
