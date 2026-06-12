package com.sbtools.backup;

import com.sbtools.util.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DriverBackupServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void formatFileSizeBytes() {
        assertEquals("512 B", RestoreRow.formatFileSize(512));
        assertEquals("0 B", RestoreRow.formatFileSize(0));
        assertEquals("1023 B", RestoreRow.formatFileSize(1023));
    }

    @Test
    void formatFileSizeKilobytes() {
        assertEquals("1.0 KB", RestoreRow.formatFileSize(1024));
        assertEquals("1.5 KB", RestoreRow.formatFileSize(1536));
        assertEquals("10.0 KB", RestoreRow.formatFileSize(10240));
    }

    @Test
    void formatFileSizeMegabytes() {
        assertEquals("1.0 MB", RestoreRow.formatFileSize(1024 * 1024));
        assertEquals("2.5 MB", RestoreRow.formatFileSize((long) (2.5 * 1024 * 1024)));
    }

    @Test
    void backupIndexRoundtripWithTempFile() throws IOException {
        Path indexFile = tempDir.resolve("index.json");

        BackupIndex index = new BackupIndex();
        index.getEntries().add(new DriverBackupEntry(
                "test-id", "PCI\\VEN_1234", "Test GPU",
                Instant.parse("2025-06-01T12:00:00Z"),
                tempDir.resolve("backup1").toString(),
                "3.0.1", "oem100.inf"
        ));
        index.getEntries().add(new DriverBackupEntry(
                "test-id-2", "PCI\\VEN_5678", "Test NIC",
                Instant.parse("2025-06-02T08:00:00Z"),
                tempDir.resolve("backup2").toString(),
                "1.2.0", "oem200.inf"
        ));

        JsonMapper.mapper().writerWithDefaultPrettyPrinter()
                .writeValue(indexFile.toFile(), index);

        assertTrue(Files.exists(indexFile));
        BackupIndex loaded = JsonMapper.mapper().readValue(indexFile.toFile(), BackupIndex.class);

        assertEquals(2, loaded.getEntries().size());
        assertEquals("test-id", loaded.getEntries().get(0).id());
        assertEquals("PCI\\VEN_5678", loaded.getEntries().get(1).deviceId());
        assertEquals("Test GPU", loaded.getEntries().get(0).friendlyName());
        assertEquals("3.0.1", loaded.getEntries().get(0).version());
    }

    @Test
    void backupEntryRemovalFromIndex() throws IOException {
        Path indexFile = tempDir.resolve("index.json");

        BackupIndex index = new BackupIndex();
        index.getEntries().add(new DriverBackupEntry(
                "keep-id", "device-1", "Keep",
                Instant.now(), "/keep", "1.0", "keep.inf"
        ));
        index.getEntries().add(new DriverBackupEntry(
                "remove-id", "device-2", "Remove",
                Instant.now(), "/remove", "2.0", "remove.inf"
        ));

        JsonMapper.mapper().writeValue(indexFile.toFile(), index);

        BackupIndex loaded = JsonMapper.mapper().readValue(indexFile.toFile(), BackupIndex.class);
        loaded.getEntries().removeIf(e -> e.id().equals("remove-id"));

        assertEquals(1, loaded.getEntries().size());
        assertEquals("keep-id", loaded.getEntries().get(0).id());
    }

    @Test
    void deleteDirectoryRecursively() throws IOException {
        Path subDir = tempDir.resolve("sub").resolve("nested");
        Files.createDirectories(subDir);
        Path file1 = tempDir.resolve("sub").resolve("file1.txt");
        Path file2 = subDir.resolve("file2.txt");
        Files.writeString(file1, "content1");
        Files.writeString(file2, "content2");

        assertTrue(Files.exists(file1));
        assertTrue(Files.exists(file2));

        Files.walk(tempDir.resolve("sub"))
                .sorted((a, b) -> -a.compareTo(b))
                .forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {}
                });

        assertFalse(Files.exists(file1));
        assertFalse(Files.exists(file2));
        assertFalse(Files.isDirectory(subDir));
    }

    @Test
    void directorySizeCalculation() throws IOException {
        Path dir = tempDir.resolve("sizedir");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("a.txt"), "hello");       // 5 bytes
        Files.writeString(dir.resolve("b.txt"), "world!");      // 6 bytes

        long total = Files.walk(dir)
                .filter(Files::isRegularFile)
                .mapToLong(p -> {
                    try { return Files.size(p); } catch (IOException e) { return 0; }
                })
                .sum();

        assertEquals(11, total);
    }

    @Test
    void restoreRowDeviceNameFallback() {
        DriverBackupEntry entryWithFriendly = new DriverBackupEntry(
                "id", "PCI\\VEN_1234", "GeForce RTX 4090",
                Instant.now(), "/path", "1.0", "test.inf"
        );
        RestoreRow row1 = new RestoreRow(entryWithFriendly);
        assertEquals("GeForce RTX 4090", row1.deviceNameProperty().get());

        DriverBackupEntry entryWithoutFriendly = new DriverBackupEntry(
                "id2", "PCI\\VEN_5678", null,
                Instant.now(), "/path", "1.0", "test.inf"
        );
        RestoreRow row2 = new RestoreRow(entryWithoutFriendly);
        assertEquals("PCI\\VEN_5678", row2.deviceNameProperty().get());

        DriverBackupEntry entryBlankFriendly = new DriverBackupEntry(
                "id3", "PCI\\VEN_9999", "  ",
                Instant.now(), "/path", "1.0", "test.inf"
        );
        RestoreRow row3 = new RestoreRow(entryBlankFriendly);
        assertEquals("PCI\\VEN_9999", row3.deviceNameProperty().get());
    }

    @Test
    void restoreRowVersionFallback() {
        DriverBackupEntry entryNoVersion = new DriverBackupEntry(
                "id", "device", "Name",
                Instant.now(), "/path", null, "test.inf"
        );
        RestoreRow row = new RestoreRow(entryNoVersion);
        assertEquals("\u2014", row.versionProperty().get());
    }

    @Test
    void restoreRowBackupFolderMissingShowsDash() {
        DriverBackupEntry entry = new DriverBackupEntry(
                "id", "device", "Name",
                Instant.now(), "/nonexistent/path/that/does/not/exist", "1.0", "test.inf"
        );
        RestoreRow row = new RestoreRow(entry);
        assertEquals("\u2014", row.sizeProperty().get());
    }
}
