package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.backup.DriverBackupService;
import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.URI;

public class DriverInstallService {

    private final DriverBackupService backupService = new DriverBackupService();
    private final ProcessRunner processRunner = new ProcessRunner(900);
    private final AtomicBoolean cancellationFlag = new AtomicBoolean(false);

    public InstallResult install(DriverUpdateCandidate candidate, AppSettings settings)
            throws IOException, InterruptedException {
        com.basicsdriverupdate.backup.DriverBackupEntry backupEntry = null;
        if (settings.autoBackupDrivers()) {
            backupEntry = backupService.backupBeforeUpdate(candidate.installed(), settings);
        }

        // Block pre-release candidates (alpha/beta/rc/preview/test) to ensure only stable drivers are installed
        String availVer = candidate.availableVersion();
        if (availVer != null && availVer.matches("(?i).*\\b(alpha|beta|rc|preview|test)\\b.*")) {
            if (backupEntry != null) {
                backupService.removeBackupEntry(backupEntry);
            }
            return new InstallResult(false, false, "Blocked: candidate appears to be a pre-release (alpha/beta/rc/preview). Only stable releases are installed.");
        }

        // Try Windows Update first
        if ("WindowsUpdate".equals(candidate.source()) && candidate.packageId() != null && !candidate.packageId().isBlank()) {
            Path script = PowerShellScripts.resolve("wu-install.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                    script.toString(), candidate.packageId()));
            if (!result.success()) {
                // Remove backup entry if installation failed
                if (backupEntry != null) {
                    backupService.removeBackupEntry(backupEntry);
                }
                throw new IOException("Windows Update install failed: " + result.combinedOutput());
            }
            boolean reboot = result.stdout() != null && result.stdout().contains("\"rebootRequired\":true");
            return new InstallResult(true, reboot, result.stdout());
        }

        // For OEM drivers: download from provided URL and install automatically
        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
            // Decode any HTML entities in the URL first
            String decodedUrl = decodeHtmlEntities(candidate.downloadUrl());

            // Verify the download URL is from a trusted provider for the candidate's source
            if (!isTrustedSource(decodedUrl, candidate.source())) {
                if (backupEntry != null) {
                    backupService.removeBackupEntry(backupEntry);
                }
                return new InstallResult(false, false, "Blocked: download URL is not from a trusted vendor. URL: " + decodedUrl);
            }

            if (backupEntry != null) {
                backupService.removeBackupEntry(backupEntry);
            }
            return downloadAndInstallDriver(candidate);
        }

        // Fallback: no download URL and no Windows Update packageId
        if (backupEntry != null) {
            backupService.removeBackupEntry(backupEntry);
        }
        return new InstallResult(false, false,
                "No download URL available for " + candidate.source() + ". Check vendor website manually.");
    }

    private InstallResult downloadAndInstallDriver(DriverUpdateCandidate candidate) {
        try {
            Path downloadsDir = Paths.get(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloadsDir);
            
            // Decode any HTML entities in the URL as a safety net
            String downloadUrl = decodeHtmlEntities(candidate.downloadUrl());
            
            String filename = extractFilename(downloadUrl);
            Path driverFile = downloadsDir.resolve(filename);
            
            AppLogger.info("Downloading driver from: " + downloadUrl);
            
            ProcessResult downloadResult = downloadFile(downloadUrl, driverFile);
            if (!downloadResult.success()) {
                return new InstallResult(false, false, "Download failed: " + downloadResult.combinedOutput());
            }
            
            AppLogger.info("Driver downloaded to: " + driverFile);
            
            ProcessResult installResult = installDriverFile(driverFile, candidate);
            if (!installResult.success()) {
                return new InstallResult(false, false, "Installation failed: " + installResult.combinedOutput());
            }
            
            AppLogger.info("Driver installed successfully from: " + driverFile);
            return new InstallResult(true, false, "Driver installed from " + driverFile.toString());
            
        } catch (Exception e) {
            AppLogger.warning("Error during download and install: " + e.getMessage());
            return new InstallResult(false, false, "Error: " + e.getMessage());
        }
    }

