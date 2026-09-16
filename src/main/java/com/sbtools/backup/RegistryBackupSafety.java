package com.sbtools.backup;

import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.ProcessManager;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Validates registry backup sessions and builds mandatory pre-restore snapshots.
 */
public final class RegistryBackupSafety {

    public static final String[] CORE_REGISTRY_KEYS = {
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
            "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Run",
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunServices",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs"
    };

    public static final String[] EXTENDED_REGISTRY_KEYS = {
            "HKLM\\SYSTEM\\CurrentControlSet\\Services",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Drivers32"
    };

    private static final Pattern SECTION_HEADER = Pattern.compile("^\\[(.+)]$");

    private RegistryBackupSafety() {
    }

    public record RegSection(String hivePath, boolean deletion) {
    }

    public record SnapshotResult(Path safetyDir, List<String> exportedKeys, List<String> absentKeys) {
    }

    public enum RegExportOutcome {
        /** Key exported successfully. */
        OK,
        /** Key is not present on this system (not a failure). */
        MISSING,
        /** Export was attempted but failed (permissions, timeout, etc.). */
        FAILED
    }

    public static List<String> allowedKeyPrefixes() {
        List<String> all = new ArrayList<>();
        for (String k : CORE_REGISTRY_KEYS) {
            all.add(normalizeHivePath(k));
        }
        for (String k : EXTENDED_REGISTRY_KEYS) {
            all.add(normalizeHivePath(k));
        }
        return all;
    }

