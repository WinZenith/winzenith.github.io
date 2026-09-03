package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import com.sbtools.util.ProcessManager;

import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class BrowserTracesCleaner implements CleanerExtension {

    private static final java.util.Map<String, String> BROWSER_PROCESS_MAP = new java.util.LinkedHashMap<>(java.util.Map.of(
            "Chrome", "chrome.exe",
            "Edge", "msedge.exe",
            "Firefox", "firefox.exe",
            "Brave", "brave.exe",
            "Vivaldi", "vivaldi.exe",
            "Opera", "opera.exe",
            "Opera GX", "opera.exe",
            "Chromium", "chromium.exe",
            "Yandex Browser", "yandexbrowser.exe"
    ));

    // Cached tasklist output to avoid spawning tasklist per profile
    private static volatile String cachedTaskListOutput = null;
    private static volatile long cachedTaskListTimestamp = 0;
    private static final long TASKLIST_CACHE_MS = 5000;

    private record BrowserProfile(String name, List<Path> cacheDirs) {}

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.WEB_BROWSING_TRACES; }

    @Override
    public void scan(CleanupRow row) {
        long totalSize = 0;
        int itemCount = 0;
        int skippedProfiles = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        String appData = CleanerUtils.safeEnv("APPDATA");
        // Consistent with clean(): running browsers are skipped (locked DBs),
        // so scan only counts what clean can actually reclaim.
        for (BrowserProfile profile : getBrowserProfiles()) {
            String browserKey = getBrowserProcessKey(profile.name());
            boolean browserRunning = browserKey != null && isBrowserRunning(BROWSER_PROCESS_MAP.get(browserKey));
            if (browserRunning) { skippedProfiles++; continue; }
            for (Path dir : profile.cacheDirs()) {
                if (Files.isDirectory(dir)) {
                    try (Stream<Path> walk = Files.walk(dir)) {
                        var stats = walk.filter(Files::isRegularFile)
                                .collect(java.util.stream.Collectors.summarizingLong(p -> {
                                    try { return Files.size(p); } catch (Exception e) { return p.toFile().length(); }
                                }));
                        totalSize += stats.getSum();
                        itemCount += (int) stats.getCount();
                    } catch (Exception ignored) {}
                }
            }
            // Include DB files that clean() will delete so scan accurately reflects reclaimable space
            List<Path> extraFiles = collectExtraFilesForProfile(profile.name(), localAppData, appData);
            for (Path f : extraFiles) {
                if (Files.isRegularFile(f)) {
                    try { totalSize += Files.size(f); itemCount++; } catch (Exception ignored) {}
                }
            }
        }
        row.setTotalBytes(totalSize);
        row.setItemCount(itemCount);
        String base = itemCount + " item" + (itemCount == 1 ? "" : "s") + " / " + CleanerUtils.formatBytes(totalSize);
        if (skippedProfiles > 0) base += " (skipped " + skippedProfiles + " running browser profile" + (skippedProfiles == 1 ? "" : "s") + " — close browser to clean)";
        row.setSizeOrCountText(base);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        long cleaned = 0;
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        String appData = CleanerUtils.safeEnv("APPDATA");

        for (BrowserProfile profile : getBrowserProfiles()) {
            if (token != null && token.isCancelled()) break;
            String browserKey = getBrowserProcessKey(profile.name());
            boolean browserRunning = browserKey != null && isBrowserRunning(BROWSER_PROCESS_MAP.get(browserKey));

            if (!browserRunning) {
                for (Path dir : profile.cacheDirs()) {
                    if (token != null && token.isCancelled()) break;
                    if (Files.isDirectory(dir)) cleaned += CleanerUtils.deleteDirectoryContents(dir, token);
                }
            }

            List<Path> extraFiles = collectExtraFilesForProfile(profile.name(), localAppData, appData);
            if (!browserRunning) {
                for (Path f : extraFiles) {
                    if (token != null && token.isCancelled()) break;
                    if (Files.isRegularFile(f)) {
                        try {
                            long size = Files.size(f);
                            CleanerUtils.deletePermanently(f, token);
                            if (!Files.exists(f)) cleaned += size;
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        return cleaned;
    }

    private List<Path> collectExtraFilesForProfile(String profileName, String localAppData, String appData) {
        List<Path> extraFiles = new ArrayList<>();
        String name = profileName;
        if (name.startsWith("Chrome")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "Google", "Chrome", "User Data", name); if (base != null) addBrowserDbFiles(extraFiles, base, true); }
        } else if (name.startsWith("Edge")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "Microsoft", "Edge", "User Data", name); if (base != null) addBrowserDbFiles(extraFiles, base, true); }
        } else if (name.equals("Firefox")) {
            if (appData != null) {
                Path profilesDir = Paths.get(appData, "Mozilla", "Firefox", "Profiles");
                if (Files.isDirectory(profilesDir)) {
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(profilesDir)) {
                        for (Path fp : ds) {
                            extraFiles.add(fp.resolve("cookies.sqlite"));
                            extraFiles.add(fp.resolve("cookies.sqlite-wal"));
                            extraFiles.add(fp.resolve("places.sqlite"));
                            extraFiles.add(fp.resolve("places.sqlite-wal"));
                            extraFiles.add(fp.resolve("formhistory.sqlite"));
                            extraFiles.add(fp.resolve("favicons.sqlite"));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } else if (name.startsWith("Brave")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "BraveSoftware", "Brave-Browser", "User Data", name); if (base != null) { extraFiles.add(base.resolve("Cookies")); extraFiles.add(base.resolve("History")); } }
        } else if (name.startsWith("Vivaldi")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "Vivaldi", "User Data", name); if (base != null) { extraFiles.add(base.resolve("Cookies")); extraFiles.add(base.resolve("History")); } }
        } else if (name.equals("Opera")) {
            if (appData != null) { Path base = Paths.get(appData, "Opera Software", "Opera Stable"); extraFiles.add(base.resolve("Cookies")); extraFiles.add(base.resolve("History")); }
        } else if (name.equals("Opera GX")) {
            if (appData != null) { Path base = Paths.get(appData, "Opera Software", "Opera GX Stable"); extraFiles.add(base.resolve("Cookies")); extraFiles.add(base.resolve("History")); }
        } else if (name.startsWith("Chromium")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "Chromium", "User Data", name); if (base != null) addBrowserDbFiles(extraFiles, base, true); }
        } else if (name.startsWith("Yandex Browser")) {
            if (localAppData != null) { Path base = dirForProfile(localAppData, "Yandex", "YandexBrowser", "User Data", name); if (base != null) addBrowserDbFiles(extraFiles, base, true); }
        }
        return extraFiles;
    }

    private String getBrowserProcessKey(String profileName) {
        // Prefer longest matching key to avoid prefix collisions (e.g. "Opera" vs "Opera GX")
        String best = null;
        for (String key : BROWSER_PROCESS_MAP.keySet()) {
            if (profileName.equals(key) || profileName.startsWith(key)) {
                if (best == null || key.length() > best.length()) best = key;
            }
        }
        return best;
    }

    private boolean isBrowserRunning(String processName) {
        if (processName == null) return false;
        String target = processName.toLowerCase();
        String output = getTaskListOutput();
        if (output == null) return false;
        // Parse CSV lines: "image.exe","PID",...  extract first column without quotes
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // CSV format: "chrome.exe","1234","Console",...
            int firstQuote = trimmed.indexOf('"');
            int secondQuote = firstQuote >= 0 ? trimmed.indexOf('"', firstQuote + 1) : -1;
            if (firstQuote >= 0 && secondQuote > firstQuote) {
                String proc = trimmed.substring(firstQuote + 1, secondQuote).toLowerCase();
                if (proc.equals(target)) return true;
            } else {
                // Fallback contains check for unexpected format
                if (trimmed.toLowerCase().contains(target)) return true;
            }
        }
        return false;
    }

    private String getTaskListOutput() {
        long now = System.currentTimeMillis();
        String cached = cachedTaskListOutput;
        if (cached != null && (now - cachedTaskListTimestamp) < TASKLIST_CACHE_MS) return cached;
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            if (finished) {
                String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                cachedTaskListOutput = output;
                cachedTaskListTimestamp = now;
                return output;
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {}
        return cached;
    }

    private List<BrowserProfile> getBrowserProfiles() {
        List<BrowserProfile> profiles = new ArrayList<>();
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        String appData = CleanerUtils.safeEnv("APPDATA");

        if (localAppData != null) {
            profiles.addAll(getChromiumProfiles("Chrome", Paths.get(localAppData, "Google", "Chrome", "User Data")));
            profiles.addAll(getChromiumProfiles("Edge", Paths.get(localAppData, "Microsoft", "Edge", "User Data")));
        }

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

        if (localAppData != null) {
            profiles.addAll(getChromiumProfiles("Brave", Paths.get(localAppData, "BraveSoftware", "Brave-Browser", "User Data")));
            profiles.addAll(getChromiumProfiles("Vivaldi", Paths.get(localAppData, "Vivaldi", "User Data")));
            profiles.addAll(getChromiumProfiles("Chromium", Paths.get(localAppData, "Chromium", "User Data")));
            profiles.addAll(getChromiumProfiles("Yandex Browser", Paths.get(localAppData, "Yandex", "YandexBrowser", "User Data")));
        }

        if (appData != null) {
            List<Path> operaDirs = new ArrayList<>();
            Path opera = Paths.get(appData, "Opera Software", "Opera Stable");
            operaDirs.add(opera.resolve("Cache"));
            operaDirs.add(opera.resolve("Code Cache"));
            profiles.add(new BrowserProfile("Opera", operaDirs));

            List<Path> operaGxDirs = new ArrayList<>();
            Path operaGX = Paths.get(appData, "Opera Software", "Opera GX Stable");
            operaGxDirs.add(operaGX.resolve("Cache"));
            operaGxDirs.add(operaGX.resolve("Code Cache"));
            profiles.add(new BrowserProfile("Opera GX", operaGxDirs));
        }

        return profiles;
    }

    private List<BrowserProfile> getChromiumProfiles(String browserName, Path userDataDir) {
        List<BrowserProfile> profiles = new ArrayList<>();
        if (!Files.isDirectory(userDataDir)) return profiles;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(userDataDir)) {
            for (Path entry : ds) {
                if (Files.isDirectory(entry)) {
                    String dirName = entry.getFileName().toString();
                    if (dirName.equals("Default") || dirName.startsWith("Profile ")) {
                        List<Path> cacheDirs = new ArrayList<>();
                        cacheDirs.add(entry.resolve("Cache"));
                        cacheDirs.add(entry.resolve("Code Cache"));
                        cacheDirs.add(entry.resolve("Network"));
                        String label = dirName.equals("Default") ? browserName : browserName + " (" + dirName + ")";
                        profiles.add(new BrowserProfile(label, cacheDirs));
                    }
                }
            }
        } catch (Exception ignored) {}
        if (profiles.isEmpty()) {
            List<Path> cacheDirs = new ArrayList<>();
            cacheDirs.add(userDataDir.resolve("Default").resolve("Cache"));
            cacheDirs.add(userDataDir.resolve("Default").resolve("Code Cache"));
            cacheDirs.add(userDataDir.resolve("Default").resolve("Network"));
            profiles.add(new BrowserProfile(browserName, cacheDirs));
        }
        return profiles;
    }

    private Path dirForProfile(String localAppData, String... pathParts) {
        String profileLabel = pathParts[pathParts.length - 1];
        String dirName = profileLabel.contains("(")
                ? profileLabel.substring(profileLabel.indexOf('(') + 1, profileLabel.indexOf(')'))
                : "Default";
        Path userData = Paths.get(localAppData, java.util.Arrays.copyOf(pathParts, pathParts.length - 1));
        Path profileDir = userData.resolve(dirName);
        return Files.isDirectory(profileDir) ? profileDir : null;
    }

    private void addBrowserDbFiles(List<Path> extraFiles, Path base, boolean hasJournals) {
        extraFiles.add(base.resolve("Cookies"));
        extraFiles.add(base.resolve("History"));
        extraFiles.add(base.resolve("Login Data"));
        if (hasJournals) {
            extraFiles.add(base.resolve("Cookies-journal"));
            extraFiles.add(base.resolve("History-journal"));
        }
    }
}
