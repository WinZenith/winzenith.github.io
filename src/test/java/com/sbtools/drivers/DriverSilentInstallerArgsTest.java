package com.sbtools.drivers;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.drivers.model.UpdateSeverity;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class DriverSilentInstallerArgsTest {

    @Test
    void bluetoothConsumerExeUsesQuiet() {
        Path p = Path.of("BT-24.40.0-64UWD-Win10-Win11.exe");
        DriverUpdateCandidate c = intelCandidate("Intel(R) Wireless Bluetooth(R)");
        assertEquals(DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER,
                DriverSilentInstallerArgs.detectIntelPackageFamily(p, c));
        assertArrayEquals(new String[]{"/quiet"},
                DriverSilentInstallerArgs.exeArgsFor(DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER));
        assertTrue(DriverSilentInstallerArgs.usesIntelSingleShotSilent(
                DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER));
    }

    @Test
    void bluetoothDetectedFromFriendlyNameWhenFilenameGeneric() {
        Path p = Path.of("setup.exe");
        DriverUpdateCandidate c = intelCandidate("Intel(R) Wireless Bluetooth(R)");
        assertEquals(DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER,
                DriverSilentInstallerArgs.detectIntelPackageFamily(p, c));
    }

    @Test
    void nonBluetoothIntelDoesNotUseProviderWideSingleShot() {
        Path p = Path.of("WiFi-Driver-Installer.exe");
        DriverUpdateCandidate c = intelCandidate("Intel(R) Wi-Fi 6 AX201");
        assertNull(DriverSilentInstallerArgs.detectIntelPackageFamily(p, c));
        assertFalse(DriverSilentInstallerArgs.usesIntelSingleShotSilent(null));
    }

    @Test
    void nonIntelReturnsNullFamily() {
        assertNull(DriverSilentInstallerArgs.detectIntelPackageFamily(
                Path.of("BT-24.40.0-64UWD-Win10-Win11.exe"),
                new DriverUpdateCandidate(null, "1", "AMD", null, null, null, UpdateSeverity.OPTIONAL, null, null)));
    }

    private static DriverUpdateCandidate intelCandidate(String friendlyName) {
        InstalledDriver installed = new InstalledDriver(
                "dev", friendlyName, "", "Intel", "1", null, null, "OK", null, true);
        return new DriverUpdateCandidate(
                installed, "24.40.0", "Intel", null, null, null, UpdateSeverity.OPTIONAL, null, null);
    }
}
