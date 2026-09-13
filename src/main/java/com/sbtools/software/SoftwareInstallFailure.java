package com.sbtools.software;

import com.sbtools.util.ProcessResult;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Classifies winget/MSI install failures into actionable guidance (no automatic repair).
 */
final class SoftwareInstallFailure {

    static final String MICROSOFT_INSTALL_TROUBLESHOOTER_URL =
            "https://support.microsoft.com/en-us/topic/fix-problems-that-block-programs-from-being-installed-or-removed-cca7d1b6-65a9-3d98-426b-e9f927e1eb4d";

    static final String INSTALLED_APPS_SETTINGS_URI = "ms-settings:appsfeatures";

    private static final Pattern MSI_CODE = Pattern.compile(
            "(?i)(?:installer|uninstall)\\s+failed\\s+with\\s+exit\\s+code:\\s*(\\d+)");
    private static final String LOG_MARKER = "installer log is available at:";
    private static final int MAX_FORMATTED_CHARS = 4000;
    private static final long MAX_LOG_BYTES = 1024 * 1024;

    enum Kind {
        MSI_SERVICE_UNAVAILABLE,
        MSI_SOURCE_MISSING,
        MSI_INSTALL_IN_PROGRESS,
        MSI_CORRUPT_CACHE,
        MSI_GENERIC,
        ZOOM_PRIVILEGE_FAILURE,
        OTHER
    }

    record Result(
            Kind kind,
            Integer installerExitCode,
            String title,
            String explanation,
            List<String> recoverySteps,
            String formattedUserMessage,
            String rawOutput,
            Path trustedLogPath,
            boolean immediateRetryUseful,
            boolean showTroubleshooter,
            boolean showInstalledAppsSettings
    ) {}

    private SoftwareInstallFailure() {
    }

    static Result classify(SoftwareUpdateEntry entry, ProcessResult result) {
        return classify(entry, result, wingetDiagRoot());
    }

    static Result classify(SoftwareUpdateEntry entry, ProcessResult result, Path trustedDiagRoot) {
        String raw = result == null ? "" : result.combinedOutput();
        if (raw == null) raw = "";
        OptionalInt codeOpt = parseInstallerExitCode(raw);
        Integer code = codeOpt.isPresent() ? codeOpt.getAsInt() : null;

        String pkgId = entry == null || entry.id() == null ? "" : entry.id().trim();
        String pkgName = entry == null || entry.getName() == null ? pkgId : entry.getName();

        Path logPath = extractLogPath(raw);
        Path trustedLog = validateTrustedLog(logPath, trustedDiagRoot);
        String logText = trustedLog == null ? "" : readTrustedLog(trustedLog);

        if (isTrustedLogRemoveExistingSourceMissing(logText)) {
            String explanation = "winget reported installer exit " + (code != null ? code : "failure")
                    + ". The installer log shows the update failed while removing the previously installed version "
                    + "(RemoveExistingProducts). The underlying Windows Installer error is 1612: the original "
                    + "installation package for that older registration is unavailable.";
            return build(Kind.MSI_SOURCE_MISSING, code, pkgName, raw, trustedLog,
                    "Original installer source unavailable",
                    explanation,
                    sourceMissingRecoverySteps(pkgName, false),
                    false, true, true);
        }

        if (isCorruptCacheSignal(raw, logText)) {
            return build(Kind.MSI_CORRUPT_CACHE, code, pkgName, raw, trustedLog,
                    "Windows Installer cache problem",
                    "The installed copy of this program cannot be removed or updated because the Windows Installer cache or registration is damaged.",
                    List.of(
                            "Open Settings > Apps > Installed apps and uninstall \"" + pkgName + "\" if listed.",
                            "Run the Microsoft Program Install and Uninstall troubleshooter (link below).",
                            "Press Scan in this tab and try installing again."
                    ),
                    false, true, true);
        }

        if (code != null && code == 1612) {
            return build(Kind.MSI_SOURCE_MISSING, code, pkgName, raw, trustedLog,
                    "Original installer source unavailable",
                    "Windows Installer cannot find the original installation package for the version currently on this PC. "
                            + "winget cannot uninstall/replace it until that is fixed (common with older Java runtimes). "
                            + "Installing a newer package from winget does not repair the missing source for the version already registered.",
                    sourceMissingRecoverySteps(pkgName, true),
                    false, true, true);
        }

        if (isZoomPackage(pkgId) && code != null && code == 1601
                && isZoomPrivilegeFailureInLog(logText)) {
            return build(Kind.ZOOM_PRIVILEGE_FAILURE, code, pkgName, raw, trustedLog,
                    "Zoom installer privilege error",
                    "Zoom's MSI custom action failed while adjusting security privileges. "
                            + "This is not a missing winget package; the installer itself aborted.",
                    List.of(
                            "Close all Zoom processes (check Task Manager), then try Update once more.",
                            "If it still fails, download Zoom's official MSI from zoom.us and install as administrator.",
                            "Open the installer log below for Zoom support if needed."
                    ),
                    true, false, false);
        }

        if (code != null && code == 1601) {
            return build(Kind.MSI_SERVICE_UNAVAILABLE, code, pkgName, raw, trustedLog,
                    "Windows Installer not accessible",
                    "The Windows Installer service could not be used for this setup. This is usually environmental, not a winget bug.",
                    List.of(
                            "Reboot the PC and try the update again.",
                            "Open services.msc, set \"Windows Installer\" startup type to Manual, and ensure it can start.",
                            "If the problem persists, run Microsoft support guidance for Windows Installer error 1601."
                    ),
                    true, false, false);
        }

        if (code != null && code == 1618) {
            return build(Kind.MSI_INSTALL_IN_PROGRESS, code, pkgName, raw, trustedLog,
                    "Another installation is in progress",
                    "Windows Installer is busy with another setup. Wait for it to finish or reboot if a setup was interrupted.",
                    List.of(
                            "Wait until other installers finish, then press Retry Failed or Update again.",
                            "Reboot if you recently cancelled an install or see a pending restart."
                    ),
                    true, false, false);
        }

        if (code != null) {
            return build(Kind.MSI_GENERIC, code, pkgName, raw, trustedLog,
                    "Installer failed",
                    "The package installer returned a failure code. See details for the full winget/MSI output.",
                    List.of(
                            "Read the details below or open the installer log if available.",
                            "Search for the exit code and package name with the vendor's support site."
                    ),
                    false, false, false);
        }

        return build(Kind.OTHER, null, pkgName, raw, trustedLog,
                "Update failed",
                "The update did not complete successfully.",
                List.of("See details below.", "Press Scan and try again after addressing the reported issue."),
                true, false, false);
    }

