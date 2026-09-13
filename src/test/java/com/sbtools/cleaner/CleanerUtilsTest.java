package com.sbtools.cleaner;

import com.sbtools.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CleanerUtilsTest {

    @TempDir
    Path temp;

    @Test
    void scanAndDeleteRespectSameDepth() throws Exception {
        Path root = temp.resolve("cache");
        Path shallow = root.resolve("a").resolve("shallow.txt");
        Path deep = root.resolve("a").resolve("b").resolve("c").resolve("d").resolve("deep.txt");
        Files.createDirectories(deep.getParent());
        Files.writeString(shallow, "12345");
        Files.writeString(deep, "67890");

        CleanupRow row = new CleanupRow(CleanupCategory.CACHE);
        CleanerUtils.scanDirectorySizes(row, List.of(root), 3, CancellationToken.NONE);
        assertEquals(5, row.getTotalBytes());

        long freed = CleanerUtils.deleteDirectoryContents(root, 3, CancellationToken.NONE);
        assertEquals(5, freed);
        assertTrue(Files.exists(deep));
    }

    @Test
    void unsafeRootIsSkippedForScanAndDelete(@TempDir Path dir) throws Exception {
        Path unsafe = dir.getRoot() != null ? dir.getRoot() : dir;
        CleanupRow row = new CleanupRow(CleanupCategory.CACHE);
        CleanerUtils.scanDirectorySizes(row, List.of(unsafe), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, null);
        assertEquals(0, row.getTotalBytes());
        assertEquals(0, CleanerUtils.deleteDirectoryContents(unsafe, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, null));
    }
}
