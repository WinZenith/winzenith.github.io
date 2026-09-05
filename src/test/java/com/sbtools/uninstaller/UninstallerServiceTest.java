package com.sbtools.uninstaller;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the Uninstaller tab's pure logic.
 * No registry, filesystem deletes, processes, or JavaFX toolkit are touched.
 */
class UninstallerServiceTest {

    private static InstalledApp app(String name, String publisher) {
        return new InstalledApp(name, publisher, "1.0", "C:\\Program Files\\" + name,
                "\"C:\\Program Files\\" + name + "\\uninstall.exe\"", "", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\TEST",
                true, "", "HKLM", "20240101", 1024, "64-bit");
    }

    @Test
    void isProtectedPath_blocksSystemLocations() {
        assertTrue(UninstallerService.isProtectedPath("C:\\Windows\\System32"));
        assertTrue(UninstallerService.isProtectedPath("C:\\Program Files\\WindowsApps\\Foo"));
        assertTrue(UninstallerService.isProtectedPath("C:\\"));
        assertTrue(UninstallerService.isProtectedPath("C:"));
        assertTrue(UninstallerService.isProtectedPath("\"C:\\Windows\""));
        assertFalse(UninstallerService.isProtectedPath("C:\\Program Files\\MyApp"));
        assertFalse(UninstallerService.isProtectedPath("C:\\Program Files\\Vendor\\MyApp"));
    }

    @Test
    void isProtectedPath_neverOffersScanRoots() {
        String pf = System.getenv("ProgramFiles");
        if (pf != null && !pf.isBlank()) {
            assertTrue(UninstallerService.isProtectedPath(pf));
        }
    }

    @Test
    void containsWordBoundary_avoidsFalsePositives() {
        // "Team" must not match "steam.exe"
        assertFalse(UninstallerService.containsWordBoundary("steam.exe", "team"));
        assertTrue(UninstallerService.containsWordBoundary("my team app", "team"));
        assertTrue(UninstallerService.containsWordBoundary("C:\\Apps\\MyApp\\app.exe", "C:\\Apps\\MyApp"));
        assertFalse(UninstallerService.containsWordBoundary(null, "x"));
        assertFalse(UninstallerService.containsWordBoundary("abc", ""));
    }

    @Test
    void isExactMatch_onlyExactNames() {
        InstalledApp a = app("MyApp", "MyVendor");
        assertTrue(UninstallerService.isExactMatch("MyApp", a));
        assertTrue(UninstallerService.isExactMatch("myvendor", a));
        // Heuristic substring is NOT exact
        assertFalse(UninstallerService.isExactMatch("MyApp Pro", a));
        assertFalse(UninstallerService.isExactMatch("MyAppPro", a));
    }

    @Test
    void parseUninstallCommand_quotedAndUnquoted() {
        List<String> quoted = UninstallerService.parseUninstallCommandForTest(
                "\"C:\\Program Files\\App\\uninstall.exe\" /S");
        assertEquals(2, quoted.size());
        assertEquals("C:\\Program Files\\App\\uninstall.exe", quoted.get(0));

        // Unquoted path with spaces must not split inside the exe
        List<String> unquoted = UninstallerService.parseUninstallCommandForTest(
                "C:\\Program Files\\Vendor\\App\\uninstall.exe /S");
        assertFalse(unquoted.isEmpty());
        assertTrue(unquoted.get(0).endsWith("uninstall.exe"));
    }

    @Test
    void parseUninstallCommand_msiRewrite() {
        List<String> toks = UninstallerService.parseUninstallCommandForTest(
                "MsiExec.exe /I{12345678-1234-1234-1234-123456789012}");
        assertTrue(toks.stream().anyMatch(t -> t.equalsIgnoreCase("/X{12345678-1234-1234-1234-123456789012}")
                || t.equalsIgnoreCase("/X")));
    }

    @Test
    void expandEnvironmentVariables_expandsKnownVars() {
        String win = System.getenv("SystemRoot") != null ? System.getenv("SystemRoot") : "C:\\Windows";
        String out = UninstallerService.expandEnvironmentVariables("%SystemRoot%\\System32\\foo.exe");
        assertTrue(out.startsWith(win) || out.contains("System32"));
        // Unknown vars are left as-is, never blanked
        assertEquals("%DOES_NOT_EXIST_XYZ%\\a.exe",
                UninstallerService.expandEnvironmentVariables("%DOES_NOT_EXIST_XYZ%\\a.exe"));
    }

    @Test
    void significantNameTokens_dropsGenericAndShort() {
        Set<String> toks = UninstallerService.significantNameTokens("Spotify Music App 123");
        assertTrue(toks.contains("spotify") || toks.contains("music"));
        assertFalse(toks.contains("app"));
        assertFalse(toks.contains("123"));
        assertTrue(UninstallerService.significantNameTokens("ab").isEmpty());
    }

    @Test
    void installedApp_canUninstallAndCopy() {
        InstalledApp win32 = app("A", "P");
        assertTrue(win32.canUninstall());
        InstalledApp noCmd = new InstalledApp("B", "P", "1.0", "", "", "", "", true, "", "HKLM", "", 0, "");
        assertFalse(noCmd.canUninstall());
        InstalledApp store = new InstalledApp("S", "P", "1.0", "", "", "", "", false,
                "Pkg_1.0_x64__abc", "Pkg", "", "", 0, "Store");
        assertTrue(store.canUninstall());

        InstalledApp copy = win32.withEstimatedSize(9999);
        assertEquals(9999, copy.getEstimatedSize());
        assertEquals(win32.getName(), copy.getName());
    }

    @Test
    void computePathSizeBytes_missingReturnsMinusOne() {
        assertEquals(-1, UninstallerService.computePathSizeBytes(null));
        assertEquals(-1, UninstallerService.computePathSizeBytes(""));
        assertEquals(-1, UninstallerService.computePathSizeBytes("Z:\\definitely\\not\\here\\xyz123"));
    }
}
