package com.sbtools.cleaner;

import java.nio.file.Path;

public interface CleanerExtension {

    CleanupCategory getCategory();

    void scan(CleanupRow row);

    long clean(Path backupRootOrNull) throws Exception;

    default boolean requiresAdmin() {
        return false;
    }

    default CleanupCategory.RiskLevel getRiskLevel() {
        return getCategory().getRiskLevel();
    }

    default long getCleanTimeoutSeconds() {
        return 120;
    }
}
