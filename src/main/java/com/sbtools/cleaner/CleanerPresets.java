package com.sbtools.cleaner;

import java.util.EnumSet;
import java.util.Set;

/**
 * Predefined selection presets for cleanup categories.
 */
public enum CleanerPresets {
    SAFE_ONLY("Safe Only", "Low-risk categories only", EnumSet.of(
            CleanupCategory.EMPTY_RECYCLE_BIN,
            CleanupCategory.JUNK_FILES,
            CleanupCategory.CACHE,
            CleanupCategory.THUMBNAIL_CACHE,
            CleanupCategory.FONT_CACHE,
            CleanupCategory.NOTIFICATION_HISTORY,
            CleanupCategory.TASKBAR_JUMP_LISTS,
            CleanupCategory.WINDOWS_LOG_FILES,
            CleanupCategory.NVIDIA_SHADER_CACHE
    )),
    HIGH_IMPACT("High Impact", "Categories that free the most space", EnumSet.of(
            CleanupCategory.REGISTRY,
            CleanupCategory.WEB_BROWSING_TRACES,
            CleanupCategory.WINDOWS_UPDATE_CLEANUP,
            CleanupCategory.OTHER_PROGRAMS_CACHE,
            CleanupCategory.OLD_WINDOWS_INSTALL,
            CleanupCategory.SOFTWARE_DISTRIBUTION_CACHE
    )),
    PRIVACY("Privacy", "Remove browsing and usage traces", EnumSet.of(
            CleanupCategory.PRIVACY_TRACES,
            CleanupCategory.WEB_BROWSING_TRACES,
            CleanupCategory.TASKBAR_JUMP_LISTS,
            CleanupCategory.OFFICE_DOCUMENT_CACHE
    )),
    MAINTENANCE("Maintenance", "System health and optimization", EnumSet.of(
            CleanupCategory.REGISTRY,
            CleanupCategory.REGISTRY_DEFRAG,
            CleanupCategory.WINDOWS_ERROR_REPORTING,
            CleanupCategory.WINDOWS_DEFENDER_CACHE,
            CleanupCategory.WINDOWS_DIAGNOSTICS_CACHE,
            CleanupCategory.MEMORY_DUMPS
    ));

    private final String displayName;
    private final String description;
    private final Set<CleanupCategory> categories;

    CleanerPresets(String displayName, String description, Set<CleanupCategory> categories) {
        this.displayName = displayName;
        this.description = description;
        this.categories = categories;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public Set<CleanupCategory> getCategories() {
        return categories;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
