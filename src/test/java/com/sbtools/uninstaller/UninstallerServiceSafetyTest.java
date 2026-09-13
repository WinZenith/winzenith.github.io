package com.sbtools.uninstaller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UninstallerServiceSafetyTest {

    @Test
    void protectedPathBlocksBareRootsAndWindows() {
        assertTrue(UninstallerService.isProtectedPath("C:\\"));
        assertTrue(UninstallerService.isProtectedPath("C:\\Windows"));
        assertTrue(UninstallerService.isProtectedPath("C:\\Windows\\System32"));
    }

    @Test
    void appSubfolderUnderProgramFilesNotProtectedRoot() {
        assertFalse(UninstallerService.isProtectedPath("C:\\Program Files\\Vendor\\App"));
    }
}
