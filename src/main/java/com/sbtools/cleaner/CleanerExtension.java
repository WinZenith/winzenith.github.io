package com.sbtools.cleaner;

import java.nio.file.Path;

public interface CleanerExtension {

    CleanupCategory getCategory();

    void scan(CleanupRow row);

    /**
     * Cooperative-cancel scan. Defaults to the legacy {@link #scan(CleanupRow)}
     * so existing cleaners keep working; long-running cleaners override this to
     * honor the token mid-walk / mid-process. The service routes all async scans
     * through this method.
     */
    default void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) {
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }
        scan(row);
    }

    long clean(Path backupRootOrNull) throws Exception;

    default long clean(Path backupRootOrNull, com.sbtools.util.CancellationToken token) throws Exception {
        if (token != null && token.isCancelled()) return 0L;
        return clean(backupRootOrNull);
    }

    default boolean requiresAdmin() {
        return false;
    }

    default CleanupCategory.RiskLevel getRiskLevel() {
        return getCategory().getRiskLevel();
    }

    default long getCleanTimeoutSeconds() {
        return 120;
    }

    /**
     * Human-readable target locations for preview dialogs.
     * Additive: defaults to empty; cleaners may override without breaking existing code.
     */
    default java.util.List<String> describeTargets() {
        return java.util.Collections.emptyList();
    }
}
