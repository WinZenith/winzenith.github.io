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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

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

        // For OEM drivers: download from provided URL and install via pnputil
        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
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
                "No download available for " + candidate.source() + ". Update source data or check vendor website manually.");
    }

    private InstallResult downloadAndInstallDriver(DriverUpdateCandidate candidate) {
        try {
            Path downloadsDir = Paths.get(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloadsDir);
            
            String filename = extractFilename(candidate.downloadUrl());
            Path driverFile = downloadsDir.resolve(filename);
            
            AppLogger.info("Downloading driver from: " + candidate.downloadUrl());
            
            // Use curl or powershell to download
            ProcessResult downloadResult = downloadFile(candidate.downloadUrl(), driverFile);
            if (!downloadResult.success()) {
                return new InstallResult(false, false, "Download failed: " + downloadResult.combinedOutput());
            }
            
            AppLogger.info("Driver downloaded to: " + driverFile);
            
            // Install via pnputil (for INF files) or direct execution
            ProcessResult installResult = installDriverFile(driverFile, candidate);
            if (!installResult.success()) {
                return new InstallResult(false, false, "Installation failed: " + installResult.combinedOutput());
            }
            
            AppLogger.info("Driver installed successfully from: " + driverFile);
            return new InstallResult(true, false, "Driver installed from " + candidate.source());
            
        } catch (Exception e) {
            AppLogger.warning("Error during download and install: " + e.getMessage());
            return new InstallResult(false, false, "Error: " + e.getMessage());
        }
    }

    private ProcessResult downloadFile(String url, Path destination) throws IOException, InterruptedException {
        // Use PowerShell to download file
        String psCommand = "Invoke-WebRequest -Uri '" + url + "' -OutFile '" + destination + "'";
        ProcessResult result = processRunner.run(List.of(new ProcessBuilder(
                "powershell", "-NoProfile", "-Command", psCommand).command().toArray(new String[0])));
        return result;
    }

    private ProcessResult installDriverFile(Path driverFile, DriverUpdateCandidate candidate) throws IOException, InterruptedException {
        String filename = driverFile.getFileName().toString().toLowerCase();
        
        if (filename.endsWith(".inf")) {
            // Install INF file using pnputil
            return processRunner.run(List.of(new ProcessBuilder(
                    "pnputil.exe", "/add-driver", driverFile.toString(), "/install").command().toArray(new String[0])));
        } else if (filename.endsWith(".exe")) {
            // Execute installer (trusted vendors only)
            return processRunner.run(List.of(new ProcessBuilder(driverFile.toString()).command().toArray(new String[0])));
        } else if (filename.endsWith(".zip") || filename.endsWith(".rar")) {
            return new ProcessResult(1, "", "Compressed driver packages require manual extraction. Download: " + driverFile);
        }
        
        return new ProcessResult(2, "", "Unknown driver file type: " + filename);
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

