package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverHealthServiceTest {

    @Test
    void testHealthyDriver() {
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
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() >= 70, "Healthy driver should score >= 70, got " + score.score());
    }

    @Test
    void testGenericDriverPenalty() {
        InstalledDriver driver = new InstalledDriver(
                "ROOT\\CC_GENERIC",
                "Generic Driver",
                "CC_GENERIC",
                "Unknown",
                "1.0.0",
                "generic.inf",
                "oem99",
                "OK"
        );
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() < 80, "Generic driver should have penalty");
    }

    @Test
    void testNonOkStatusPenalty() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_8086&DEV_1502",
                "Intel Ethernet",
                "PCI\\VEN_8086&DEV_1502",
                "Intel",
                "25.0.0",
                "e1d68x64.inf",
                "oem10",
                "Error"
        );
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() <= 80, "Non-OK status should have penalty");
    }

    @Test
    void testMicrosoftProviderNoPenalty() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_8086&DEV_1502",
                "Intel Ethernet",
                "PCI\\VEN_8086&DEV_1502",
                "Microsoft",
                "25.0.0",
                "e1d68x64.inf",
                "oem10",
                "OK"
        );
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() >= 90, "Microsoft signed should have no provider penalty");
    }

    @Test
    void testUnknownProviderPenalty() {
        InstalledDriver driver = new InstalledDriver(
                "PCI\\VEN_1234&DEV_5678",
                "Some Device",
                "PCI\\VEN_1234&DEV_5678",
                "Unknown Corp",
                "25.0.0",
                "some.inf",
                "oem50",
                "OK"
        );
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() <= 85, "Unknown provider should have penalty");
    }

    @Test
    void testScoreBounds() {
        InstalledDriver driver = new InstalledDriver(
                "deviceId", "name", "hw", "provider", "1.0", "inf", "key", "Error"
        );
        DriverHealthService.DriverHealthScore score = DriverHealthService.scoreDriver(driver);
        assertTrue(score.score() >= 0, "Score should be >= 0");
        assertTrue(score.score() <= 100, "Score should be <= 100");
    }
}
