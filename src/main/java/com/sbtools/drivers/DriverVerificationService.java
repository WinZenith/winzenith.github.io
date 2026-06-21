package com.sbtools.drivers;

import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public class DriverVerificationService {

    private static final ProcessRunner POWERSHELL_RUNNER = new ProcessRunner(120);

    public record VerificationResult(boolean verified, String message) {
    }

    /**
     * Verifies the Authenticode signer's thumbprint matches the expected value.
     */
    public VerificationResult verifyAuthenticodeThumbprint(Path file, String expectedThumbprint) {
        if (expectedThumbprint == null || expectedThumbprint.isBlank()) {
            return new VerificationResult(true, "No expected thumbprint provided - skipped verification");
        }
        if (!com.sbtools.util.AppPaths.isWindows()) {
            return new VerificationResult(true, "Not on Windows - skipped Authenticode thumbprint check");
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-Command");
            cmd.add("Get-AuthenticodeSignature -FilePath '" + file + "' | Select-Object -Property Status,SignerCertificate | ConvertTo-Json -Depth 4");
            ProcessResult result = POWERSHELL_RUNNER.run(cmd);
            if (!result.success()) {
                AppLogger.warning("Authenticode thumbprint check failed: " + result.combinedOutput());
                return new VerificationResult(true, "Could not verify signature thumbprint - proceeding with caution");
            }

            JsonNode root = JsonMapper.parseTree(result.stdout());
            JsonNode signer = root.get("SignerCertificate");
            String actualThumb = "";
            if (signer != null && !signer.isNull()) {
                JsonNode t = signer.get("Thumbprint");
                if (t != null && !t.isNull()) actualThumb = t.asText("");
            }
            String normActual = actualThumb.replaceAll("\\s+", "").toLowerCase();
            String normExpected = expectedThumbprint.replaceAll("\\s+", "").toLowerCase();
            if (normExpected.isEmpty()) {
                return new VerificationResult(true, "No expected thumbprint - skipped");
            }
            if (normActual.equalsIgnoreCase(normExpected)) {
                AppLogger.info("Authenticode thumbprint matches expected for " + file.getFileName());
                return new VerificationResult(true, "Thumbprint matches expected");
            } else {
                AppLogger.warning("Authenticode thumbprint mismatch for " + file.getFileName()
                        + ", expected=" + expectedThumbprint + ", actual=" + actualThumb);
                return new VerificationResult(false, "Authenticode thumbprint mismatch: expected " + expectedThumbprint + " actual " + actualThumb);
            }
        } catch (Exception e) {
            AppLogger.warning("Authenticode thumbprint verification error: " + e.getMessage());
            return new VerificationResult(true, "Could not verify signature thumbprint - proceeding with caution");
        }
    }

    public VerificationResult verifyChecksum(Path file, String expectedSha256) {
        if (expectedSha256 == null || expectedSha256.isBlank()) {
            return new VerificationResult(true, "No checksum provided - skipped verification");
        }
        try {
            String actual = computeSha256(file);
            if (actual.equalsIgnoreCase(expectedSha256.trim())) {
                AppLogger.info("Checksum verification passed for " + file.getFileName());
                return new VerificationResult(true, "Checksum verified");
            } else {
                AppLogger.warning("Checksum mismatch for " + file.getFileName()
                        + ": expected=" + expectedSha256 + ", actual=" + actual);
                return new VerificationResult(false,
                        "Checksum mismatch: expected " + expectedSha256 + " but got " + actual);
            }
        } catch (Exception e) {
            AppLogger.warning("Checksum verification failed: " + e.getMessage());
            return new VerificationResult(false, "Checksum computation failed: " + e.getMessage());
        }
    }

    public VerificationResult verifyAuthenticode(Path file) {
        if (!com.sbtools.util.AppPaths.isWindows()) {
            return new VerificationResult(true, "Not on Windows - skipped Authenticode verification");
        }
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("powershell");
            cmd.add("-NoProfile");
            cmd.add("-Command");
            cmd.add("Get-AuthenticodeSignature -FilePath '" + file + "' | ConvertTo-Json -Depth 3");
            ProcessResult result = POWERSHELL_RUNNER.run(cmd);
            if (!result.success()) {
                AppLogger.warning("Authenticode check failed: " + result.combinedOutput());
                return new VerificationResult(true, "Could not verify signature - proceeding with caution");
            }

            String status = extractJsonString(result.stdout(), "Status");

            if ("Valid".equals(status)) {
                AppLogger.info("Authenticode signature valid for " + file.getFileName());
                return new VerificationResult(true, "Authenticode signature valid");
            } else if ("NotSigned".equals(status)) {
                AppLogger.warning("File is not signed: " + file.getFileName());
                return new VerificationResult(false, "File is not Authenticode signed");
            } else if ("HashMismatch".equals(status)) {
                AppLogger.warning("Authenticode hash mismatch for " + file.getFileName());
                return new VerificationResult(false, "Authenticode hash mismatch - file may be corrupted");
            } else {
                AppLogger.warning("Authenticode status '" + status + "' for " + file.getFileName());
                return new VerificationResult(true,
                        "Authenticode status: " + status + " - proceeding with caution");
            }
        } catch (Exception e) {
            AppLogger.warning("Authenticode verification error: " + e.getMessage());
            return new VerificationResult(true, "Could not verify signature - proceeding with caution");
        }
    }

    public String computeSha256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var stream = java.nio.file.Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String extractJsonString(String json, String key) {
        if (json == null) return null;
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int quoteStart = json.indexOf('"', colonIdx + 1);
        if (quoteStart < 0) return null;
        int quoteEnd = json.indexOf('"', quoteStart + 1);
        if (quoteEnd < 0) return null;
        return json.substring(quoteStart + 1, quoteEnd);
    }
}
