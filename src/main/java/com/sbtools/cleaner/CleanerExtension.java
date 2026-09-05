package com.sbtools.cleaner;

import java.nio.file.Path;

public interface CleanerExtension {

    CleanupCategory getCategory();

    void scan(CleanupRow row);

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
