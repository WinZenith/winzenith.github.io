package com.sbtools.uninstaller;

import java.time.Instant;

/**
 * Audit record for a single uninstall / force-remove operation.
 */
public record UninstallHistoryEntry(
        String appName,
        String version,
        String publisher,
        String appType,
        String mode,
        boolean success,
        int exitCode,
        int leftoversDeleted,
        String detail,
        Instant uninstalledAt
) {
    public UninstallHistoryEntry(String appName, String version, String publisher,
                                 String appType, String mode, boolean success,
                                 int exitCode, int leftoversDeleted, String detail) {
        this(appName, version, publisher, appType, mode, success,
                exitCode, leftoversDeleted, detail == null ? "" : detail, Instant.now());
    }
}
