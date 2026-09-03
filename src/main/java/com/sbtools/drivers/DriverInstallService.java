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
    private volatile ProgressCallback progressCallback;
    private volatile StatusCallback statusCallback;

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
        if (candidate == null || candidate.installed() == null) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Invalid driver candidate.");
        }
        // Fail fast without admin: pnputil/msiexec/setup would half-apply
        // and surface cryptic errors. No destructive attempt without elevation.
        if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
            return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                    "Administrator rights required. Please restart the app as administrator and retry.");
        }
        boolean restoreRequested = settings.createSystemRestorePoint();
        boolean backupRequested = settings.autoBackupDrivers();
        boolean restoreOk = false;
        int restorePointSeq = -1;
        if (restoreRequested) {
            reportStatus("Creating system restore point…");
            AppLogger.info("Creating system restore point before driver update");
            var rpResult = restoreService.createRestorePoint(
                    "WinZenith driver update: " + candidate.installed().friendlyName());
            if (rpResult.success()) {
                restorePointSeq = rpResult.sequenceNumber();
                restoreOk = true;
                AppLogger.info("System restore point created successfully (seq=" + restorePointSeq + ")");
            } else {
                String err = rpResult.error() == null ? "" : rpResult.error();
                // Windows allows only one restore point per 24h by default.
                // FREQUENCY_LIMIT means a recent point already exists, so the
                // safety net is present even though creation was skipped.
                if (err.contains("FREQUENCY_LIMIT")) {
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
        if (backupRequested) {
            try {
                backupEntry = backupService.backupBeforeUpdate(candidate.installed(), settings);
                backupOk = backupEntry != null;
            } catch (Exception e) {
                AppLogger.warning("Pre-install driver backup failed: " + e.getMessage());
                reportStatus("Driver backup failed — " + e.getMessage());
            }
            if (!backupOk) {
                // Fail closed when the user asked for a safety net: never
                // proceed to a destructive install with no backup. If restore
                // also failed/unrequested, there is no rollback at all.
                if (!restoreOk) {
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Aborted: pre-install driver backup failed and no system restore point is available. "
                            + "No changes were made. Free disk space / run as administrator and retry.");
                }
                AppLogger.warning("Proceeding without driver backup (restore point seq=" + restorePointSeq + " available)");
            }
        } else if (!restoreOk && restoreRequested) {
            // Restore requested but failed, backup disabled: no safety net.
            return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                    "Aborted: system restore point could not be created and automatic driver backup is disabled. "
                    + "Enable driver backup or fix System Protection, then retry. No changes were made.");
        }

        String availVer = candidate.availableVersion();
        if (availVer != null && availVer.matches("(?i).*\\b(alpha|beta|rc|preview|test)\\b.*")) {
            removeBackupIfPresent(backupEntry);
            removeRestorePointIfPresent(restorePointSeq);
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
            try {
                Path script = PowerShellScripts.resolve("wu-install.ps1");
                ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                        script.toString(), candidate.packageId()), cancellationFlag);
                if (!result.success()) {
                    // KEEP backup + restore point on failure: they are the
                    // rollback for a partially-applied update.
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false,
                            "Windows Update install failed: " + result.combinedOutput());
                }
                boolean reboot = false;
                String message = "Driver installed via Windows Update.";
                if (result.stdout() != null && !result.stdout().isBlank()) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode root = com.sbtools.util.JsonMapper.parseTree(result.stdout());
                        if (root.has("rebootRequired")) {
                            reboot = root.get("rebootRequired").asBoolean(false);
                        }
                        if (reboot) {
                            message = "Driver installed via Windows Update. A restart is required to complete the installation.";
                        }
                    } catch (Exception parseEx) {
                        if (result.stdout().contains("\"rebootRequired\":true")) {
                            reboot = true;
                            message = "Driver installed via Windows Update. A restart is required to complete the installation.";
                        }
                    }
                }
                return new InstallResult(InstallStatus.SUCCESS, reboot, message);
            } catch (Exception e) {
                // KEEP rollback on exception (partial apply possible).
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Error: " + e.getMessage());
            }
        }

        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
            String downloadUrl = candidate.downloadUrl();
            if (!isTrustedSource(downloadUrl, candidate.source())) {
                removeBackupIfPresent(backupEntry);
                removeRestorePointIfPresent(restorePointSeq);
                return new InstallResult(InstallStatus.BLOCKED_UNTRUSTED, false,
                        "Blocked: download URL is not from a trusted vendor. URL: " + downloadUrl);
            }
            try {
                InstallResult result = downloadAndInstallDriver(candidate, settings);
                // KEEP backup + restore point when the install did not
                // succeed: the device may be partially updated.
                return result;
            } catch (Exception e) {
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Error: " + e.getMessage());
            }
        }

        removeBackupIfPresent(backupEntry);
        removeRestorePointIfPresent(restorePointSeq);
        return new InstallResult(InstallStatus.NO_DOWNLOAD_URL, false,
                "No download URL available for " + candidate.source() + ". Check vendor website manually.");
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

    private void removeRestorePointIfPresent(int sequenceNumber) {
        if (sequenceNumber > 0) {
            try {
                restoreService.deleteRestorePoint(sequenceNumber);
            } catch (Exception e) {
                AppLogger.warning("Failed to remove restore point: " + e.getMessage());
            }
        }
    }

    private InstallResult downloadAndInstallDriver(DriverUpdateCandidate candidate, AppSettings settings) {
        try {
            String configuredDir = settings.downloadDirectory();
            Path downloadsDir = (configuredDir != null && !configuredDir.isBlank())
                    ? Path.of(configuredDir)
                    : Paths.get(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloadsDir);
            String downloadUrl = candidate.downloadUrl();
            String filename = extractFilename(downloadUrl);
            Path driverFile = downloadsDir.resolve(filename);

            AppLogger.info("Downloading driver from: " + downloadUrl);
            reportProgress(0, 0, 0);
            
            try {
                driverFile = downloadFileWithProgress(downloadUrl, driverFile);
            } catch (IOException e) {
                if (e.getMessage() != null && e.getMessage().contains("HTML page")) {
                    AppLogger.info("Download returned HTML, attempting to scrape actual download URL from: " + downloadUrl);
                    String scrapedUrl = scrapeDownloadUrlFromPage(downloadUrl);
                    if (scrapedUrl != null && !scrapedUrl.equals(downloadUrl)) {
                        if (!isTrustedSource(scrapedUrl, candidate.source())) {
                            cleanupTempFiles(driverFile);
                            return new InstallResult(InstallStatus.BLOCKED_UNTRUSTED, false,
                                    "Blocked: scraped download URL is not from a trusted vendor host. URL: " + scrapedUrl);
                        }
                        AppLogger.info("Found alternative download URL: " + scrapedUrl);
                        filename = extractFilename(scrapedUrl);
                        driverFile = downloadsDir.resolve(filename);
                        driverFile = downloadFileWithProgress(scrapedUrl, driverFile);
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }

            if (!Files.exists(driverFile) || Files.size(driverFile) == 0) {
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

            String lowerForVerify = driverFile.getFileName().toString().toLowerCase();
            boolean isArchive = lowerForVerify.endsWith(".zip") || lowerForVerify.endsWith(".cab")
                    || lowerForVerify.endsWith(".7z") || lowerForVerify.endsWith(".zip.exe");
            // Also detect archives disguised as .exe (self-extracting, actually zip)
            if (!isArchive) {
                String magic = detectExtensionByMagicBytes(driverFile);
                if (".zip".equals(magic) || ".cab".equals(magic) || ".7z".equals(magic)) {
                    isArchive = true;
                }
            }
            if (isArchive) {
                AppLogger.info("Skipping outer Authenticode check for archive " + driverFile.getFileName()
                        + " — inner INF/CAT will be verified after extraction");
            } else {
                DriverVerificationService.VerificationResult sigResult = verificationService.verifyAuthenticode(driverFile);
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

            if (cancellationFlag.get()) {
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }

            String lowerName = driverFile.getFileName().toString().toLowerCase();
            if (lowerName.endsWith(".zip.exe")) {
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
            } else if (lowerName.endsWith(".exe")) {
                AppLogger.info("Launching silent installer: " + driverFile);

                Path msiFile = extractMsiFromExe(driverFile);
                if (msiFile != null) {
                    if (cancellationFlag.get()) {
                        cleanupTempFiles(driverFile);
                        return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                    }
                    AppLogger.info("Extracted MSI: " + msiFile);
                    ProcessResult result = processRunner.run(java.util.List.of(new ProcessBuilder(
                            "msiexec.exe", "/i", msiFile.toString(), "/qn", "/norestart"
                    ).command().toArray(new String[0])), cancellationFlag);
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
                    AppLogger.warning("MSI install failed, falling back to EXE: " + result.combinedOutput());
                }

                // AMD Adrenalin and chipset installers use /S (silent) whereas Intel/Nvidia use /quiet.
                // Try vendor-specific order to reduce failed attempts and timeout.
                boolean isAmd = "AMD".equals(candidate.source());
                String[] primaryArgs = isAmd ? new String[]{"/S"} : new String[]{"/quiet"};
                String[] secondaryArgs = isAmd ? new String[]{"/quiet"} : new String[]{"/S"};
                String[] tertiaryArgs = isAmd ? new String[]{"/INSTALL"} : new String[]{"/passive", "/silent"};

                if (cancellationFlag.get()) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                ProcessResult result;
                try {
                    result = processRunner.run(java.util.List.of(new ProcessBuilder(
                            driverFile.toString(), primaryArgs[0]
                    ).command().toArray(new String[0])), cancellationFlag);
                } catch (java.util.concurrent.CancellationException ce) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                boolean exeReboot = isRebootRequiredExitCode(result.exitCode());
                if (result.success() || exeReboot) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.SUCCESS, exeReboot,
                            exeReboot ? "Driver installed silently. A restart is required."
                                    : "Driver installed silently.");
                }

                if (cancellationFlag.get()) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                AppLogger.warning("EXE " + primaryArgs[0] + " failed (" + result.exitCode() + "), trying " + secondaryArgs[0] + ": " + result.combinedOutput());
                ProcessResult fallbackResult;
                try {
                    fallbackResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                            driverFile.toString(), secondaryArgs[0]
                    ).command().toArray(new String[0])), cancellationFlag);
                } catch (java.util.concurrent.CancellationException ce) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                boolean fallbackReboot = isRebootRequiredExitCode(fallbackResult.exitCode());
                if (fallbackResult.success() || fallbackReboot) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.SUCCESS, fallbackReboot,
                            fallbackReboot ? "Driver installed silently via fallback installer. A restart is required."
                                    : "Driver installed silently via fallback installer.");
                }
                if (cancellationFlag.get()) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                AppLogger.warning("EXE " + secondaryArgs[0] + " failed, trying " + tertiaryArgs[0] + ": " + fallbackResult.combinedOutput());
                ProcessResult thirdResult;
                try {
                    thirdResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                            driverFile.toString(), tertiaryArgs[0]
                    ).command().toArray(new String[0])), cancellationFlag);
                } catch (java.util.concurrent.CancellationException ce) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
                }
                boolean thirdReboot = isRebootRequiredExitCode(thirdResult.exitCode());
                if (thirdResult.success() || thirdReboot) {
                    cleanupTempFiles(driverFile);
                    return new InstallResult(InstallStatus.SUCCESS, thirdReboot,
                            thirdReboot ? "Driver installed silently via tertiary installer. A restart is required."
                                    : "Driver installed silently via tertiary installer.");
                }
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Silent installation failed: " + thirdResult.combinedOutput());
            }

            if (cancellationFlag.get()) {
                cleanupTempFiles(driverFile);
                return new InstallResult(InstallStatus.INSTALL_FAILED, false, "Installation cancelled by user.");
            }
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
            AppLogger.warning("Error during download and install: " + e.getMessage());
            return new InstallResult(InstallStatus.UNKNOWN_ERROR, false, "Error: " + e.getMessage());
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
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                    "href\\s*=\\s*\"(https?://[^\"]+\\.(?:exe|zip|msi))\"",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = p.matcher(html);
            // Vendor-CDN matches only. No firstFallback: returning an
            // arbitrary exe/zip link lets an attacker-controlled page pick
            // the installed binary (untrusted-code risk).
            while (m.find()) {
                String url = decodeHtmlEntities(m.group(1));
                String lower = url.toLowerCase();
                if (lower.contains("drivers.amd.com") || lower.contains("download.amd.com")
                        || lower.contains("downloadmirror.intel.com") || lower.contains("download.intel.com")
                        || lower.contains("realtek.com") || lower.contains("broadcom.com")
                        || lower.contains("synaptics.com") || lower.contains("qualcomm.com")
                        || lower.contains("hp.com") || lower.contains("lenovo.com")) {
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
            String name = driverFile.getFileName().toString();
            Path extractDir = driverFile.getParent().resolve(
                    name.replaceFirst("\\.(?:zip|cab)(?:\\.exe)?$", "_extracted"));
            if (Files.isDirectory(extractDir)) {
                try (var walk = Files.walk(extractDir)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                            });
                }
            }
            String baseName = name.replaceAll("\\.[^.]+$", "");
            Path msiCopy = driverFile.getParent().resolve(baseName + ".msi");
            Files.deleteIfExists(msiCopy);
        } catch (Exception e) {
            AppLogger.debug("Could not clean up temp files: " + e.getMessage());
        }
    }

    private Path downloadFileWithProgress(String url, Path destination) throws IOException, InterruptedException {
        int maxRetries = 3;
        int attempt = 0;
        long backoffMs = 1000;
        while (true) {
            attempt++;
            try {
                return downloadFileWithProgressOnce(url, destination);
            } catch (IOException e) {
                if (attempt >= maxRetries || cancellationFlag.get() || "Download cancelled".equals(e.getMessage())) {
                    throw e;
                }
                AppLogger.warning("Download attempt " + attempt + " failed for " + url + ": " + e.getMessage() + ". Retrying in " + backoffMs + "ms...");
                Thread.sleep(backoffMs);
                backoffMs *= 2;
            }
        }
    }

    private Path downloadFileWithProgressOnce(String url, Path destination) throws IOException, InterruptedException {
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

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " when downloading " + url);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        String contentDisposition = response.headers().firstValue("Content-Disposition").orElse("");
        if (contentType.toLowerCase().contains("text/html")) {
            throw new IOException("Download URL returned an HTML page instead of a file. The download link may be invalid or require a browser.");
        }

        String destName = destination.getFileName().toString();
        // Honor Content-Disposition filename if present (e.g. attachment; filename="...")
        if (!contentDisposition.isBlank()) {
            java.util.regex.Matcher cdMatcher = java.util.regex.Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(contentDisposition);
            if (cdMatcher.find()) {
                String cdName = cdMatcher.group(1).trim();
                if (!cdName.isBlank()) {
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
        }
        // Sanitize destination filename from URL as well
        destName = sanitizeFilename(destName);
        if (destName.isBlank()) {
            destName = "driver_" + System.currentTimeMillis() + ".exe";
        }
        destination = destination.getParent().resolve(destName);
        Path finalDest = destination;
        int lastDot = destName.lastIndexOf('.');
        if (lastDot < 1) {
            String ext = extensionFromContentType(contentType);
            if (ext != null) {
                finalDest = destination.getParent().resolve(destName + ext);
                AppLogger.info("No file extension in URL, detected type from Content-Type: " + contentType + " → " + ext);
            }
        }

        long totalBytes = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(finalDest)) {
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
        }

        if (finalDest.getFileName().toString().lastIndexOf('.') < 1 && Files.size(finalDest) > 0) {
            String magicExt = detectExtensionByMagicBytes(finalDest);
            if (magicExt != null) {
                Path renamed = finalDest.getParent().resolve(finalDest.getFileName().toString() + magicExt);
                Files.move(finalDest, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                AppLogger.info("Detected file type from magic bytes → " + magicExt + ", renamed to: " + renamed);
                finalDest = renamed;
            }
        }

        return finalDest;
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
                    driverFile.getFileName().toString().replaceFirst("\\.zip(?:\\.exe)?$", "_extracted"));
            Files.createDirectories(extractDir);

            ProcessResult extractResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Expand-Archive -Path " + ProcessRunner.psQuote(driverFile.toString())
                            + " -DestinationPath " + ProcessRunner.psQuote(extractDir.toString()) + " -Force"
            ).command().toArray(new String[0])), cancellationFlag);

            if (!extractResult.success()) {
                return new ProcessResult(1, "", "Failed to extract zip: " + extractResult.combinedOutput());
            }

            Path setupExe = findFile(extractDir, "setup.exe");
            if (setupExe != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                // Fail closed: any inner signature failure blocks silent /S
                // execution (untrusted-code risk). No substring allowlist.
                DriverVerificationService.VerificationResult innerSig = verificationService.verifyAuthenticode(setupExe);
                if (!innerSig.verified()) {
                    AppLogger.warning("Inner setup.exe signature check failed: " + innerSig.message());
                    return new ProcessResult(1, "", "Inner setup.exe signature check failed: " + innerSig.message());
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
                    driverFile.getFileName().toString().replace(".cab", "_extracted"));
            Files.createDirectories(extractDir);

            ProcessResult extractResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Expand-Archive -Path " + ProcessRunner.psQuote(driverFile.toString())
                            + " -DestinationPath " + ProcessRunner.psQuote(extractDir.toString()) + " -Force"
            ).command().toArray(new String[0])), cancellationFlag);

            if (!extractResult.success()) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                ProcessResult expandResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                        "expand.exe", driverFile.toString(), "-F:*", extractDir.toString()
                ).command().toArray(new String[0])), cancellationFlag);
                if (!expandResult.success()) {
                    return new ProcessResult(1, "", "Failed to extract cab: " + expandResult.combinedOutput());
                }
            }

            Path setupExe = findFile(extractDir, "setup.exe");
            if (setupExe != null) {
                if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
                DriverVerificationService.VerificationResult innerSig = verificationService.verifyAuthenticode(setupExe);
                if (!innerSig.verified()) {
                    AppLogger.warning("Inner setup.exe (CAB) signature check failed: " + innerSig.message());
                    return new ProcessResult(1, "", "Inner setup.exe signature check failed: " + innerSig.message());
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
        } else if (filename.endsWith(".msi")) {
            if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
            AppLogger.info("Installing MSI driver package: " + driverFile);
            // /norestart: exit 1641 means the installer already forced a
            // reboot (data loss). Never allow a silent driver install to
            // reboot the machine out from under the user.
            ProcessResult result = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "msiexec.exe", "/i", driverFile.toString(), "/qn", "/norestart"
            ).command().toArray(new String[0])), cancellationFlag);
            boolean msiReboot = isRebootRequiredExitCode(result.exitCode());
            if (result.success() || msiReboot) {
                return new ProcessResult(0, "", msiReboot
                        ? "Driver installed silently via MSI. A restart is required."
                        : "Driver installed silently via MSI.");
            }
            if (cancellationFlag.get()) throw new java.util.concurrent.CancellationException("Installation cancelled");
            AppLogger.warning("MSI /qn failed, trying /quiet: " + result.combinedOutput());
            ProcessResult fallbackResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "msiexec.exe", "/i", driverFile.toString(), "/quiet", "/norestart"
            ).command().toArray(new String[0])), cancellationFlag);
            boolean fallbackReboot = isRebootRequiredExitCode(fallbackResult.exitCode());
            if (fallbackResult.success() || fallbackReboot) {
                return new ProcessResult(0, "", fallbackReboot
                        ? "Driver installed silently via MSI. A restart is required."
                        : "Driver installed silently via MSI.");
            }
            return new ProcessResult(1, "", "MSI installation failed: " + fallbackResult.combinedOutput());
        }

        String magicExt = detectExtensionByMagicBytes(driverFile);
        if (magicExt != null) {
            Path renamed = driverFile.getParent().resolve(driverFile.getFileName().toString() + magicExt);
            Files.move(driverFile, renamed, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            AppLogger.info("installDriverFile: detected type " + magicExt + " from magic bytes, retrying with " + renamed);
            return installDriverFile(renamed, candidate);
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
            String content = Files.readString(inf).toUpperCase();
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
            if (tokens.isEmpty()) return true;
            for (String tok : tokens) {
                if (tok.length() >= 8 && content.contains(tok)) return true;
            }
            // Fallback: INF filename hint matches device infName
            String infName = candidate.installed().infName();
            if (infName != null && !infName.isBlank()
                    && inf.getFileName().toString().equalsIgnoreCase(infName)) return true;
            return false;
        } catch (Exception e) {
            return false;
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

            Path msiPath = exeFile.getParent().resolve(
                    exeFile.getFileName().toString().replace(".exe", ".msi"));
            Files.copy(exeFile, msiPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return msiPath;
        } catch (Exception e) {
            AppLogger.debug("Not an MSI-in-EXE: " + e.getMessage());
            return null;
        }
    }

    private static boolean isRebootRequiredExitCode(int exitCode) {
        return exitCode == 3010 || exitCode == 1641 || exitCode == 5103;
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

    private boolean isTrustedSource(String url, String source) {
        if (url == null || source == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost().toLowerCase();
            return switch (source) {
                case "Intel" -> host.equals("intel.com") || host.endsWith(".intel.com")
                        || host.equals("downloadmirror.intel.com");
                case "Nvidia" -> host.equals("nvidia.com") || host.endsWith(".nvidia.com")
                        || host.equals("geforce.com") || host.endsWith(".geforce.com")
                        || host.endsWith(".nvdlcdn.com");
                case "AMD" -> host.equals("amd.com") || host.endsWith(".amd.com")
                        || host.equals("drivers.amd.com") || host.endsWith(".drivers.amd.com");
                case "Realtek" -> host.equals("realtek.com") || host.endsWith(".realtek.com")
                        || host.equals("realtek.com.tw") || host.endsWith(".realtek.com.tw");
                case "Broadcom" -> host.equals("broadcom.com") || host.endsWith(".broadcom.com");
                case "Qualcomm" -> host.equals("qualcomm.com") || host.endsWith(".qualcomm.com");
                case "Synaptics" -> host.equals("synaptics.com") || host.endsWith(".synaptics.com")
                        || host.endsWith(".hp.com") || host.equals("hp.com") || host.endsWith("ftp.hp.com")
                        || host.endsWith(".lenovo.com") || host.equals("lenovo.com");
                case "Lenovo" -> host.equals("lenovo.com") || host.endsWith(".lenovo.com")
                        || host.equals("lenovo-images.com") || host.endsWith(".lenovo-images.com")
                        || host.endsWith(".lenovo.net");
                case "Dell" -> host.equals("dell.com") || host.endsWith(".dell.com")
                        || host.equals("dellcdn.com") || host.endsWith(".dellcdn.com")
                        || host.endsWith(".dell-cdn.com");
                case "HP" -> host.equals("hp.com") || host.endsWith(".hp.com")
                        || host.equals("hpe.com") || host.endsWith(".hpe.com")
                        || host.endsWith(".hp.com.cn");
                case "ASUS" -> host.equals("asus.com") || host.endsWith(".asus.com")
                        || host.equals("asusnet.net") || host.endsWith(".asusnet.net")
                        || host.endsWith(".asus.com.cn");
                case "WindowsUpdate" -> host.equals("microsoft.com") || host.endsWith(".microsoft.com")
                        || host.equals("windowsupdate.com") || host.endsWith(".windowsupdate.com")
                        || host.endsWith(".windowsupdate.microsoft.com");
                default -> false;
            };
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

    public record InstallResult(InstallStatus status, boolean rebootRequired, String message) {
        public boolean installed() {
            return status.isSuccess();
        }
    }
}
