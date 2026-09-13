package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanerUtils;
import com.sbtools.util.CancellationToken;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ItunesBackupsCleanerTest {

    @TempDir
    Path temp;

    @Test
    void measuresAndDeletesDeepBackupTree() throws Exception {
        Path backupRoot = temp.resolve("Backup");
        Path uuid = backupRoot.resolve("00000000-0000-0000-0000-000000000001");
        Path deep = uuid.resolve("a").resolve("b").resolve("c").resolve("file");
        Files.createDirectories(deep.getParent());
        Files.writeString(deep, "backup-data");

        long[] stats = ItunesBackupsCleaner.measureBackupTree(uuid, CancellationToken.NONE);
        assertEquals(1, stats[1]);
        assertTrue(stats[0] > 0);

        assertTrue(ItunesBackupsCleaner.isBackupChildDir(uuid));
        long freed = CleanerUtils.deleteDirectoryContents(uuid, Integer.MAX_VALUE, CancellationToken.NONE);
        assertTrue(freed >= 10);
        assertFalse(Files.exists(deep));
    }
}
