package com.sbtools.software;

import com.sbtools.util.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareUpdateServiceTest {

    @Test
    void dedupeByIdIsCaseInsensitive() {
        SoftwareUpdateEntry a = new SoftwareUpdateEntry("Google.Chrome", "Chrome", "1", "2");
        SoftwareUpdateEntry b = new SoftwareUpdateEntry("google.chrome", "Chrome", "1", "3");
        var out = SoftwareUpdateViewModel.dedupeById(java.util.List.of(a, b));
        assertEquals(1, out.size());
        assertEquals("Google.Chrome", out.get(0).id());
    }

    @Test
    void parseTextOutputSkipsFooterExplicitTargeting() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        String table = """
                Name                    Id                      Version      Available
                --------------------------------------------------------------------------------
                Foo                     Publisher.Foo           1.0.0        2.0.0

                The following packages have explicit targeting.
                Name                    Id
                --------------------------------------------------------------------------------
                Bar                     Bad.Slice
                """;
        var rows = svc.parseTextOutput(table);
        assertTrue(rows.isEmpty() || rows.stream().noneMatch(e -> e.id() != null && e.id().contains("Bad")));
    }

    @Test
    void windowsUpdateSuccessRequiresExitZeroAndJson() {
        ProcessResult failExit = new ProcessResult(4,
                "{\"resultCode\":4,\"installed\":2,\"rebootRequired\":true}", "");
        assertFalse(SoftwareUpdateService.isWindowsUpdateInstallSuccess(failExit));
        assertFalse(SoftwareUpdateService.isWindowsUpdateRebootRequired(failExit));

        ProcessResult ok = new ProcessResult(0,
                "done\n{\"resultCode\":2,\"installed\":2,\"rebootRequired\":true}", "");
        assertTrue(SoftwareUpdateService.isWindowsUpdateInstallSuccess(ok));
        assertTrue(SoftwareUpdateService.isWindowsUpdateRebootRequired(ok));
    }

    @Test
    void wingetRebootExit3010StillSuccess() {
        ProcessResult r = new ProcessResult(ProcessResult.MSI_SUCCESS_REBOOT_REQUIRED, "", "");
        assertTrue(SoftwareUpdateService.isWingetInstallSuccess(r));
        assertTrue(SoftwareUpdateService.isWingetRebootRequired(r));
    }

    @Test
    void deleteRejectsOutsideWingetCache(@TempDir Path temp) throws Exception {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        Path outside = temp.resolve("evil.exe");
        Files.writeString(outside, "x".repeat(200_000));
        assertTrue(svc.deleteInstallerFiles(java.util.List.of(outside)).isEmpty());
    }

    @Test
    void isUnderWingetDownloadCacheFalseForArbitraryPath(@TempDir Path temp) throws Exception {
        Path f = temp.resolve("a.exe");
        Files.createFile(f);
        assertFalse(SoftwareUpdateService.isUnderWingetDownloadCache(f));
    }

    @Test
    void findCandidatesOnlyUnderWingetCache(@TempDir Path temp) throws Exception {
        Path downloads = temp.resolve("Downloads");
        Files.createDirectories(downloads);
        Path dlFile = downloads.resolve("Google.Chrome.exe");
        Files.writeString(dlFile, "x".repeat(200_000));
        SoftwareUpdateEntry pkg = new SoftwareUpdateEntry("Google.Chrome", "Chrome", "1", "2");
        SoftwareUpdateService svc = new SoftwareUpdateService();
        assertTrue(svc.findCandidateInstallersForPackage(pkg, Instant.EPOCH).isEmpty());
    }
}
