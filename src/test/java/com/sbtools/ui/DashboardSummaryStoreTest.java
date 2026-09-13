package com.sbtools.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DashboardSummaryStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripPreservesRowsAndTimestamp() {
        Path file = tempDir.resolve("dashboard-last.json");
        Instant when = Instant.parse("2024-01-15T12:00:00Z");
        List<DashboardTabView.IssueCategory> issues = List.of(
                new DashboardTabView.IssueCategory("Outdated Software", 2, 0, "Software",
                        List.of("App 1 → 2")),
                DashboardTabView.IssueCategory.error("Outdated Drivers", "err", "", "Drivers", 0));
        DashboardSummaryStore.save(file, when, new ArrayList<>(issues));
        DashboardSummaryStore.Snapshot snap = DashboardSummaryStore.load(file);
        assertNotNull(snap);
        assertEquals(when.toEpochMilli(), snap.scannedEpochMilli());
        assertEquals(2, snap.issues().size());
        assertTrue(snap.issues().get(1).error());
    }

    @Test
    void loadRejectsOversizeFile() throws Exception {
        Path file = tempDir.resolve("big.json");
        byte[] junk = new byte[512 * 1024 + 1];
        Files.write(file, junk);
        assertNull(DashboardSummaryStore.load(file));
    }

    @Test
    void loadReturnsNullOnCorruptJson() throws Exception {
        Path file = tempDir.resolve("bad.json");
        Files.writeString(file, "{not json");
        assertNull(DashboardSummaryStore.load(file));
    }

    @Test
    void saveRespectsMaxRowsAndDetails() {
        Path file = tempDir.resolve("cap.json");
        List<DashboardTabView.IssueCategory> many = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            List<String> details = List.of("a", "b", "c", "d", "e", "f");
            many.add(new DashboardTabView.IssueCategory(
                    "Cat" + i, "1 item", "", "Cleanup", 100, details));
        }
        DashboardSummaryStore.save(file, Instant.now(), many);
        DashboardSummaryStore.Snapshot snap = DashboardSummaryStore.load(file);
        assertNotNull(snap);
        assertEquals(60, snap.issues().size());
        assertTrue(snap.issues().get(0).details().size() <= 5);
    }
}
