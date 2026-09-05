package com.sbtools.cleaner;

import java.util.EnumSet;
import java.util.Set;

public enum CleanerPresets {
    SAFE_ONLY("Safe Only", "Low-risk categories only", EnumSet.of(
            CleanupCategory.EMPTY_RECYCLE_BIN,
            CleanupCategory.JUNK_FILES,
            CleanupCategory.PRIVACY_TRACES,
            CleanupCategory.CACHE,
            CleanupCategory.TEMPORARY_SYSTEM_FILES,
            CleanupCategory.MEMORY_DUMPS,
            CleanupCategory.WINDOWS_ERROR_REPORTING,
            CleanupCategory.THUMBNAIL_CACHE,
            CleanupCategory.EMPTY_FOLDERS,
            CleanupCategory.NOTIFICATION_HISTORY,
            CleanupCategory.TASKBAR_JUMP_LISTS,
            CleanupCategory.OFFICE_DOCUMENT_CACHE,
            CleanupCategory.WINDOWS_DEFENDER_CACHE,
            CleanupCategory.WINDOWS_STORE_CACHE,
            CleanupCategory.OTHER_PROGRAMS_CACHE,
            CleanupCategory.NVIDIA_SHADER_CACHE,
            CleanupCategory.WINDOWS_DIAGNOSTICS_CACHE,
            CleanupCategory.NPM_CACHE,
            CleanupCategory.YARN_CACHE,
            CleanupCategory.MAVEN_CACHE,
            CleanupCategory.GRADLE_CACHE,
            CleanupCategory.PIP_CACHE,
            CleanupCategory.JETBRAINS_CACHE,
            CleanupCategory.DELIVERY_OPTIMIZATION_FILES,
            CleanupCategory.DIRECTX_SHADER_CACHE,
            CleanupCategory.SERVICE_PROFILE_TEMP,
            CleanupCategory.ONEDRIVE_SYNC_LOGS,
            CleanupCategory.VSCODE_WORKSPACE_STORAGE,
            CleanupCategory.DOTNET_TEMP_CACHE
    )),
    HIGH_IMPACT("High Impact", "Categories that free the most space", EnumSet.of(
            CleanupCategory.REGISTRY,
            CleanupCategory.OTHER_PROGRAMS_CACHE,
            CleanupCategory.SOFTWARE_DISTRIBUTION_CACHE,
            CleanupCategory.DOCKER_CACHE,
            CleanupCategory.GRADLE_CACHE,
            CleanupCategory.JETBRAINS_CACHE
    )),
    PRIVACY("Privacy", "Remove browsing and usage traces", EnumSet.of(
            CleanupCategory.PRIVACY_TRACES,
            CleanupCategory.WEB_BROWSING_TRACES,
            CleanupCategory.TASKBAR_JUMP_LISTS,
            CleanupCategory.OFFICE_DOCUMENT_CACHE,
            CleanupCategory.WINDOWS_SEARCH_CACHE
    )),
    MAINTENANCE("Maintenance", "System health and optimization", EnumSet.of(
            CleanupCategory.REGISTRY,
            CleanupCategory.WINDOWS_ERROR_REPORTING,
            CleanupCategory.WINDOWS_DEFENDER_CACHE,
            CleanupCategory.WINDOWS_DIAGNOSTICS_CACHE,
            CleanupCategory.MEMORY_DUMPS
    )),
    DEV_TOOLS("Dev Tools", "Clear caches from development tools", EnumSet.of(
            CleanupCategory.NPM_CACHE,
            CleanupCategory.YARN_CACHE,
            CleanupCategory.MAVEN_CACHE,
            CleanupCategory.GRADLE_CACHE,
            CleanupCategory.PIP_CACHE,
            CleanupCategory.JETBRAINS_CACHE,
            CleanupCategory.VSCODE_WORKSPACE_STORAGE,
            CleanupCategory.DOTNET_TEMP_CACHE
    ));

    private final String displayName;
    private final String description;
    private final Set<CleanupCategory> categories;

    CleanerPresets(String displayName, String description, Set<CleanupCategory> categories) {
        this.displayName = displayName;
        this.description = description;
        this.categories = categories;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public Set<CleanupCategory> getCategories() { return categories; }

    @Override
    public String toString() { return displayName; }
}
