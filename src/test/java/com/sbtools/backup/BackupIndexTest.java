package com.sbtools.backup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BackupIndexTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void emptyIndexSerializesAndDeserializes() throws IOException {
        BackupIndex index = new BackupIndex();
        String json = mapper.writeValueAsString(index);
        BackupIndex deserialized = mapper.readValue(json, BackupIndex.class);
        assertNotNull(deserialized.getEntries());
        assertTrue(deserialized.getEntries().isEmpty());
    }

    @Test
    void setEntriesNullCreatesEmptyList() {
        BackupIndex index = new BackupIndex();
        index.setEntries(null);
        assertNotNull(index.getEntries());
        assertTrue(index.getEntries().isEmpty());
    }

    @Test
    void roundtripWithEntries() throws IOException {
        BackupIndex index = new BackupIndex();
        index.getEntries().add(new DriverBackupEntry(
                "id-1", "device-1", "Test Device",
                Instant.parse("2025-01-15T10:30:00Z"),
                "/backup/folder", "1.0.0", "oem123.inf"
        ));
        index.getEntries().add(new DriverBackupEntry(
                "id-2", "device-2", "Another Device",
                Instant.parse("2025-02-20T14:00:00Z"),
                "/backup/folder2", "2.0.0", "oem456.inf"
        ));

        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
        BackupIndex deserialized = mapper.readValue(json, BackupIndex.class);

        assertEquals(2, deserialized.getEntries().size());
        assertEquals("id-1", deserialized.getEntries().get(0).id());
        assertEquals("device-2", deserialized.getEntries().get(1).deviceId());
        assertEquals("Test Device", deserialized.getEntries().get(0).friendlyName());
    }

    @Test
    void handlesUnknownFieldsGracefully() throws IOException {
        String json = """
                {
                  "entries": [
                    {
                      "id": "id-1",
                      "deviceId": "device-1",
                      "friendlyName": "Test",
                      "createdAt": "2025-01-15T10:30:00Z",
                      "backupFolder": "/tmp",
                      "version": "1.0",
                      "infName": "test.inf",
                      "unknownField": "should be ignored"
                    }
                  ],
                  "extraTopLevelField": 42
                }
                """;
        BackupIndex deserialized = mapper.readValue(json, BackupIndex.class);
        assertEquals(1, deserialized.getEntries().size());
        assertEquals("id-1", deserialized.getEntries().get(0).id());
    }

    @Test
    void driverBackupEntryDefaultsWork() {
        DriverBackupEntry entry = new DriverBackupEntry(
                "id-1", "device-1", "Test",
                Instant.now(), "/path", "1.0", "test.inf"
        );
        assertEquals("id-1", entry.id());
        assertEquals("device-1", entry.deviceId());
        assertEquals("Test", entry.friendlyName());
        assertNotNull(entry.createdAt());
        assertEquals("/path", entry.backupFolder());
        assertEquals("1.0", entry.version());
        assertEquals("test.inf", entry.infName());
    }

    @Test
    void entriesListIsMutable() {
        BackupIndex index = new BackupIndex();
        assertTrue(index.getEntries().isEmpty());

        index.getEntries().add(new DriverBackupEntry(
                "id-1", "device-1", "Test",
                Instant.now(), "/path", "1.0", "test.inf"
        ));
        assertEquals(1, index.getEntries().size());

        index.getEntries().clear();
        assertTrue(index.getEntries().isEmpty());
    }
}
