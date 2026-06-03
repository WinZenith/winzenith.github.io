package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.backup.DriverBackupService;
import com.basicsdriverupdate.backup.SystemRestoreService;
import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;

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
    private final ProcessRunner processRunner = new ProcessRunner(900);
    private final AtomicBoolean cancellationFlag = new AtomicBoolean(false);
    private volatile ProgressCallback progressCallback;
    private volatile StatusCallback statusCallback;

    public void setProgressCallback(ProgressCallback callback) {
        this.progressCallback = callback;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public InstallResult install(DriverUpdateCandidate candidate, AppSettings settings)
            throws IOException, InterruptedException {
        if (settings.createSystemRestorePoint()) {
            reportStatus("Creating system restore point…");
            AppLogger.info("Creating system restore point before driver update");
            boolean created = restoreService.createRestorePoint(
                    "SBasic Driver Update: " + candidate.installed().friendlyName());
            if (created) {
                AppLogger.info("System restore point created successfully");
            } else {
                AppLogger.warning("System restore point creation failed or was skipped");
            }
        }

        com.basicsdriverupdate.backup.DriverBackupEntry backupEntry = null;
        if (settings.autoBackupDrivers()) {
            backupEntry = backupService.backupBeforeUpdate(candidate.installed(), settings);
        }

        String availVer = candidate.availableVersion();
        if (availVer != null && availVer.matches("(?i).*\\b(alpha|beta|rc|preview|test)\\b.*")) {
            removeBackupIfPresent(backupEntry);
            return new InstallResult(false, false, "Blocked: candidate appears to be a pre-release (alpha/beta/rc/preview). Only stable releases are installed.");
        }

        if ("WindowsUpdate".equals(candidate.source()) && candidate.packageId() != null && !candidate.packageId().isBlank()) {
            try {
                Path script = PowerShellScripts.resolve("wu-install.ps1");
                ProcessResult result = processRunner.run(ProcessRunner.powershellScript(
                        script.toString(), candidate.packageId()));
                if (!result.success()) {
                    removeBackupIfPresent(backupEntry);
                    throw new IOException("Windows Update install failed: " + result.combinedOutput());
                }
                boolean reboot = result.stdout() != null && result.stdout().contains("\"rebootRequired\":true");
                return new InstallResult(true, reboot, result.stdout());
            } catch (Exception e) {
                removeBackupIfPresent(backupEntry);
                throw e;
            }
        }

        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
            String downloadUrl = candidate.downloadUrl();
            if (!isTrustedSource(downloadUrl, candidate.source())) {
                removeBackupIfPresent(backupEntry);
                return new InstallResult(false, false, "Blocked: download URL is not from a trusted vendor. URL: " + downloadUrl);
            }
            try {
                InstallResult result = downloadAndInstallDriver(candidate);
                if (result.installed()) {
                    removeBackupIfPresent(backupEntry);
                }
                return result;
            } catch (Exception e) {
                removeBackupIfPresent(backupEntry);
                throw e;
            }
        }

        removeBackupIfPresent(backupEntry);
        return new InstallResult(false, false,
                "No download URL available for " + candidate.source() + ". Check vendor website manually.");
    }

    private void removeBackupIfPresent(com.basicsdriverupdate.backup.DriverBackupEntry backupEntry) {
        if (backupEntry != null) {
            try {
                backupService.removeBackupEntry(backupEntry);
            } catch (Exception e) {
                AppLogger.warning("Failed to remove backup entry: " + e.getMessage());
            }
        }
    }

    private InstallResult downloadAndInstallDriver(DriverUpdateCandidate candidate) {
        try {
            Path downloadsDir = Paths.get(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloadsDir);
            String downloadUrl = candidate.downloadUrl();
            String filename = extractFilename(downloadUrl);
            Path driverFile = downloadsDir.resolve(filename);

            AppLogger.info("Downloading driver from: " + downloadUrl);
            reportProgress(0, 0, 0);
            driverFile = downloadFileWithProgress(downloadUrl, driverFile);

            if (!Files.exists(driverFile) || Files.size(driverFile) == 0) {
                String vendorUrl = candidate.vendorPageUrl();
                if (vendorUrl != null && !vendorUrl.isBlank()) {
                    return new InstallResult(false, false,
                            "Download failed: received empty file from " + downloadUrl
                            + "\nYou can try downloading manually from: " + vendorUrl);
                }
                return new InstallResult(false, false, "Download failed: received empty file from " + downloadUrl);
            }

            long fileSize = Files.size(driverFile);
            AppLogger.info("Driver downloaded (" + fileSize + " bytes) to: " + driverFile);
            reportProgress(fileSize, fileSize, 1.0);
            reportStatus("Installing driver. Please wait…");

            String lowerName = driverFile.getFileName().toString().toLowerCase();
            if (lowerName.endsWith(".exe")) {
                AppLogger.info("Launching silent installer: " + driverFile);

                Path msiFile = extractMsiFromExe(driverFile);
                if (msiFile != null) {
                    AppLogger.info("Extracted MSI: " + msiFile);
                    ProcessResult result = processRunner.run(java.util.List.of(new ProcessBuilder(
                            "msiexec.exe", "/i", msiFile.toString(), "/qn", "/norestart"
                    ).command().toArray(new String[0])));
                    if (result.success()) {
                        return new InstallResult(true, false, "Driver installed silently via MSI.");
                    }
                    AppLogger.warning("MSI install failed, falling back to EXE: " + result.combinedOutput());
                }

                ProcessResult result = processRunner.run(java.util.List.of(new ProcessBuilder(
                        driverFile.toString(), "/quiet", "/norestart"
                ).command().toArray(new String[0])));
                if (result.success()) {
                    return new InstallResult(true, false, "Driver installed silently.");
                }

                AppLogger.warning("EXE /quiet failed, trying /S: " + result.combinedOutput());
                new ProcessBuilder(driverFile.toString(), "/S").start();
                return new InstallResult(true, false, "Silent installation started in the background.");
            }

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

    private Path downloadFileWithProgress(String url, Path destination) throws IOException, InterruptedException {
        HttpRequest.Builder reqBuilder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                .header("Accept", "application/octet-stream, */*");
        if (url.contains("intel.com")) {
            reqBuilder.header("Referer", "https://www.intel.com/");
        }
        HttpRequest req = reqBuilder.GET().build();

        HttpResponse<InputStream> response = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + " when downloading " + url);
        }

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (contentType.toLowerCase().contains("text/html")) {
            throw new IOException("Download URL returned an HTML page instead of a file. The download link may be invalid or require a browser.");
        }

        String destName = destination.getFileName().toString();
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
            byte[] buffer = new byte[8192];
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
            if (header[0] == 0x37 && header[1] == 0x7A && header[2] == 0xBC && header[3] == 0xAF) return ".7z";
            if (header[0] == 0x1F && header[1] == (byte) 0x8B) return ".zip";
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
        String filename = driverFile.getFileName().toString().toLowerCase();

        if (filename.endsWith(".inf")) {
            return processRunner.run(java.util.List.of(new ProcessBuilder(
                    "pnputil.exe", "/add-driver", driverFile.toString(), "/install").command().toArray(new String[0])));
        } else if (filename.endsWith(".zip") || filename.endsWith(".zip.exe")) {
            Path extractDir = driverFile.getParent().resolve(
                    driverFile.getFileName().toString().replaceFirst("\\.zip(?:\\.exe)?$", "_extracted"));
            Files.createDirectories(extractDir);

            ProcessResult extractResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Expand-Archive -Path '" + driverFile + "' -DestinationPath '" + extractDir + "' -Force"
            ).command().toArray(new String[0])));

            if (!extractResult.success()) {
                return new ProcessResult(1, "", "Failed to extract zip: " + extractResult.combinedOutput());
            }

            Path setupExe = findFile(extractDir, "setup.exe");
            if (setupExe != null) {
                String[] cmd = new String[]{setupExe.toString(), "/S"};
                return processRunner.run(java.util.List.of(new ProcessBuilder(cmd).command().toArray(new String[0])));
            }

            Path infFile = findFile(extractDir, ".inf");
            if (infFile != null) {
                return processRunner.run(java.util.List.of(new ProcessBuilder(
                        "pnputil.exe", "/add-driver", infFile.toString(), "/install"
                ).command().toArray(new String[0])));
            }

            return new ProcessResult(1, "", "No setup.exe or .inf found in extracted archive: " + extractDir);
        } else if (filename.endsWith(".cab")) {
            Path extractDir = driverFile.getParent().resolve(
                    driverFile.getFileName().toString().replace(".cab", "_extracted"));
            Files.createDirectories(extractDir);

            ProcessResult extractResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                    "powershell", "-NoProfile", "-Command",
                    "Expand-Archive -Path '" + driverFile + "' -DestinationPath '" + extractDir + "' -Force"
            ).command().toArray(new String[0])));

            if (!extractResult.success()) {
                ProcessResult expandResult = processRunner.run(java.util.List.of(new ProcessBuilder(
                        "expand.exe", driverFile.toString(), "-F:*", extractDir.toString()
                ).command().toArray(new String[0])));
                if (!expandResult.success()) {
                    return new ProcessResult(1, "", "Failed to extract cab: " + expandResult.combinedOutput());
                }
            }

            Path setupExe = findFile(extractDir, "setup.exe");
            if (setupExe != null) {
                String[] cmd = new String[]{setupExe.toString(), "/S"};
                return processRunner.run(java.util.List.of(new ProcessBuilder(cmd).command().toArray(new String[0])));
            }

            Path infFile = findFile(extractDir, ".inf");
            if (infFile != null) {
                return processRunner.run(java.util.List.of(new ProcessBuilder(
                        "pnputil.exe", "/add-driver", infFile.toString(), "/install"
                ).command().toArray(new String[0])));
            }

            return new ProcessResult(1, "", "No setup.exe or .inf found in extracted cab: " + extractDir);
        } else if (filename.endsWith(".rar")) {
            return new ProcessResult(1, "", "RAR archives require manual extraction. Download: " + driverFile);
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

    private String extractFilename(String url) {
        try {
            URI uri = new URI(url);
            String path = uri.getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.isEmpty()) {
                return "driver_" + System.currentTimeMillis() + ".exe";
            }
            return name;
        } catch (Exception e) {
            return "driver_" + System.currentTimeMillis() + ".exe";
        }
    }

    private boolean isTrustedSource(String url, String source) {
        if (url == null || source == null) return false;
        try {
            java.net.URL u = new java.net.URL(url);
            String host = u.getHost().toLowerCase();
            return switch (source) {
                case "Intel" -> host.contains("intel.com");
                case "Nvidia" -> host.contains("nvidia.com") || host.contains("geforce.com")
                        || host.contains("download.nvidia.com") || host.contains("international.download.nvidia.com");
                case "AMD" -> host.contains("amd.com");
                case "Realtek" -> host.contains("realtek.com");
                case "Broadcom" -> host.contains("broadcom.com");
                case "Qualcomm" -> host.contains("qualcomm.com");
                case "WindowsUpdate" -> host.contains("microsoft.com") || host.contains("windowsupdate.com")
                        || host.contains("download.microsoft.com") || host.contains("catalog.update.microsoft.com")
                        || host.contains("catalog.s.download.windowsupdate.com");
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

    public record InstallResult(boolean installed, boolean rebootRequired, String message) {
    }
}
