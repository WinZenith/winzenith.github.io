package com.sbtools.startup;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.sbtools.backup.BackupHealth;

/**
 * Pure backup metadata validation and path confinement for startup backups.
 */
public final class StartupBackupValidation {

    private static final Set<String> DOS_DEVICE_STEMS = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private StartupBackupValidation() {
    }

    public static void validateEntryMetadata(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing id). Backup kept.");
        }
        if (!isValidBackupId(entry.getId())) {
            throw new IOException("Backup entry is corrupt (invalid id). Backup kept.");
        }
        String type = entry.getType();
        if (type == null || type.isBlank()) {
            throw new IOException("Backup entry is corrupt (missing type). Backup kept.");
        }
        switch (type) {
            case "Registry" -> validateRegistryEntry(entry);
            case "Folder" -> validateFolderEntry(entry);
            case "Task" -> validateTaskEntry(entry);
            default -> throw new IOException("Backup entry has unsupported type \"" + type + "\". Backup kept.");
        }
    }

    public static boolean requiresAdmin(StartupService.StartupBackupEntry entry) {
        if (entry == null || entry.getType() == null) {
            return false;
        }
        return switch (entry.getType()) {
            case "Registry" -> "HKLM".equals(entry.getHive());
            case "Folder" -> folderRestoreRequiresAdmin(entry.getKeyPath(), userStartupDir(), commonStartupDir());
            case "Task" -> isSystemTaskPath(entry.getTaskPath());
            default -> false;
        };
    }

    public static boolean folderRestoreRequiresAdmin(String keyPath, Path userStartup, Path commonStartup) {
        try {
            Path dest = resolveConfinedFolderDest(keyPath, userStartup, commonStartup);
            return isDirectChildOf(dest, commonStartup);
        } catch (IOException e) {
            return true;
        }
    }

    static Path resolveConfinedBackupFolder(Path backupsDir, String backupId) throws IOException {
        if (backupsDir == null || backupId == null || !isValidBackupId(backupId)) {
            throw new IOException("Backup entry is corrupt (invalid id). Backup kept.");
        }
        Path root = requireRealBackupRoot(backupsDir);
        Path folder = root.resolve(backupId).normalize();
        if (!folder.startsWith(root)) {
            throw new IOException("Backup entry is corrupt (invalid path). Backup kept.");
        }
        if (Files.exists(folder, LinkOption.NOFOLLOW_LINKS) && BackupHealth.isReparseOrSymlink(folder)) {
            throw new IOException("Backup entry is corrupt (invalid path). Backup kept.");
        }
        return folder;
    }

    /** False when {@code dir} exists as a junction/symlink (createDirectories would follow it). */
    static boolean isUsableBackupRoot(Path dir) {
        if (dir == null) {
            return false;
        }
        try {
            if (Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && BackupHealth.isReparseOrSymlink(dir)) {
                return false;
            }
            Files.createDirectories(dir);
            return !BackupHealth.isReparseOrSymlink(dir)
                    && Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)
                    && Files.isWritable(dir);
        } catch (Exception e) {
            return false;
        }
    }

    static Path requireRealBackupRoot(Path backupsDir) throws IOException {
        if (backupsDir == null) {
            throw new IOException("Backup folder is missing. Backup kept.");
        }
        Path root = backupsDir.toAbsolutePath().normalize();
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS) && BackupHealth.isReparseOrSymlink(root)) {
            throw new IOException("Startup backups folder is a reparse point. Backup kept.");
        }
        return root;
    }

    static void refuseReparseFile(Path file, String what) throws IOException {
        if (file != null && Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                && BackupHealth.isReparseOrSymlink(file)) {
            throw new IOException(what + " is a reparse point.");
        }
    }

    static boolean isValidBackupId(String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        if (id.contains("..") || id.contains("/") || id.contains("\\")) {
            return false;
        }
        return UUID_PATTERN.matcher(id).matches();
    }

    private static void validateRegistryEntry(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry.getKeyPath() == null || entry.getKeyPath().isBlank()
                || entry.getValueName() == null || entry.getValueName().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing registry key/value). Backup kept.");
        }
        requireNonBlankCommand(entry.getCommand());
        if (!"HKCU".equals(entry.getHive()) && !"HKLM".equals(entry.getHive())) {
            throw new IOException("Backup entry is corrupt (missing hive). Backup kept.");
        }
        if (StartupConstants.canonicalizeRestoreRunKey(entry.getKeyPath()) == null) {
            throw new IOException("Backup entry is corrupt (registry key is not a Run/RunOnce key). Backup kept.");
        }
    }

    private static void validateFolderEntry(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry.getKeyPath() == null || entry.getKeyPath().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing original file path). Backup kept.");
        }
        String payload = folderPayloadName(entry);
        if (!isSafePayloadName(payload)) {
            throw new IOException("Backup entry is corrupt (invalid folder payload name). Backup kept.");
        }
        resolveConfinedFolderDest(entry.getKeyPath());
    }

    private static void validateTaskEntry(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry.getName() == null || entry.getName().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing task name). Backup kept.");
        }
        String xml = entry.getBackupXmlName();
        if (xml == null || xml.isBlank()) {
            xml = "task.xml";
        }
        if (!isSafePayloadName(xml)) {
            throw new IOException("Backup entry is corrupt (invalid task payload name). Backup kept.");
        }
    }

    static void requireNonBlankCommand(String command) throws IOException {
        if (command == null || command.isBlank()) {
            throw new IOException("Backup entry is corrupt (missing command). Backup kept.");
        }
    }

    static boolean isSystemTaskPath(String taskPath) {
        if (taskPath == null || taskPath.isBlank()) {
            return false;
        }
        String lower = taskPath.toLowerCase(Locale.ROOT);
        return lower.startsWith("\\microsoft\\") || lower.startsWith("\\windows\\")
                || lower.contains("\\microsoft\\windows");
    }

    static String expectedTaskUri(String taskPath, String taskName) {
        if (taskName == null || taskName.isBlank()) {
            return "";
        }
        String n = taskName.trim();
        while (n.startsWith("\\") || n.startsWith("/")) {
            n = n.substring(1);
        }
        if (n.isEmpty()) {
            return "";
        }
        String p = taskPath == null || taskPath.isBlank() ? "\\" : taskPath.trim().replace('/', '\\');
        while (p.endsWith("\\") && p.length() > 1) {
            p = p.substring(0, p.length() - 1);
        }
        if (!p.startsWith("\\")) {
            p = "\\" + p;
        }
        return "\\".equals(p) ? "\\" + n : p + "\\" + n;
    }

    static String parseTaskUri(String xml) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<URI>(.*?)</URI>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(xml);
            if (!m.find()) {
                return "";
            }
            String uri = m.group(1).trim().replace('/', '\\');
            while (uri.endsWith("\\") && uri.length() > 1) {
                uri = uri.substring(0, uri.length() - 1);
            }
            return uri;
        } catch (Exception e) {
            return "";
        }
    }

    static boolean taskXmlUriMatches(String xml, String taskPath, String taskName) {
        String uri = parseTaskUri(xml);
        if (uri.isEmpty()) {
            return false;
        }
        String expected = expectedTaskUri(taskPath, taskName);
        return !expected.isEmpty() && expected.equalsIgnoreCase(uri);
    }

    static String folderPayloadName(StartupService.StartupBackupEntry entry) {
        if (entry == null) {
            return "";
        }
        String payload = entry.getBackupXmlName();
        if (payload == null || payload.isBlank()) {
            payload = entry.getValueName();
        }
        return payload == null ? "" : payload;
    }

    public static boolean isSafePayloadName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        if (name.contains("..") || name.contains("/") || name.contains("\\") || name.contains(":")
                || name.contains("\0")) {
            return false;
        }
        Path p = Path.of(name);
        if (p.isAbsolute() || p.getNameCount() != 1) {
            return false;
        }
        return !isReservedDosDeviceName(name);
    }

    /** CON/PRN/NUL/COM1/LPT1 and the same names with an extension are Windows devices. */
    public static boolean isReservedDosDeviceName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return true;
        }
        String n = fileName.trim();
        int colon = n.indexOf(':');
        if (colon >= 0) {
            n = n.substring(0, colon);
        }
        int dot = n.indexOf('.');
        String stem = (dot > 0 ? n.substring(0, dot) : n).toLowerCase(Locale.ROOT);
        while (stem.endsWith(".")) {
            stem = stem.substring(0, stem.length() - 1);
        }
        return DOS_DEVICE_STEMS.contains(stem);
    }

    static Path resolveConfinedFolderPayload(Path backupFolder, StartupService.StartupBackupEntry entry)
            throws IOException {
        if (backupFolder == null) {
            throw new IOException("Backup folder is missing. Backup kept.");
        }
        Path root = backupFolder.toAbsolutePath().normalize();
        String payload = folderPayloadName(entry);
        if (isSafePayloadName(payload)) {
            Path src = root.resolve(payload).normalize();
            if (src.startsWith(root) && isSafeBackupPayloadFile(src)) {
                return src;
            }
        }
        Path found = null;
        try (var stream = Files.list(root)) {
            for (Path p : stream.toList()) {
                Path n = p.toAbsolutePath().normalize();
                if (!n.startsWith(root) || !isSafeBackupPayloadFile(n)) {
                    continue;
                }
                if (found != null) {
                    throw new IOException("Backup folder has multiple files; payload name is missing. Backup kept.");
                }
                found = n;
            }
        }
        if (found == null) {
            throw new FileNotFoundException("Backup file missing for startup folder item: " + payload);
        }
        return found;
    }

    public static Path resolveConfinedFolderDest(String keyPath) throws IOException {
        Path dest = resolveConfinedFolderDest(keyPath, userStartupDir(), commonStartupDir());
        Path parent = dest.getParent();
        if (!looksLikeStartupFolder(parent)) {
            throw new IOException("Backup entry is corrupt (restore path is not a Startup folder). Backup kept.");
        }
        assertNoReparseOnStartupPath(parent);
        return dest;
    }

    public static Path resolveConfinedFolderDest(String keyPath, Path userStartup, Path commonStartup) throws IOException {
        if (keyPath == null || keyPath.isBlank()) {
            throw new IOException("Backup entry is corrupt (missing original file path). Backup kept.");
        }
        Path dest;
        try {
            dest = Path.of(keyPath).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new IOException("Backup entry has invalid file path. Backup kept: " + e.getMessage());
        }
        if (dest.getParent() == null || dest.getFileName() == null) {
            throw new IOException("Backup entry has invalid file path (no parent). Backup kept.");
        }
        if (!isSafePayloadName(dest.getFileName().toString())) {
            throw new IOException("Backup entry is corrupt (reserved file name). Backup kept.");
        }
        if (isDirectChildOf(dest, userStartup) || isDirectChildOf(dest, commonStartup)) {
            return dest;
        }
        throw new IOException("Backup entry is corrupt (restore path is not a Startup folder). Backup kept.");
    }

    public static Path resolveConfinedLiveStartupFile(String filePath) throws IOException {
        if (filePath == null || filePath.isBlank()) {
            throw new IOException("Startup folder path is missing.");
        }
        Path dest = resolveConfinedFolderDest(filePath);
        if (Files.isDirectory(dest, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Startup folder path is a directory.");
        }
        return dest;
    }

    public static boolean looksLikeStartupFolder(Path dir) {
        if (dir == null) {
            return false;
        }
        String s = dir.toAbsolutePath().normalize().toString().replace('/', '\\');
        while (s.endsWith("\\")) {
            s = s.substring(0, s.length() - 1);
        }
        return s.toLowerCase(Locale.ROOT).endsWith("\\start menu\\programs\\startup");
    }

    static void assertNoReparseOnStartupPath(Path dir) throws IOException {
        Path p = dir == null ? null : dir.toAbsolutePath().normalize();
        while (p != null) {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && BackupHealth.isReparseOrSymlink(p)) {
                throw new IOException("Cannot restore: Startup path contains a reparse point. Backup kept.");
            }
            p = p.getParent();
        }
    }

    static boolean isSafeBackupPayloadFile(Path path) {
        if (path == null || BackupHealth.isReparseOrSymlink(path)) {
            return false;
        }
        return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    static boolean isDirectChildOf(Path dest, Path dir) {
        if (dest == null || dir == null || dest.getFileName() == null) {
            return false;
        }
        Path parent = dest.getParent();
        if (parent == null) {
            return false;
        }
        return parent.toAbsolutePath().normalize().equals(dir.toAbsolutePath().normalize());
    }

    static Path userStartupDir() {
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) {
            return null;
        }
        return Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
    }

    static Path commonStartupDir() {
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
        }
        String windir = System.getenv("WINDIR");
        if (windir == null || windir.isBlank()) {
            return null;
        }
        Path parent = Path.of(windir).getParent();
        if (parent == null) {
            return null;
        }
        return parent.resolve("ProgramData").resolve("Microsoft").resolve("Windows")
                .resolve("Start Menu").resolve("Programs").resolve("Startup");
    }

    static void assertFolderRestoreTargetAbsent(Path dest) throws IOException {
        if (dest == null || dest.getFileName() == null) {
            throw new IOException("Backup entry is corrupt (missing original file path). Backup kept.");
        }
        Path disabled = dest.resolveSibling(dest.getFileName() + ".disabled");
        if (Files.exists(dest, LinkOption.NOFOLLOW_LINKS) || Files.exists(disabled, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Cannot restore: a file already exists at the destination. "
                    + "Remove or rename it first. Backup kept.");
        }
    }
}
