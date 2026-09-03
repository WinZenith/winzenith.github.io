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
        long totalSize = 0;
        int itemCount = 0;
        long[] sub = new long[2];

        scanSubCache(sub, this::scanDiscord); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanVscode); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanAdobe); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanSteam); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanSlack); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanZoom); totalSize += sub[0]; itemCount += (int) sub[1];
        scanSubCache(sub, this::scanTeams); totalSize += sub[0]; itemCount += (int) sub[1];

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
        cleaned += cleanDiscord(); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanVscode(); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanAdobe(); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanSteam(); if (token != null && token.isCancelled()) return cleaned;
        cleaned += cleanSlack(); if (token != null && token.isCancelled()) return cleaned;
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
                try (Stream<Path> walk = Files.walk(dir)) {
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

    private long cleanAppCacheDirs(List<Path> dirs) {
        return cleanAppCacheDirs(dirs, com.sbtools.util.CancellationToken.NONE);
    }

    private long cleanAppCacheDirs(List<Path> dirs, com.sbtools.util.CancellationToken token) {
        long cleaned = 0;
        for (Path dir : dirs) {
            if (token != null && token.isCancelled()) break;
            if (Files.isDirectory(dir)) cleaned += CleanerUtils.deleteDirectoryContents(dir, token);
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

    private long cleanDiscord() {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path discord = Path.of(appData, "discord");
        return cleanAppCacheDirs(collectDirs(
                discord.resolve("Cache").toString(),
                discord.resolve("Code Cache").toString(),
                discord.resolve("GPUCache").toString()));
    }

    private void scanVscode(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path code = Path.of(appData, "Code");
        scanAppCache(row, collectDirs(
                code.resolve("Cache").toString(),
                code.resolve("CachedData").toString(),
                code.resolve("CachedExtensions").toString()));
    }

    private long cleanVscode() {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path code = Path.of(appData, "Code");
        return cleanAppCacheDirs(collectDirs(
                code.resolve("Cache").toString(),
                code.resolve("CachedData").toString(),
                code.resolve("CachedExtensions").toString()));
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
                scanAppCache(row, collectDirs(
                        adobeLocal.resolve("CameraRaw").resolve("Cache").toString(),
                        adobeLocal.resolve("CameraRaw").resolve("CameraRawDatabase").toString(),
                        adobeLocal.resolve("Flash Player").resolve("SharedAssets").toString(),
                        adobeLocal.resolve("Color").resolve("CachedProfiles").toString()));
            }
        }
    }

    private long cleanAdobe() {
        long cleaned = 0;
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (appData != null) {
            Path adobeCommon = Path.of(appData, "Adobe", "Common");
            cleaned += cleanAppCacheDirs(collectDirs(
                    adobeCommon.resolve("Media Cache").toString(),
                    adobeCommon.resolve("Media Cache Files").toString()));
        }
        if (localAppData != null) {
            Path adobeLocal = Path.of(localAppData, "Adobe");
            if (Files.isDirectory(adobeLocal)) {
                cleaned += cleanAppCacheDirs(collectDirs(
                        adobeLocal.resolve("CameraRaw").resolve("Cache").toString(),
                        adobeLocal.resolve("CameraRaw").resolve("CameraRawDatabase").toString(),
                        adobeLocal.resolve("Flash Player").resolve("SharedAssets").toString(),
                        adobeLocal.resolve("Color").resolve("CachedProfiles").toString()));
            }
        }
        return cleaned;
    }

    private void scanSteam(CleanupRow row) {
        Path steamDir = findSteamDir();
        if (steamDir == null) return;
        scanAppCache(row, collectDirs(
                steamDir.resolve("appcache").toString(),
                steamDir.resolve("logs").toString(),
                steamDir.resolve("steamapps").resolve("downloading").toString()));
    }

    private long cleanSteam() {
        Path steamDir = findSteamDir();
        if (steamDir == null) return 0;
        return cleanAppCacheDirs(collectDirs(
                steamDir.resolve("appcache").toString(),
                steamDir.resolve("logs").toString(),
                steamDir.resolve("steamapps").resolve("downloading").toString()));
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

    private long cleanSlack() {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path slack = Path.of(appData, "Slack");
        return cleanAppCacheDirs(collectDirs(
                slack.resolve("Cache").toString(),
                slack.resolve("Code Cache").toString(),
                slack.resolve("GPUCache").toString()));
    }

    private void scanZoom(CleanupRow row) {
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return;
        Path zoomData = Path.of(appData, "Zoom", "data");
        if (!Files.isDirectory(zoomData)) return;
        try (Stream<Path> walk = Files.walk(zoomData, 1)) {
            var stats = walk.filter(Files::isRegularFile)
                    .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
            row.setTotalBytes(row.getTotalBytes() + stats.getSum());
            row.setItemCount(row.getItemCount() + (int) stats.getCount());
        } catch (Exception ignored) {}
    }

    private long cleanZoom() {
        return cleanZoom(com.sbtools.util.CancellationToken.NONE);
    }

    private long cleanZoom(com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        String appData = CleanerUtils.safeEnv("APPDATA");
        if (appData == null) return 0;
        Path zoomData = Path.of(appData, "Zoom", "data");
        if (!Files.isDirectory(zoomData)) return 0;
        long cleaned = 0;
        try (Stream<Path> files = Files.list(zoomData)) {
            for (Path f : (Iterable<Path>) files::iterator) {
                if (token != null && token.isCancelled()) break;
                if (Files.isRegularFile(f)) { long size = Files.size(f); CleanerUtils.deletePermanently(f, token); if (!Files.exists(f)) cleaned += size; }
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
                        if (pkgName.contains("MicrosoftTeams") || pkgName.contains("MSTeams")) {
                            Path ac = pkg.resolve("AC");
                            if (Files.isDirectory(ac)) {
                                try (Stream<Path> walk = Files.walk(ac)) {
                                    var stats = walk.filter(Files::isRegularFile)
                                            .collect(java.util.stream.Collectors.summarizingLong(p -> p.toFile().length()));
                                    row.setTotalBytes(row.getTotalBytes() + stats.getSum());
                                    row.setItemCount(row.getItemCount() + (int) stats.getCount());
                                } catch (Exception ignored) {}
                            }
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

    private long cleanTeams() {
        return cleanTeams(com.sbtools.util.CancellationToken.NONE);
    }

    private long cleanTeams(com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String appData = CleanerUtils.safeEnv("APPDATA");
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        cleaned += cleanTeamsDirs(appData != null ? Path.of(appData, "Microsoft", "Teams") : null);
        cleaned += cleanTeamsDirs(appData != null ? Path.of(appData, "Microsoft", "Teams classic") : null);
        if (localAppData != null) {
            Path teamsPackage = Path.of(localAppData, "Packages");
            if (Files.isDirectory(teamsPackage)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(teamsPackage)) {
                    for (Path pkg : ds) {
                        if (token != null && token.isCancelled()) break;
                        String pkgName = pkg.getFileName().toString();
                        if (pkgName.contains("MicrosoftTeams") || pkgName.contains("MSTeams")) {
                            Path ac = pkg.resolve("AC");
                            if (Files.isDirectory(ac)) cleaned += CleanerUtils.deleteDirectoryContents(ac, token);
                        }
                    }
                } catch (Exception ignored) {}
            }
        }
        return cleaned;
    }

    private long cleanTeamsDirs(Path teamsBase) {
        if (teamsBase == null || !Files.isDirectory(teamsBase)) return 0;
        return cleanAppCacheDirs(collectDirs(
                teamsBase.resolve("Cache").toString(),
                teamsBase.resolve("Code Cache").toString(),
                teamsBase.resolve("Application Cache").toString()));
    }
}
