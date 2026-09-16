package com.sbtools.browserext;

import java.nio.file.Paths;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decides whether a browser <em>channel</em> (Stable vs Canary, etc.) is
 * running, using process command lines and install paths — not exe name alone.
 */
public final class BrowserChannelRunning {

    private static final Set<String> MULTI_CHANNEL_EXES = Set.of(
            "chrome.exe", "msedge.exe", "opera.exe");

    private static final Pattern USER_DATA_DIR = Pattern.compile(
            "--user-data-dir=(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"']+))",
            Pattern.CASE_INSENSITIVE);

    private BrowserChannelRunning() {
    }

    /**
     * @return browser labels from {@code rows} whose channel appears running
     */
    public static Set<String> runningAmong(List<BrowserExtensionRow> rows,
                                           BrowserProcessProbe.Snapshot snapshot) {
        Set<String> out = new HashSet<>();
        if (rows == null || snapshot == null) return out;
        for (BrowserExtensionRow row : rows) {
            if (row == null) continue;
            String browser = row.getBrowser();
            if (browser == null || browser.isBlank()) continue;
            if (isChannelRunning(browser, row.getProfilePath(), snapshot)) {
                out.add(browser);
            }
        }
        return out;
    }

    /**
     * When the detailed WMI snapshot is unavailable, {@code tasklist} only
     * reports shared exes (chrome/msedge/opera). Callers must warn — never
     * treat as "target channel not running".
     */
    public static Set<String> multiChannelExeWithoutDetailAmong(List<BrowserExtensionRow> rows,
                                                                BrowserProcessProbe.Snapshot snapshot) {
        Set<String> out = new HashSet<>();
        if (rows == null || snapshot == null || snapshot.detailedOk()) return out;
        Set<String> running = snapshot.runningExeNames();
        if (running == null || running.isEmpty()) return out;
        for (BrowserExtensionRow row : rows) {
            if (row == null) continue;
            String browser = row.getBrowser();
            if (browser == null || browser.isBlank()) continue;
            String expectedExe = BrowserExtensionService.expectedExeFor(browser);
            if (expectedExe == null || expectedExe.isBlank()) continue;
            String exeLower = expectedExe.toLowerCase(Locale.ROOT);
            if (MULTI_CHANNEL_EXES.contains(exeLower) && running.contains(exeLower)) {
                out.add(browser);
            }
        }
        return out;
    }

    /**
     * Running processes whose channel cannot be resolved (missing command line
     * and install path). Callers must warn for matching multi-channel browsers.
     */
    public static Set<String> ambiguousMultiChannelExeBrowsers(List<BrowserExtensionRow> rows,
                                                               BrowserProcessProbe.Snapshot snapshot) {
        Set<String> out = new HashSet<>();
        if (rows == null || snapshot == null || !snapshot.detailedOk()) return out;
        Set<String> ambiguousExes = ambiguousMultiChannelExes(snapshot);
        if (ambiguousExes.isEmpty()) return out;
        for (BrowserExtensionRow row : rows) {
            if (row == null) continue;
            String browser = row.getBrowser();
            if (browser == null || browser.isBlank()) continue;
            String expectedExe = BrowserExtensionService.expectedExeFor(browser);
            if (expectedExe == null || expectedExe.isBlank()) continue;
            if (ambiguousExes.contains(expectedExe.toLowerCase(Locale.ROOT))) {
                out.add(browser);
            }
        }
        return out;
    }

    private static Set<String> ambiguousMultiChannelExes(BrowserProcessProbe.Snapshot snapshot) {
        Set<String> ambiguous = new HashSet<>();
        if (snapshot == null || !snapshot.detailedOk()) return ambiguous;
        for (BrowserProcessProbe.ProcessEntry proc : snapshot.entries()) {
            if (proc == null || proc.name() == null) continue;
            String exeLower = proc.name().toLowerCase(Locale.ROOT);
            if (!MULTI_CHANNEL_EXES.contains(exeLower)) continue;
            if (resolvesToAnyCatalogChannel(proc)) continue;
            ambiguous.add(exeLower);
        }
        return ambiguous;
    }

