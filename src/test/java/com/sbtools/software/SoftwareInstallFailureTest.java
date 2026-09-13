package com.sbtools.software;

import com.sbtools.util.ProcessResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareInstallFailureTest {

    @TempDir
    Path temp;

    private static SoftwareUpdateEntry wingetEntry(String id, String name) {
        return new SoftwareUpdateEntry(id, name, "1", "2", "winget", null, 0);
    }

    private static ProcessResult output(String text) {
        return new ProcessResult(1, text, "");
    }

    @Test
    void parseInstallerAndUninstallCodes() {
        assertEquals(1601, SoftwareInstallFailure.parseInstallerExitCode(
                "Installer failed with exit code: 1601").getAsInt());
        assertEquals(1612, SoftwareInstallFailure.parseInstallerExitCode(
                "UNINSTALL FAILED WITH EXIT CODE: 1612").getAsInt());
        assertEquals(1618, SoftwareInstallFailure.parseInstallerExitCode(
                "noise\nInstaller failed with exit code: 1618\nmore").getAsInt());
        assertEquals(9999, SoftwareInstallFailure.parseInstallerExitCode(
                "Installer failed with exit code: 1601\nInstaller failed with exit code: 9999").getAsInt());
        assertTrue(SoftwareInstallFailure.parseInstallerExitCode("exit code: xyzzy").isEmpty());
        assertTrue(SoftwareInstallFailure.parseInstallerExitCode("").isEmpty());
    }

    @Test
    void classifies1612AsManualRepair() {
        var entry = wingetEntry("Oracle.JavaRuntimeEnvironment", "Java 8");
        String raw = "Starting uninstall...\nUninstall failed with exit code: 1612\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), temp);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SOURCE_MISSING, result.kind());
        assertEquals(1612, result.installerExitCode());
        assertFalse(result.immediateRetryUseful());
        assertTrue(result.showTroubleshooter());
        assertTrue(result.formattedUserMessage().toLowerCase().contains("original installer source"));
        assertEquals(1, SoftwareInstallFailure.countInstallerExitMentions(result.formattedUserMessage(), 1612));
        assertTrue(result.showInstalledAppsSettings());
        assertEquals(raw, result.rawOutput());
    }

    @Test
    void historyPayloadIncludesGuidanceAndRaw() {
        var entry = wingetEntry("Oracle.JavaRuntimeEnvironment", "Java 8");
        String raw = "Uninstall failed with exit code: 1612\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), temp);
        String history = SoftwareInstallFailure.historyPayload(result);
        assertTrue(history.contains("Original installer source unavailable"));
        assertTrue(history.contains("--- Technical details ---"));
        assertTrue(history.contains("Uninstall failed with exit code: 1612"));
    }

    @Test
    void generic1601WithoutLogEvidence() {
        var entry = wingetEntry("Some.App", "Some App");
        var result = SoftwareInstallFailure.classify(entry,
                output("Installer failed with exit code: 1601"), temp);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SERVICE_UNAVAILABLE, result.kind());
        assertTrue(result.immediateRetryUseful());
        assertFalse(result.showTroubleshooter());
        assertNull(result.trustedLogPath());
    }

    @Test
    void zoom1601WithTrustedLogPrivilegeSignature() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("Zoom.Zoom-1.log");
        String zoomLog = "CustomAction ZoomMsiInstaller SetPrivilege failed token does not have the specified privilege .1300\n";
        Files.writeString(log, zoomLog, StandardCharsets.UTF_8);

        var entry = wingetEntry("Zoom.Zoom", "Zoom");
        String raw = "Installer failed with exit code: 1601\nInstaller log is available at:\n" + log + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertEquals(SoftwareInstallFailure.Kind.ZOOM_PRIVILEGE_FAILURE, result.kind());
        assertEquals(log.toRealPath(), result.trustedLogPath());
        assertTrue(result.immediateRetryUseful());
    }

    @Test
    void zoomLogOutsideDiagDirNotTrusted() throws Exception {
        Path outside = temp.resolve("outside.log");
        Files.writeString(outside, "token does not have the specified privilege ZoomMsiInstaller", StandardCharsets.UTF_8);
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);

        var entry = wingetEntry("Zoom.Zoom", "Zoom");
        String raw = "Installer failed with exit code: 1601\nInstaller log is available at:\n" + outside + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SERVICE_UNAVAILABLE, result.kind());
        assertNull(result.trustedLogPath());
    }

    @Test
    void rejectsNonLogExtensionAndParentPrefixEscape() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path txt = diag.resolve("evil.txt");
        Files.writeString(txt, "x", StandardCharsets.UTF_8);
        assertNull(SoftwareInstallFailure.validateTrustedLog(txt, diag));

        Path sibling = temp.resolve("DiagOutputDirEvil").resolve("sibling.log");
        Files.createDirectories(sibling.getParent());
        Files.writeString(sibling, "x", StandardCharsets.UTF_8);
        assertNull(SoftwareInstallFailure.validateTrustedLog(sibling, diag));
    }

    @Test
    void utf16LeZoomSignatureParses() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("Zoom.Zoom-u16.log");
        byte[] body = "ZoomCleaner ERROR_NOT_ALL_ASSIGNED Zoomenum".getBytes(StandardCharsets.UTF_16LE);
        Files.write(log, body);

        assertTrue(SoftwareInstallFailure.isZoomPrivilegeFailureInLog(
                SoftwareInstallFailure.decodeInstallerLog(body)));
    }

    @Test
    void classifies1618AsRetryable() {
        var result = SoftwareInstallFailure.classify(wingetEntry("X", "X"),
                output("Installer failed with exit code: 1618"), temp);
        assertEquals(SoftwareInstallFailure.Kind.MSI_INSTALL_IN_PROGRESS, result.kind());
        assertTrue(result.immediateRetryUseful());
    }

    @Test
    void corruption1714InOutputIsManualRepair() {
        var result = SoftwareInstallFailure.classify(wingetEntry("Edge", "Edge"),
                output("Product cannot be removed. Error 1714."), temp);
        assertEquals(SoftwareInstallFailure.Kind.MSI_CORRUPT_CACHE, result.kind());
        assertFalse(result.immediateRetryUseful());
        assertTrue(result.showTroubleshooter());
    }

    @Test
    void zoom1603WithTrustedLogTerminal1714And1612IsSourceMissing() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("Zoom.Zoom-1603.log");
        String logBody = "SetPrivilege token does not have the specified privilege .1300 ZoomMsiInstaller\n"
                + "Action ended: InstallExecute. Return value 1.\n"
                + "Action start: RemoveExistingProducts.\n"
                + "CustomAction returned actual error code 1612\n"
                + "Error 1714. The older version cannot be removed. System Error 1612.\n"
                + "Action ended: RemoveExistingProducts. Return value 3.\n";
        Files.writeString(log, logBody, StandardCharsets.UTF_8);

        var entry = wingetEntry("Zoom.Zoom", "Zoom Workplace (64-bit)");
        String raw = "Installer failed with exit code: 1603\nInstaller log is available at:\n" + log + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertEquals(SoftwareInstallFailure.Kind.MSI_SOURCE_MISSING, result.kind());
        assertEquals(1603, result.installerExitCode());
        assertFalse(result.immediateRetryUseful());
        assertTrue(result.explanation().contains("1612"));
        assertTrue(result.explanation().contains("RemoveExistingProducts"));
        assertEquals(log.toRealPath(), result.trustedLogPath());
    }

    @Test
    void zoom1603With1714InLogWithout1612StaysCorruptCache() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("Zoom-only-1714.log");
        Files.writeString(log, "Error 1714. cannot be removed\n", StandardCharsets.UTF_8);
        var entry = wingetEntry("Zoom.Zoom", "Zoom");
        String raw = "Installer failed with exit code: 1603\nInstaller log is available at:\n" + log + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertEquals(SoftwareInstallFailure.Kind.MSI_CORRUPT_CACHE, result.kind());
    }

    @Test
    void zoom1603PrivilegeNoiseWithoutTerminalRemovalIsNotPrivilegeFailure() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("Zoom-no-terminal.log");
        Files.writeString(log, "SetPrivilege .1300 ZoomCleaner\nInstallExecute Return value 1\n",
                StandardCharsets.UTF_8);
        var entry = wingetEntry("Zoom.Zoom", "Zoom");
        String raw = "Installer failed with exit code: 1603\nInstaller log is available at:\n" + log + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertNotEquals(SoftwareInstallFailure.Kind.ZOOM_PRIVILEGE_FAILURE, result.kind());
    }

    @Test
    void trustedLogReadCapIgnoresSignatureBeyondOneMiB() throws Exception {
        Path diag = temp.resolve("DiagOutputDir");
        Files.createDirectories(diag);
        Path log = diag.resolve("big.log");
        String signature = "Action start: RemoveExistingProducts.\n"
                + "Error 1714. The older version cannot be removed. System Error 1612.\n";
        byte[] pad = new byte[1024 * 1024];
        java.util.Arrays.fill(pad, (byte) 'A');
        try (var out = Files.newOutputStream(log)) {
            out.write(pad);
            out.write(signature.getBytes(StandardCharsets.UTF_8));
        }
        var entry = wingetEntry("Zoom.Zoom", "Zoom");
        String raw = "Installer failed with exit code: 1603\nInstaller log is available at:\n" + log + "\n";
        var result = SoftwareInstallFailure.classify(entry, output(raw), diag);
        assertNotEquals(SoftwareInstallFailure.Kind.MSI_SOURCE_MISSING, result.kind());
    }

    @Test
    void trustedLogPredicateDetectsRemoveExistingSourceMissing() {
        String log = "Action start: RemoveExistingProducts.\n"
                + "Error 1714. cannot be removed\nSystem Error 1612.\n";
        assertTrue(SoftwareInstallFailure.isTrustedLogRemoveExistingSourceMissing(log));
    }

    @Test
    void extractLogPathFromOutput() {
        String raw = "Installer log is available at:\nC:\\foo\\bar.log\nDone";
        assertEquals("C:\\foo\\bar.log", SoftwareInstallFailure.extractLogPathLine(raw));
    }

    @Test
    void formattedMessageBounded() {
        String longStep = "x".repeat(5000);
        String msg = SoftwareInstallFailure.formatMessage("T", "E", List.of(longStep), 1);
        assertTrue(msg.length() <= 4000 + 20);
    }
}
