package com.sbtools.ui;

import com.sbtools.drivers.DriverScanService;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class DashboardTabViewLogicTest {

    @Test
    void tabIndexForSourceMapsKnownSources() {
        assertEquals(1, DashboardTabView.tabIndexForSource("Drivers"));
        assertEquals(3, DashboardTabView.tabIndexForSource("Software"));
        assertEquals(7, DashboardTabView.tabIndexForSource("Cleanup"));
        assertEquals(-1, DashboardTabView.tabIndexForSource("Other"));
    }

    @Test
    void shouldKeepProgressRowForPartialSoftwareProgressFailure() {
        assertTrue(DashboardTabView.shouldKeepProgressRowVisible(0, false, true, false));
    }

    @Test
    void excludedDriverKeyNormalizationMatchesDriversTab() {
        Set<String> excluded = new HashSet<>();
        excluded.add(DriverScanService.normalizeDeviceKey("  pci\\ven_10de  "));
        String enumerated = "PCI\\VEN_10DE&DEV_1234";
        assertTrue(excluded.contains(DriverScanService.normalizeDeviceKey(enumerated)));
    }
}
