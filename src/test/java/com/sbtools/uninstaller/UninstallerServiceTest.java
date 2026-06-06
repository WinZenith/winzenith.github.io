package com.sbtools.uninstaller;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class UninstallerServiceTest {

    private final UninstallerService service = new UninstallerService();

    @Test
    public void testListWin32Apps() {
        List<InstalledApp> apps = service.listWin32Apps();
        assertNotNull(apps);
        
        // Ensure that if we retrieve apps, they are well-formed
        for (InstalledApp app : apps) {
            assertNotNull(app.getName());
            assertFalse(app.getName().isBlank(), "App name should not be blank");
            assertTrue(app.isWin32());
            assertNotNull(app.getUninstallString());
            assertFalse(app.getUninstallString().isBlank(), "Uninstall string should not be blank");
        }
    }

    @Test
    public void testLeftoverScanningSafety() {
        // Create an app that definitely does not exist
        InstalledApp dummy = new InstalledApp(
                "NonExistentDummyAppName12345",
                "NonExistentDummyPublisher12345",
                "1.0.0",
                "C:\\Program Files\\NonExistentDummyFolder12345",
                "C:\\Program Files\\NonExistentDummyFolder12345\\uninst.exe",
                "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\NonExistentDummyAppName12345",
                true,
                "",
                "HKLM"
        );

        // Filesystem check: since the directory does not exist, it should be empty
        List<String> fileLeftovers = service.scanFilesystemLeftovers(dummy);
        assertNotNull(fileLeftovers);
        assertTrue(fileLeftovers.isEmpty(), "Filesystem leftovers should be empty for non-existent app");

        // Registry check: since keys don't exist, they should not be found
        List<String> regLeftovers = service.scanRegistryLeftovers(dummy);
        assertNotNull(regLeftovers);
        assertTrue(regLeftovers.isEmpty(), "Registry leftovers should be empty for non-existent keys");
    }
}
