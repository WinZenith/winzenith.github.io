package com.sbtools.backup;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record DriverBackupEntry(
        String id,
        String deviceId,
        String friendlyName,
        Instant createdAt,
        String backupFolder,
        String version,
        String infName
) {
}
