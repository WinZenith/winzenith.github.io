package com.sbtools.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class BackupHealthTest {

    @TempDir
    Path temp;

    @Test
    void deepInfIsHealthy() throws Exception {
        Path folder = temp.resolve("backup");
        Path deep = folder;
        for (int i = 0; i < 6; i++) {
            deep = deep.resolve("d" + i);
        }
        Files.createDirectories(deep);
        Files.writeString(deep.resolve("driver.inf"), ";");
        BackupHealth.Stats stats = BackupHealth.inspect(folder);
        assertEquals(BackupHealth.Status.OK, stats.status());
        assertEquals(1, stats.infCount());
    }

    @Test
    void unsafePathRejected() {
        assertFalse(BackupHealth.isPathShapeSafe(Path.of("C:\\Windows\\Temp\\x\\y")));
    }
}
