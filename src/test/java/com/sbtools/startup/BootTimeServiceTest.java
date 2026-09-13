package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BootTimeServiceTest {

    @Test
    void parseBootJson_validUtc() {
        var info = BootTimeService.parseBootJson("{\"BootTime\":\"2026-09-01T06:00:00Z\"}");
        assertNotNull(info);
        assertNotNull(info.bootTime());
        assertTrue(info.display().startsWith("Last boot:"));
    }

    @Test
    void formatUptime_ranges() {
        assertEquals("45m", BootTimeService.formatUptime(java.time.Duration.ofMinutes(45)));
        assertEquals("2h 5m", BootTimeService.formatUptime(java.time.Duration.ofMinutes(125)));
    }
}
