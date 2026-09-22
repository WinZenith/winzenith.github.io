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
            // Value deletions ("Name"=- / @=-) are never written by our export
            // path and would silently remove data on merge-import.
            if (isValueDeletionLine(trimmed)) {
                throw new IOException("Registry file " + regFile.getFileName()
                        + " contains a value-deletion line, which backup export never writes.\n"
                        + "Refusing restore: " + trimmed);
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

    /** True for `"Name"=-` / `@=-` value-deletion lines in .reg text. */
    static boolean isValueDeletionLine(String trimmedLine) {
        if (trimmedLine == null || trimmedLine.isEmpty()) {
            return false;
        }
        // "ValueName"=-  or  @=-  (optional whitespace around =)
        return trimmedLine.matches("(?i)^(\"[^\"]*\"|@)\\s*=\\s*-\\s*$");
    }

    /**
     * Session is listable for Restore when it has no .reg files (hive-only —
     * restore UI warns) or every .reg passes allowlist / deletion checks.
     */
    public static boolean isRestorableSession(Path sessionDir) {
        if (sessionDir == null || !Files.isDirectory(sessionDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (BackupHealth.isReparseOrSymlink(sessionDir)) {
            return false;
        }
        try (var stream = Files.list(sessionDir)) {
            List<Path> regs = stream
                    .filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".reg"))
                    .filter(p -> !BackupHealth.isReparseOrSymlink(p))
                    .toList();
            if (regs.isEmpty()) {
                return true; // hive-only or empty — restore path shows its own warning
            }
            collectTargetKeys(regs);
            return true;
        } catch (Exception e) {
            return false;
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
            Path out = safetyDir.resolve(preRestoreFileName(key));
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
        return queryKeyPresence(fullKey) == KeyPresence.PRESENT;
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
     * Imports session {@code .reg} files in order. Refuses to start when a partial
     * merge could not be undone. On a later failure, deletes keys already imported
     * and, when a pre-restore export exists, imports that export. Re-import alone
     * is not a rollback: {@code reg import} merges and leaves values the session added.
     */
    public static void importRegSessionAtomically(List<Path> regFiles, Path safetySnapshotDir) throws IOException {
        if (regFiles == null || regFiles.isEmpty()) {
            return;
        }
        if (safetySnapshotDir == null) {
            throw new IOException("Refusing registry restore: no safety snapshot folder. "
                    + "A partial merge cannot be undone.");
        }
        List<List<String>> keysPerFile = new ArrayList<>();
        LinkedHashSet<String> allKeys = new LinkedHashSet<>();
        for (Path regFile : regFiles) {
            List<RegSection> sections = parseRegFile(regFile);
            validateSections(sections, regFile);
            LinkedHashSet<String> keys = new LinkedHashSet<>();
            for (RegSection s : sections) {
                keys.add(s.hivePath());
            }
            keysPerFile.add(new ArrayList<>(keys));
            allKeys.addAll(keys);
        }
        LinkedHashSet<String> snapshottedKeys = new LinkedHashSet<>();
        for (String key : allKeys) {
            KeyPresence presence = queryKeyPresence(key);
            boolean snapshot = Files.isRegularFile(safetySnapshotDir.resolve(preRestoreFileName(key)));
            if (snapshot) {
                snapshottedKeys.add(key);
            }
            if (!rollbackCoverageSufficient(presence == KeyPresence.PRESENT, presence == KeyPresence.UNKNOWN, snapshot)) {
                String why = presence == KeyPresence.UNKNOWN
                        ? "key state could not be confirmed"
                        : "no pre-restore export for an existing key";
                throw new IOException("Refusing registry restore before any import.\n"
                        + "Cannot undo a partial merge of:\n" + key + "\n(" + why + ").");
            }
        }
        List<String> importedKeys = new ArrayList<>();
        int sessionImported = 0;
        for (int i = 0; i < regFiles.size(); i++) {
            Path regFile = regFiles.get(i);
            if (!runRegImport(regFile)) {
                String failed = regFile.getFileName().toString();
                if (importedKeys.isEmpty()) {
                    throw new IOException("Registry import failed at " + failed);
                }
                try {
                    replaceImportedKeys(importedKeys, safetySnapshotDir, snapshottedKeys);
                } catch (IOException rollbackEx) {
                    throw new IOException(partialImportFailureMessage(failed, sessionImported, false)
                            + "\n\n" + rollbackEx.getMessage(), rollbackEx);
                }
                throw new IOException(partialImportFailureMessage(failed, sessionImported, true)
                        + "\n\nSafety snapshot:\n" + safetySnapshotDir);
            }
            importedKeys.addAll(keysPerFile.get(i));
            sessionImported++;
        }
    }

    /** {@code pre-restore_} file name for one normalized hive path. */
    static String preRestoreFileName(String hivePath) {
        String normalized = normalizeHivePath(hivePath);
        String safeName = normalized.replace('\\', '_').replace(':', '_');
        return "pre-restore_" + safeName + ".reg";
    }

    /**
     * A snapshot can replace the key after delete. Without one, only a
     * confirmed-absent key is safe (delete removes what the session created).
     * Unknown presence and no snapshot is not safe: the key may already exist.
     */
    static boolean rollbackCoverageSufficient(boolean keyPresent, boolean keyStateUnknown, boolean snapshotPresent) {
        if (snapshotPresent) {
            return true;
        }
        return !keyPresent && !keyStateUnknown;
    }

    /**
     * Failure text for a multi-file import that stopped after at least one merge.
     * Claims the keys were restored only when replace-from-snapshot finished.
     */
    static String partialImportFailureMessage(String failedFile, int importedFileCount, boolean rollbackUndone) {
        String failed = failedFile == null ? "unknown file" : failedFile;
        if (!rollbackUndone) {
            return "Registry import failed at " + failed + " after " + importedFileCount
                    + " file(s) had already been merged.\n\n"
                    + "Automatic rollback could not undo that partial merge.\n"
                    + "The registry may still contain values from the backup.\n"
                    + "Use System Restore or import a known-good registry backup session.";
        }
        return "Registry import failed at " + failed + " after " + importedFileCount
                + " file(s) had already been merged.\n\n"
                + "Those keys were removed and, where a pre-restore export existed, replaced from that export.";
    }

    private enum KeyPresence {
        PRESENT, ABSENT, UNKNOWN
    }

    /**
     * Deletes each imported key, then imports its pre-restore export when one
     * exists. Delete is required: importing the old .reg does not remove values
     * the session added.
     */
    private static void replaceImportedKeys(List<String> importedKeys, Path safetyDir, Set<String> snapshottedKeys)
            throws IOException {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (importedKeys != null) {
            keys.addAll(importedKeys);
        }
        List<String> problems = new ArrayList<>();
        for (String key : keys) {
            if (!deleteKeyForRollback(key)) {
                problems.add(key + " (could not remove merged key)");
                continue;
            }
            if (snapshottedKeys != null && snapshottedKeys.contains(key)) {
                Path snap = safetyDir.resolve(preRestoreFileName(key));
                if (!Files.isRegularFile(snap) || !runRegImport(snap)) {
                    problems.add(key + " (removed, but pre-restore import failed)");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException(String.join("\n", problems));
        }
    }

    private static boolean deleteKeyForRollback(String fullKey) {
        KeyPresence presence = queryKeyPresence(fullKey);
        if (presence == KeyPresence.ABSENT) {
            return true;
        }
        if (presence == KeyPresence.UNKNOWN) {
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "delete", fullKey, "/f");
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            drainInBackground(p);
            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                AppLogger.warning("reg delete timed out for " + fullKey);
                return false;
            }
            return queryKeyPresence(fullKey) == KeyPresence.ABSENT;
        } catch (Exception e) {
            AppLogger.warning("reg delete error for " + fullKey + ": " + e.getMessage());
            return false;
        }
    }

    private static KeyPresence queryKeyPresence(String fullKey) {
        try {
            ProcessBuilder pb = new ProcessBuilder("reg", "query", fullKey);
            pb.redirectErrorStream(true);
            Process p = ProcessManager.start(pb);
            drainInBackground(p);
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return KeyPresence.UNKNOWN;
            }
            if (p.exitValue() == 0) {
                return KeyPresence.PRESENT;
            }
            return KeyPresence.ABSENT;
        } catch (Exception e) {
            AppLogger.warning("reg query failed for " + fullKey + ": " + e.getMessage());
            return KeyPresence.UNKNOWN;
        }
    }

    private static void drainInBackground(Process process) {
        Thread drain = new Thread(() -> {
            try {
                process.getInputStream().readAllBytes();
            } catch (Exception ignored) {
            }
        }, "reg-output-drain");
        drain.setDaemon(true);
        drain.start();
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
