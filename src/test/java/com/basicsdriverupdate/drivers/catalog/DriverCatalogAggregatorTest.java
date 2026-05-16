package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.drivers.model.UpdateSeverity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DriverCatalogAggregatorTest {

    @Test
    void prefersWindowsUpdateOverOem() {
        InstalledDriver gpu = new InstalledDriver(
                "PCI\\VEN_10DE", "NVIDIA GPU", "VEN_10DE", "NVIDIA",
                "31.0.0.0", "x.inf", "", "OK");
        DriverCatalogProvider wu = new DriverCatalogProvider() {
            @Override
            public String id() {
                return "WindowsUpdate";
            }

            @Override
            public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
                return List.of(new DriverUpdateCandidate(
                        gpu, "32.0.0.0", "WindowsUpdate", "wu-id", "WU", "", UpdateSeverity.IMPORTANT));
            }
        };
        DriverCatalogProvider oem = new DriverCatalogProvider() {
            @Override
            public String id() {
                return "Nvidia";
            }

            @Override
            public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
                return List.of(new DriverUpdateCandidate(
                        gpu, "33.0.0.0", "Nvidia", "oem-id", "OEM", "", UpdateSeverity.RECOMMENDED));
            }
        };
        var agg = new DriverCatalogAggregator(List.of(wu, oem));
        var result = agg.findUpdates(List.of(gpu));
        assertEquals(1, result.size());
        assertEquals("WindowsUpdate", result.get(0).source());
    }
}
