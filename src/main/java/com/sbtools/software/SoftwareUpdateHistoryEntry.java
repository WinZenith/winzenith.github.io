package com.sbtools.software;

import java.time.Instant;

public record SoftwareUpdateHistoryEntry(
        String packageName,
        String packageId,
        String oldVersion,
        String newVersion,
        String source,
        Instant installedAt,
        boolean success,
        String errorMessage
) {}
