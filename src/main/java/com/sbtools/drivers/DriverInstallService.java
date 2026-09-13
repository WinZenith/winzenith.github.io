package com.sbtools.drivers;

import com.sbtools.backup.DriverBackupService;
import com.sbtools.backup.SystemRestoreService;
import com.sbtools.drivers.catalog.CatalogEntry;
import com.sbtools.drivers.catalog.DriverCatalogDatabase;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstallStatus;
import com.sbtools.settings.AppSettings;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.WindowsUpdateInstallResult;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicBoolean;

public class DriverInstallService {

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long bytesReceived, long totalBytes, double fraction);
    }

    @FunctionalInterface
    public interface StatusCallback {
        void onStatusChanged(String status);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final DriverBackupService backupService = new DriverBackupService();
    private final SystemRestoreService restoreService = new SystemRestoreService();
    private final DriverVerificationService verificationService = new DriverVerificationService();
    private final DriverCatalogDatabase catalogDatabase;
    private final ProcessRunner processRunner = new ProcessRunner(900);
    private final AtomicBoolean cancellationFlag = new AtomicBoolean(false);
    // Live download body: closing it unblocks a thread wedged in InputStream
    // .read() during a CDN stall (socket reads ignore thread interrupts).
    private volatile InputStream activeDownloadStream;
    private volatile ProgressCallback progressCallback;
    private volatile StatusCallback statusCallback;
    // Exact MSI-copy path created by extractMsiFromExe for the current install
    // (installs are serial via the INSTALL gate). Lets cleanup delete exactly
    // what we created instead of guessing sibling names that could be user files.
    private volatile Path lastMsiCopy;
    // Same for the magic-bytes rename (driver.exe -> driver.exe.zip): the
    // caller only knows the original path, so cleanup would miss the renamed
    // payload and its extract dir without this pointer.
    private volatile Path lastMagicRename;

    public DriverInstallService() {
        this.catalogDatabase = DriverCatalogDatabase.load();
    }

    public DriverInstallService(DriverCatalogDatabase catalogDatabase) {
        this.catalogDatabase = catalogDatabase;
    }

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public InstallResult install(DriverUpdateCandidate candidate, AppSettings settings)
            throws IOException, InterruptedException {
        return install(candidate, settings, false);
    }

    public InstallResult install(DriverUpdateCandidate candidate, AppSettings settings,
            boolean allowRestoreOnlyForUnsupportedBackup)
            throws IOException, InterruptedException {
        if (candidate == null || candidate.installed() == null) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Invalid driver candidate.");
        }
        // Fail fast without admin: pnputil/msiexec/setup would half-apply
        // and surface cryptic errors. No destructive attempt without elevation.
        if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                    "Administrator rights required. Please restart the app as administrator and retry.");
        }
        // Cheap rejects BEFORE expensive side effects: a blocked candidate
        // must not cost a system restore point or a minutes-long driver
        // backup first (the old order created both, then returned BLOCKED).
        // Word boundaries alone miss suffixed forms (31.0.15.5009-beta1,
        // 1.0-rc1, 2.0alpha1): the trailing digit glues to the tag. The
        // digit-suffixed alternative closes the stable-only policy bypass.
        String earlyVer = candidate.availableVersion();
        if (isPreReleaseVersion(earlyVer)) {
            return new InstallResult(InstallStatus.BLOCKED_PRE_RELEASE, false,
                    "Blocked: candidate appears to be a pre-release (alpha/beta/rc/preview). Only stable releases are installed.");
        }
        String earlyUrl = candidate.downloadUrl();
        boolean earlyHasUrl = earlyUrl != null && !earlyUrl.isBlank();
        boolean earlyIsWU = "WindowsUpdate".equals(candidate.source())
                && candidate.packageId() != null && !candidate.packageId().isBlank();
        if (earlyHasUrl && !DriverInstallTrust.isTrustedHttpsUrl(earlyUrl, candidate.source())) {
            return new InstallResult(InstallStatus.BLOCKED_UNTRUSTED, false,
                    "Blocked: download URL is not from a trusted vendor. URL: " + earlyUrl);
        }
        if (!earlyIsWU && !earlyHasUrl) {
            return new InstallResult(InstallStatus.NO_DOWNLOAD_URL, false,
                    "No download URL available for " + candidate.source() + ". Check vendor website manually.");
        }
        boolean restoreRequested = settings.createSystemRestorePoint();
        boolean backupRequested = settings.autoBackupDrivers();
        boolean restoreOk = false;
        int restorePointSeq = -1;
        if (restoreRequested) {
            reportStatus("Creating system restore point…");
            AppLogger.info("Creating system restore point before driver update");
            com.sbtools.backup.SystemRestoreService.RestorePointResult rpResult;
            try {
                rpResult = restoreService.createRestorePoint(
                        "WinZenith driver update: " + candidate.installed().friendlyName(),
                        cancellationFlag);
            } catch (java.util.concurrent.CancellationException cancelEx) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }
            if (rpResult.success()) {
                restorePointSeq = rpResult.sequenceNumber();
                restoreOk = true;
                AppLogger.info("System restore point created successfully (seq=" + restorePointSeq + ")");
            } else {
                String err = rpResult.error() == null ? "" : rpResult.error();
                // Windows allows only one restore point per 24h by default.
                // FREQUENCY_LIMIT means a recent point already exists, so the
                // safety net is present even though creation was skipped.
                // Never trust it when a VSS error rides along (old script
                // versions mapped 0x80042316 into this prefix).
                if (err.contains("FREQUENCY_LIMIT") && !err.contains("0x80042316")) {
                    restoreOk = true;
                    AppLogger.info("Restore point skipped (recent point exists, FREQUENCY_LIMIT); treating safety net as present.");
                    reportStatus("Recent system restore point already exists — continuing.");
                } else {
                    AppLogger.warning("System restore point creation failed or was skipped: " + rpResult.error());
                    reportStatus("Warning: restore point unavailable — " + rpResult.error());
                }
            }
        }

        com.sbtools.backup.DriverBackupEntry backupEntry = null;
        boolean backupOk = false;
        String backupSupportIssue = backupRequested
                ? com.sbtools.backup.DriverBackupService.backupSupportIssue(candidate.installed()) : null;
        boolean backupSupported = backupSupportIssue == null;
        if (backupRequested) {
            if (!backupSupported) {
                if (!allowRestoreOnlyForUnsupportedBackup || !restoreOk) {
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Aborted: automatic driver backup is not supported for this device ("
                                    + backupSupportIssue + "). "
                                    + (restoreOk ? "Acknowledge the warning and retry, or disable automatic backup."
                                    : "No system restore point is available."));
                }
                AppLogger.warning("Proceeding without driver backup (unsupported INF) — restore point seq="
                        + restorePointSeq + " available");
            } else try {
                backupEntry = backupService.backupBeforeUpdate(candidate.installed(), settings, cancellationFlag);
                backupOk = backupEntry != null;
            } catch (java.util.concurrent.CancellationException | InterruptedException cancelEx) {
                // Stop Install during the minutes-long backup: never fall
                // through to "proceed without backup" — abort the install.
                if (cancelEx instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            } catch (Exception e) {
                AppLogger.warning("Pre-install driver backup failed: " + e.getMessage());
                reportStatus("Driver backup failed — " + e.getMessage());
            }
            if (cancellationFlag.get()) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }
            if (backupSupported && !backupOk) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                        restoreOk
                                ? "Aborted: pre-install driver backup failed. A system restore point exists (seq="
                                + restorePointSeq + ") but the requested driver backup did not complete. No install was started."
                                : "Aborted: pre-install driver backup failed and no system restore point is available. "
                                + "No changes were made. Free disk space / run as administrator and retry.");
            }
        } else if (!restoreOk && restoreRequested) {
            // Restore requested but failed, backup disabled: no safety net.
            return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                    "Aborted: system restore point could not be created and automatic driver backup is disabled. "
                    + "Enable driver backup or fix System Protection, then retry. No changes were made.");
        }

        String availVer = candidate.availableVersion();
        if (isPreReleaseVersion(availVer)) {
            removeBackupIfPresent(backupEntry);
            // System restore points are intentionally kept: they are a safety net
            // and are never auto-deleted (see Backup/Rollback policy).
            return new InstallResult(InstallStatus.BLOCKED_PRE_RELEASE, false,
                    "Blocked: candidate appears to be a pre-release (alpha/beta/rc/preview). Only stable releases are installed.");
        }

        if (backupEntry != null) {
            AppLogger.info("Backup preserved for rollback at: " + backupEntry.backupFolder());
        }

        if ("WindowsUpdate".equals(candidate.source()) && candidate.packageId() != null && !candidate.packageId().isBlank()) {
            if (cancellationFlag.get()) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }
            // packageId flows into a WU UpdateID='…' search string: reject
            // anything outside GUID-shaped characters (quotes would break the
            // criterion or select the wrong update).
            if (!candidate.packageId().matches("^[\\w\\{\\}-]+$")) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                        "Windows Update install blocked: malformed package ID.");
            }
            try {
                Path script = PowerShellScripts.resolve("wu-install.ps1");
                // Non-interactive: a profile/confirmation prompt would hang to
                // the 900s timeout (same reason SystemRestoreService uses it).
                ProcessResult result = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                        script.toString(), candidate.packageId()), cancellationFlag);
                if (!result.success()) {
                    // KEEP backup + restore point on failure: they are the
                    // rollback for a partially-applied update.
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Windows Update install failed: " + result.combinedOutput());
                }
                WindowsUpdateInstallResult.Parsed wu = WindowsUpdateInstallResult.parse(result.stdout());
                if (!wu.success()) {
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Windows Update install failed: " + wu.diagnostic() + ". " + result.combinedOutput());
                }
                boolean reboot = wu.rebootRequired();
                String message = reboot
                        ? "Driver installed via Windows Update. A restart is required to complete the installation."
                        : "Driver installed via Windows Update.";
                return new InstallResult(InstallStatus.SUCCESS, reboot, message);
            } catch (Exception e) {
                // KEEP rollback on exception (partial apply possible).
                return installFailure("Windows Update install", e);
            }
        }

        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
            String downloadUrl = candidate.downloadUrl();
            if (!DriverInstallTrust.isTrustedHttpsUrl(downloadUrl, candidate.source())) {
                removeBackupIfPresent(backupEntry);
                // Keep any restore point created above (never auto-delete).
                return new InstallResult(InstallStatus.BLOCKED_UNTRUSTED, false,
                        "Blocked: download URL is not from a trusted vendor. URL: " + downloadUrl);
            }
            try {
                InstallResult result = downloadAndInstallDriver(candidate, settings);
                // KEEP backup + restore point when the install did not
                // succeed: the device may be partially updated.
                return result;
            } catch (Exception e) {
                return installFailure("download and install", e);
            }
        }

        removeBackupIfPresent(backupEntry);
        // Keep restore point (never auto-delete); only the unused driver backup is cleaned.
        return new InstallResult(InstallStatus.NO_DOWNLOAD_URL, false,
                "No download URL available for " + candidate.source() + ". Check vendor website manually.");
    }

    /**
     * Stable-only policy gate: blocks alpha/beta/rc/preview/test candidates.
     * Word boundaries alone miss digit-glued suffixes (beta1, rc2), so a
     * digit-suffixed alternative is included.
     */
    static boolean isPreReleaseVersion(String v) {
        if (v == null) return false;
        return v.matches("(?i).*\\b(alpha|beta|rc|preview|test)\\b.*")
                || v.matches("(?i).*(alpha|beta|rc|preview|test)[-._]?\\d+.*");
    }

    private void removeBackupIfPresent(com.sbtools.backup.DriverBackupEntry backupEntry) {
        if (backupEntry != null) {
            try {
                backupService.removeBackupEntry(backupEntry);
            } catch (Exception e) {
                AppLogger.warning("Failed to remove backup entry: " + e.getMessage());
            }
        }
    }

    private InstallResult installFailure(String stage, Throwable e) {
        DriverInstallFailure.restoreInterruptFlag(e);
        String user = DriverInstallFailure.userMessage(stage, e, cancellationFlag.get());
        AppLogger.warning("Driver install failed at stage [" + stage + "]: "
                + DriverInstallFailure.deepestMessage(e), e);
        return new InstallResult(DriverInstallFailure.failureStatus(), false, user);
    }

    private InstallResult downloadAndInstallDriver(DriverUpdateCandidate candidate, AppSettings settings) {
        String stage = "resolving download destination";
        try {
            String configuredDir = settings.downloadDirectory();
            Path downloadsDir = (configuredDir != null && !configuredDir.isBlank())
                    ? Path.of(configuredDir)
                    : Paths.get(System.getProperty("user.home"), "Downloads");
            String protectedReason = DriverPreflightService.protectedDownloadLocation(downloadsDir);
            if (protectedReason != null) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                        "Download directory is not allowed (" + protectedReason
                                + "): " + downloadsDir + ". Choose a regular folder. No changes were made.");
            }
            Files.createDirectories(downloadsDir);
            String downloadUrl = candidate.downloadUrl();
            String filename = extractFilename(downloadUrl);
            // Never truncate a same-named user file (e.g. ~/Downloads/setup.exe):
            // our payload gets a unique name when the target already exists.
            Path driverFile = uniqueDestination(downloadsDir.resolve(filename));

            AppLogger.info("Downloading driver from: " + downloadUrl);
            reportProgress(0, 0, 0);

            stage = "download";
            try {
                driverFile = downloadFileWithProgress(downloadUrl, driverFile, candidate.source());
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTML page")) {
                    AppLogger.info("Download returned HTML, attempting to scrape actual download URL from: " + downloadUrl);
                    String scrapedUrl = scrapeDownloadUrlFromPage(downloadUrl);
                    if (scrapedUrl != null && !scrapedUrl.equals(downloadUrl)) {
                        if (!DriverInstallTrust.isTrustedHttpsUrl(scrapedUrl, candidate.source())) {
                            cleanupTempFiles(driverFile);
                            return new InstallResult(InstallStatus.BLOCKED_UNTRUSTED, false,
                                    "Blocked: scraped download URL is not from a trusted vendor host. URL: " + scrapedUrl);
                        }
                        AppLogger.info("Found alternative download URL: " + scrapedUrl);
                        filename = extractFilename(scrapedUrl);
                        driverFile = uniqueDestination(downloadsDir.resolve(filename));
                        driverFile = downloadFileWithProgress(scrapedUrl, driverFile, candidate.source());
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }

            if (!Files.exists(driverFile) || Files.size(driverFile) == 0) {
                cleanupTempFiles(driverFile);
                String vendorUrl = candidate.vendorPageUrl();
                if (vendorUrl != null && !vendorUrl.isBlank()) {
                    return new InstallResult(InstallStatus.DOWNLOAD_FAILED, false,
                            "Download failed: received empty file from " + downloadUrl
                            + "\nYou can try downloading manually from: " + vendorUrl);
                }
                return new InstallResult(InstallStatus.DOWNLOAD_FAILED, false,
                        "Download failed: received empty file from " + downloadUrl);
            }

            long fileSize = Files.size(driverFile);
            AppLogger.info("Driver downloaded (" + fileSize + " bytes) to: " + driverFile);
            reportProgress(fileSize, fileSize, 1.0);

            reportStatus("Verifying driver integrity…");
            stage = "checksum verification";

            java.util.Optional<CatalogEntry> catalogEntry = java.util.Optional.empty();
            if (catalogDatabase != null) {
                catalogEntry = catalogDatabase.findBestMatch(candidate.installed());
                if (catalogEntry.isPresent() && catalogEntry.get().hashSha256() != null
                        && !catalogEntry.get().hashSha256().isBlank()) {
                    String expectedHash = catalogEntry.get().hashSha256();
                    DriverVerificationService.VerificationResult hashResult = verificationService.verifyChecksum(driverFile, expectedHash);
                    if (!hashResult.verified()) {
                        AppLogger.warning("Catalog hash verification failed: " + hashResult.message());
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.VERIFICATION_FAILED, false,
                                "Catalog hash verification failed: " + hashResult.message());
                    }
                    AppLogger.info("Catalog hash verification passed for " + driverFile.getFileName());
                }
            }

            reportStatus("Verifying driver signature…");
            stage = "signature verification";

            String lowerForVerify = driverFile.getFileName().toString().toLowerCase();
            boolean isArchive = lowerForVerify.endsWith(".zip") || lowerForVerify.endsWith(".cab")
                    || lowerForVerify.endsWith(".7z") || lowerForVerify.endsWith(".zip.exe");
            // Archive bytes under an .exe name (vendor mislabel / rename).
            String magicArchiveExt = null;
            // A .zip.exe that is really an MZ executable is a self-extracting
            // installer: it must keep the outer signature check below and take
            // the EXE flow, not the zip-extract dead end.
            boolean sfxExecutable = lowerForVerify.endsWith(".zip.exe")
                    && ".exe".equals(detectExtensionByMagicBytes(driverFile));
            // gzip/rar bytes under an .exe name must skip the outer signature
            // check too and land on the manual-extraction guidance instead of
            // a misleading VERIFICATION_FAILED.
            if (!isArchive) {
                String magic = detectExtensionByMagicBytes(driverFile);
                if (".zip".equals(magic) || ".cab".equals(magic) || ".7z".equals(magic)
                        || ".gz".equals(magic) || ".rar".equals(magic)) {
                    isArchive = true;
                    magicArchiveExt = magic;
                }
            }
            if (isArchive && !sfxExecutable) {
                AppLogger.info("Skipping outer Authenticode check for archive " + driverFile.getFileName()
                        + " — inner INF/CAT will be verified after extraction");
            } else {                DriverVerificationService.VerificationResult sigResult = verificationService.verifyAuthenticode(driverFile);
                if (!sigResult.verified()) {
                    AppLogger.warning("Authenticode verification failed: " + sigResult.message());
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.VERIFICATION_FAILED, false,
                            "Signature verification failed: " + sigResult.message());
                }

                // If the catalog provides an expected signer thumbprint, verify it matches.
                if (catalogEntry.isPresent() && catalogEntry.get().certThumbprint() != null
                        && !catalogEntry.get().certThumbprint().isBlank()) {
                    String expectedThumb = catalogEntry.get().certThumbprint();
                    DriverVerificationService.VerificationResult thumbResult = verificationService.verifyAuthenticodeThumbprint(driverFile, expectedThumb);
                    if (!thumbResult.verified()) {
                        AppLogger.warning("Authenticode thumbprint verification failed: " + thumbResult.message());
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.VERIFICATION_FAILED, false,
                                "Signature thumbprint verification failed: " + thumbResult.message());
                    }
                    AppLogger.info("Authenticode thumbprint verified for " + driverFile.getFileName());
                }
            }

            reportStatus("Installing driver. Please wait…");
            stage = "installer launch";

            if (cancellationFlag.get()) {
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }

            // Re-verify the payload immediately before executing it: minutes
            // may have passed since the download-time check (extraction,
            // signature checks), and the file could have been swapped
            // meanwhile (AV quarantine restore, concurrent download, tamper).
            if (catalogEntry.isPresent() && catalogEntry.get().hashSha256() != null
                    && !catalogEntry.get().hashSha256().isBlank()) {
                DriverVerificationService.VerificationResult recheck =
                        verificationService.verifyChecksum(driverFile, catalogEntry.get().hashSha256());
                if (!recheck.verified()) {
                    AppLogger.warning("Pre-install hash re-check failed: " + recheck.message());
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.VERIFICATION_FAILED, false,
                            "Driver file changed after download (" + recheck.message() + "). Aborted safely.");
                }
            }

            String lowerName = driverFile.getFileName().toString().toLowerCase();
            if (lowerName.endsWith(".zip.exe") && !sfxExecutable) {
                AppLogger.info("Self-extracting ZIP archive detected, extracting: " + driverFile);
                ProcessResult installResult = installDriverFile(driverFile, candidate);
                if (!installResult.success()) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Self-extracting archive installation failed: " + installResult.combinedOutput());
                }
                AppLogger.info("Driver installed from self-extracting archive: " + driverFile);
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.SUCCESS, false, "Driver installed from " + driverFile.toString());
            } else if ((lowerName.endsWith(".exe") || sfxExecutable) && magicArchiveExt == null) {
                AppLogger.info("Launching silent installer: " + driverFile);

                DriverSilentInstallerArgs.IntelPackageFamily intelFamily =
                        DriverSilentInstallerArgs.detectIntelPackageFamily(driverFile, candidate);
                Path msiFile = null;
                if (!DriverSilentInstallerArgs.usesIntelSingleShotSilent(intelFamily)) {
                    msiFile = extractMsiFromExe(driverFile);
                }
                if (msiFile != null) {
                    if (cancellationFlag.get()) {
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                    }
                    AppLogger.info("Extracted MSI: " + msiFile);
                    stage = "MSI installation";
                    ProcessResult result = runMsiexecQuietInstall(msiFile);
                    boolean msiReboot = isRebootRequiredExitCode(result.exitCode());
                    if (result.success() || msiReboot) {
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.SUCCESS, msiReboot,
                                msiReboot ? "Driver installed silently via MSI. A restart is required."
                                        : "Driver installed silently via MSI.");
                    }
                    if (cancellationFlag.get()) {
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                    }
                    AppLogger.warning("MSI install failed: " + result.combinedOutput());
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "MSI installation failed: " + truncateInstallerOutput(result.combinedOutput()));
                }

                if (intelFamily == DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER) {
                    stage = "Intel Bluetooth installer launch";
                    reportStatus("Installing Intel Bluetooth (/quiet). Intel may reboot automatically when finished.");
                    AppLogger.info("Intel Bluetooth consumer package: /quiet may reboot automatically per vendor docs; "
                            + "file=" + driverFile.getFileName());
                }
                InstallResult silentExe = installSilentExeInstaller(driverFile, candidate, stage, intelFamily);
                cleanupTempFiles(driverFile);
                return silentExe;
            }

            if (cancellationFlag.get()) {
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }
            stage = "driver package installation";
            ProcessResult installResult = installDriverFile(driverFile, candidate);
            if (!installResult.success()) {
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                        "Installation failed: " + installResult.combinedOutput());
            }
            AppLogger.info("Driver installed successfully from: " + driverFile);
            cleanupTempFiles(driverFile);
            return new InstallResult(InstallStatus.SUCCESS, false, "Driver installed from " + driverFile.toString());
        } catch (Exception e) {
            return installFailure(stage, e);
        }
    }

    private String scrapeDownloadUrlFromPage(String pageUrl) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(pageUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "en-US,en;q=0.9");
            if (pageUrl.contains("amd.com")) {
                builder.header("Referer", "https://www.amd.com/en/support");
            } else if (pageUrl.contains("intel.com")) {
                builder.header("Referer", "https://www.intel.com/");
            } else if (pageUrl.contains("realtek.com")) {
                builder.header("Referer", "https://www.realtek.com/en/downloads");
            } else if (pageUrl.contains("broadcom.com")) {
                builder.header("Referer", "https://www.broadcom.com/support/download-search");
            } else if (pageUrl.contains("synaptics.com")) {
                builder.header("Referer", "https://www.synaptics.com/support");
            } else if (pageUrl.contains("qualcomm.com")) {
                builder.header("Referer", "https://www.qualcomm.com/support");
            }
            HttpRequest req = builder.GET().build();

            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return null;
            }

            String html = resp.body();
            // Quote- and query-tolerant (mirrors the provider link pattern):
            // minified pages use single quotes and CDN links carry ?ver= tails.
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "href\\s*=\\s*['\"](https?://[^'\"]+\\.(?:exe|zip|msi))(?:[?#][^'\"]*)?['\"]",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            // Vendor-CDN matches only. No firstFallback: returning an
            // arbitrary exe/zip link lets an attacker-controlled page pick
            // the installed binary (untrusted-code risk).
            while (m.find()) {
                String url = decodeHtmlEntities(m.group(1));
                String lower = url.toLowerCase();
                // Vendor-CDN matches only, aligned with isTrustedSource hosts:
                // anything else stays on the manual-download path.
                if (lower.contains("drivers.amd.com") || lower.contains("download.amd.com")
                        || lower.contains("downloadmirror.intel.com") || lower.contains("download.intel.com")
                        || lower.contains("nvidia.com") || lower.contains("nvdlcdn.com") || lower.contains("geforce.com")
                        || lower.contains("realtek.com") || lower.contains("broadcom.com")
                        || lower.contains("synaptics.com") || lower.contains("qualcomm.com")
                        || lower.contains("hp.com") || lower.contains("lenovo.com") || lower.contains("lenovo-images.com")
                        || lower.contains("dell.com") || lower.contains("dellcdn.com") || lower.contains("dell-cdn.com")
                        || lower.contains("asus.com") || lower.contains("asusnet.net")) {
                    return url;
                }
            }
            return null;
        } catch (Exception e) {
            AppLogger.warning("Error scraping download page: " + e.getMessage());
        }
        return null;
    }

    private static String decodeHtmlEntities(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    private void cleanupTempFiles(Path driverFile) {
        try {
            // Delete our downloaded payload too: every install left a
            // hundreds-of-MB installer behind in Downloads (disk leak).
            // driverFile is always our own unique download (see
            // uniqueDestination), never a pre-existing user file.
            if (driverFile != null) {
                try { Files.deleteIfExists(driverFile); } catch (Exception ignored) {}
            }
            if (driverFile == null || driverFile.getFileName() == null) return;
            String name = driverFile.getFileName().toString();
            Path extractDir = driverFile.getParent().resolve(
                    name.replaceFirst("(?i)\\.(?:zip|cab)(?:\\.exe)?$", "_extracted"));
            deleteDirectoryQuietly(extractDir);
            // Same for a magic-bytes rename the caller never saw (its extract
            // dir derives from the renamed filename, not the original).
            Path magicRenamed = lastMagicRename;
            if (magicRenamed != null && magicRenamed.getFileName() != null
                    && magicRenamed.getParent() != null
                    && driverFile.getParent() != null
                    && magicRenamed.getParent().equals(driverFile.getParent())) {
                Path renamedExtract = magicRenamed.getParent().resolve(
                        magicRenamed.getFileName().toString().replaceFirst("(?i)\\.(?:zip|cab)(?:\\.exe)?$", "_extracted"));
                if (!renamedExtract.equals(extractDir)) {
                    deleteDirectoryQuietly(renamedExtract);
                }
            }
            // Delete only the MSI copy WE created (tracked exact path): the old
            // base-derived guess could match a pre-existing user file.
            // Same for a magic-bytes rename the caller never saw.
            for (Path ours : new Path[]{lastMsiCopy, lastMagicRename}) {
                if (ours != null) {
                    try { Files.deleteIfExists(ours); } catch (Exception ignored) {}
                    try {
                        if (driverFile != null && driverFile.getParent() != null
                                && ours.getParent() != null
                                && ours.getParent().equals(driverFile.getParent())) {
                            if (ours.equals(lastMsiCopy)) lastMsiCopy = null;
                            if (ours.equals(lastMagicRename)) lastMagicRename = null;
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) {
            AppLogger.debug("Could not clean up temp files: " + e.getMessage());
        }
    }

    private Path downloadFileWithProgress(String url, Path destination, String source) throws IOException, InterruptedException {
        int maxRetries = 3;
        int attempt = 0;
        long backoffMs = 1000;
        while (true) {
            attempt++;
            try {
                return downloadFileWithProgressOnce(url, destination, source);
            } catch (IOException e) {
                if (attempt >= maxRetries || cancellationFlag.get() || "Download cancelled".equals(e.getMessage())) {
                    throw e;
                }
                AppLogger.warning("Download attempt " + attempt + " failed for " + url + ": " + e.getMessage() + ". Retrying in " + backoffMs + "ms...");
                // Interruptible backoff: Stop Install during the 1/2/4s wait
                // must cancel promptly instead of sleeping through it.
                long waited = 0;
                while (waited < backoffMs) {
                    if (cancellationFlag.get() || Thread.currentThread().isInterrupted()) {
                        throw new IOException("Download cancelled");
                    }
                    long slice = Math.min(100, backoffMs - waited);
                    try {
                        Thread.sleep(slice);
                    } catch (InterruptedException ie) {
                        // Translate to the codebase's cancel signal and clear
                        // the flag: this pooled thread is reused and a sticky
                        // interrupt would instantly fail the next install.
                        Thread.interrupted();
                        throw new IOException("Download cancelled");
                    }
                    waited += slice;
                }
                backoffMs *= 2;
            }
        }
    }

    private Path downloadFileWithProgressOnce(String url, Path destination, String source) throws IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept", "application/octet-stream, */*")
                .header("Accept-Language", "en-US,en;q=0.9");
        if (url.contains("amd.com")) {
            reqBuilder.header("Referer", "https://www.amd.com/en/support");
        } else if (url.contains("intel.com")) {
            reqBuilder.header("Referer", "https://www.intel.com/");
        } else if (url.contains("realtek.com")) {
            reqBuilder.header("Referer", "https://www.realtek.com/en/downloads");
        } else if (url.contains("broadcom.com")) {
            reqBuilder.header("Referer", "https://www.broadcom.com/support/download-search");
        } else if (url.contains("synaptics.com")) {
            reqBuilder.header("Referer", "https://www.synaptics.com/support");
        } else if (url.contains("qualcomm.com")) {
            reqBuilder.header("Referer", "https://www.qualcomm.com/support");
        }
        if (url.contains("nvidia.com") || url.contains("nvdlcdn.com")) {
            reqBuilder.header("Referer", "https://www.nvidia.com/");
        }
        HttpRequest req = reqBuilder.GET().build();

        HttpResponse<InputStream> response = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());

        // Redirect re-validation: HttpClient follows 302s automatically, so a
        // trusted URL could otherwise bounce to an attacker host whose bytes
        // we would then execute as admin. The final host must still be trusted.
        try {
            String finalUrl = response.uri() != null ? response.uri().toString() : url;
            if (source != null && !source.isBlank() && !DriverInstallTrust.isTrustedHttpsUrl(finalUrl, source)) {
                try { response.body().close(); } catch (Exception ignored) {}
                throw new IOException("Blocked: download redirected to untrusted host: " + finalUrl);
            }
        } catch (IOException blocked) {
            throw blocked;
        } catch (Exception ex) {
            try { response.body().close(); } catch (Exception ignored) {}
            throw new IOException("Redirect trust check failed: " + ex.getMessage());
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            try { response.body().close(); } catch (Exception ignored) {}
            throw new IOException("HTTP " + response.statusCode() + " when downloading " + url);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String contentDisposition = response.headers().firstValue("Content-Disposition").orElse("");
        if (contentType.toLowerCase().contains("text/html")) {
            try { response.body().close(); } catch (Exception ignored) {}
            throw new IOException("Download URL returned an HTML page instead of a file. The download link may be invalid or require a browser.");
        }

        String destName = destination.getFileName().toString();
        // Honor Content-Disposition filename if present. Quoted-string aware
        // (a quoted ";" must not truncate: filename="my;driver.zip"), plus
        // RFC 5987 filename*=UTF-8''... (percent-encoded, non-ASCII names).
        if (!contentDisposition.isBlank()) {
            String cdName = extractDispositionFilename(contentDisposition);
            if (cdName != null && !cdName.isBlank()) {
                cdName = sanitizeFilename(cdName);
                if (!cdName.isBlank()) {
                    destName = cdName;
                    destination = destination.getParent().resolve(cdName);
                    // Defend against path traversal – ensure still inside parent dir
                    Path parent = destination.getParent().toAbsolutePath().normalize();
                    Path normalized = destination.toAbsolutePath().normalize();
                    if (!normalized.startsWith(parent)) {
                        AppLogger.warning("Content-Disposition filename traverses outside downloads dir, ignoring: " + cdName);
                        destName = sanitizeFilename(destination.getFileName().toString());
                        destination = parent.resolve(destName);
                    }
                }
            }
        }
        // Sanitize destination filename from URL as well
        destName = sanitizeFilename(destName);
        if (destName.isBlank()) {
            destName = "driver_" + System.currentTimeMillis() + ".exe";
        }
        destination = destination.getParent().resolve(destName);
        Path finalDest = uniqueDestination(destination);
        int lastDot = destName.lastIndexOf('.');
        if (lastDot < 1) {
            String ext = extensionFromContentType(contentType);
            if (ext != null) {
                finalDest = uniqueDestination(destination.getParent().resolve(destName + ext));
                AppLogger.info("No file extension in URL, detected type from Content-Type: " + contentType + " → " + ext);
            }
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        // Pre-streaming space check: a 1.5GB bundle on a 600MB-free volume
        // passes the 500MB preflight and would otherwise fail minutes in,
        // leaving a partial file. 64MB headroom covers filesystem overhead.
        if (totalBytes > 0) {
            try {
                Path dlParent = finalDest.getParent();
                if (dlParent != null) {
                    long free = Files.getFileStore(dlParent).getUsableSpace();
                    if (totalBytes > Math.max(0, free - (64L * 1024 * 1024))) {
                        try { response.body().close(); } catch (Exception ignored) {}
                        throw new IOException("Not enough free space for this download (needs "
                                + totalBytes + " bytes, " + free + " available). Free disk space and retry.");
                    }
                }
            } catch (IOException nospace) {
                throw nospace;
            } catch (Exception ignored) {
            }
        }
        // Partial-file cleanup on ANY write failure (cancel, truncate, ENOSPC
        // mid-stream): retries start fresh and final failures leave no litter.
        // The truncation branch above deletes eagerly before throwing; this
        // covers the rest without double-delete harm.
        try {
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(finalDest)) {
            activeDownloadStream = in;
            try {
            byte[] buffer = new byte[65536];
            long bytesReceived = 0;
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (cancellationFlag.get()) {
                    throw new IOException("Download cancelled");
                }
                out.write(buffer, 0, read);
                bytesReceived += read;
                reportProgress(bytesReceived, totalBytes, totalBytes > 0 ? (double) bytesReceived / totalBytes : -1);
            }
            out.flush();
            // Truncated downloads (proxy/CDN cut closing the stream cleanly)
            // must never reach signature/install: they fail safe downstream
            // but with misleading errors, so reject here with a clear message.
            if (totalBytes > 0 && bytesReceived != totalBytes) {
                try { Files.deleteIfExists(finalDest); } catch (Exception ignored) {}
                throw new IOException("Download truncated: received " + bytesReceived + " of "
                        + totalBytes + " bytes from " + url + ". Retry the update.");
            }
            } finally {
                activeDownloadStream = null;
            }
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(finalDest); } catch (Exception ignored) {}
            throw e;
        }

        if (finalDest.getFileName().toString().lastIndexOf('.') < 1 && Files.size(finalDest) > 0) {
            String magicExt = detectExtensionByMagicBytes(finalDest);
            if (magicExt != null) {
                Path renamed = uniqueDestination(finalDest.getParent().resolve(finalDest.getFileName().toString() + magicExt));
                Files.move(finalDest, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                AppLogger.info("Detected file type from magic bytes → " + magicExt + ", renamed to: " + renamed);
                finalDest = renamed;
            }
        }

        return finalDest;
    }

    /**
     * Extracts a filename from a Content-Disposition header value. Handles
     * quoted strings containing ';' and RFC 5987 {@code filename*=UTF-8''...}
     * percent-encoding. Returns null when absent. Never throws.
     */
    static String extractDispositionFilename(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) return null;
        try {
            java.util.regex.Matcher star = java.util.regex.Pattern.compile(
                    "filename\\*\\s*=\\s*([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(headerValue);
            if (star.find()) {
                String v = star.group(1).trim();
                int enc = v.indexOf("''");
                String encoded = enc >= 0 ? v.substring(enc + 2) : v;
                try {
                    String decoded = java.net.URLDecoder.decode(encoded, java.nio.charset.StandardCharsets.UTF_8);
                    if (!decoded.isBlank()) return decoded.trim();
                } catch (Exception ignored) {
                    if (!encoded.isBlank()) return encoded.trim();
                }
            }
            java.util.regex.Matcher quoted = java.util.regex.Pattern.compile(
                    "filename\\s*=\\s*\"([^\"]*)\"", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(headerValue);
            if (quoted.find()) {
                return quoted.group(1).trim();
            }
            java.util.regex.Matcher bare = java.util.regex.Pattern.compile(
                    "filename\\s*=\\s*([^;\\s]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(headerValue);
            if (bare.find()) {
                return bare.group(1).trim();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String extensionFromContentType(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase();
        if (ct.contains("zip")) return ".zip";
        if (ct.contains("cab")) return ".cab";
        if (ct.contains("msi")) return ".msi";
        if (ct.contains("exe") || ct.contains("application/octet-stream")) return ".exe";
        if (ct.contains("x-7z")) return ".7z";
        return null;
    }

    private String detectExtensionByMagicBytes(Path file) {
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "r")) {
            byte[] header = new byte[16];
            int read = raf.read(header);
            if (read < 4) return null;

            if (header[0] == 'P' && header[1] == 'K' && header[2] == 0x03 && header[3] == 0x04) return ".zip";
            if (header[0] == 'M' && header[1] == 'Z') return ".exe";
            if (header[0] == 'M' && header[1] == 'S' && header[2] == 'C' && header[3] == 'F') return ".cab";
            if (header[0] == 0xD0 && header[1] == 0xCF && header[2] == 0x11 && header[3] == 0xE0
                    && header[4] == (byte) 0xA1 && header[5] == (byte) 0xB1
                    && header[6] == 0x1A && header[7] == (byte) 0xE1) return ".msi";
            if (header[0] == 0x37 && header[1] == 0x7A && header[2] == 0xBC && header[3] == 0xAF) return ".7z";
            if (header[0] == 0x1F && header[1] == (byte) 0x8B) return ".gz";
            // RAR v4/v5 signature: routes extensionless RAR bytes to the
            // manual-extraction branch instead of Authenticode failure.
            if (read >= 7 && header[0] == 0x52 && header[1] == 0x61 && header[2] == 0x72
                    && header[3] == 0x21 && header[4] == 0x1A && header[5] == 0x07) return ".rar";
        } catch (Exception e) {
            AppLogger.warning("Could not read magic bytes: " + e.getMessage());
        }
        return null;
    }

    private void reportProgress(long bytesReceived, long totalBytes, double fraction) {
        if (progressCallback != null) {
            progressCallback.onProgress(bytesReceived, totalBytes, fraction);
        }
    }

    private void reportStatus(String status) {
        if (statusCallback != null) {
            statusCallback.onStatusChanged(status);
        }
    }

    private ProcessResult installDriverFile(Path driverFile, DriverUpdateCandidate candidate) throws IOException, InterruptedException {
        if (cancellationFlag.get()) {
            throw new java.util.concurrent.CancellationException("Installation cancelled");
        }
        String filename = driverFile.getFileName().toString().toLowerCase();

        if (filename.endsWith(".inf")) {
            // Fail closed: a directly-downloaded INF must reference the
            // target device HW, otherwise refuse (wrong-device risk).
            if (!isInfPlausibleForDevice(driverFile, candidate)) {
                return new ProcessResult(1, "", "Downloaded INF does not match device "
                        + candidate.installed().friendlyName() + " — refusing install.");
            }
            return processRunner.run(java.util.List.of(new ProcessBuilder(
                    "pnputil.exe", "/add-driver", driverFile.toString(), "/install").command().toArray(new String[0])), cancellationFlag);
        } else if (filename.endsWith(".zip") || filename.endsWith(".zip.exe")) {
            Path extractDir = driverFile.getParent().resolve(
                    // Case-insensitive: DRIVER.ZIP must not map onto the file
                    // itself (createDirectories would throw FileAlreadyExists).
                    driverFile.getFileName().toString().replaceFirst("(?i)\\.zip(?:\\.exe)?$", "_extracted"));
            // Fresh dir: a previous crashed run (or an a.zip vs a.zip.exe
            // collision — same mapped name) may have left a stale setup.exe /
            // .inf behind that findMatchingInf/findFile would then pick.
            deleteDirectoryQuietly(extractDir);
            Files.createDirectories(extractDir);

            Path extractScript = PowerShellScripts.resolve("extract-driver-archive.ps1");
            ProcessResult extractResult = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                    extractScript.toString(), driverFile.toString(), extractDir.toString()), cancellationFlag);

            if (!extractResult.success()) {
                return new ProcessResult(1, "", "Failed to extract zip: " + extractResult.combinedOutput());
            }

            Path setupExe = findBestSetupExe(extractDir);
            if (setupExe != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                // Fail closed: any inner signature failure blocks silent /S
                // execution (untrusted-code risk). No substring allowlist.
                DriverVerificationService.VerificationResult innerSig = verificationService.verifyAuthenticode(setupExe);
                if (!innerSig.verified()) {
                    AppLogger.warning("Inner setup.exe signature check failed: " + innerSig.message());
                    return new ProcessResult(1, "", "Inner setup.exe signature check failed: " + innerSig.message());
                }
                // Fail closed: a signed setup.exe for the wrong device must
                // not run as admin. Require at least one INF in the bundle
                // to plausibly match the target device first.
                if (findMatchingInf(extractDir, candidate) == null) {
                    String dev = candidate == null || candidate.installed() == null
                            ? "?" : candidate.installed().friendlyName();
                    AppLogger.warning("Refusing setup.exe with no device-matching INF for " + dev);
                    return new ProcessResult(1, "", "Refusing to run setup.exe: no INF in the archive matches device "
                            + dev + " — wrong-device bundle risk.");
                }
                String[] cmd = new String[]{setupExe.toString(), "/S"};
                return processRunner.run(java.util.List.of(new ProcessBuilder(cmd).command().toArray(new String[0])), cancellationFlag);
            }

            Path infFile = findMatchingInf(extractDir, candidate);
            if (infFile != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                // Fail closed: accompanying .cat must verify when present.
                Path catFile = findFile(infFile.getParent(), ".cat");
                if (catFile != null) {
                    DriverVerificationService.VerificationResult catSig = verificationService.verifyAuthenticode(catFile);
                    if (!catSig.verified()) {
                        AppLogger.warning("Inner .cat signature check failed: " + catSig.message());
                        return new ProcessResult(1, "", "Inner .cat signature check failed: " + catSig.message());
                    }
                }
                return processRunner.run(java.util.List.of(new ProcessBuilder(
                        "pnputil.exe", "/add-driver", infFile.toString(), "/install"
                ).command().toArray(new String[0])), cancellationFlag);
            }

            return new ProcessResult(1, "", "No setup.exe or matching .inf found in extracted archive: " + extractDir
                    + " (refusing to install an INF that does not match the device).");
        } else if (filename.endsWith(".cab")) {
            Path extractDir = driverFile.getParent().resolve(
                    // Case-insensitive (see .zip branch above).
                    driverFile.getFileName().toString().replaceFirst("(?i)\\.cab$", "_extracted"));
            deleteDirectoryQuietly(extractDir);
            Files.createDirectories(extractDir);

            Path extractScript = PowerShellScripts.resolve("extract-driver-archive.ps1");
            ProcessResult extractResult = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                    extractScript.toString(), driverFile.toString(), extractDir.toString()), cancellationFlag);
            if (!extractResult.success()) {
                return new ProcessResult(1, "", "Failed to extract cab: " + extractResult.combinedOutput());
            }

            Path setupExe = findBestSetupExe(extractDir);
            if (setupExe != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                DriverVerificationService.VerificationResult innerSig = verificationService.verifyAuthenticode(setupExe);
                if (!innerSig.verified()) {
                    AppLogger.warning("Inner setup.exe (CAB) signature check failed: " + innerSig.message());
                    return new ProcessResult(1, "", "Inner setup.exe signature check failed: " + innerSig.message());
                }
                // Same wrong-device gate as the ZIP branch above.
                if (findMatchingInf(extractDir, candidate) == null) {
                    String dev = candidate == null || candidate.installed() == null
                            ? "?" : candidate.installed().friendlyName();
                    AppLogger.warning("Refusing CAB setup.exe with no device-matching INF for " + dev);
                    return new ProcessResult(1, "", "Refusing to run setup.exe: no INF in the archive matches device "
                            + dev + " — wrong-device bundle risk.");
                }
                String[] cmd = new String[]{setupExe.toString(), "/S"};
                return processRunner.run(java.util.List.of(new ProcessBuilder(cmd).command().toArray(new String[0])), cancellationFlag);
            }

            Path infFile = findMatchingInf(extractDir, candidate);
            if (infFile != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                Path catFile = findFile(infFile.getParent(), ".cat");
                if (catFile != null) {
                    DriverVerificationService.VerificationResult catSig = verificationService.verifyAuthenticode(catFile);
                    if (!catSig.verified()) {
                        AppLogger.warning("Inner .cat (CAB) signature check failed: " + catSig.message());
                        return new ProcessResult(1, "", "Inner .cat signature check failed: " + catSig.message());
                    }
                }
                return processRunner.run(java.util.List.of(new ProcessBuilder(
                        "pnputil.exe", "/add-driver", infFile.toString(), "/install"
                ).command().toArray(new String[0])), cancellationFlag);
            }

            return new ProcessResult(1, "", "No setup.exe or matching .inf found in extracted cab: " + extractDir
                    + " (refusing to install an INF that does not match the device).");
        } else if (filename.endsWith(".rar")) {
            return new ProcessResult(1, "", "RAR archives require manual extraction. Download: " + driverFile);
        } else if (filename.endsWith(".7z") || filename.endsWith(".gz") || filename.endsWith(".tar")
                || filename.endsWith(".tgz") || filename.endsWith(".bz2")) {
            return new ProcessResult(1, "", "Archive format requires manual extraction (no silent install path). Download: " + driverFile
                    + " — extract it, then install via setup.exe or right-click the matching .inf → Install, or use pnputil.");
        } else if (filename.endsWith(".msi")) {
            if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
            AppLogger.info("Installing MSI driver package: " + driverFile);
            ProcessResult result = runMsiexecQuietInstall(driverFile);
            boolean msiReboot = isRebootRequiredExitCode(result.exitCode());
            if (result.success() || msiReboot) {
                return new ProcessResult(0, "", msiReboot
                        ? "Driver installed silently via MSI. A restart is required."
                        : "Driver installed silently via MSI.");
            }
            if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
            AppLogger.warning("MSI /qn failed, trying /quiet: " + result.combinedOutput());
            ProcessResult fallbackResult = runMsiexecQuietInstallAlt(driverFile);
            boolean fallbackReboot = isRebootRequiredExitCode(fallbackResult.exitCode());
            if (fallbackResult.success() || fallbackReboot) {
                return new ProcessResult(0, "", fallbackReboot
                        ? "Driver installed silently via MSI. A restart is required."
                        : "Driver installed silently via MSI.");
            }
            return new ProcessResult(1, "", "MSI installation failed: " + fallbackResult.combinedOutput());
        }

        String magicExt = detectExtensionByMagicBytes(driverFile);
        if (magicExt != null && !filename.endsWith(magicExt)) {
            // Unique target: never overwrite a same-named user file sitting
            // next to the download (old code used REPLACE_EXISTING).
            Path renamed = uniqueDestination(driverFile.getParent().resolve(driverFile.getFileName().toString() + magicExt));
            // Guard against runaway renames (e.g. driver.7z + .7z → driver.7z.7z → …):
            // only rename when the extension actually changes, and refuse formats
            // with no silent install path instead of recursing forever.
            if (".7z".equals(magicExt) || ".gz".equals(magicExt)) {
                return new ProcessResult(1, "", "Archive format requires manual extraction (no silent install path). Download: " + driverFile
                        + " — extract it, then install via setup.exe or right-click the matching .inf → Install, or use pnputil.");
            }
            Files.move(driverFile, renamed);
            AppLogger.info("installDriverFile: detected type " + magicExt + " from magic bytes, retrying with " + renamed);
            // Track for the caller's cleanup: it only knows the original path
            // (now missing), so without this the renamed payload + its extract
            // dir would leak on every mislabeled download.
            lastMagicRename = renamed;
            return installDriverFile(renamed, candidate);
        }

        return new ProcessResult(2, "", "Unknown driver file type: " + filename);
    }

    /**
     * Finds the setup executable to run, preferring the OS architecture when a
     * bundle ships several (x86/setup.exe + x64/setup.exe): filesystem order
     * is nondeterministic and previously could install 32-bit bits on x64.
     */
    private Path findBestSetupExe(Path dir) throws IOException {
        java.util.List<Path> all;
        try (var walk = Files.walk(dir, 5)) {
            all = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase("setup.exe"))
                    .sorted()
                    .toList();
        }
        if (all.isEmpty()) return null;
        if (all.size() == 1) return all.get(0);
        String osArch = System.getProperty("os.arch", "").toLowerCase(java.util.Locale.ROOT);
        boolean is64 = osArch.contains("64") || osArch.contains("amd64");
        for (Path p : all) {
            String s = p.toString().toLowerCase(java.util.Locale.ROOT);
            if (is64 && (s.contains("x64") || s.contains("_64") || s.contains("64bit"))) return p;
            if (!is64 && (s.contains("x86") || s.contains("_32") || s.contains("32bit"))) return p;
        }
        AppLogger.warning("Multiple setup.exe in bundle, no arch-tagged match — using " + all.get(0));
        return all.get(0);
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

    /**
     * Picks the INF matching the target device's hardware IDs instead of the
     * arbitrary first INF in the archive (wrong-device risk). Falls back to
     * null — never to an unrelated INF — when no HW evidence matches.
     */
    private Path findMatchingInf(Path dir, DriverUpdateCandidate candidate) throws IOException {
        java.util.List<Path> infs;
        try (var walk = Files.walk(dir, 5)) {
            infs = walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".inf"))
                    .toList();
        }
        if (infs.isEmpty()) return null;
        if (infs.size() == 1) {
            return isInfPlausibleForDevice(infs.get(0), candidate) ? infs.get(0) : null;
        }
        for (Path inf : infs) {
            if (isInfPlausibleForDevice(inf, candidate)) return inf;
        }
        AppLogger.warning("No INF in " + dir + " matches device "
                + (candidate == null || candidate.installed() == null ? "?" : candidate.installed().friendlyName())
                + " (" + infs.size() + " INF(s) found) — refusing arbitrary pick");
        return null;
    }

    private boolean isInfPlausibleForDevice(Path inf, DriverUpdateCandidate candidate) {
        try {
            if (candidate == null || candidate.installed() == null) return false;
            String hw = candidate.installed().hardwareIds() == null ? "" : candidate.installed().hardwareIds().toUpperCase();
            String devId = candidate.installed().deviceId() == null ? "" : candidate.installed().deviceId().toUpperCase();
            // INFs are commonly UTF-16 LE (or ANSI); Files.readString assumes
            // UTF-8 and garbles them, causing false refusals of correct INFs.
            String content = readInfContent(inf).toUpperCase();
            // HW evidence: VEN_/DEV_/SUBSYS_/ACPI_/USB VID/PID tokens
            java.util.Set<String> tokens = new java.util.HashSet<>();
            for (String src : new String[]{hw, devId}) {
                for (String part : src.split("[;\\s,]+")) {
                    if (part.length() >= 4) tokens.add(part);
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                            "(VEN_[0-9A-F]{4}|DEV_[0-9A-F]{4}|SUBSYS_[0-9A-F]+|VID_[0-9A-F]{4}|PID_[0-9A-F]{4}|ACPI\\\\[A-Z0-9]+)")
                            .matcher(part);
                    while (m.find()) tokens.add(m.group(1));
                }
            }
            // Single-INF archives with no HW strings: allow (nothing to mismatch).
            // Multi-INF archives require at least one HW token hit.
            String infName = candidate.installed().infName();
            if (infName != null && !infName.isBlank()
                    && inf.getFileName().toString().equalsIgnoreCase(infName)) {
                return true;
            }
            if (tokens.isEmpty()) return false;
            for (String tok : tokens) {
                // Vendor-only tokens (bare VEN_xxxx / VID_xxxx) match every
                // same-vendor INF in a family bundle: require a
                // device-specific token (DEV_/PID_/SUBSYS_/ACPI_ or a longer
                // composite such as PCI\VEN_...&DEV_...).
                if (tok.matches("(VEN|VID)_[0-9A-F]{4}")) continue;
                if (tok.length() >= 8 && content.contains(tok)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Reads INF text tolerating UTF-16 LE/BE (common for driver INFs) and
     * ANSI fallbacks. BOM-aware; heuristic null-byte detection for BOM-less
     * UTF-16. Never throws: returns "" when undecodable.
     */
    static String readInfContent(Path inf) {
        try {
            byte[] bytes = Files.readAllBytes(inf);
            if (bytes.length >= 2) {
                if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
                    return new String(bytes, 3, bytes.length - 3, java.nio.charset.StandardCharsets.UTF_8);
                }
                if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xFE) {
                    return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16LE);
                }
                if ((bytes[0] & 0xFF) == 0xFE && (bytes[1] & 0xFF) == 0xFF) {
                    return new String(bytes, 2, bytes.length - 2, java.nio.charset.StandardCharsets.UTF_16BE);
                }
                // Heuristic: interleaved NULs indicate UTF-16 without BOM.
                int nulEven = 0;
                int nulOdd = 0;
                int sample = Math.min(bytes.length, 512);
                for (int i = 0; i < sample; i++) {
                    if (bytes[i] == 0) {
                        if ((i & 1) == 0) nulEven++;
                        else nulOdd++;
                    }
                }
                if (nulOdd > sample / 8 && nulOdd > nulEven * 2) {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE);
                }
                if (nulEven > sample / 8 && nulEven > nulOdd * 2) {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_16BE);
                }
            }
            try {
                return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
                return new String(bytes, java.nio.charset.Charset.forName("windows-1252"));
            }
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Best-effort recursive delete. Never throws.
     */
    static void deleteDirectoryQuietly(Path dir) {
        try {
            if (dir == null || !Files.isDirectory(dir)) return;
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                        });
            }
        } catch (Exception ignored) {
        }
    }

    private Path extractMsiFromExe(Path exeFile) {
        try {
            byte[] header = new byte[8];
            try (var in = Files.newInputStream(exeFile)) {
                if (in.read(header) != 8) return null;
            }
            boolean isMsi = (header[0] == (byte)0xD0 && header[1] == (byte)0xCF
                    && header[2] == (byte)0x11 && header[3] == (byte)0xE0
                    && header[4] == (byte)0xA1 && header[5] == (byte)0xB1
                    && header[6] == (byte)0x1A && header[7] == (byte)0xE1);
            if (!isMsi) return null;

            // Unique target: never overwrite a same-named user .msi.
            Path msiPath = uniqueDestination(exeFile.getParent().resolve(
                    replaceExeSuffix(exeFile.getFileName().toString(), ".msi")));
            // Move, not copy: same directory, so this is a cheap rename — a
            // copy would duplicate 700MB-2GB Dell/Lenovo bundles and can hit
            // NoSpaceException despite passing the 500MB preflight.
            // Callers keep using driverFile for messages; cleanup deletes the
            // moved file via lastMsiCopy, and deleteIfExists(original) is a
            // harmless no-op afterwards.
            Files.move(exeFile, msiPath);
            lastMsiCopy = msiPath;
            return msiPath;
        } catch (Exception e) {
            AppLogger.debug("Not an MSI-in-EXE: " + e.getMessage());
            return null;
        }
    }

    private InstallResult installSilentExeInstaller(Path driverFile, DriverUpdateCandidate candidate, String stage,
            DriverSilentInstallerArgs.IntelPackageFamily intelFamily)
            throws IOException, InterruptedException {
        if (DriverSilentInstallerArgs.usesIntelSingleShotSilent(intelFamily)) {
            String[] args = DriverSilentInstallerArgs.exeArgsFor(intelFamily);
            AppLogger.info("Intel single-shot install family=" + intelFamily
                    + " file=" + driverFile.getFileName()
                    + " args=[" + String.join(" ", args) + "]");
            return runSilentExeOnce(driverFile, args, candidate.source(), stage, intelFamily);
        }
        boolean isAmd = "AMD".equals(candidate.source());
        String[] primaryArgs = isAmd ? new String[]{"/S"} : new String[]{"/quiet"};
        String[] secondaryArgs = isAmd ? new String[]{"/quiet"} : new String[]{"/S"};
        String[] tertiaryArgs = isAmd ? new String[]{"/INSTALL"} : new String[]{"/passive", "/silent"};

        InstallResult lastFailed = runSilentExeOnce(driverFile, primaryArgs, candidate.source(), stage, null);
        if (lastFailed.installed()) {
            return lastFailed;
        }
        AppLogger.warning("EXE " + primaryArgs[0] + " failed, trying " + secondaryArgs[0]);
        lastFailed = runSilentExeOnce(driverFile, secondaryArgs, candidate.source(), stage, null);
        if (lastFailed.installed()) {
            return new InstallResult(lastFailed.status(), lastFailed.rebootRequired(),
                    "Driver installed silently via fallback installer."
                            + (lastFailed.rebootRequired() ? " A restart is required." : ""));
        }
        for (String tertiaryArg : tertiaryArgs) {
            AppLogger.warning("EXE trying tertiary arg " + tertiaryArg);
            lastFailed = runSilentExeOnce(driverFile, new String[]{tertiaryArg}, candidate.source(), stage, null);
            if (lastFailed.installed()) {
                return new InstallResult(lastFailed.status(), lastFailed.rebootRequired(),
                        "Driver installed silently via tertiary installer."
                                + (lastFailed.rebootRequired() ? " A restart is required." : ""));
            }
        }
        return lastFailed;
    }

    private InstallResult runSilentExeOnce(Path driverFile, String[] args, String source, String stage,
            DriverSilentInstallerArgs.IntelPackageFamily intelFamily)
            throws IOException, InterruptedException {
        if (cancellationFlag.get()) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, DriverInstallFailure.CANCELLED_MESSAGE);
        }
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(driverFile.toString());
        for (String arg : args) {
            command.add(arg);
        }
        long startNanos = System.nanoTime();
        boolean intelBluetooth = intelFamily == DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER;
        String waitStage = intelBluetooth ? "installer wait" : (stage == null ? "installer wait" : stage);
        AppLogger.info("Launching silent installer (" + source + "): "
                + driverFile.getFileName() + " " + String.join(" ", args));
        ProcessResult result;
        try {
            result = processRunner.run(command, cancellationFlag);
        } catch (java.util.concurrent.CancellationException ce) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, DriverInstallFailure.CANCELLED_MESSAGE);
        } catch (InterruptedException ie) {
            DriverInstallFailure.restoreInterruptFlag(ie);
            if (cancellationFlag.get()) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, DriverInstallFailure.CANCELLED_MESSAGE);
            }
            String msg = intelBluetooth
                    ? DriverInstallFailure.intelInstallerWaitInterrupted(source)
                    : DriverInstallFailure.interruptedMessage(waitStage);
            AppLogger.warning(msg, ie);
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, msg);
        } catch (IOException ioe) {
            String msg = intelBluetooth
                    ? DriverInstallFailure.intelInstallerLaunchFailure(source, driverFile, args, ioe)
                    : DriverInstallFailure.stageFailureMessage(stage, DriverInstallFailure.deepestMessage(ioe));
            AppLogger.warning(msg, ioe);
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, msg);
        }
        logInstallerResult(source, driverFile, args, result, startNanos);
        return classifySilentExeOutcome(result, intelFamily);
    }

    static InstallResult classifySilentExeOutcome(ProcessResult result,
            DriverSilentInstallerArgs.IntelPackageFamily intelFamily) {
        if (WindowsInstallerInvoke.isUsageHelpOutput(result.stdout(), result.stderr())) {
            AppLogger.warning("Installer emitted Windows Installer usage help (exit " + result.exitCode() + "): "
                    + truncateInstallerOutput(result.combinedOutput()));
            String msg = intelFamily == DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER
                    ? WindowsInstallerInvoke.INTEL_BLUETOOTH_INVALID_CMD_MESSAGE
                    : WindowsInstallerInvoke.INVALID_INVOCATION_MESSAGE;
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, msg);
        }
        boolean reboot = isRebootRequiredExitCode(result.exitCode());
        if (result.success() || reboot) {
            return new InstallResult(InstallStatus.SUCCESS, reboot,
                    reboot ? "Driver installed silently. A restart is required."
                            : "Driver installed silently.");
        }
        if (WindowsInstallerInvoke.isUsageHelpOutput(result.combinedOutput())) {
            AppLogger.warning("Installer failed with usage help in output (exit " + result.exitCode() + "): "
                    + truncateInstallerOutput(result.combinedOutput()));
            String msg = intelFamily == DriverSilentInstallerArgs.IntelPackageFamily.BLUETOOTH_CONSUMER
                    ? WindowsInstallerInvoke.INTEL_BLUETOOTH_INVALID_CMD_MESSAGE
                    : WindowsInstallerInvoke.INVALID_INVOCATION_MESSAGE;
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, msg);
        }
        String detail = truncateInstallerOutput(result.combinedOutput());
        return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                "Silent installation failed (exit " + result.exitCode() + "): " + detail);
    }

    private static void logInstallerResult(String source, Path driverFile, String[] args,
                                           ProcessResult result, long startNanos) {
        long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
        AppLogger.info("Installer " + source + " " + driverFile.getFileName()
                + " args=[" + String.join(" ", args) + "] exit=" + result.exitCode()
                + " elapsedMs=" + elapsedMs + " output=" + truncateInstallerOutput(result.combinedOutput()));
    }

    private static String truncateInstallerOutput(String output) {
        if (output == null || output.isBlank()) {
            return "";
        }
        String trimmed = output.trim();
        return trimmed.length() > 1500 ? trimmed.substring(0, 1500) + "… [truncated]" : trimmed;
    }

    private ProcessResult runMsiexecQuietInstall(Path msiPackage) throws IOException, InterruptedException {
        return runMsiexecInstall(msiPackage, "/qn");
    }

    private ProcessResult runMsiexecQuietInstallAlt(Path msiPackage) throws IOException, InterruptedException {
        return runMsiexecInstall(msiPackage, "/quiet");
    }

    private ProcessResult runMsiexecInstall(Path msiPackage, String quietFlag)
            throws IOException, InterruptedException {
        String validationError = WindowsInstallerInvoke.validateMsiPackage(msiPackage);
        if (validationError != null) {
            AppLogger.warning("MSI validation failed: " + validationError);
            return new ProcessResult(1, "", validationError);
        }
        ProcessResult result = processRunner.run(java.util.List.of(new ProcessBuilder(
                "msiexec.exe", "/i", msiPackage.toString(), quietFlag, "/norestart"
        ).command().toArray(new String[0])), cancellationFlag);
        if (WindowsInstallerInvoke.isUsageHelpOutput(result.stdout(), result.stderr())) {
            return new ProcessResult(1, "", WindowsInstallerInvoke.INVALID_INVOCATION_MESSAGE);
        }
        return result;
    }

    private static boolean isRebootRequiredExitCode(int exitCode) {
        return exitCode == 3010 || exitCode == 1641 || exitCode == 5103;
    }

    /**
     * Replaces only a trailing {@code .exe} suffix (case-insensitive).
     * {@code String#replace} rewrote every occurrence, so {@code driver.EXE}
     * mapped onto itself (self-copy + doomed msiexec attempt) and
     * {@code my.exe.setup.exe} became {@code my.msi.setup.msi}.
     */
    static String replaceExeSuffix(String name, String replacement) {
        if (name != null && name.length() > 4 && name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
            return name.substring(0, name.length() - 4) + replacement;
        }
        return name;
    }

    private String extractFilename(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            if (path == null || path.isEmpty()) {
                return "driver_" + System.currentTimeMillis() + ".exe";
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                return "driver_" + System.currentTimeMillis() + ".exe";
            }
            String decoded = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
            String sanitized = sanitizeFilename(decoded);
            if (sanitized.isEmpty()) {
                return "driver_" + System.currentTimeMillis() + ".exe";
            }
            return sanitized;
        } catch (Exception e) {
            return "driver_" + System.currentTimeMillis() + ".exe";
        }
    }

    /**
     * Sanitizes a filename to prevent path traversal and illegal characters.
     * Strips directory components, null bytes, and replaces unsafe chars.
     */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) return "";
        // Strip null bytes
        name = name.replace("\0", "");
        // Take only basename (handles both / and \ separators)
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        // Remove leading dots and handle traversal attempts
        name = name.replaceAll("^[.]+", "");
        // Reject if still contains parent traversal or separators after extraction
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")) {
            name = name.replaceAll("[./\\\\:]+", "_");
        }
        // Replace control chars and unsafe filesystem chars (<>\"|?* plus control)
        name = name.replaceAll("[\\x00-\\x1F\\x7F<>\"|?*]", "_");
        // Limit length to 128 to avoid path-length issues
        if (name.length() > 128) {
            int dot = name.lastIndexOf('.');
            if (dot > 0 && dot < name.length() - 1 && name.length() - dot <= 10) {
                String ext = name.substring(dot);
                name = name.substring(0, 128 - ext.length()) + ext;
            } else {
                name = name.substring(0, 128);
            }
        }
        name = name.trim();
        if (name.isEmpty() || name.equals(".") || name.equals("..")) return "";
        return name;
    }

    /**
     * Returns a non-existing sibling when {@code p} already exists
     * (appends _1, _2, …), otherwise {@code p} unchanged. Prevents our
     * download from truncating a same-named user file. Never throws.
     */
    static Path uniqueDestination(Path p) {
        try {
            if (p == null || !Files.exists(p)) return p;
            Path parent = p.getParent();
            String name = p.getFileName().toString();
            // Compound extension stays atomic: driver.zip.exe collides to
            // driver_1.zip.exe (same routing), not driver.zip_1.exe (which
            // would flip to the EXE flow for identical bytes).
            String ext;
            String stem;
            if (name.length() > 8 && name.regionMatches(true, name.length() - 8, ".zip.exe", 0, 8)) {
                ext = name.substring(name.length() - 8);
                stem = name.substring(0, name.length() - 8);
            } else {
                int dot = name.lastIndexOf('.');
                stem = (dot > 0 ? name.substring(0, dot) : name);
                ext = (dot > 0 ? name.substring(dot) : "");
            }
            if (stem.length() > 100) stem = stem.substring(0, 100);
            for (int n = 1; n < 1000; n++) {
                Path q = parent.resolve(stem + "_" + n + ext);
                if (!Files.exists(q)) return q;
            }
            return parent.resolve(stem + "_" + java.util.UUID.randomUUID().toString().substring(0, 8) + ext);
        } catch (Exception e) {
            return p;
        }
    }

    public void cancel() {
        cancellationFlag.set(true);
        // Unblock a stalled body read (see activeDownloadStream): the loop
        // translates the resulting IOException into "Download cancelled".
        try {
            InputStream s = activeDownloadStream;
            if (s != null) s.close();
        } catch (Exception ignored) {
        }
    }

    public void resetCancellation() {
        cancellationFlag.set(false);
    }

    public boolean isCancelled() {
        return cancellationFlag.get();
    }

    public record InstallResult(InstallStatus status, boolean rebootRequired, String message) {
        /** Installer/WU finished without a failure status — not a verified on-disk version bump. */
        public boolean installed() {
            return status.isSuccess();
        }
    }
}
