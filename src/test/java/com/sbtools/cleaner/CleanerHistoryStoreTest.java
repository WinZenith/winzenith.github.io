package com.sbtools.cleaner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CleanerHistoryStoreTest {

    @TempDir
    Path temp;

    @Test
    void appendAndLoadRoundTrip() throws Exception {
        CleanerHistoryStore store = new CleanerHistoryStore();
        store.setHistoryFileForTests(temp.resolve("cleanup-history.json"));
        CleanupService.CleanSummary summary = new CleanupService.CleanSummary(
                100, 2, Map.of(CleanupCategory.CACHE, 100L), List.of());
        store.append(summary);
        assertEquals(1, store.load().size());
        assertEquals(100, store.getTotalBytesFreedAllTime());
        assertEquals("Cache", CleanerHistoryStore.formatCategoryHistoryKey("CACHE"));
        assertEquals("Legacy Label", CleanerHistoryStore.formatCategoryHistoryKey("Legacy Label"));
    }

    @Test
    void clearRemovesEntries() throws Exception {
        CleanerHistoryStore store = new CleanerHistoryStore();
        store.setHistoryFileForTests(temp.resolve("history.json"));
        store.append(new CleanupService.CleanSummary(50, 1, Map.of(CleanupCategory.JUNK_FILES, 50L)));
        store.clear();
        assertTrue(store.load().isEmpty());
        assertEquals(0, store.getTotalBytesFreedAllTime());
    }
}
