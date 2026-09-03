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
            cmd.add("Get-AuthenticodeSignature -FilePath " + ProcessRunner.psQuote(file.toString()) + " | Select-Object -Property Status,SignerCertificate | ConvertTo-Json -Depth 4");
            ProcessResult result = POWERSHELL_RUNNER.run(cmd);
            if (!result.success()) {
                AppLogger.warning("Authenticode thumbprint check failed: " + result.combinedOutput());
                return new VerificationResult(false, "Could not verify signature thumbprint: " + result.combinedOutput());
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
            return new VerificationResult(false, "Could not verify signature thumbprint: " + e.getMessage());
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
            cmd.add("Get-AuthenticodeSignature -FilePath " + ProcessRunner.psQuote(file.toString()) + " | ConvertTo-Json -Depth 3");
            ProcessResult result = POWERSHELL_RUNNER.run(cmd);
            if (!result.success()) {
                AppLogger.warning("Authenticode check failed: " + result.combinedOutput());
                return new VerificationResult(false, "Could not verify signature: " + result.combinedOutput());
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
            } else if ("NotTrusted".equals(status)) {
                // Fail closed: self-signed / untrusted-chain / revoked must
                // not install silently as admin. Caller surfaces the message
                // and offers manual download instead.
                AppLogger.warning("Authenticode NotTrusted for " + file.getFileName() + " — blocking install");
                return new VerificationResult(false, "Authenticode signed but not trusted (NotTrusted) - file signature not trusted");
            } else if ("UnknownError".equals(status) || "Unknown".equals(status)) {
                AppLogger.warning("Authenticode status '" + status + "' for " + file.getFileName() + " - treating as invalid");
                return new VerificationResult(false,
                        "Authenticode status invalid: " + status + " - file signature not trusted");
            } else {
                String safeStatus = status != null ? status : "null";
                // Additional B4 permissiveness: HashMismatch is still fatal, but for WHQL .cat/.sys contexts
                // an Unknown status is often transient (e.g. timestamp server offline); log warning and allow
                // if the file at least has a signer. We keep strict for now except NotTrusted.
                AppLogger.warning("Authenticode status '" + safeStatus + "' for " + file.getFileName() + " - treating as invalid");
                return new VerificationResult(false,
                        "Authenticode status invalid: " + safeStatus);
            }
        } catch (Exception e) {
            AppLogger.warning("Authenticode verification error: " + e.getMessage());
            return new VerificationResult(false, "Could not verify signature: " + e.getMessage());
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
        try {
            JsonNode root = JsonMapper.parseTree(json);
            JsonNode node = root.get(key);
            if (node != null && !node.isNull() && !node.isMissingNode()) {
                return node.asText("");
            }
            return null;
        } catch (Exception e) {
            AppLogger.debug("Failed to parse JSON for key '" + key + "': " + e.getMessage());
            return null;
        }
    }
}
