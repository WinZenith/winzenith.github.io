package com.sbtools.cleaner;

public enum CleanupCategory {
    REGISTRY("Registry", "Invalid or unused registry entries"),
    REGISTRY_DEFRAG("Registry Defrag", "Compact and optimize registry hives"),
    EMPTY_RECYCLE_BIN("Empty Recycle Bin", "Files in the Recycle Bin"),
    JUNK_FILES("Junk files", "Temporary files from applications"),
    INVALID_SHORTCUTS("Invalid shortcuts", "Broken shortcut (.lnk) files"),
    PRIVACY_TRACES("Privacy traces", "Recent documents, run history, and usage traces"),
    WEB_BROWSING_TRACES("Web browsing traces", "Browser cache, cookies, and history across all browsers"),
    CACHE("Cache", "System and application cache data"),
    INSTALLER_FILES("Installer Files", "Cached installer packages and downloaded setup files"),
    TEMPORARY_SYSTEM_FILES("Temporary System Files", "Windows temporary system files, prefetch, and update cache"),
    MEMORY_DUMPS("Memory Dumps", "System crash dump files (.dmp)"),
    WINDOWS_ERROR_REPORTING("Windows Error Reporting", "Archived error reports from Windows Error Reporting"),
    WINDOWS_UPDATE_CLEANUP("Windows Update Cleanup", "Superseded components in WinSxS via DISM"),
    THUMBNAIL_CACHE("Thumbnail Cache", "Explorer thumbnail database cache"),
    EMPTY_FOLDERS("Empty Folders", "Empty directories under user profile and temp folders"),
    NOTIFICATION_HISTORY("Notification History", "Windows toast notification cache"),
    FONT_CACHE("Font Cache", "System font cache files"),
    TASKBAR_JUMP_LISTS("Taskbar Jump Lists", "Recent jump list destinations on the taskbar"),
    OFFICE_DOCUMENT_CACHE("Office Document Cache", "Microsoft Office file cache"),
    WINDOWS_DEFENDER_CACHE("Windows Defender Cache", "Defender scan history and quarantine files"),
    WINDOWS_LOG_FILES("Windows Log Files", "System log files (*.log)"),
    WINDOWS_STORE_CACHE("Windows Store Cache", "Per-app Windows Store package caches"),
    OTHER_PROGRAMS_CACHE("Other Programs Cache", "Cache data from Discord, VS Code, Adobe, Steam, Slack, Zoom, and Teams");

    private final String displayName;
    private final String description;

    CleanupCategory(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }
}
