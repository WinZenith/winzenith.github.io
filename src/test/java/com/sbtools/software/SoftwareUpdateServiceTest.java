package com.sbtools.software;

import com.sbtools.util.CancelBridge;
import com.sbtools.util.ProcessResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the Software Update tab's pure logic:
 * winget text/JSON parsing, reboot detection, tech-mismatch detection,
 * id dedupe, cancel bridging and the in-memory scan cache.
 * No processes are launched; no JavaFX toolkit is started.
 */
class SoftwareUpdateServiceTest {

    private static final String TEXT_FIXTURE =
            "Name              Id              Version   Available   Source\r\n"
                    + "--------------------------------------------------------------------------------\r\n"
                    + "Google Chrome     Google.Chrome   1.0       2.0         winget\r\n";

    @Test
    void parseTextOutput_parsesSingleRow() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        try {
            List<SoftwareUpdateEntry> out = svc.parseTextOutput(TEXT_FIXTURE);
            assertEquals(1, out.size());
            assertEquals("Google.Chrome", out.get(0).id());
            assertEquals("Google Chrome", out.get(0).getName());
            assertEquals("1.0", out.get(0).getCurrentVersion());
            assertEquals("2.0", out.get(0).getAvailableVersion());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void parseTextOutput_noUpdatesMessageYieldsEmpty() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        try {
            assertTrue(svc.parseTextOutput("No applicable upgrades found.").isEmpty());
            assertTrue(svc.parseTextOutput("").isEmpty());
        } finally {
            svc.shutdown();
        }
    }

    /** Builds winget-style fixed-width aligned output like the real CLI produces. */
    private static String alignedTable(String[][] rows) {
        String header = String.format("%-20s%-15s%-10s%-12s%s", "Name", "Id", "Version", "Available", "Source");
        StringBuilder sb = new StringBuilder(header).append('\n');
        sb.append("-".repeat(header.length())).append('\n');
        for (String[] r : rows) {
            sb.append(String.format("%-20s%-15s%-10s%-12s%s", r[0], r[1], r[2], r[3], r[4])).append('\n');
        }
        return sb.toString();
    }