    public static List<RegSection> parseRegFile(Path regFile) throws IOException {
        byte[] bytes = Files.readAllBytes(regFile);
        String text = decodeRegText(bytes);
        List<RegSection> sections = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                continue;
            }
            var m = SECTION_HEADER.matcher(trimmed);
            if (!m.matches()) {
                continue;
            }
            String inner = m.group(1).trim();
            boolean deletion = inner.startsWith("-");
            if (deletion) {
                inner = inner.substring(1).trim();
            }
            String hive = normalizeHivePath(inner);
            if (hive.isEmpty()) {
                throw new IOException("Malformed registry section in " + regFile.getFileName() + ": " + trimmed);
            }
            sections.add(new RegSection(hive, deletion));
        }
        if (sections.isEmpty()) {
            throw new IOException("No registry sections found in " + regFile.getFileName());
        }
        return sections;
    }

    public static void validateSections(List<RegSection> sections, Path sourceFile) throws IOException {
        for (RegSection s : sections) {
            if (s.deletion()) {
                throw new IOException("Registry file " + sourceFile.getFileName()
                        + " contains a key-deletion section ([-key]), which backup export never writes.\n"
                        + "Refusing restore of " + s.hivePath());
            }
            if (!isAllowedHivePath(s.hivePath())) {
                throw new IOException("Registry file " + sourceFile.getFileName()
                        + " targets a key outside the allowed backup scope:\n" + s.hivePath());
            }
        }
    }

    public static Set<String> collectTargetKeys(List<Path> regFiles) throws IOException {
        Set<String> keys = new LinkedHashSet<>();
        for (Path reg : regFiles) {
            List<RegSection> sections = parseRegFile(reg);
            validateSections(sections, reg);
            for (RegSection s : sections) {
                keys.add(s.hivePath());
            }
        }
        return keys;
    }

    /**
     * Exports every target key that exists; records absent keys in manifest.json.
     * Aborts on any export failure for an existing key.
     */
    public static SnapshotResult createPreRestoreSnapshot(Path safetyDir, Set<String> targetKeys) throws IOException {
        Files.createDirectories(safetyDir);
        List<String> exported = new ArrayList<>();
        List<String> absent = new ArrayList<>();
        for (String key : targetKeys) {
            if (!keyExists(key)) {
                absent.add(key);
                continue;
            }
            String safeName = key.replace('\\', '_').replace(':', '_');
            Path out = safetyDir.resolve("pre-restore_" + safeName + ".reg");
            if (!runRegExport(key, out)) {
                throw new IOException("Pre-restore safety export failed for " + key);
            }
            exported.add(key);
        }
        writeManifest(safetyDir, targetKeys, exported, absent);
        if (exported.isEmpty() && !absent.isEmpty()) {
            // All targets absent — still a valid snapshot (nothing to merge back).
            return new SnapshotResult(safetyDir, exported, absent);
        }
        return new SnapshotResult(safetyDir, exported, absent);
    }

    private static String decodeRegText(byte[] bytes) {
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xFE) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (bytes.length >= 2 && bytes[0] == (byte) 0xFE && bytes[1] == (byte) 0xFF) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return new String(bytes, Charset.forName("Windows-1252"));
    }

    private static String normalizeHivePath(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        if (p.startsWith("-")) {
            p = p.substring(1).trim();
        }
        p = p.replace('/', '\\');
        String upper = p.toUpperCase(Locale.ROOT);
        if (upper.startsWith("HKEY_CURRENT_USER\\")) {
            p = "HKCU\\" + p.substring("HKEY_CURRENT_USER\\".length());
        } else if (upper.startsWith("HKEY_LOCAL_MACHINE\\")) {
            p = "HKLM\\" + p.substring("HKEY_LOCAL_MACHINE\\".length());
        } else if (upper.equals("HKEY_CURRENT_USER")) {
            p = "HKCU";
        } else if (upper.equals("HKEY_LOCAL_MACHINE")) {
            p = "HKLM";
        }
        return p;
    }

    private static boolean isAllowedHivePath(String hivePath) {
        String norm = normalizeHivePath(hivePath).toUpperCase(Locale.ROOT);
        for (String allowed : allowedKeyPrefixes()) {
            String a = allowed.toUpperCase(Locale.ROOT);
            if (norm.equals(a) || norm.startsWith(a + "\\")) {
                return true;
            }
        }
        return false;
    }

    private static boolean keyExists(String fullKey) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", fullKey);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            AppLogger.warning("reg query failed for " + fullKey + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Exports a registry key when it exists. Missing keys are reported as {@link RegExportOutcome#MISSING}
     * so optional/legacy areas (e.g. RunServices) do not count as backup failures.
     */
    public static RegExportOutcome exportRegKeyIfPresent(String fullKey, Path outputFile) {
        if (!keyExists(fullKey)) {
            return RegExportOutcome.MISSING;
        }
        return runRegExport(fullKey, outputFile) ? RegExportOutcome.OK : RegExportOutcome.FAILED;
    }

    /**
     * Imports session {@code .reg} files in order. On any failure, re-applies the
     * pre-restore safety snapshot (when provided) before throwing.
     */
    public static void importRegSessionAtomically(List<Path> regFiles, Path safetySnapshotDir) throws IOException {
        if (regFiles == null || regFiles.isEmpty()) {
            return;
        }
        int sessionImported = 0;
        for (Path regFile : regFiles) {
            if (!runRegImport(regFile)) {
                String failed = regFile.getFileName().toString();
                if (sessionImported > 0 && safetySnapshotDir != null) {
                    if (!hasPreRestoreSafetyRegs(safetySnapshotDir)) {
                        throw new IOException("Registry import failed at " + failed + " after " + sessionImported
                                + " file(s) had already been merged.\n\n"
                                + "No pre-restore .reg exports were available (keys may not have existed on this PC), "
                                + "so automatic rollback was not possible.\n"
                                + "Use System Restore or import a known-good registry backup session.");
                    }
                    try {
                        importPreRestoreSafetyRegs(safetySnapshotDir);
                    } catch (IOException rollbackEx) {
                        throw new IOException("Registry import failed at " + failed
                                + " and automatic rollback from the safety snapshot also failed: "
                                + rollbackEx.getMessage(), rollbackEx);
                    }
                    throw new IOException("Registry import failed at " + failed
                            + ". Pre-restore keys were re-applied from the safety snapshot at:\n"
                            + safetySnapshotDir);
                }
                throw new IOException("Registry import failed at " + failed);
            }
            sessionImported++;
        }
    }

    private static boolean hasPreRestoreSafetyRegs(Path safetyDir) throws IOException {
        if (safetyDir == null || !Files.isDirectory(safetyDir)) {
            return false;
        }
        try (var stream = Files.list(safetyDir)) {
            return stream.anyMatch(p -> {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                return name.startsWith("pre-restore_") && name.endsWith(".reg");
            });
        }
    }

    /** Imports all {@code pre-restore_*.reg} files from a safety session directory. */
    public static void importPreRestoreSafetyRegs(Path safetyDir) throws IOException {
        if (safetyDir == null || !Files.isDirectory(safetyDir)) {
            throw new IOException("Safety snapshot folder missing: " + safetyDir);
        }
        List<Path> safetyRegs;
        try (var stream = Files.list(safetyDir)) {
            safetyRegs = stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.startsWith("pre-restore_") && name.endsWith(".reg");
                    })
                    .sorted()
                    .toList();
        }
        for (Path reg : safetyRegs) {
            if (!runRegImport(reg)) {
                throw new IOException("Safety rollback import failed for " + reg.getFileName());
            }
        }
    }

    private static boolean runRegImport(Path regFile) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "import", regFile.toString());
            pb.redirectErrorStream(true);
            Process process = ProcessManager.start(pb);
            boolean finished = process.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                AppLogger.warning("reg import timed out for " + regFile.getFileName());
                return false;
            }
            if (process.exitValue() != 0) {
                AppLogger.warning("reg import failed for " + regFile.getFileName()
                        + " (exit=" + process.exitValue() + ")");
                return false;
            }
            return true;
        } catch (Exception e) {
            AppLogger.warning("reg import error for " + regFile.getFileName() + ": " + e.getMessage());
            return false;
        }
    }

    private static boolean runRegExport(String fullKey, Path outputFile) {
        try {
            Files.createDirectories(outputFile.getParent());
            ProcessBuilder pb = new ProcessBuilder("reg", "export", fullKey, outputFile.toString(), "/y");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0 && Files.isRegularFile(outputFile);
        } catch (Exception e) {
            AppLogger.warning("reg export failed for " + fullKey + ": " + e.getMessage());
            return false;
        }
    }

    private static void writeManifest(Path safetyDir, Set<String> targets, List<String> exported, List<String> absent)
            throws IOException {
        var root = JsonMapper.mapper().createObjectNode();
        root.put("createdAt", java.time.Instant.now().toString());
        var t = root.putArray("targets");
        for (String k : targets) {
            t.add(k);
        }
        var e = root.putArray("exported");
        for (String k : exported) {
            e.add(k);
        }
        var a = root.putArray("absent");
        for (String k : absent) {
            a.add(k);
        }
        JsonMapper.mapper().writerWithDefaultPrettyPrinter()
                .writeValue(safetyDir.resolve("manifest.json").toFile(), root);
    }
}
