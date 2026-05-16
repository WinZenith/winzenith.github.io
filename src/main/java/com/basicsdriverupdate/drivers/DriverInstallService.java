package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.backup.DriverBackupService;
import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;

public class DriverInstallService {

    private final DriverBackupService backupService = new DriverBackupService();
    private final ProcessRunner processRunner = new ProcessRunner(900);

    public InstallResult install(DriverUpdateCandidate candidate, AppSettings settings)
            throws IOException, InterruptedException {
        if (settings.autoBackupDrivers()) {
            backupService.backupBeforeUpdate(candidate.installed(), settings);
        }
        if ("WindowsUpdate".equals(candidate.source()) && candidate.packageId() != null && !candidate.packageId().isBlank()) {
            Path script = PowerShellScripts.resolve("wu-install.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                    script.toString(), candidate.packageId()));
            if (!result.success()) {
                throw new IOException("Windows Update install failed: " + result.combinedOutput());
            }
            boolean reboot = result.stdout() != null && result.stdout().contains("\"rebootRequired\":true");
            return new InstallResult(true, reboot, result.stdout());
        }
        return new InstallResult(false, false,
                "No Windows Update package ID. Open " + candidate.source() + " support to download certified driver.");
    }

    public record InstallResult(boolean installed, boolean rebootRequired, String message) {
    }
}
