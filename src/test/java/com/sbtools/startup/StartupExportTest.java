package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CSV export + filter predicates. Pure logic, no JavaFX.
 */
class StartupExportTest {

    private static StartupItem item(String name, boolean enabled, double ms, StartupItemType type) {
        StartupItem it = new StartupItem(name, "Pub", "C:\\app.exe", enabled,
                "HKCU Run", name, "", "", type, null);
        it.setEstimatedBootImpactMs(ms);
        return it;
    }

    @Test
    void toCsv_escapesQuotesAndNewlines() {
        StartupItem it = new StartupItem("A\"B", "P", "C:\\x.exe /arg \"hi\"\nsecond", true,
                "HKCU Run", "A", "", "", StartupItemType.REGISTRY, null);
        it.setEstimatedBootImpactMs(123.6);
        String csv = StartupExport.toCsv(List.of(it));
        assertTrue(csv.startsWith("Name,Publisher,Location,Command,Status,Boot Impact (ms),Type,Service Start Type,Service State"));
        // Quotes doubled, newline flattened, one data row
        assertTrue(csv.contains("\"A\"\"B\""));
        assertEquals(2, csv.split("\n").length);
    }

    @Test
    void toCsv_nullSafe() {
        String csv = StartupExport.toCsv(null);
        assertTrue(csv.startsWith("Name,"));
        assertEquals(1, csv.split("\n").length);
    }

    @Test
    void matchesSearch_caseInsensitiveAcrossFields() {
        StartupItem it = item("OneDrive", true, 50, StartupItemType.REGISTRY);
        assertTrue(StartupExport.matchesSearch(it, ""));
        assertTrue(StartupExport.matchesSearch(it, "onedrive"));
        assertTrue(StartupExport.matchesSearch(it, "ONEDRIVE"));
        assertTrue(StartupExport.matchesSearch(it, "pub"));
        assertTrue(StartupExport.matchesSearch(it, "hkcu"));
        assertFalse(StartupExport.matchesSearch(it, "zzz-no-match"));
        assertFalse(StartupExport.matchesSearch(null, "x"));
    }

    @Test
    void matchesStatus_filter() {
        StartupItem en = item("A", true, 10, StartupItemType.REGISTRY);
        StartupItem dis = item("B", false, 10, StartupItemType.REGISTRY);
        assertTrue(StartupExport.matchesStatus(en, StartupExport.StatusFilter.ALL));
        assertTrue(StartupExport.matchesStatus(en, StartupExport.StatusFilter.ENABLED));
        assertFalse(StartupExport.matchesStatus(en, StartupExport.StatusFilter.DISABLED));
        assertTrue(StartupExport.matchesStatus(dis, StartupExport.StatusFilter.DISABLED));
    }

    @Test
    void matchesImpact_thresholds() {
        assertTrue(StartupExport.matchesImpact(item("A", true, 50, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.LOW));
        assertTrue(StartupExport.matchesImpact(item("B", true, 150, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.MEDIUM));
        assertTrue(StartupExport.matchesImpact(item("C", true, 500, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.HIGH));
        assertFalse(StartupExport.matchesImpact(item("A", true, 50, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.HIGH));
        assertTrue(StartupExport.matchesImpact(item("A", true, 50, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.ALL));
    }

    @Test
    void extractExecutablePath_edgeCases() {
        assertEquals("", StartupService.extractExecutablePath(null));
        assertEquals("", StartupService.extractExecutablePath("  "));
        assertEquals("C:\\a.exe", StartupService.extractExecutablePath("\"C:\\a.exe\" /silent"));
        assertTrue(StartupService.extractExecutablePath("C:\\a.exe /x").contains("C:\\a.exe"));
    }

    @Test
    void bootTime_parse() {
        var info = BootTimeService.parseBootJson("{\"BootTime\":\"2026-09-01T06:00:00Z\"}");
        assertNotNull(info);
        assertNotNull(info.bootTime());
        assertTrue(info.display().startsWith("Last boot:"));

        assertNull(BootTimeService.parseBootJson("{\"BootTime\":null}"));
        assertNull(BootTimeService.parseBootJson(""));
        assertNull(BootTimeService.parseBootJson(null));
    }

    @Test
    void bootTime_formatUptime() {
        assertEquals("0m", BootTimeService.formatUptime(java.time.Duration.ofMinutes(0)));
        assertEquals("45m", BootTimeService.formatUptime(java.time.Duration.ofMinutes(45)));
        assertEquals("2h 5m", BootTimeService.formatUptime(java.time.Duration.ofMinutes(125)));
        assertEquals("3d 1h", BootTimeService.formatUptime(java.time.Duration.ofHours(73)));
    }

    @Test
    void impact_estimatesAreStable() {
        StartupItem reg = item("plain-app", true, 0, StartupItemType.REGISTRY);
        double ms = StartupImpactService.estimateBootImpactMs(reg);
        assertTrue(ms >= 0 && ms <= 500);

        StartupItem disabled = item("plain-app", false, 0, StartupItemType.REGISTRY);
        assertEquals(0, StartupImpactService.estimateBootImpactMs(disabled));

        StartupItem svc = new StartupItem("Spooler", "Microsoft", "C:\\x", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertTrue(StartupImpactService.estimateBootImpactMs(svc) > 0);
    }
}
