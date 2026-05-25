package com.basicsdriverupdate.software;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class SoftwareUpdateServiceTest {

    @Test
    public void parseJsonOutput_findsExpectedUpdates() throws Exception {
        InputStream is = getClass().getResourceAsStream("/fixtures/winget-upgrade-sample.json");
        byte[] data = is.readAllBytes();
        String json = new String(data, StandardCharsets.UTF_8);

        SoftwareUpdateService svc = new SoftwareUpdateService();
        List<SoftwareUpdateEntry> list = svc.parseJsonOutput(json);
        // Only SomeVendor.SomeApp should be returned (available > version and source == winget)
        assertEquals(1, list.size());
        SoftwareUpdateEntry e = list.get(0);
        assertEquals("SomeVendor.SomeApp", e.id());
        assertEquals("Some App", e.getName());
        assertEquals("1.0.0", e.getCurrentVersion());
        assertEquals("1.2.0", e.getAvailableVersion());
    }

    @Test
    public void parseTextOutput_parsesTableLikeOutput() {
        String text = "Name  Id  Version  Available  Source\n"
                + "Some App  SomeVendor.SomeApp  1.0.0  1.2.0  winget\n"
                + "Other App  Other.Vendor  2.0.0  2.0.0  winget\n";

        SoftwareUpdateService svc = new SoftwareUpdateService();
        List<SoftwareUpdateEntry> list = svc.parseTextOutput(text);
        assertEquals(1, list.size());
        SoftwareUpdateEntry e = list.get(0);
        assertEquals("SomeVendor.SomeApp", e.id());
        assertEquals("Some App", e.getName());
        assertEquals("1.0.0", e.getCurrentVersion());
        assertEquals("1.2.0", e.getAvailableVersion());
    }
}
