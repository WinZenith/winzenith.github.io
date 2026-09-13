package com.sbtools.uninstaller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class InstalledAppTest {

    @Test
    void effectiveUninstallInteractiveOnly() {
        InstalledApp app = new InstalledApp("A", "P", "1", "C:\\a",
                "C:\\a\\unins.exe", "", "key", true, "", "", "", 0, "");
        assertTrue(app.hasInteractiveUninstallString());
        assertFalse(app.isQuietOnlyUninstall());
        assertEquals("C:\\a\\unins.exe", app.getEffectiveUninstallString(false));
    }

    @Test
    void quietOnlyRequiresExplicitQuiet() {
        InstalledApp app = new InstalledApp("A", "P", "1", "C:\\a",
                "", "C:\\a\\unins.exe /S", "key", true, "", "", "", 0, "");
        assertFalse(app.hasInteractiveUninstallString());
        assertTrue(app.isQuietOnlyUninstall());
        assertEquals("C:\\a\\unins.exe /S", app.getEffectiveUninstallString(false));
        assertEquals("C:\\a\\unins.exe /S", app.getEffectiveUninstallString(true));
    }

    @Test
    void bothStringsModeChoice() {
        InstalledApp app = new InstalledApp("A", "P", "1", "C:\\a",
                "C:\\a\\unins.exe", "C:\\a\\unins.exe /S", "key", true, "", "", "", 0, "");
        assertTrue(app.hasInteractiveUninstallString());
        assertTrue(app.hasQuietUninstallString());
        assertFalse(app.isQuietOnlyUninstall());
    }
}
