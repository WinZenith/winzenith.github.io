package com.sbtools.cleaner;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class CleanupService {

    public static final class CleanSummary {
        private final long totalBytes;
        private final int totalItems;
        private final Map<CleanupCategory, Long> perCategory;

        public CleanSummary(long totalBytes, int totalItems, Map<CleanupCategory, Long> perCategory) {
            this.totalBytes = totalBytes;
            this.totalItems = totalItems;
            this.perCategory = perCategory;
        }

        public long getTotalBytes() { return totalBytes; }
        public int getTotalItems() { return totalItems; }
        public Map<CleanupCategory, Long> getPerCategory() { return perCategory; }
    }

    public List<CleanupRow> scan(Runnable onProgress) {
        List<CleanupRow> rows = new ArrayList<>();
        for (CleanupCategory cat : CleanupCategory.values()) {
            CleanupRow row = new CleanupRow(cat);
            scanCategory(row);
            if (onProgress != null) onProgress.run();
            rows.add(row);
        }
        return rows;
    }

    private void scanCategory(CleanupRow row) {
        try {
            switch (row.getCategory()) {
                case REGISTRY -> scanRegistry(row);
                case EMPTY_RECYCLE_BIN -> scanRecycleBin(row);
                case JUNK_FILES -> scanJunkFiles(row);
                case INVALID_SHORTCUTS -> scanInvalidShortcuts(row);
                case PRIVACY_TRACES -> scanPrivacyTraces(row);
                case WEB_BROWSING_TRACES -> scanBrowserTraces(row);
                case CACHE -> scanCache(row);
                case INSTALLER_FILES -> scanInstallerFiles(row);
                case TEMPORARY_SYSTEM_FILES -> scanTempSystemFiles(row);
                case MEMORY_DUMPS -> scanMemoryDumps(row);
                case WINDOWS_ERROR_REPORTING -> scanWindowsErrorReporting(row);
                case WINDOWS_UPDATE_CLEANUP -> scanWindowsUpdateCleanup(row);
                case THUMBNAIL_CACHE -> scanThumbnailCache(row);
                case EMPTY_FOLDERS -> scanEmptyFolders(row);
                case NOTIFICATION_HISTORY -> scanNotificationHistory(row);
                case FONT_CACHE -> scanFontCache(row);
                case TASKBAR_JUMP_LISTS -> scanTaskbarJumpLists(row);
                case OFFICE_DOCUMENT_CACHE -> scanOfficeDocumentCache(row);
                case WINDOWS_DEFENDER_CACHE -> scanWindowsDefenderCache(row);
                case WINDOWS_LOG_FILES -> scanWindowsLogFiles(row);
                case WINDOWS_STORE_CACHE -> scanWindowsStoreCache(row);
            }
        } catch (Exception e) {
            AppLogger.warning("Scan failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
            row.setSizeOrCountText("Error");
        }
    }

    public CleanSummary clean(List<CleanupRow> selectedRows, boolean registryBackup, Runnable onProgress) {
        long totalBytes = 0;
        int totalItems = 0;
        Map<CleanupCategory, Long> perCategory = new HashMap<>();

        Path backupRoot = null;
        if (registryBackup) {
            backupRoot = AppPaths.backupsRoot().resolve("cleanup-backups")
                    .resolve(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        }

        for (CleanupRow row : selectedRows) {
            if (!row.isSelected()) continue;
            try {
                long cleaned = cleanCategory(row.getCategory(), registryBackup ? backupRoot : null);
                int items = row.getItemCount();
                totalBytes += cleaned;
                totalItems += items;
                perCategory.put(row.getCategory(), cleaned);
                if (onProgress != null) onProgress.run();
            } catch (Exception e) {
                AppLogger.warning("Clean failed for " + row.getCategory().getDisplayName() + ": " + e.getMessage());
            }
        }
        return new CleanSummary(totalBytes, totalItems, perCategory);
    }

    private long cleanCategory(CleanupCategory category, Path backupRootOrNull) throws Exception {
        return switch (category) {
            case REGISTRY -> cleanRegistry(backupRootOrNull);
            case EMPTY_RECYCLE_BIN -> cleanRecycleBin();
            case JUNK_FILES -> cleanDirectoryPattern(getJunkDirs());
            case INVALID_SHORTCUTS -> cleanInvalidShortcuts();
            case PRIVACY_TRACES -> cleanPrivacyTraces();
            case WEB_BROWSING_TRACES -> cleanBrowserTraces();
            case CACHE -> cleanDirectoryPattern(getCacheDirs());
            case INSTALLER_FILES -> cleanInstallerFiles();
            case TEMPORARY_SYSTEM_FILES -> cleanDirectoryPattern(getTempSystemDirs());
            case MEMORY_DUMPS -> cleanMemoryDumps();
            case WINDOWS_ERROR_REPORTING -> cleanWindowsErrorReporting();
            case WINDOWS_UPDATE_CLEANUP -> cleanWindowsUpdateCleanup();
            case THUMBNAIL_CACHE -> cleanThumbnailCache();
            case EMPTY_FOLDERS -> cleanEmptyFolders();
            case NOTIFICATION_HISTORY -> cleanNotificationHistory();
            case FONT_CACHE -> cleanFontCache();
            case TASKBAR_JUMP_LISTS -> cleanTaskbarJumpLists();
            case OFFICE_DOCUMENT_CACHE -> cleanOfficeDocumentCache();
            case WINDOWS_DEFENDER_CACHE -> cleanWindowsDefenderCache();
            case WINDOWS_LOG_FILES -> cleanWindowsLogFiles();
            case WINDOWS_STORE_CACHE -> cleanWindowsStoreCache();
        };
    }

    // ── Registry ──────────────────────────────────────────────────────────

    private void scanRegistry(CleanupRow row) {
        int count = 0;
        count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        count += countInvalidRegistryValues(WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        count += countInvalidRegistryValues(WinReg.HKEY_LOCAL_MACHINE,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        row.setItemCount(count);
        row.setSizeOrCountText(count + " invalid entr" + (count == 1 ? "y" : "ies"));
    }

    private int countInvalidRegistryValues(WinReg.HKEY hive, String keyPath) {
        int count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (value.contains("\\") && !value.startsWith("-")) {
                        String filePath = value;
                        if (filePath.toLowerCase().startsWith("c:")) {
                            int idx = filePath.indexOf('\\');
                            if (idx >= 0) {
                                String possiblePath = filePath.substring(idx);
                                if (!possiblePath.isEmpty()) {
                                    Path p = Paths.get(possiblePath);
                                    if (!Files.exists(p.getParent())) {
                                        count++;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return count;
    }

    private long cleanRegistry(Path backupRootOrNull) {
        long cleaned = 0;
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_CURRENT_USER,
                "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        cleaned += deleteInvalidRegistryValues(backupRootOrNull, WinReg.HKEY_LOCAL_MACHINE,
                "Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        return cleaned;
    }

    private long deleteInvalidRegistryValues(Path backupRootOrNull, WinReg.HKEY hive, String keyPath) {
        long count = 0;
        try {
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
                List<String> toDelete = new ArrayList<>();
                for (Map.Entry<String, Object> entry : values.entrySet()) {
                    String value = entry.getValue().toString();
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (value.contains("\\") && !value.startsWith("-")) {
                        String filePath = value;
                        if (filePath.toLowerCase().startsWith("c:")) {
                            int idx = filePath.indexOf('\\');
                            if (idx >= 0) {
                                String possiblePath = filePath.substring(idx);
                                if (!possiblePath.isEmpty()) {
                                    Path p = Paths.get(possiblePath);
                                    if (!Files.exists(p.getParent())) {
                                        toDelete.add(entry.getKey());
                                    }
                                }
                            }
                        }
                    }
                }
                if (!toDelete.isEmpty()) {
                    if (backupRootOrNull != null) {
                        Path regBackup = backupRootOrNull.resolve("registry-" + keyPath.replace("\\", "_") + ".reg");
                        Files.createDirectories(regBackup.getParent());
                        try {
                            new ProcessBuilder("reg", "export",
                                    hive == WinReg.HKEY_LOCAL_MACHINE ? "HKLM" : "HKCU" + "\\" + keyPath,
                                    regBackup.toString(), "/y")
                                    .inheritIO().start().waitFor();
                        } catch (Exception ignored) {}
                    }

                    for (String valName : toDelete) {
                        try {
                            Advapi32Util.registryDeleteValue(hive, keyPath, valName);
                            count++;
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Registry cleanup error: " + e.getMessage());
        }
        return count;
    }

    // ── Recycle Bin ───────────────────────────────────────────────────────

    private void scanRecycleBin(CleanupRow row) {
        long size = 0;
        try {
            Path recycleBin = Paths.get(System.getenv("SYSTEMDRIVE") + "\\$Recycle.Bin");
            if (Files.isDirectory(recycleBin)) {
                try (Stream<Path> walk = Files.walk(recycleBin)) {
                    size = walk.filter(Files::isRegularFile)
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                }
            }
        } catch (Exception ignored) {}
        row.setTotalBytes(size);
        if (size > 0) {
            row.setSizeOrCountText(formatBytes(size));
        } else {
            row.setSizeOrCountText("Empty");
        }
    }

    private long cleanRecycleBin() {
        try {
            ProcessBuilder pb = new ProcessBuilder("cmd", "/c",
                    "rd", "/s", "/q", System.getenv("SYSTEMDRIVE") + "\\$Recycle.Bin");
            pb.inheritIO().start().waitFor();
        } catch (Exception ex) {
            AppLogger.warning("Failed to empty Recycle Bin: " + ex.getMessage());
        }
        return 0;
    }

    // ── Junk Files ────────────────────────────────────────────────────────

    private List<Path> getJunkDirs() {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("TEMP"));
        addPath(dirs, System.getenv("TMP"));
        addPath(dirs, System.getenv("WINDIR") + "\\Temp");
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Temp");
        return dirs;
    }

    private void scanJunkFiles(CleanupRow row) {
        scanDirectorySizes(row, getJunkDirs());
    }

    // ── Invalid Shortcuts ─────────────────────────────────────────────────

    private void scanInvalidShortcuts(CleanupRow row) {
        int count = 0;
        List<Path> searchPaths = new ArrayList<>();
        addPath(searchPaths, System.getenv("USERPROFILE") + "\\Desktop");
        addPath(searchPaths, System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu");
        addPath(searchPaths, System.getenv("APPDATA") + "\\Microsoft\\Internet Explorer\\Quick Launch");
        addPath(searchPaths, System.getenv("PUBLIC") + "\\Desktop");

        for (Path dir : searchPaths) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.find(dir, 5,
                        (p, a) -> p.toString().toLowerCase().endsWith(".lnk") && a.isRegularFile())) {
                    count += files.mapToInt(p -> isShortcutBroken(p) ? 1 : 0).sum();
                } catch (Exception ignored) {}
            }
        }
        row.setItemCount(count);
        row.setSizeOrCountText(count + " broken shortcut" + (count == 1 ? "" : "s"));
    }

    private boolean isShortcutBroken(Path lnkPath) {
        try {
            byte[] data = Files.readAllBytes(lnkPath);
            if (data.length < 28) return true;
            int flags = (data[20] & 0xFF) | ((data[21] & 0xFF) << 8);
            boolean hasTargetIdList = (flags & 0x01) != 0;
            boolean hasLinkInfo = (flags & 0x02) != 0;
            boolean hasRelativePath = (flags & 0x04) != 0;

            int offset = 78;
            String target = "";

            if (hasRelativePath && offset + 4 < data.length) {
                int charCount = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
                offset += 4;
                if (offset + charCount * 2 <= data.length) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < charCount - 1; i++) {
                        char c = (char) ((data[offset + i * 2] & 0xFF) | ((data[offset + i * 2 + 1] & 0xFF) << 8));
                        sb.append(c);
                    }
                    target = sb.toString().trim();
                }
            } else if (hasLinkInfo && offset + 28 < data.length) {
                int linkInfoSize = (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8)
                        | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
                if (linkInfoSize >= 28 && offset + 28 < data.length) {
                    int localBasePathOffset = (data[offset + 24] & 0xFF) | ((data[offset + 25] & 0xFF) << 8)
                            | ((data[offset + 26] & 0xFF) << 16) | ((data[offset + 27] & 0xFF) << 24);
                    if (localBasePathOffset > 0 && offset + localBasePathOffset < data.length) {
                        int maxChars = (data.length - offset - localBasePathOffset) / 2;
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < maxChars; i++) {
                            int pos = offset + localBasePathOffset + i * 2;
                            if (pos + 1 >= data.length) break;
                            char c = (char) ((data[pos] & 0xFF) | ((data[pos + 1] & 0xFF) << 8));
                            if (c == 0) break;
                            sb.append(c);
                        }
                        target = sb.toString().trim();
                    }
                }
            }

            if (target.isEmpty()) return true;
            Path targetPath = Paths.get(target);
            return !Files.exists(targetPath);

        } catch (Exception e) {
            return true;
        }
    }

    private long cleanInvalidShortcuts() {
        long cleaned = 0;
        List<Path> searchPaths = new ArrayList<>();
        addPath(searchPaths, System.getenv("USERPROFILE") + "\\Desktop");
        addPath(searchPaths, System.getenv("APPDATA") + "\\Microsoft\\Windows\\Start Menu");
        addPath(searchPaths, System.getenv("APPDATA") + "\\Microsoft\\Internet Explorer\\Quick Launch");
        addPath(searchPaths, System.getenv("PUBLIC") + "\\Desktop");

        for (Path dir : searchPaths) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.find(dir, 5,
                        (p, a) -> p.toString().toLowerCase().endsWith(".lnk") && a.isRegularFile())) {
                    for (Path lnk : (Iterable<Path>) files::iterator) {
                        if (isShortcutBroken(lnk)) {
                            deletePermanently(lnk);
                            cleaned++;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Privacy Traces ────────────────────────────────────────────────────

    private void scanPrivacyTraces(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;

        Path recentDir = Paths.get(System.getenv("APPDATA") + "\\Microsoft\\Windows\\Recent");
        if (Files.isDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        totalSize += Files.size(f);
                        itemCount++;
                    }
                }
            } catch (Exception ignored) {}
        }

        scanRegistryPrefetch(row);
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(itemCount + " item" + (itemCount == 1 ? "" : "s") + " / " + formatBytes(totalSize));
    }

    private void scanRegistryPrefetch(CleanupRow row) {
        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                // just count entries
            }
        } catch (Exception ignored) {}
    }

    private long cleanPrivacyTraces() {
        long cleaned = 0;
        Path recentDir = Paths.get(System.getenv("APPDATA") + "\\Microsoft\\Windows\\Recent");
        if (Files.isDirectory(recentDir)) {
            try (Stream<Path> files = Files.list(recentDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    }
                }
            } catch (Exception ignored) {}
        }

        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU")) {
                Map<String, Object> values = Advapi32Util.registryGetValues(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU");
                for (String key : values.keySet()) {
                    if (!"MRUListEx".equals(key) && !"MRUList".equals(key)) {
                        try {
                            Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER,
                                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RunMRU", key);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            if (Advapi32Util.registryKeyExists(WinReg.HKEY_CURRENT_USER,
                    "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs")) {
                Advapi32Util.registryDeleteKey(WinReg.HKEY_CURRENT_USER,
                        "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\RecentDocs");
            }
        } catch (Exception ignored) {}

        return cleaned;
    }

    // ── Web Browsing Traces ───────────────────────────────────────────────

    private record BrowserProfile(String name, List<Path> cacheDirs) {}

    private List<BrowserProfile> getBrowserProfiles() {
        List<BrowserProfile> profiles = new ArrayList<>();

        String localAppData = System.getenv("LOCALAPPDATA");
        String appData = System.getenv("APPDATA");

        // Chrome
        List<Path> chromeDirs = new ArrayList<>();
        if (localAppData != null) {
            Path chrome = Paths.get(localAppData, "Google", "Chrome", "User Data", "Default");
            chromeDirs.add(chrome.resolve("Cache"));
            chromeDirs.add(chrome.resolve("Code Cache"));
            chromeDirs.add(chrome.resolve("Network"));
        }
        profiles.add(new BrowserProfile("Chrome", chromeDirs));

        // Edge
        List<Path> edgeDirs = new ArrayList<>();
        if (localAppData != null) {
            Path edge = Paths.get(localAppData, "Microsoft", "Edge", "User Data", "Default");
            edgeDirs.add(edge.resolve("Cache"));
            edgeDirs.add(edge.resolve("Code Cache"));
            edgeDirs.add(edge.resolve("Network"));
        }
        profiles.add(new BrowserProfile("Edge", edgeDirs));

        // Firefox
        List<Path> firefoxDirs = new ArrayList<>();
        if (appData != null) {
            Path firefoxProfiles = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
            if (Files.isDirectory(firefoxProfiles)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(firefoxProfiles)) {
                    for (Path profile : ds) {
                        firefoxDirs.add(profile.resolve("cache2"));
                        firefoxDirs.add(profile.resolve("thumbnails"));
                        firefoxDirs.add(profile.resolve("offlinecache"));
                    }
                } catch (Exception ignored) {}
            }
        }
        profiles.add(new BrowserProfile("Firefox", firefoxDirs));

        // Brave
        List<Path> braveDirs = new ArrayList<>();
        if (localAppData != null) {
            Path brave = Paths.get(localAppData, "BraveSoftware", "Brave-Browser", "User Data", "Default");
            braveDirs.add(brave.resolve("Cache"));
            braveDirs.add(brave.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Brave", braveDirs));

        // Opera
        List<Path> operaDirs = new ArrayList<>();
        if (appData != null) {
            Path opera = Paths.get(appData, "Opera Software", "Opera Stable");
            operaDirs.add(opera.resolve("Cache"));
            operaDirs.add(opera.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Opera", operaDirs));

        // Opera GX
        List<Path> operaGxDirs = new ArrayList<>();
        if (appData != null) {
            Path operaGX = Paths.get(appData, "Opera Software", "Opera GX Stable");
            operaGxDirs.add(operaGX.resolve("Cache"));
            operaGxDirs.add(operaGX.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Opera GX", operaGxDirs));

        // Vivaldi
        List<Path> vivaldiDirs = new ArrayList<>();
        if (localAppData != null) {
            Path vivaldi = Paths.get(localAppData, "Vivaldi", "User Data", "Default");
            vivaldiDirs.add(vivaldi.resolve("Cache"));
            vivaldiDirs.add(vivaldi.resolve("Code Cache"));
        }
        profiles.add(new BrowserProfile("Vivaldi", vivaldiDirs));

        return profiles;
    }

    private void scanBrowserTraces(CleanupRow row) {
        long totalSize = 0;
        for (BrowserProfile profile : getBrowserProfiles()) {
            for (Path dir : profile.cacheDirs()) {
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        totalSize += walk.filter(Files::isRegularFile)
                                .mapToLong(p -> p.toFile().length())
                                .sum();
                    } catch (Exception ignored) {}
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanBrowserTraces() {
        long cleaned = 0;
        for (BrowserProfile profile : getBrowserProfiles()) {
            for (Path dir : profile.cacheDirs()) {
                if (Files.isDirectory(dir)) {
                    cleaned += deleteDirectoryContents(dir);
                }
            }
            // Also try to delete cookies and history files
            String localAppData = System.getenv("LOCALAPPDATA");
            String appData = System.getenv("APPDATA");
            String userProfile = System.getenv("USERPROFILE");
            List<Path> extraFiles = new ArrayList<>();
            switch (profile.name()) {
                case "Chrome" -> {
                    if (localAppData != null) {
                        Path base = Paths.get(localAppData, "Google", "Chrome", "User Data", "Default");
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("Cookies-journal"));
                        extraFiles.add(base.resolve("History"));
                        extraFiles.add(base.resolve("History-journal"));
                        extraFiles.add(base.resolve("Login Data"));
                    }
                }
                case "Edge" -> {
                    if (localAppData != null) {
                        Path base = Paths.get(localAppData, "Microsoft", "Edge", "User Data", "Default");
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("Cookies-journal"));
                        extraFiles.add(base.resolve("History"));
                        extraFiles.add(base.resolve("History-journal"));
                        extraFiles.add(base.resolve("Login Data"));
                    }
                }
                case "Firefox" -> {
                    if (appData != null) {
                        Path profilesDir = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
                        if (Files.isDirectory(profilesDir)) {
                            try (DirectoryStream<Path> ds = Files.newDirectoryStream(profilesDir)) {
                                for (Path firefoxProfile : ds) {
                                    extraFiles.add(firefoxProfile.resolve("cookies.sqlite"));
                                    extraFiles.add(firefoxProfile.resolve("cookies.sqlite-wal"));
                                    extraFiles.add(firefoxProfile.resolve("places.sqlite"));
                                    extraFiles.add(firefoxProfile.resolve("places.sqlite-wal"));
                                    extraFiles.add(firefoxProfile.resolve("formhistory.sqlite"));
                                    extraFiles.add(firefoxProfile.resolve("favicons.sqlite"));
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
                case "Brave" -> {
                    if (localAppData != null) {
                        Path base = Paths.get(localAppData, "BraveSoftware", "Brave-Browser", "User Data", "Default");
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("History"));
                    }
                }
                case "Vivaldi" -> {
                    if (localAppData != null) {
                        Path base = Paths.get(localAppData, "Vivaldi", "User Data", "Default");
                        extraFiles.add(base.resolve("Cookies"));
                        extraFiles.add(base.resolve("History"));
                    }
                }
            }
            for (Path f : extraFiles) {
                if (Files.isRegularFile(f)) {
                    try {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    } catch (Exception ignored) {}
                }
            }
        }
        return cleaned;
    }

    // ── Cache ─────────────────────────────────────────────────────────────

    private List<Path> getCacheDirs() {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\INetCache");
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\INetCookies");
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Temp");
        return dirs;
    }

    private void scanCache(CleanupRow row) {
        scanDirectorySizes(row, getCacheDirs());
    }

    // ── Installer Files ───────────────────────────────────────────────────

    private void scanInstallerFiles(CleanupRow row) {
        long totalSize = 0;
        List<Path> dirs = new ArrayList<>();

        Path winInstaller = Paths.get(System.getenv("WINDIR") + "\\Installer");
        if (Files.isDirectory(winInstaller)) {
            dirs.add(winInstaller);
        }

        Path tempDir = Paths.get(System.getenv("TEMP"));
        if (Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".msi") || name.endsWith(".exe")) {
                            long size = Files.size(f);
                            if (size > 10 * 1024 * 1024) {
                                totalSize += size;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // Downloaded installer directories
        Path downloads = Paths.get(System.getenv("USERPROFILE") + "\\Downloads");
        if (Files.isDirectory(downloads)) {
            try (Stream<Path> files = Files.list(downloads)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if ((name.endsWith(".msi") || name.endsWith(".exe")) && !name.contains("unins")) {
                            try {
                                long size = Files.size(f);
                                if (size > 10 * 1024 * 1024) {
                                    totalSize += size;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        for (Path dir : dirs) {
            if (Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir)) {
                    totalSize += walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".msi") || name.contains(".cab");
                            })
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {}
            }
        }

        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanInstallerFiles() {
        long cleaned = 0;

        Path tempDir = Paths.get(System.getenv("TEMP"));
        if (Files.isDirectory(tempDir)) {
            try (Stream<Path> files = Files.list(tempDir)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if (name.endsWith(".msi") || name.endsWith(".exe")) {
                            long size = Files.size(f);
                            if (size > 10 * 1024 * 1024) {
                                deletePermanently(f);
                                cleaned += size;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        Path downloads = Paths.get(System.getenv("USERPROFILE") + "\\Downloads");
        if (Files.isDirectory(downloads)) {
            try (Stream<Path> files = Files.list(downloads)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (Files.isRegularFile(f)) {
                        String name = f.getFileName().toString().toLowerCase();
                        if ((name.endsWith(".msi") || name.endsWith(".exe")) && !name.contains("unins")) {
                            try {
                                long size = Files.size(f);
                                if (size > 10 * 1024 * 1024) {
                                    deletePermanently(f);
                                    cleaned += size;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return cleaned;
    }

    // ── Temporary System Files ────────────────────────────────────────────

    private List<Path> getTempSystemDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        String sysdrive = System.getenv("SYSTEMDRIVE");
        if (windir != null) {
            addPath(dirs, windir + "\\Prefetch");
            addPath(dirs, windir + "\\SoftwareDistribution\\Download");
            addPath(dirs, windir + "\\Logs");
        }
        if (sysdrive != null) {
            addPath(dirs, sysdrive + "\\$Windows.~BT");
            addPath(dirs, sysdrive + "\\$Windows.~WS");
            addPath(dirs, sysdrive + "\\$SysReset");
        }
        return dirs;
    }

    private void scanTempSystemFiles(CleanupRow row) {
        scanDirectorySizes(row, getTempSystemDirs());
    }

    // ── Memory Dumps ──────────────────────────────────────────────────────

    private void scanMemoryDumps(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("WINDIR") + "\\Minidump");
        addPath(dirs, System.getenv("WINDIR"));
        long totalSize = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    totalSize += files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".dmp"))
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {}
            }
        }
        // Also check root for memory.dmp
        String sysdrive = System.getenv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path rootDump = Paths.get(sysdrive + "\\memory.dmp");
            if (Files.isRegularFile(rootDump)) {
                totalSize += rootDump.toFile().length();
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanMemoryDumps() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("WINDIR") + "\\Minidump");
        String sysdrive = System.getenv("SYSTEMDRIVE");
        if (sysdrive != null) {
            Path rootDump = Paths.get(sysdrive + "\\memory.dmp");
            if (Files.isRegularFile(rootDump)) {
                long size = rootDump.toFile().length();
                deletePermanently(rootDump);
                cleaned += size;
            }
            Path swaDump = Paths.get(sysdrive + "\\SWA.DMP");
            if (Files.isRegularFile(swaDump)) {
                long size = swaDump.toFile().length();
                deletePermanently(swaDump);
                cleaned += size;
            }
        }
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().endsWith(".dmp")) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Windows Error Reporting ───────────────────────────────────────────

    private void scanWindowsErrorReporting(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\WER");
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows\\WER");
        scanDirectorySizes(row, dirs, 4);
    }

    private long cleanWindowsErrorReporting() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\WER");
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows\\WER");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Windows Update Cleanup ────────────────────────────────────────────

    private void scanWindowsUpdateCleanup(CleanupRow row) {
        long totalSize = 0;
        // Try fast DISM query with short timeout
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image",
                    "/AnalyzeComponentStore");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
                for (String line : output.split("\\n")) {
                    if (line.contains("Size of superseded components")) {
                        String[] parts = line.split(":");
                        if (parts.length >= 2) {
                            String sizeStr = parts[1].replaceAll("[^0-9]", "").trim();
                            if (!sizeStr.isEmpty()) {
                                totalSize = Long.parseLong(sizeStr) * 1024L * 1024L;
                            }
                        }
                    }
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
        // Fallback: scan SoftwareDistribution\Download
        if (totalSize == 0) {
            List<Path> dirs = new ArrayList<>();
            addPath(dirs, System.getenv("WINDIR") + "\\SoftwareDistribution\\Download");
            try {
                for (Path dir : dirs) {
                    if (dir != null && Files.isDirectory(dir)) {
                        try (Stream<Path> walk = Files.walk(dir)) {
                            totalSize += walk.filter(Files::isRegularFile)
                                    .mapToLong(p -> p.toFile().length()).sum();
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize) + (totalSize > 0 ? "" : " (via fallback)"));
    }

    private long cleanWindowsUpdateCleanup() {
        long cleaned = 0;
        try {
            ProcessBuilder pb = new ProcessBuilder("dism", "/Online", "/Cleanup-Image",
                    "/StartComponentCleanup", "/ResetBase");
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
            cleaned += 1; // Signal success since DISM doesn't report bytes
        } catch (Exception e) {
            AppLogger.warning("DISM cleanup failed: " + e.getMessage());
        }
        // Also clean SoftwareDistribution\Download
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            Path sd = Paths.get(windir + "\\SoftwareDistribution\\Download");
            if (Files.isDirectory(sd)) {
                cleaned += deleteDirectoryContents(sd);
            }
        }
        return cleaned;
    }

    // ── Thumbnail Cache ───────────────────────────────────────────────────

    private void scanThumbnailCache(CleanupRow row) {
        long totalSize = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    totalSize += files.filter(Files::isRegularFile)
                            .filter(p -> p.getFileName().toString().toLowerCase().startsWith("thumbcache_"))
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanThumbnailCache() {
        long cleaned = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path explorerDir = Paths.get(localAppData, "Microsoft", "Windows", "Explorer");
            if (Files.isDirectory(explorerDir)) {
                try (Stream<Path> files = Files.list(explorerDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().startsWith("thumbcache_")) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {}
                // Also delete iconcache_* files
                try (Stream<Path> files = Files.list(explorerDir)) {
                    for (Path f : (Iterable<Path>) files::iterator) {
                        if (Files.isRegularFile(f) && f.getFileName().toString().toLowerCase().startsWith("iconcache_")) {
                            long size = Files.size(f);
                            deletePermanently(f);
                            cleaned += size;
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Empty Folders ─────────────────────────────────────────────────────

    private void scanEmptyFolders(CleanupRow row) {
        int count = 0;
        List<Path> roots = new ArrayList<>();
        String userHome = System.getenv("USERPROFILE");
        if (userHome != null) {
            addPath(roots, userHome + "\\Desktop");
            addPath(roots, userHome + "\\Documents");
            addPath(roots, userHome + "\\Downloads");
            addPath(roots, userHome + "\\Pictures");
            addPath(roots, userHome + "\\Music");
            addPath(roots, userHome + "\\Videos");
        }
        addPath(roots, System.getenv("TEMP"));
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    count += (int) walk.filter(Files::isDirectory)
                            .filter(this::isEmptyDirectory)
                            .count();
                } catch (Exception ignored) {}
            }
        }
        row.setItemCount(count);
        row.setSizeOrCountText(count + " empty folder" + (count == 1 ? "" : "s"));
    }

    private boolean isEmptyDirectory(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            return !ds.iterator().hasNext();
        } catch (Exception e) {
            return false;
        }
    }

    private long cleanEmptyFolders() {
        long cleaned = 0;
        List<Path> roots = new ArrayList<>();
        String userHome = System.getenv("USERPROFILE");
        if (userHome != null) {
            addPath(roots, userHome + "\\Desktop");
            addPath(roots, userHome + "\\Documents");
            addPath(roots, userHome + "\\Downloads");
            addPath(roots, userHome + "\\Pictures");
            addPath(roots, userHome + "\\Music");
            addPath(roots, userHome + "\\Videos");
        }
        addPath(roots, System.getenv("TEMP"));
        for (Path root : roots) {
            if (root != null && Files.isDirectory(root)) {
                try (Stream<Path> walk = Files.walk(root, 3)) {
                    List<Path> emptyDirs = walk.filter(Files::isDirectory)
                            .filter(this::isEmptyDirectory)
                            .sorted(Comparator.reverseOrder())
                            .toList();
                    for (Path dir : emptyDirs) {
                        try {
                            Files.deleteIfExists(dir);
                            cleaned++;
                        } catch (Exception ignored) {}
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Notification History ──────────────────────────────────────────────

    private void scanNotificationHistory(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\Notifications");
        scanDirectorySizes(row, dirs);
    }

    private long cleanNotificationHistory() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("LOCALAPPDATA") + "\\Microsoft\\Windows\\Notifications");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Font Cache ────────────────────────────────────────────────────────

    private void scanFontCache(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("WINDIR") + "\\ServiceProfiles\\LocalService\\AppData\\Local\\FontCache");
        scanDirectorySizes(row, dirs);
    }

    private long cleanFontCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("WINDIR") + "\\ServiceProfiles\\LocalService\\AppData\\Local\\FontCache");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Taskbar Jump Lists ────────────────────────────────────────────────

    private void scanTaskbarJumpLists(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("APPDATA") + "\\Microsoft\\Windows\\Recent\\AutomaticDestinations");
        scanDirectorySizes(row, dirs);
    }

    private long cleanTaskbarJumpLists() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("APPDATA") + "\\Microsoft\\Windows\\Recent\\AutomaticDestinations");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Office Document Cache ─────────────────────────────────────────────

    private void scanOfficeDocumentCache(CleanupRow row) {
        long totalSize = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) {
                                try (Stream<Path> walk = Files.walk(fileCache)) {
                                    totalSize += walk.filter(Files::isRegularFile)
                                            .mapToLong(p -> p.toFile().length()).sum();
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanOfficeDocumentCache() {
        long cleaned = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path officeParent = Paths.get(localAppData, "Microsoft", "Office");
            if (Files.isDirectory(officeParent)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(officeParent)) {
                    for (Path versionDir : ds) {
                        if (Files.isDirectory(versionDir)) {
                            Path fileCache = versionDir.resolve("OfficeFileCache");
                            if (Files.isDirectory(fileCache)) {
                                cleaned += deleteDirectoryContents(fileCache);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Windows Defender Cache ────────────────────────────────────────────

    private void scanWindowsDefenderCache(CleanupRow row) {
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows Defender\\Scans\\History");
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows Defender\\Quarantine");
        scanDirectorySizes(row, dirs, 4);
    }

    private long cleanWindowsDefenderCache() {
        long cleaned = 0;
        List<Path> dirs = new ArrayList<>();
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows Defender\\Scans\\History");
        addPath(dirs, System.getenv("PROGRAMDATA") + "\\Microsoft\\Windows Defender\\Quarantine");
        cleaned += cleanDirectoryPattern(dirs);
        return cleaned;
    }

    // ── Windows Log Files ─────────────────────────────────────────────────

    private void scanWindowsLogFiles(CleanupRow row) {
        long totalSize = 0;
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                try (Stream<Path> walk = Files.walk(logsDir, 2)) {
                    totalSize += walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                String name = p.getFileName().toString().toLowerCase();
                                return name.endsWith(".log") || name.endsWith(".etl");
                            })
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanWindowsLogFiles() {
        long cleaned = 0;
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            Path logsDir = Paths.get(windir, "Logs");
            if (Files.isDirectory(logsDir)) {
                cleaned += deleteDirectoryContents(logsDir);
            }
        }
        return cleaned;
    }

    private List<Path> getLogDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = System.getenv("WINDIR");
        if (windir != null) {
            addPath(dirs, windir + "\\Logs");
        }
        return dirs;
    }

    // ── Windows Store Cache ───────────────────────────────────────────────

    private void scanWindowsStoreCache(CleanupRow row) {
        long totalSize = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (Files.isDirectory(pkg)) {
                            Path localState = pkg.resolve("LocalState");
                            if (Files.isDirectory(localState)) {
                                try (Stream<Path> walk = Files.walk(localState, 1)) {
                                    totalSize += walk.filter(Files::isRegularFile)
                                            .filter(f -> { try { return !Files.isHidden(f); } catch (Exception e) { return true; } })
                                            .mapToLong(f -> f.toFile().length()).sum();
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanWindowsStoreCache() {
        long cleaned = 0;
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path packagesDir = Paths.get(localAppData, "Packages");
            if (Files.isDirectory(packagesDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(packagesDir)) {
                    for (Path pkg : ds) {
                        if (Files.isDirectory(pkg)) {
                            Path localState = pkg.resolve("LocalState");
                            if (Files.isDirectory(localState)) {
                                cleaned += deleteDirectoryContents(localState);
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    // ── Shared helpers ────────────────────────────────────────────────────

    private void addPath(List<Path> list, String pathStr) {
        if (pathStr != null && !pathStr.isBlank()) {
            Path p = Paths.get(pathStr);
            if (Files.exists(p)) {
                list.add(p);
            }
        }
    }

    private void scanDirectorySizes(CleanupRow row, List<Path> dirs) {
        scanDirectorySizes(row, dirs, -1);
    }

    private void scanDirectorySizes(CleanupRow row, List<Path> dirs, int maxDepth) {
        long totalSize = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = maxDepth > 0 ? Files.walk(dir, maxDepth) : Files.walk(dir)) {
                    totalSize += walk.filter(Files::isRegularFile)
                            .filter(p -> {
                                try {
                                    return !Files.isHidden(p);
                                } catch (Exception e) {
                                    return true;
                                }
                            })
                            .mapToLong(p -> p.toFile().length())
                            .sum();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(totalSize);
        row.setSizeOrCountText(formatBytes(totalSize));
    }

    private long cleanDirectoryPattern(List<Path> dirs) {
        long cleaned = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                cleaned += deleteDirectoryContents(dir);
            }
        }
        return cleaned;
    }

    private long deleteDirectoryContents(Path dir) {
        long cleaned = 0;
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> sorted = walk.sorted(Comparator.reverseOrder()).toList();
            for (Path f : sorted) {
                if (f.equals(dir)) continue;
                try {
                    if (Files.isRegularFile(f) || Files.isSymbolicLink(f)) {
                        long size = Files.size(f);
                        deletePermanently(f);
                        cleaned += size;
                    } else if (Files.isDirectory(f)) {
                        deletePermanently(f);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return cleaned;
    }

    private void deletePermanently(Path source) {
        try {
            Files.deleteIfExists(source);
        } catch (IOException e) {
            try {
                source.toFile().deleteOnExit();
            } catch (Exception ignored) {}
        }
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
