package com.sbtools.drivers;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class WindowsInstallerInvokeTest {

    @Test
    void detectsFullMsiexecUsageHelpCaseInsensitive() {
        String sample = "Windows ® Installer. V 5.0.26100.8875\n"
                + "msiexec /Option <Required Parameter>\n"
                + "Install Options\n"
                + "\t</package | /i> <Product.msi>\n"
                + "Setting Public Properties";
        assertTrue(WindowsInstallerInvoke.isUsageHelpOutput(sample));
        assertTrue(WindowsInstallerInvoke.isUsageHelpOutput(sample.toUpperCase()));
    }

    @Test
    void detectsUsageHelpAcrossStdoutAndStderr() {
        String stdout = "Windows Installer\nInstall Options";
        String stderr = "</package | /i> <Product.msi>\nSetting Public Properties";
        assertTrue(WindowsInstallerInvoke.isUsageHelpOutput(stdout, stderr));
    }

    @Test
    void rejectsPartialInstallerOutput() {
        assertFalse(WindowsInstallerInvoke.isUsageHelpOutput("Installation success"));
        assertFalse(WindowsInstallerInvoke.isUsageHelpOutput("Install Options only"));
    }

    @Test
    void rejectsMissingMsi(@TempDir Path dir) throws Exception {
        Path missing = dir.resolve("x.msi");
        assertNotNull(WindowsInstallerInvoke.validateMsiPackage(missing));
    }

    @Test
    void acceptsOleHeader(@TempDir Path dir) throws Exception {
        Path msi = dir.resolve("pkg.msi");
        byte[] ole = new byte[]{
                (byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1,
                0, 0, 0, 0
        };
        Files.write(msi, ole);
        assertNull(WindowsInstallerInvoke.validateMsiPackage(msi));
    }
}