    private static Result build(Kind kind, Integer code, String pkgName, String raw, Path trustedLog,
                                String title, String explanation, List<String> steps,
                                boolean retryUseful, boolean troubleshooter, boolean installedApps) {
        String formatted = formatMessage(title, explanation, steps, code);
        return new Result(kind, code, title, explanation, steps, formatted, raw, trustedLog,
                retryUseful, troubleshooter, installedApps);
    }

    static String historyPayload(Result failure) {
        if (failure == null) return "";
        String formatted = failure.formattedUserMessage();
        String raw = failure.rawOutput();
        if (raw == null || raw.isBlank()) return formatted;
        return formatted + "\n\n--- Technical details ---\n" + raw;
    }

    static int countInstallerExitMentions(String message, int code) {
        if (message == null || code < 0) return 0;
        String needle = "installer exit " + code;
        int count = 0;
        int idx = 0;
        String lower = message.toLowerCase(Locale.ROOT);
        String n = needle.toLowerCase(Locale.ROOT);
        while ((idx = lower.indexOf(n, idx)) >= 0) {
            count++;
            idx += n.length();
        }
        return count;
    }

    static String formatMessage(String title, String explanation, List<String> steps, Integer code) {
        StringBuilder sb = new StringBuilder();
        sb.append(title);
        if (code != null) sb.append(" (installer exit ").append(code).append(")");
        sb.append("\n\n").append(explanation).append("\n\n");
        for (int i = 0; i < steps.size(); i++) {
            sb.append(i + 1).append(". ").append(steps.get(i)).append("\n");
        }
        String out = sb.toString().trim();
        if (out.length() > MAX_FORMATTED_CHARS) {
            return out.substring(0, MAX_FORMATTED_CHARS) + "\n...[truncated]";
        }
        return out;
    }

    static OptionalInt parseInstallerExitCode(String output) {
        if (output == null || output.isBlank()) return OptionalInt.empty();
        Matcher m = MSI_CODE.matcher(output);
        int last = -1;
        while (m.find()) {
            try {
                last = Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return last >= 0 ? OptionalInt.of(last) : OptionalInt.empty();
    }

    static Path wingetDiagRoot() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) return null;
        return Paths.get(local, "Packages", "Microsoft.DesktopAppInstaller_8wekyb3d8bbwe",
                "LocalState", "DiagOutputDir");
    }

    static String extractLogPathLine(String output) {
        Path p = extractLogPath(output);
        return p == null ? null : p.toString();
    }

    private static Path extractLogPath(String output) {
        if (output == null) return null;
        int idx = output.toLowerCase(Locale.ROOT).indexOf(LOG_MARKER);
        if (idx < 0) return null;
        String after = output.substring(idx + LOG_MARKER.length());
        for (String line : after.split("\\r?\\n")) {
            String t = line.trim();
            if (!t.isEmpty()) {
                try {
                    return Paths.get(t);
                } catch (Exception e) {
                    return null;
                }
            }
        }
        return null;
    }

