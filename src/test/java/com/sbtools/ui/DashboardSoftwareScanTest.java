package com.sbtools.ui;

import com.sbtools.software.SoftwareUpdateEntry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardSoftwareScanTest {

    @Test
    void cleanEmptyScanProducesNoCategory() {
        var built = DashboardTabView.resolveSoftwareScanCategory(List.of(), null, null, List.of());
        assertNull(built.category());
        assertFalse(built.categoryFailed());
    }

    @Test
    void failedEmptyScanProducesErrorCategory() {
        var built = DashboardTabView.resolveSoftwareScanCategory(
                List.of(), "winget missing", null, List.of());
        assertNotNull(built.category());
        assertTrue(built.categoryFailed());
        assertTrue(built.category().isError());
    }

    @Test
    void partialResultsMarkFailedWithWarningDetail() {
        SoftwareUpdateEntry e = new SoftwareUpdateEntry("Pub.App", "App", "1", "2");
        var built = DashboardTabView.resolveSoftwareScanCategory(
                List.of(e), null, "WU timeout", List.of("App 1 → 2"));
        assertNotNull(built.category());
        assertTrue(built.categoryFailed());
        assertFalse(built.category().isError());
        assertTrue(built.category().getDetails().stream().anyMatch(d -> d.contains("Partial scan")));
    }

    @Test
    void cleanResultsSucceed() {
        SoftwareUpdateEntry e = new SoftwareUpdateEntry("Pub.App", "App", "1", "2");
        var built = DashboardTabView.resolveSoftwareScanCategory(
                List.of(e), null, null, List.of("App 1 → 2"));
        assertNotNull(built.category());
        assertFalse(built.categoryFailed());
        assertEquals(1, built.category().getCount());
    }
}
