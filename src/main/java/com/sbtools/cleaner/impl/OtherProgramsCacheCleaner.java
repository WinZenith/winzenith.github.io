package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Stream;

public class OtherProgramsCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.OTHER_PROGRAMS_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        long totalSize = 0;
        int itemCount = 0;
        long[] sub = new long[2];

        java.util.List<java.util.function.Consumer<CleanupRow>> scanners = java.util.List.of(
                this::scanDiscord, this::scanVscode, this::scanAdobe, this::scanSteam,
                this::scanSlack, this::scanZoom, this::scanTeams);
        for (var scanner : scanners) {
            if (token != null && token.isCancelled()) break;
            scanSubCache(sub, scanner);
            totalSize += sub[0];
            itemCount += (int) sub[1];
        }
        if (token != null && token.isCancelled()) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("Canceled");
            row.setScanStatus(CleanupRow.ScanStatus.ERROR);
            row.setErrorMessage("Scan canceled by user");
            return;
        }

        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        row.setSizeOrCountText(CleanerUtils.formatBytes(totalSize) + (itemCount > 0 ? " (" + itemCount + " files)" : ""));
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        // Every branch receives the token: previously Discord/VSCode/Adobe/Steam/Slack
        // deletions ran to completion even after the user pressed Cancel.
        cleaned += cleanDiscord(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanVscode(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanAdobe(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanSteam(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanSlack(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanZoom(token); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanTeams(token);
        return cleaned;
    }

    private void scanSubCache(long[] result, Consumer<CleanupRow> scanner) {
        CleanupRow temp = new CleanupRow(CleanupCategory.OTHER_PROGRAMS_CACHE);
        scanner.accept(temp);
        result[0] = temp.getTotalBytes();
        result[1] = temp.getItemCount();
    }

    private void scanAppCache(CleanupRow row, List<Path> dirs) {
        long totalSize = 0;
        int itemCount = 0;
        for (Path dir : dirs) {
            if (dir != null && Files.isDirectory(dir)) {
                try (Stream<Path> walk = Files.walk(dir, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH)) {
                    var stats = walk.filter(Files::isRegularFile)
                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                    totalSize += stats.getSum();
                    itemCount += (int) stats.getCount();
                } catch (Exception ignored) {}
            }
        }
        row.setTotalBytes(row.getTotalBytes() + totalSize);
        row.setItemCount(row.getItemCount() + itemCount);
    }

    private long cleanAppCacheDirs(List<Path> dirs, com.sbtools.util.CancellationToken token) {
        long cleaned = 0;
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (Files.isDirectory(dir) && CleanerUtils.isSafeToCleanDirectory(dir)) cleaned += CleanerUtils.deleteDirectoryContents(dir, token);
        }
        return cleaned;
    }

    private List<Path> collectDirs(String... paths) {
        List<Path> dirs = new ArrayList<>();
        for (String p : paths) {
            Path path = Path.of(p);
            if (Files.isDirectory(path)) dirs.add(path);
        }
        return dirs;
    }

    private void scanDiscord(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path discord = Path.of(appData, "discord");
        scanAppCache(row, collectDirs(
                discord.resolve("Cache").toString(),
                discord.resolve("Code Cache").toString(),
                discord.resolve("GPUCache").toString()));
    }

    private long cleanDiscord(com.sbtools.util.CancellationToken token) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path discord = Path.of(appData, "discord");
        return cleanAppCacheDirs(collectDirs(
                discord.resolve("Cache").toString(),
                discord.resolve("Code Cache").toString(),
                discord.resolve("GPUCache").toString()), token);
    }

    private void scanVscode(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path code = Path.of(appData, "Code");
        // CachedExtensions holds installed extensions (not regenerable cache)
        // so only true caches are counted.
        scanAppCache(row, collectDirs(
                code.resolve("Cache").toString(),
                code.resolve("Code Cache").toString(),
                code.resolve("GPUCache").toString()));
    }

    private long cleanVscode(com.sbtools.util.CancellationToken token) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path code = Path.of(appData, "Code");
        return cleanAppCacheDirs(collectDirs(
                code.resolve("Cache").toString(),
                code.resolve("Code Cache").toString(),
                code.resolve("GPUCache").toString()), token);
    }

    private void scanAdobe(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (appData != null) {
            Path adobeCommon = Path.of(appData, "Adobe", "Common");
            scanAppCache(row, collectDirs(
                    adobeCommon.resolve("Media Cache").toString(),
                    adobeCommon.resolve("Media Cache Files").toString()));
        }
        if (localAppData != null) {
            Path adobeLocal = Path.of(localAppData, "Adobe");
            if (Files.isDirectory(adobeLocal)) {
                // CameraRawDatabase holds user XMP edits, not cache — keep only Cache.
                scanAppCache(row, collectDirs(
                        adobeLocal.resolve("CameraRaw").resolve("Cache").toString(),
                        adobeLocal.resolve("Flash Player").resolve("SharedAssets").toString(),
                        adobeLocal.resolve("Color").resolve("CachedProfiles").toString()));
            }
        }
    }

    private long cleanAdobe(com.sbtools.util.CancellationToken token) {
        long cleaned = 0;
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (appData != null) {
            Path adobeCommon = Path.of(appData, "Adobe", "Common");
            cleaned += cleanAppCacheDirs(collectDirs(
                    adobeCommon.resolve("Media Cache").toString(),
                    adobeCommon.resolve("Media Cache Files").toString()), token);
        }
        if (localAppData != null) {
            Path adobeLocal = Path.of(localAppData, "Adobe");
            if (Files.isDirectory(adobeLocal)) {
                cleaned += cleanAppCacheDirs(collectDirs(
                        adobeLocal.resolve("CameraRaw").resolve("Cache").toString(),
                        adobeLocal.resolve("Flash Player").resolve("SharedAssets").toString(),
                        adobeLocal.resolve("Color").resolve("CachedProfiles").toString()), token);
            }
        }
        return cleaned;
    }

    private void scanSteam(CleanupRow row) {
        Path steamDir = findSteamDir();
        if (steamDir == null) return;
        // steamapps/downloading holds in-progress downloads — never touch.
        scanAppCache(row, collectDirs(
                steamDir.resolve("appcache").toString(),
                steamDir.resolve("logs").toString()));
    }

    private long cleanSteam(com.sbtools.util.CancellationToken token) {
        Path steamDir = findSteamDir();
        if (steamDir == null) return 0;
        return cleanAppCacheDirs(collectDirs(
                steamDir.resolve("appcache").toString(),
                steamDir.resolve("logs").toString()), token);
    }

    private Path findSteamDir() {
        String progFilesX86 = CleanerUtils.safeEnv("PROGRAMFILES(X86)");
        if (progFilesX86 != null) {
            Path steam = Path.of(progFilesX86, "Steam");
            if (Files.isDirectory(steam)) return steam;
        }
        String progFiles = CleanerUtils.safeEnv("PROGRAMFILES");
        if (progFiles != null) {
            Path steam = Path.of(progFiles, "Steam");
            if (Files.isDirectory(steam)) return steam;
        }
        return null;
    }

    private void scanSlack(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path slack = Path.of(appData, "Slack");
        scanAppCache(row, collectDirs(
                slack.resolve("Cache").toString(),
                slack.resolve("Code Cache").toString(),
                slack.resolve("GPUCache").toString()));
    }

    private long cleanSlack(com.sbtools.util.CancellationToken token) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path slack = Path.of(appData, "Slack");
        return cleanAppCacheDirs(collectDirs(
                slack.resolve("Cache").toString(),
                slack.resolve("Code Cache").toString(),
                slack.resolve("GPUCache").toString()), token);
    }

    private void scanZoom(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path zoomData = Path.of(appData, "Zoom", "data");
        if (!Files.isDirectory(zoomData) || !CleanerUtils.isSafeToCleanDirectory(zoomData)) return;
        // Logs only: Zoom/data also holds settings — never wipe wholesale.
        try (Stream<Path> walk = Files.walk(zoomData, 1)) {
            var stats = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".log"))
                    .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
            row.setTotalBytes(row.getTotalBytes() + stats.getSum());
            row.setItemCount(row.getItemCount() + (int) stats.getCount());
        } catch (Exception ignored) {}
    }

    private long cleanZoom(com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path zoomData = Path.of(appData, "Zoom", "data");
        if (!Files.isDirectory(zoomData) || !CleanerUtils.isSafeToCleanDirectory(zoomData)) return 0;
        long cleaned = 0;
        try (Stream<Path> files = Files.list(zoomData)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (token != null && token.isCancelled()) break;
                try {
                    if (Files.isRegularFile(f)
                            && f.getFileName().toString().toLowerCase().endsWith(".log")) { long size = Files.size(f); CleanerUtils.deletePermanently(f, token); if (!Files.exists(f)) cleaned += size; }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return cleaned;
    }

    private void scanTeams(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        scanTeamsDirs(row, appData != null ? Path.of(appData, "Microsoft", "Teams") : null);
        scanTeamsDirs(row, appData != null ? Path.of(appData, "Microsoft", "Teams classic") : null);
        if (localAppData != null) {
            Path teamsPackage = Path.of(localAppData, "Packages");
            if (Files.isDirectory(teamsPackage)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(teamsPackage)) {
                    for (Path pkg : ds) {
                        String pkgName = pkg.getFileName().toString();
                        if (pkgName.startsWith("MicrosoftTeams_") || pkgName.startsWith("MSTeams_")) {
                            Path ac = pkg.resolve("AC");
                            if (Files.isDirectory(ac)) scanAppCache(row, collectDirs(
                                    ac.resolve("INetCache").toString(),
                                    ac.resolve("Cache").toString()));
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void scanTeamsDirs(CleanupRow row, Path teamsBase) {
        if (teamsBase == null || !Files.isDirectory(teamsBase)) return;
        scanAppCache(row, collectDirs(
                teamsBase.resolve("Cache").toString(),
                teamsBase.resolve("Code Cache").toString(),
                teamsBase.resolve("Application Cache").toString()));
    }

    private long cleanTeams(com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        cleaned += cleanTeamsDirs(appData != null ? Path.of(appData, "Microsoft", "Teams") : null, token);
        cleaned += cleanTeamsDirs(appData != null ? Path.of(appData, "Microsoft", "Teams classic") : null, token);
        if (localAppData != null) {
            Path teamsPackage = Path.of(localAppData, "Packages");
            if (Files.isDirectory(teamsPackage)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(teamsPackage)) {
                    for (Path pkg : ds) {
                        if (token != null && token.isCancelled()) break;
                        String pkgName = pkg.getFileName().toString();
                        if (pkgName.startsWith("MicrosoftTeams_") || pkgName.startsWith("MSTeams_")) {
                            Path ac = pkg.resolve("AC");
                            // AC holds auth/settings — only cache subdirs are safe.
                            if (Files.isDirectory(ac)) cleaned += cleanAppCacheDirs(collectDirs(
                                    ac.resolve("INetCache").toString(),
                                    ac.resolve("Cache").toString()), token);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    private long cleanTeamsDirs(Path teamsBase, com.sbtools.util.CancellationToken token) {
        if (teamsBase == null || !Files.isDirectory(teamsBase)) return 0;
        return cleanAppCacheDirs(collectDirs(
                teamsBase.resolve("Cache").toString(),
                teamsBase.resolve("Code Cache").toString(),
                teamsBase.resolve("Application Cache").toString()), token);
    }
}