    private ProcessResult downloadFile(String url, Path destination) throws IOException, InterruptedException {
        // Write a PowerShell download script to a temp file to avoid quoting issues
        Path scriptFile = Files.createTempFile("sbasic-download-", ".ps1");
        try {
            String script = "$ErrorActionPreference = 'Stop'\n"
                    + "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12\n"
                    + "$url = '" + url.replace("'", "''") + "'\n"
                    + "$out = '" + destination.toString().replace("'", "''") + "'\n"
                    + "$ua = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36'\n"
                    + "Invoke-WebRequest -Uri $url -OutFile $out -UserAgent $ua -UseBasicParsing -MaximumRedirection 10\n";
            Files.writeString(scriptFile, script);
            ProcessResult result = processRunner.run(List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", scriptFile.toString()
            ).command().toArray(new String[0])));
            if (result.success() && Files.exists(destination) && Files.size(destination) > 0) {
                AppLogger.info("Downloaded " + Files.size(destination) + " bytes from " + url);
                return new ProcessResult(0, "", "");
            }
            AppLogger.warning("PowerShell download failed: " + result.combinedOutput());
        } finally {
            Files.deleteIfExists(scriptFile);
        }

        // Fallback: curl with cookie jar
        Path cookieJar = Files.createTempFile("sbasic-cookies-", ".txt");
        try {
            ProcessResult curlResult = processRunner.run(List.of(new ProcessBuilder(
                    "curl", "-L", "-s", "-S",
                    "-o", destination.toString(),
                    "-c", cookieJar.toString(),
                    "-b", cookieJar.toString(),
                    "-A", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36",
                    "-H", "Accept: application/octet-stream, */*",
                    "--max-time", "300",
                    url
            ).command().toArray(new String[0])));
            if (curlResult.success() && Files.exists(destination) && Files.size(destination) > 0) {
                AppLogger.info("Downloaded " + Files.size(destination) + " bytes via curl from " + url);
                return new ProcessResult(0, "", "");
            }
        } finally {
            Files.deleteIfExists(cookieJar);
        }

        return new ProcessResult(-1, "", "Download failed for: " + url);
    }

    private ProcessResult installDriverFile(Path driverFile, DriverUpdateCandidate candidate) throws IOException, InterruptedException {
        String filename = driverFile.getFileName().toString().toLowerCase();
        
        if (filename.endsWith(".inf")) {
            // Install INF file using pnputil
            return processRunner.run(List.of(new ProcessBuilder(
                    "pnputil.exe", "/add-driver", driverFile.toString(), "/install").command().toArray(new String[0])));
        } else if (filename.endsWith(".exe")) {
            // Execute installer (trusted vendors only)
            // Use silent flag for Intel installer executables
            String[] cmd;
            if ("Intel".equals(candidate.source())) {
                cmd = new String[]{driverFile.toString(), "/S"};
            } else {
                cmd = new String[]{driverFile.toString()};
            }
            return processRunner.run(List.of(new ProcessBuilder(cmd).command().toArray(new String[0])));
        } else if (filename.endsWith(".zip")) {
            // Extract zip and look for INF files or setup.exe
            Path extractDir = driverFile.getParent().resolve(
                    driverFile.getFileName().toString().replace(".zip", "_extracted"));
            Files.createDirectories(extractDir);
            
            // Extract using PowerShell Expand-Archive
            ProcessResult extractResult = processRunner.run(List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Expand-Archive -Path '" + driverFile + "' -DestinationPath '" + extractDir + "' -Force"
            ).command().toArray(new String[0])));
            
            if (!extractResult.success()) {
                return new ProcessResult(1, "", "Failed to extract zip: " + extractResult.combinedOutput());
            }
            
            // Look for setup.exe or *.inf files in the extracted directory
            Path setupExe = findFile(extractDir, "setup.exe");
            if (setupExe != null) {
                String[] cmd = "Intel".equals(candidate.source())
                        ? new String[]{setupExe.toString(), "/S"}
                        : new String[]{setupExe.toString()};
                return processRunner.run(List.of(new ProcessBuilder(cmd).command().toArray(new String[0])));
            }
            
            // Look for INF files and install via pnputil
            Path infFile = findFile(extractDir, ".inf");
            if (infFile != null) {
                return processRunner.run(List.of(new ProcessBuilder(
                        "pnputil.exe", "/add-driver", infFile.toString(), "/install"
                ).command().toArray(new String[0])));
            }
            
            return new ProcessResult(1, "", "No setup.exe or .inf found in extracted archive: " + extractDir);
        } else if (filename.endsWith(".rar")) {
            return new ProcessResult(1, "", "RAR archives require manual extraction. Download: " + driverFile);
        }
        
        return new ProcessResult(2, "", "Unknown driver file type: " + filename);
    }

    private Path findFile(Path dir, String nameOrExtension) throws IOException {
        try (var walk = Files.walk(dir, 5)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        if (nameOrExtension.startsWith(".")) {
                            return name.endsWith(nameOrExtension.toLowerCase());
                        }
                        return name.equals(nameOrExtension.toLowerCase());
                    })
                    .findFirst()
                    .orElse(null);
        }
    }

    private String extractFilename(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        } catch (Exception e) {
            return "driver_" + System.currentTimeMillis();
        }
    }

    /**
     * Decodes common HTML entities in a string so extracted URLs are usable.
     */
    private static String decodeHtmlEntities(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            if (s.charAt(i) == '&' && i + 1 < s.length() && s.charAt(i + 1) == '#') {
                int semi = s.indexOf(';', i + 2);
                if (semi > i + 2) {
                    String entity = s.substring(i + 2, semi);
                    try {
                        int codePoint = Integer.parseInt(entity);
                        sb.appendCodePoint(codePoint);
                        i = semi + 1;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            sb.append(s.charAt(i));
            i++;
        }
        String result = sb.toString();
        result = result.replace("&amp;", "&");
        result = result.replace("&lt;", "<");
        result = result.replace("&gt;", ">");
        result = result.replace("&quot;", "\"");
        result = result.replace("&apos;", "'");
        return result;
    }

    private boolean isTrustedSource(String url, String source) {
        if (url == null || source == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost().toLowerCase();
            switch (source) {
                case "Intel":
                    return host.contains("intel.com");
                case "Nvidia":
                    return host.contains("nvidia.com") || host.contains("geforce.com");
                case "AMD":
                    return host.contains("amd.com");
                case "Realtek":
                    return host.contains("realtek.com");
                case "Broadcom":
                    return host.contains("broadcom.com");
                case "Qualcomm":
                    return host.contains("qualcomm.com");
                case "WindowsUpdate":
                    return host.contains("microsoft.com") || host.contains("download.microsoft.com") || host.contains("catalog.update.microsoft.com");
                default:
                    for (String t : new String[]{"intel.com", "nvidia.com", "amd.com", "realtek.com", "broadcom.com", "qualcomm.com", "microsoft.com"}) {
                        if (host.contains(t)) return true;
                    }
                    return false;
            }
        } catch (Exception e) {
            return false;
        }
    }

    public void cancel() {
        cancellationFlag.set(true);
    }

    public void resetCancellation() {
        cancellationFlag.set(false);
    }

    public boolean isCancelled() {
        return cancellationFlag.get();
    }

    public record InstallResult(boolean installed, boolean rebootRequired, String message) {
    }
}

