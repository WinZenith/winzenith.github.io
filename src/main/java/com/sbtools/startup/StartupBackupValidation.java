package com.sbtools.startup;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure backup metadata validation and path confinement for startup backups.
 */
final class StartupBackupValidation {

    private static final Pattern UUID_PATTERN = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private StartupBackupValidation() {
    }

    static void validateEntryMetadata(StartupService.StartupBackupEntry entry) throws IOException {
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

    static boolean requiresAdmin(StartupService.StartupBackupEntry entry) {
        if (entry == null || entry.getType() == null) {
            return false;
        }
        return switch (entry.getType()) {
            case "Registry" -> "HKLM".equals(entry.getHive());
            case "Folder" -> entry.getLocation() != null && entry.getLocation().contains("Common");
            case "Task" -> isSystemTaskPath(entry.getTaskPath());
            default -> false;
        };
    }

    static Path resolveConfinedBackupFolder(Path backupsDir, String backupId) throws IOException {
        if (backupsDir == null || backupId == null || !isValidBackupId(backupId)) {
            throw new IOException("Backup entry is corrupt (invalid id). Backup kept.");
        }
        Path root = backupsDir.toAbsolutePath().normalize();
        Path folder = root.resolve(backupId).normalize();
        if (!folder.startsWith(root)) {
            throw new IOException("Backup entry is corrupt (invalid path). Backup kept.");
        }
        return folder;
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
    }

    private static void validateFolderEntry(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry.getKeyPath() == null || entry.getKeyPath().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing original file path). Backup kept.");
        }
        String payload = entry.getBackupXmlName();
        if (payload == null || payload.isBlank()) {
            payload = entry.getValueName();
        }
        if (payload == null || payload.isBlank()) {
            throw new IOException("Backup entry is corrupt (missing folder payload). Backup kept.");
        }
    }

    private static void validateTaskEntry(StartupService.StartupBackupEntry entry) throws IOException {
        if (entry.getName() == null || entry.getName().isBlank()) {
            throw new IOException("Backup entry is corrupt (missing task name). Backup kept.");
        }
        String xml = entry.getBackupXmlName();
        if (xml == null || xml.isBlank()) {
            xml = "task.xml";
        }
        if (xml.contains("..") || xml.contains("/") || xml.contains("\\")) {
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

    static void assertFolderRestoreTargetAbsent(String keyPath) throws IOException {
        if (keyPath == null || keyPath.isBlank()) {
            throw new IOException("Backup entry is corrupt (missing original file path). Backup kept.");
        }
        try {
            Path dest = Path.of(keyPath);
            Path disabled = dest.resolveSibling(dest.getFileName() + ".disabled");
            if (Files.exists(dest) || Files.exists(disabled)) {
                throw new IOException("Cannot restore: a file already exists at the destination. "
                        + "Remove or rename it first. Backup kept.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Backup entry has invalid file path. Backup kept: " + e.getMessage());
        }
    }
}
