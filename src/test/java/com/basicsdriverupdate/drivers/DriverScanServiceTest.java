package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverScanServiceTest {

    @Test
    void parseDriversFromFixture() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/enumerate-sample.json"));
        var list = DriverScanService.parseDrivers(json);
        assertEquals(2, list.size());
        InstalledDriver nvidia = list.stream()
                .filter(d -> d.friendlyName().contains("NVIDIA"))
                .findFirst()
                .orElseThrow();
        assertEquals("31.0.15.3623", nvidia.driverVersion());
        assertTrue(nvidia.isHealthy());
    }
}
