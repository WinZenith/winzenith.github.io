package com.sbtools.drivers;

import com.sbtools.drivers.model.InstallStatus;
import com.sbtools.util.ProcessResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverInstallServiceIntelBluetoothTest {

    private static final String USAGE_HELP = "Windows Installer\nInstall Options\n"
            + "\t</package | /i> <Product.msi>\nSetting Public Properties";

    @Test
    void exit8WithUsageHelpMapsToBluetoothDiagnostic() {
        ProcessResult result = new ProcessResult(8, USAGE_HELP, "");
        var installResult = DriverInstallService.classifySilentExeOutcome(
                result, DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER);
        assertEquals(InstallStatus.INSTALL_FAILED, installResult.status());
        assertFalse(installResult.installed());
        assertEquals(WindowsInstallerInvoke.INTEL_BLUETOOTH_INVALID_CMD_MESSAGE, installResult.message());
        assertFalse(installResult.message().contains("exit 8"));
    }

    @Test
    void exit8WithoutUsageHelpKeepsGenericSilentFailure() {
        ProcessResult result = new ProcessResult(8, "Some other installer error", "");
        var installResult = DriverInstallService.classifySilentExeOutcome(
                result, DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER);
        assertTrue(installResult.message().contains("exit 8"));
        assertFalse(installResult.message().contains("msiexec"));
    }

    @Test
    void bluetoothArgsAreDocumentedQuietOnly() {
        assertArrayEquals(new String[]{"/quiet"},
                DriverSilentInstallerArgs.exeArgsFor(DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER));
    }
}
