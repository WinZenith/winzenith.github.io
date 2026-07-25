package com.sbtools.cleaner;

public enum CleanupCategory {
    REGISTRY("Registry", "Invalid or unused registry entries", RiskLevel.MEDIUM),
    EMPTY_RECYCLE_BIN("Empty Recycle Bin", "Files in the Recycle Bin", RiskLevel.LOW),
    JUNK_FILES("Junk files", "Temporary files from applications", RiskLevel.LOW),
    PRIVACY_TRACES("Privacy traces", "Recent documents, run history, and usage traces", RiskLevel.LOW),
    WEB_BROWSING_TRACES("Web browsing traces", "Browser cache, cookies, history, and saved passwords (WARNING: saves passwords, cookies, and history will be deleted)", RiskLevel.MEDIUM),
    CACHE("Cache", "System and application cache data (including INetCache; INetCookies not cleaned for safety)", RiskLevel.LOW),
    INSTALLER_FILES("Installer Files", "Cached installer packages and downloaded setup files (TEMP only)", RiskLevel.MEDIUM),
    TEMPORARY_SYSTEM_FILES("Temporary System Files", "Windows temporary system files and prefetch", RiskLevel.MEDIUM),
    MEMORY_DUMPS("Memory Dumps", "System crash dump files (.dmp)", RiskLevel.LOW),
    WINDOWS_ERROR_REPORTING("Windows Error Reporting", "Archived error reports from Windows Error Reporting", RiskLevel.LOW),
    WINDOWS_UPDATE_CLEANUP("Windows Update Cleanup", "Superseded components in WinSxS via DISM", RiskLevel.HIGH),
    THUMBNAIL_CACHE("Thumbnail Cache", "Explorer thumbnail database cache", RiskLevel.MEDIUM),
    EMPTY_FOLDERS("Empty Folders", "Empty directories under temp folders only (user profile folders excluded for safety)", RiskLevel.LOW),
    NOTIFICATION_HISTORY("Notification History", "Windows toast notification cache", RiskLevel.MEDIUM),
    FONT_CACHE("Font Cache", "System font cache files", RiskLevel.MEDIUM),
    TASKBAR_JUMP_LISTS("Taskbar Jump Lists", "Recent jump list destinations on the taskbar", RiskLevel.LOW),
    OFFICE_DOCUMENT_CACHE("Office Document Cache", "Microsoft Office file cache", RiskLevel.LOW),
    WINDOWS_DEFENDER_CACHE("Windows Defender Cache", "Defender scan history and quarantine files", RiskLevel.LOW),
    WINDOWS_LOG_FILES("Windows Log Files", "System log files (*.log only, .etl trace logs preserved)", RiskLevel.MEDIUM),
    WINDOWS_STORE_CACHE("Windows Store Cache", "Per-app Windows Store package caches", RiskLevel.LOW),
    OTHER_PROGRAMS_CACHE("Other Programs Cache", "Cache data from Discord, VS Code, Adobe, Steam, Slack, Zoom, and Teams", RiskLevel.LOW),
    NVIDIA_SHADER_CACHE("NVIDIA/AMD Shader Cache", "GPU shader cache files that are regenerated on demand", RiskLevel.LOW),
    SOFTWARE_DISTRIBUTION_CACHE("Software Distribution Cache", "Windows Update download cache in SoftwareDistribution\\Download (skipped during active updates)", RiskLevel.MEDIUM),
    WINDOWS_DIAGNOSTICS_CACHE("Diagnostics Cache", "Windows diagnostic and error reporting data files", RiskLevel.LOW),
    OLD_WINDOWS_INSTALL("Previous Windows Installation", "Windows.old folder from a previous OS upgrade (large)", RiskLevel.HIGH),
    DOCKER_CACHE("Docker Cache", "Docker images, containers, volumes at %PROGRAMDATA%\\Docker", RiskLevel.HIGH),
    NPM_CACHE("NPM Cache", "Node.js package manager cache", RiskLevel.LOW),
    YARN_CACHE("Yarn Cache", "Yarn package manager cache", RiskLevel.LOW),
    MAVEN_CACHE("Maven Cache", "Old snapshot artifacts in .m2\\repository", RiskLevel.LOW),
    GRADLE_CACHE("Gradle Cache", "Gradle build tool caches", RiskLevel.LOW),
    PIP_CACHE("PIP Cache", "Python package installer cache", RiskLevel.LOW),
    JETBRAINS_CACHE("JetBrains Cache", "JetBrains IDE cache files (IntelliJ, PyCharm, etc.)", RiskLevel.LOW),
    ITUNES_BACKUPS("iTunes Backups", "iOS backups at %APPDATA%\\Apple Computer\\MobileSync\\Backup", RiskLevel.MEDIUM),
    WINDOWS_SEARCH_CACHE("Windows Search Cache", "Search cache data (service-safe cleanup, index database preserved)", RiskLevel.MEDIUM);

    public enum RiskLevel {
        LOW("Low", "Safe to clean, files are regenerated as needed"),
        MEDIUM("Medium", "May affect some app state or require system restart, review recommended"),
        HIGH("High", "Irreversible or system-critical, review before cleaning");

        private final String displayName;
        private final String description;

        RiskLevel(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
    }

    private final String displayName;
    private final String description;
    private final RiskLevel riskLevel;

    CleanupCategory(String displayName, String description, RiskLevel riskLevel) {
        this.displayName = displayName;
        this.description = description;
        this.riskLevel = riskLevel;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public RiskLevel getRiskLevel() {
        return riskLevel;
    }
}
