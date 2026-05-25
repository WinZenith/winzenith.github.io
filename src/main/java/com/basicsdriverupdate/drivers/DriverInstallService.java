package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.backup.DriverBackupService;
import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.awt.Desktop;
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
        // For OEM drivers or missing Windows Update package ID: attempt to open vendor support page
        if (backupEntry != null) {
            backupService.removeBackupEntry(backupEntry);
        }
        // Build support URL for known vendors
        String source = candidate.source() != null ? candidate.source() : "Vendor";
        String supportUrl;
        switch (source) {
            case "Intel" -> supportUrl = "https://www.intel.com/content/www/us/en/support/detect.html";
            case "Nvidia", "NVIDIA" -> supportUrl = "https://www.nvidia.com/Download/index.aspx";
            case "AMD" -> supportUrl = "https://www.amd.com/en/support";
            case "Realtek" -> supportUrl = "https://www.realtek.com/en/downloads";
            case "Broadcom" -> supportUrl = "https://www.broadcom.com/support";
            case "Qualcomm" -> supportUrl = "https://www.qualcomm.com/support";
            default -> {
                String q = URLEncoder.encode(source + " driver download", StandardCharsets.UTF_8);
                supportUrl = "https://www.google.com/search?q=" + q;
            }
        }
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(supportUrl));
            } else {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", supportUrl).start();
            }
        } catch (Exception e) {
            // ignore
        }
        return new InstallResult(false, false,
                "No Windows Update package ID. Open " + source + " support to download certified driver. (" + supportUrl + ")");
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