    @Test
    void parseTextOutput_skipsUnknownAvailableSameVersionNonWingetAndBlankId() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        try {
            // unknown available
            assertTrue(svc.parseTextOutput(
                    alignedTable(new String[][]{{"A", "A.B", "1.0", "unknown", "winget"}})).isEmpty());
            // same version
            assertTrue(svc.parseTextOutput(
                    alignedTable(new String[][]{{"A", "A.B", "2.0", "2.0", "winget"}})).isEmpty());
            // non-winget source
            assertTrue(svc.parseTextOutput(
                    alignedTable(new String[][]{{"A", "A.B", "1.0", "2.0", "msstore"}})).isEmpty());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void parseJsonOutput_parsesArrayAndDataWrapper() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        try {
            String array = "[{\"Id\":\"A.B\",\"Name\":\"B\",\"Version\":\"1.0\",\"Available\":\"2.0\"}]";
            List<SoftwareUpdateEntry> a = svc.parseJsonOutput(array);
            assertEquals(1, a.size());
            assertEquals("A.B", a.get(0).id());

            String wrapped = "{\"Data\":[{\"PackageIdentifier\":\"X.Y\",\"Name\":\"Y\","
                    + "\"Version\":\"3.0\",\"Available\":\"4.0\"}]}";
            List<SoftwareUpdateEntry> w = svc.parseJsonOutput(wrapped);
            assertEquals(1, w.size());
            assertEquals("X.Y", w.get(0).id());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void parseJsonOutput_skipsSameVersionUnknownAndNonWinget() {
        SoftwareUpdateService svc = new SoftwareUpdateService();
        try {
            assertTrue(svc.parseJsonOutput(
                    "[{\"Id\":\"A.B\",\"Name\":\"B\",\"Version\":\"2.0\",\"Available\":\"2.0\"}]").isEmpty());
            assertTrue(svc.parseJsonOutput(
                    "[{\"Id\":\"A.B\",\"Name\":\"B\",\"Version\":\"1.0\",\"Available\":\"unknown\"}]").isEmpty());
            assertTrue(svc.parseJsonOutput(
                    "[{\"Id\":\"A.B\",\"Name\":\"B\",\"Version\":\"1.0\",\"Available\":\"2.0\",\"Source\":\"msstore\"}]")
                    .isEmpty());
            assertTrue(svc.parseJsonOutput("[{\"Name\":\"NoId\",\"Version\":\"1.0\",\"Available\":\"2.0\"}]")
                    .isEmpty());
        } finally {
            svc.shutdown();
        }
    }

    @Test
    void isRebootRequired_exitCodes() {
        assertTrue(SoftwareUpdateService.isRebootRequired(new ProcessResult(3010, "", "")));
        assertTrue(SoftwareUpdateService.isRebootRequired(new ProcessResult(1641, "", "")));
        assertFalse(SoftwareUpdateService.isRebootRequired(new ProcessResult(0, "done", "")));
        assertFalse(SoftwareUpdateService.isRebootRequired(new ProcessResult(1, "failed", "")));
        assertFalse(SoftwareUpdateService.isRebootRequired(null));
    }

    @Test
    void isRebootRequired_jsonAndPhrasing() {
        assertTrue(SoftwareUpdateService.isRebootRequired(
                new ProcessResult(0, "{\"rebootRequired\":true}", "")));
        assertFalse(SoftwareUpdateService.isRebootRequired(
                new ProcessResult(0, "{\"rebootRequired\":false}", "")));
        assertTrue(SoftwareUpdateService.isRebootRequired(
                new ProcessResult(0, "Progress...\n{\"rebootRequired\":true}", "")));
        assertTrue(SoftwareUpdateService.isRebootRequired(
                new ProcessResult(0, "A reboot is required to finish", "")));
    }

    @Test
    void isSuccessOrRebootRequired_counts3010AsSuccess() {
        assertTrue(SoftwareUpdateService.isSuccessOrRebootRequired(new ProcessResult(0, "ok", "")));
        assertTrue(SoftwareUpdateService.isSuccessOrRebootRequired(new ProcessResult(3010, "ok", "")));
        assertFalse(SoftwareUpdateService.isSuccessOrRebootRequired(new ProcessResult(1603, "fail", "")));
    }

    @Test
    void isInstallTechnologyMismatch_detectsMarkers() {
        assertTrue(SoftwareUpdateService.isInstallTechnologyMismatch(
                new ProcessResult(1, "The install technology is different", "")));
        assertTrue(SoftwareUpdateService.isInstallTechnologyMismatch(
                new ProcessResult(1, "error 0x8A150011 occurred", "")));
        assertFalse(SoftwareUpdateService.isInstallTechnologyMismatch(
                new ProcessResult(1, "generic failure", "")));
    }

    @Test
    void dedupeById_caseInsensitiveKeepFirst() {
        SoftwareUpdateEntry winget = new SoftwareUpdateEntry("Google.Chrome", "Chrome", "1.0", "2.0",
                "winget", null, 0);
        SoftwareUpdateEntry dup = new SoftwareUpdateEntry("google.chrome", "Chrome dup", "1.0", "3.0",
                "winget", null, 0);
        SoftwareUpdateEntry wu = new SoftwareUpdateEntry("guid-1", "Security Update", "", "KB123",
                "WindowsUpdate", "guid-1", 10);
        List<SoftwareUpdateEntry> out =
                SoftwareUpdateViewModel.dedupeById(List.of(winget, dup, wu));
        assertEquals(2, out.size());
        assertSame(winget, out.get(0));
        assertSame(wu, out.get(1));
    }

    @Test
    void cancelBridge_nullAndPreCancelled() {
        try (CancelBridge b = CancelBridge.bridge(null, "t-null")) {
            assertNotNull(b.flag());
            assertFalse(b.flag().get());
        }
        AtomicBoolean cancelled = new AtomicBoolean(true);
        try (CancelBridge b = CancelBridge.bridge(cancelled::get, "t-pre")) {
            assertTrue(b.flag().get());
        }
    }

    @Test
    void cancelBridge_followsSupplier() throws Exception {
        AtomicBoolean supplier = new AtomicBoolean(false);
        try (CancelBridge b = CancelBridge.bridge(supplier::get, "t-follow")) {
            assertFalse(b.flag().get());
            supplier.set(true);
            long deadline = System.currentTimeMillis() + 3000;
            while (!b.flag().get() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(b.flag().get());
        }
    }

    @Test
    void scanCache_putGetInvalidateAndCopyIsolation() {
        SoftwareUpdateScanCache.invalidate();
        assertTrue(SoftwareUpdateScanCache.getIfFresh().isEmpty());

        SoftwareUpdateEntry e = new SoftwareUpdateEntry("A.B", "B", "1.0", "2.0", "winget", null, 0);
        SoftwareUpdateScanCache.put(List.of(e), null, null);
        var cached = SoftwareUpdateScanCache.getIfFresh();
        assertTrue(cached.isPresent());
        assertEquals(1, cached.get().entries().size());
        assertEquals("A.B", cached.get().entries().get(0).id());
        // Mutating the returned copy must not poison the cache.
        cached.get().entries().get(0).setSelected(true);
        var again = SoftwareUpdateScanCache.getIfFresh();
        assertTrue(again.isPresent());
        assertFalse(again.get().entries().get(0).isSelected());

        // Empty puts never poison.
        SoftwareUpdateScanCache.invalidate();
        SoftwareUpdateScanCache.put(List.of(), null, null);
        assertTrue(SoftwareUpdateScanCache.getIfFresh().isEmpty());
    }
}
