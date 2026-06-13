package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OemVendorHelperTest {

    @Test
    void testDetectNvidia() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_10DE&DEV_2484",
                "NVIDIA GeForce RTX 4090",
                "PCI\\VEN_10DE&DEV_2484",
                "NVIDIA",
                "31.0.101.5120",
                "nv_dispi.inf",
                "oem123",
                "OK"
        );
        assertEquals(OemVendorHelper.NVIDIA, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectIntel() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_8086&DEV_1502",
                "Intel Ethernet Connection",
                "PCI\\VEN_8086&DEV_1502",
                "Intel",
                "25.0.0",
                "e1d68x64.inf",
                "oem10",
                "OK"
        );
        assertEquals(OemVendorHelper.INTEL, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectRealtek() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_10EC&DEV_8168",
                "Realtek PCIe GbE Family Controller",
                "PCI\\VEN_10EC&DEV_8168",
                "Realtek",
                "10.75.324.2026",
                "rt640x64.inf",
                "oem5",
                "OK"
        );
        assertEquals(OemVendorHelper.REALTEK, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectAMD() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_1002&DEV_73BF",
                "AMD Radeon RX 7900 XTX",
                "PCI\\VEN_1002&DEV_73BF",
                "Advanced Micro Devices",
                "31.0.12019.0",
                "aticfx64.inf",
                "oem8",
                "OK"
        );
        assertEquals(OemVendorHelper.AMD, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectLenovo() {
        InstalledDriver driver = new InstalledDriver(
                "ACPI\\LEN0000",
                "Lenovo ACPI-Compliant Virtual Power Controller",
                "ACPI\\LEN0000",
                "Lenovo",
                "1.0.0.0",
                "lenovo.inf",
                "oem20",
                "OK"
        );
        assertEquals(OemVendorHelper.LENOVO, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectDell() {
        InstalledDriver driver = new InstalledDriver(
                "ACPI\\DELL0000",
                "Dell Data Protection",
                "ACPI\\DELL0000",
                "Dell Inc.",
                "1.0.0.0",
                "dell.inf",
                "oem25",
                "OK"
        );
        assertEquals(OemVendorHelper.DELL, OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectNull() {
        assertNull(OemVendorHelper.detect(null));
    }

    @Test
    void testDetectUnknown() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_1234&DEV_5678",
                "Some Unknown Device",
                "PCI\\VEN_1234&DEV_5678",
                "Unknown Corp",
                "1.0.0",
                "unknown.inf",
                "oem99",
                "OK"
        );
        assertNull(OemVendorHelper.detect(driver));
    }

    @Test
    void testDetectByProvider() {
        InstalledDriver driver = new InstalledDriver(
                "ROOT\\DISPLAY\\0000",
                "Display Adapter",
                "ROOT\\DISPLAY\\0000",
                "Qualcomm",
                "1.0.0",
                "qualcomm.inf",
                "oem30",
                "OK"
        );
        assertEquals(OemVendorHelper.QUALCOMM, OemVendorHelper.detect(driver));
    }
}
