package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertTrue(csv.contains("\"A\"\"B\""));
        assertEquals(2, csv.split("\n").length);
    }

    @Test
    void matchesSearch_caseInsensitiveAcrossFields() {
        StartupItem it = item("OneDrive", true, 50, StartupItemType.REGISTRY);
        assertTrue(StartupExport.matchesSearch(it, "onedrive"));
        assertFalse(StartupExport.matchesSearch(it, "zzz-no-match"));
    }

    @Test
    void matchesImpact_thresholds() {
        assertTrue(StartupExport.matchesImpact(item("C", true, 500, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.HIGH));
        assertFalse(StartupExport.matchesImpact(item("A", true, 50, StartupItemType.REGISTRY),
                StartupExport.ImpactFilter.HIGH));
    }
}
