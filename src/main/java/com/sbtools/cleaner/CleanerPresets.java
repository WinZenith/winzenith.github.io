package com.sbtools.cleaner;

import com.sbtools.i18n.Messages;

import java.util.EnumSet;
import java.util.Set;

public enum CleanerPresets {
    SAFE_ONLY("Safe Only", "Low-risk categories only", lowRiskCategories()),
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

    private static EnumSet<CleanupCategory> lowRiskCategories() {
        EnumSet<CleanupCategory> set = EnumSet.noneOf(CleanupCategory.class);
        for (CleanupCategory c : CleanupCategory.values()) {
            if (c.getRiskLevel() == CleanupCategory.RiskLevel.LOW) set.add(c);
        }
        return set;
    }

    public String getDisplayName() { return Messages.get(displayName); }
    public String getDescription() { return Messages.get(description); }
    public Set<CleanupCategory> getCategories() { return categories; }

    @Override
    public String toString() { return getDisplayName(); }
}