    private static boolean resolvesToAnyCatalogChannel(BrowserProcessProbe.ProcessEntry proc) {
        String fromCmd = parseUserDataDir(proc.commandLine());
        for (String browser : BrowserExtensionService.ALL_BROWSERS) {
            String expectedExe = BrowserExtensionService.expectedExeFor(browser);
            if (expectedExe == null || expectedExe.isBlank()) continue;
            if (!expectedExe.equalsIgnoreCase(proc.name())) continue;
            String catalogUserData = BrowserExtensionService.userDataDirFor(browser);
            if (fromCmd != null && !catalogUserData.isBlank()
                    && userDataPathsEqual(fromCmd, catalogUserData)) {
                return true;
            }
            String inferred = inferUserDataFromInstallPath(browser, proc.executablePath());
            if (inferred != null && !catalogUserData.isBlank()
                    && userDataPathsEqual(inferred, catalogUserData)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isChannelRunning(String browser, String profilePath,
                                           BrowserProcessProbe.Snapshot snapshot) {
        if (browser == null || browser.isBlank() || snapshot == null) return false;
        String expectedExe = BrowserExtensionService.expectedExeFor(browser);
        if (expectedExe == null || expectedExe.isBlank()) return false;
        String exeLower = expectedExe.toLowerCase(Locale.ROOT);
        String catalogUserData = normalizePath(BrowserExtensionService.userDataDirFor(browser));
        boolean multiChannel = MULTI_CHANNEL_EXES.contains(exeLower);

        if (!snapshot.detailedOk()) {
            // Without command lines, only single-channel exes can be matched safely.
            if (multiChannel) return false;
            return snapshot.runningExeNames().contains(exeLower);
        }

        for (BrowserProcessProbe.ProcessEntry proc : snapshot.entries()) {
            if (proc == null || proc.name() == null) continue;
            if (!exeLower.equals(proc.name().toLowerCase(Locale.ROOT))) continue;

            String fromCmd = parseUserDataDir(proc.commandLine());
            if (fromCmd != null && !catalogUserData.isBlank()
                    && userDataPathsEqual(fromCmd, catalogUserData)) {
                return true;
            }

            String inferred = inferUserDataFromInstallPath(browser, proc.executablePath());
            if (inferred != null && !catalogUserData.isBlank()
                    && userDataPathsEqual(inferred, catalogUserData)) {
                return true;
            }

            // firefox/brave/vivaldi: any matching exe means that browser is open.
            if (!multiChannel) {
                return true;
            }
        }
        return false;
    }

    private static String parseUserDataDir(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) return null;
        Matcher m = USER_DATA_DIR.matcher(commandLine);
        if (!m.find()) return null;
        if (m.group(1) != null) return normalizePath(m.group(1));
        if (m.group(2) != null) return normalizePath(m.group(2));
        return normalizePath(m.group(3));
    }

    private static String inferUserDataFromInstallPath(String browser, String executablePath) {
        if (executablePath == null || executablePath.isBlank()) return null;
        String lower = executablePath.replace('/', '\\').toLowerCase(Locale.ROOT);
        return switch (browser) {
            case "Chrome" -> lower.contains("google\\chrome\\application") && !lower.contains("sxs")
                    ? BrowserExtensionService.userDataDirFor("Chrome") : null;
            case "Chrome Canary" -> lower.contains("chrome sxs")
                    ? BrowserExtensionService.userDataDirFor("Chrome Canary") : null;
            case "Edge" -> lower.contains("microsoft\\edge\\application")
                    && !lower.contains("edge beta") && !lower.contains("edge dev")
                    && !lower.contains("edge sxs")
                    ? BrowserExtensionService.userDataDirFor("Edge") : null;
            case "Edge Beta" -> lower.contains("edge beta\\application")
                    ? BrowserExtensionService.userDataDirFor("Edge Beta") : null;
            case "Edge Dev" -> lower.contains("edge dev\\application")
                    ? BrowserExtensionService.userDataDirFor("Edge Dev") : null;
            case "Edge Canary" -> lower.contains("edge sxs")
                    ? BrowserExtensionService.userDataDirFor("Edge Canary") : null;
            case "Opera" -> lower.contains("opera") && !lower.contains("opera gx")
                    ? BrowserExtensionService.userDataDirFor("Opera") : null;
            case "Opera GX" -> lower.contains("opera gx")
                    ? BrowserExtensionService.userDataDirFor("Opera GX") : null;
            default -> null;
        };
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) return "";
        try {
            return Paths.get(path).toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return path.trim().replace('/', '\\').toLowerCase(Locale.ROOT);
        }
    }

    private static boolean userDataPathsEqual(String a, String b) {
        if (a == null || b == null || a.isBlank() || b.isBlank()) return false;
        return normalizePath(a).equals(normalizePath(b));
    }
}
