package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WindowsUpdateCatalogProviderTest {

    @Test
    void matchNvidiaDriverUpdate() throws Exception {
        String json = Files.readString(Path.of("src/test/resources/fixtures/enumerate-sample.json"));
        var installed = com.basicsdriverupdate.drivers.DriverScanService.parseDrivers(json);
        String wuJson = Files.readString(Path.of("src/test/resources/fixtures/wu-drivers-sample.json"));
        List<DriverUpdateCandidate> updates = WindowsUpdateCatalogProvider.matchUpdates(installed, wuJson);
        assertFalse(updates.isEmpty());
        assertEquals("WindowsUpdate", updates.get(0).source());
        assertEquals("32.0.15.6094", updates.get(0).availableVersion());
    }
}