    static Path validateTrustedLog(Path candidate, Path trustedDiagRoot) {
        if (candidate == null || trustedDiagRoot == null) return null;
        try {
            if (!Files.isRegularFile(candidate)) return null;
            String name = candidate.getFileName().toString().toLowerCase(Locale.ROOT);
            if (!name.endsWith(".log")) return null;
            Path realFile = candidate.toRealPath();
            Path realRoot = trustedDiagRoot.toRealPath();
            if (!realFile.startsWith(realRoot)) return null;
            return realFile;
        } catch (Exception e) {
            return null;
        }
    }

    private static String readTrustedLog(Path trustedLog) {
        try (var in = Files.newInputStream(trustedLog)) {
            byte[] buf = in.readNBytes((int) MAX_LOG_BYTES);
            return decodeInstallerLog(buf);
        } catch (Exception e) {
            return "";
        }
    }

    static boolean isTrustedLogRemoveExistingSourceMissing(String logText) {
        if (logText == null || logText.isBlank()) return false;
        String lower = logText.toLowerCase(Locale.ROOT);
        if (!lower.contains("removeexistingproducts")) return false;
        boolean has1612 = lower.contains("system error 1612")
                || lower.contains("actual error code 1612")
                || lower.contains("error code 1612");
        boolean has1714 = lower.contains("error 1714")
                || lower.contains("cannot be removed");
        return has1612 && has1714;
    }

    private static List<String> sourceMissingRecoverySteps(String pkgName, boolean javaStyleVendorStep) {
        if (javaStyleVendorStep) {
            return List.of(
                    "Open Settings > Apps > Installed apps and try to uninstall \"" + pkgName + "\" if it is listed.",
                    "If you still have Oracle's trusted installer for the exact version currently installed, use its supported repair or remove option.",
                    "Otherwise run the Microsoft Program Install and Uninstall troubleshooter, then press Scan here.",
                    "Do not copy random .msi files into C:\\Windows\\Installer — that can damage the system."
            );
        }
        return List.of(
                "Open Settings > Apps > Installed apps and try to uninstall \"" + pkgName + "\" if it is listed.",
                "If uninstall fails, run the Microsoft Program Install and Uninstall troubleshooter (link below).",
                "If needed, use only the vendor's official installer or support guidance to remove the old registration.",
                "Press Scan in this tab after the old version is repaired or removed.",
                "Do not copy random .msi files into C:\\Windows\\Installer — that can damage the system."
        );
    }

    static boolean isZoomPrivilegeFailureInLog(String logText) {
        if (logText == null || logText.isBlank()) return false;
        String lower = logText.toLowerCase(Locale.ROOT);
        boolean priv = lower.contains("token does not have the specified privilege")
                || lower.contains("error_not_all_assigned")
                || lower.contains(".1300")
                || lower.contains("setprivilege");
        boolean zoomCtx = lower.contains("zoommsiinstaller")
                || lower.contains("zoomcleaner")
                || lower.contains("zoomenum");
        return priv && zoomCtx;
    }

    private static boolean isZoomPackage(String id) {
        return id != null && id.equalsIgnoreCase("Zoom.Zoom");
    }

    private static boolean isCorruptCacheSignal(String output, String logText) {
        if (isTrustedLogRemoveExistingSourceMissing(logText)) return false;
        if (output == null) output = "";
        String lower = output.toLowerCase(Locale.ROOT);
        if (lower.contains("cannot be removed") || lower.contains("error 1714")) return true;
        if (logText != null && !logText.isBlank()) {
            String ll = logText.toLowerCase(Locale.ROOT);
            if (ll.contains("error 1714") || ll.contains("cannot be removed")) return true;
        }
        return false;
    }

    /**
     * Decodes an installer log with BOM sniffing (MSI logs are often UTF-16LE).
     */
    static String decodeInstallerLog(byte[] buf) {
        if (buf == null || buf.length == 0) return "";
        if (buf.length >= 2) {
            int b0 = buf[0] & 0xFF, b1 = buf[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return new String(buf, java.nio.charset.StandardCharsets.UTF_16);
            if (b0 == 0xFE && b1 == 0xFF) return new String(buf, java.nio.charset.StandardCharsets.UTF_16BE);
        }
        if (buf.length >= 3 && (buf[0] & 0xFF) == 0xEF && (buf[1] & 0xFF) == 0xBB && (buf[2] & 0xFF) == 0xBF) {
            return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
        }
        int sample = Math.min(buf.length, 512);
        int nulOdd = 0, checks = 0;
        for (int i = 1; i < sample; i += 2) {
            checks++;
            if (buf[i] == 0) nulOdd++;
        }
        if (checks > 0 && nulOdd * 2 > checks) {
            return new String(buf, java.nio.charset.StandardCharsets.UTF_16LE);
        }
        return new String(buf, java.nio.charset.StandardCharsets.UTF_8);
    }

    static Path revalidateLogForOpen(Path path) {
        return validateTrustedLog(path, wingetDiagRoot());
    }
}
