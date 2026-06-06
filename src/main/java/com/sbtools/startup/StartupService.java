package com.sbtools.startup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.sbtools.util.*;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Version;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;

public class StartupService {

    private static final String REG_RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String REG_RUN_DISABLED = "Software\\Microsoft\\Windows\\CurrentVersion\\RunDisabled";

    private final ProcessRunner processRunner = new ProcessRunner(60); // 1-minute timeout for PowerShell commands

    public static class StartupBackupEntry {
        private String id;
        private String name;
        private String type; // "Registry", "File", "Task"
        private String command;
        private String location;
        
        // Registry specific
        private String hive; // "HKCU", "HKLM"
        private String keyPath;
        private String valueName;
        
        // File specific
        private String originalFilePath;
        private String backupFileName;
        
        // Task specific
        private String taskPath;
        private String backupXmlName;
        
        private long backupTime;

        public StartupBackupEntry() {}

        public StartupBackupEntry(String id, String name, String type, String command, String location, long backupTime) {
            this.id = id;
            this.name = name;
            this.type = type;
            this.command = command;
            this.location = location;
            this.backupTime = backupTime;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getCommand() { return command; }
        public void setCommand(String command) { this.command = command; }
        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }
        public String getHive() { return hive; }
        public void setHive(String hive) { this.hive = hive; }
        public String getKeyPath() { return keyPath; }
        public void setKeyPath(String keyPath) { this.keyPath = keyPath; }
        public String getValueName() { return valueName; }
        public void setValueName(String valueName) { this.valueName = valueName; }
        public String getOriginalFilePath() { return originalFilePath; }
        public void setOriginalFilePath(String originalFilePath) { this.originalFilePath = originalFilePath; }
        public String getBackupFileName() { return backupFileName; }
        public void setBackupFileName(String backupFileName) { this.backupFileName = backupFileName; }
        public String getTaskPath() { return taskPath; }
        public void setTaskPath(String taskPath) { this.taskPath = taskPath; }
        public String getBackupXmlName() { return backupXmlName; }
        public void setBackupXmlName(String backupXmlName) { this.backupXmlName = backupXmlName; }
        public long getBackupTime() { return backupTime; }
        public void setBackupTime(long backupTime) { this.backupTime = backupTime; }
    }

    /**
     * Lists all startup items from Registry Run keys, Startup folders, and Scheduled Tasks.
     */
    public List<StartupItem> listAll() {
        List<StartupItem> items = new ArrayList<>();

        if (!AppPaths.isWindows()) {
            return items;
        }

        // 1. Scan Registry Keys (HKCU and HKLM)
        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU Run", REG_RUN, true, items);
        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU Run (Disabled)", REG_RUN_DISABLED, false, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run", REG_RUN, true, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run (Disabled)", REG_RUN_DISABLED, false, items);

        // 2. Scan Startup folders and Scheduled Tasks using get-startup-details.ps1
        try {
            Path script = PowerShellScripts.resolve("get-startup-details.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
            if (result.success() && result.stdout() != null && !result.stdout().isBlank()) {
                JsonNode root = JsonMapper.parseTree(result.stdout());
                
                // Parse Startup Folders
                JsonNode foldersNode = root.path("StartupFolders");
                if (foldersNode.isArray()) {
                    for (JsonNode node : foldersNode) {
                        String name = node.path("Name").asText("");
                        String path = node.path("Path").asText("");
                        String filePath = node.path("FilePath").asText("");
                        String location = node.path("Location").asText("");
                        boolean enabled = node.path("Enabled").asBoolean(true);
                        String publisher = node.path("Publisher").asText("");

                        items.add(new StartupItem(
                                name,
                                publisher.isEmpty() ? "Unknown" : publisher,
                                path,
                                enabled,
                                location,
                                "",       // registryValueName
                                filePath,
                                ""        // taskPath
                        ));
                    }
                }

                // Parse Scheduled Tasks
                JsonNode tasksNode = root.path("ScheduledTasks");
                if (tasksNode.isArray()) {
                    for (JsonNode node : tasksNode) {
                        String taskName = node.path("TaskName").asText("");
                        String taskPath = node.path("TaskPath").asText("");
                        boolean enabled = node.path("Enabled").asBoolean(true);
                        String path = node.path("Actions").asText("");
                        String publisher = node.path("Publisher").asText("");

                        items.add(new StartupItem(
                                taskName,
                                publisher.isEmpty() ? "Unknown" : publisher,
                                path,
                                enabled,
                                "Scheduled Task",
                                "",       // registryValueName
                                "",       // filePath
                                taskPath
                        ));
                    }
                }
            } else {
                AppLogger.warning("Failed to run startup detail script: " + result.combinedOutput());
            }
        } catch (Exception e) {
            AppLogger.error("Error running startup detail script", e);
        }

        // Sort items alphabetically
        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private void scanRegistry(HKEY hive, String locationLabel, String keyPath, boolean active, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                return;
            }
            Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String valName = entry.getKey();
                Object valData = entry.getValue();
                if (valData instanceof String cmd) {
                    String exePath = extractExecutablePath(cmd);
                    String publisher = getCompanyName(exePath);
                    if (publisher == null || publisher.isBlank()) {
                        publisher = "Unknown";
                    }
                    items.add(new StartupItem(
                            valName,
                            publisher,
                            cmd,
                            active,
                            locationLabel,
                            valName,
                            "", // filePath
                            ""  // taskPath
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry for location " + locationLabel + ": " + e.getMessage());
        }
    }

    /**
     * Toggles the enabled/disabled status of a startup item.
     */
    public void toggleStatus(StartupItem item) throws Exception {
        if ("Scheduled Task".equals(item.getLocation())) {
            // Toggle Scheduled Task
            String scriptName = item.isEnabled() ? "disable-task" : "enable-task";
            String cmd = item.isEnabled() ? "Disable-ScheduledTask" : "Enable-ScheduledTask";
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command", 
                    cmd + " -TaskName '" + item.getName() + "' -TaskPath '" + item.getTaskPath() + "'"));
            if (!result.success()) {
                throw new IOException("Failed to toggle Scheduled Task: " + result.combinedOutput());
            }
            item.setEnabled(!item.isEnabled());
        } else if (item.getLocation().contains("Startup Folder")) {
            // Toggle Shortcut/File in Startup Folder
            File file = new File(item.getFilePath());
            if (!file.exists()) {
                throw new FileNotFoundException("Startup item file not found: " + item.getFilePath());
            }
            
            String newPath;
            if (item.isEnabled()) {
                // Disable: rename file by adding .disabled
                newPath = item.getFilePath() + ".disabled";
            } else {
                // Enable: remove .disabled suffix
                if (item.getFilePath().endsWith(".disabled")) {
                    newPath = item.getFilePath().substring(0, item.getFilePath().length() - 9);
                } else {
                    newPath = item.getFilePath();
                }
            }
            
            File destFile = new File(newPath);
            if (!file.renameTo(destFile)) {
                throw new IOException("Failed to rename file from " + file.getName() + " to " + destFile.getName());
            }
            
            item.setFilePath(newPath);
            item.setEnabled(!item.isEnabled());
        } else if (item.getLocation().contains("Run")) {
            // Toggle Registry Item (Move between Run and RunDisabled)
            boolean isHkcu = item.getLocation().contains("HKCU");
            HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            
            String sourceKey = item.isEnabled() ? REG_RUN : REG_RUN_DISABLED;
            String destKey = item.isEnabled() ? REG_RUN_DISABLED : REG_RUN;
            
            if (!Advapi32Util.registryValueExists(hive, sourceKey, item.getRegistryValueName())) {
                throw new IllegalArgumentException("Registry value does not exist: " + item.getRegistryValueName());
            }
            
            String cmd = Advapi32Util.registryGetStringValue(hive, sourceKey, item.getRegistryValueName());
            
            if (!Advapi32Util.registryKeyExists(hive, destKey)) {
                Advapi32Util.registryCreateKey(hive, destKey);
            }
            
            Advapi32Util.registrySetStringValue(hive, destKey, item.getRegistryValueName(), cmd);
            Advapi32Util.registryDeleteValue(hive, sourceKey, item.getRegistryValueName());
            
            item.setLocation(isHkcu ? (item.isEnabled() ? "HKCU Run (Disabled)" : "HKCU Run") 
                                    : (item.isEnabled() ? "HKLM Run (Disabled)" : "HKLM Run"));
            item.setEnabled(!item.isEnabled());
        }
    }

    /**
     * Safely deletes a startup item, creating a backup first.
     */
    public void deleteItem(StartupItem item) throws Exception {
        // 1. Create a backup entry
        createBackup(item);

        // 2. Perform deletion
        if ("Scheduled Task".equals(item.getLocation())) {
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command", 
                    "Unregister-ScheduledTask -TaskName '" + item.getName() + "' -TaskPath '" + item.getTaskPath() + "' -Confirm:$false"));
            if (!result.success()) {
                throw new IOException("Failed to delete Scheduled Task: " + result.combinedOutput());
            }
        } else if (item.getLocation().contains("Startup Folder")) {
            File file = new File(item.getFilePath());
            if (file.exists()) {
                if (!file.delete()) {
                    throw new IOException("Failed to delete startup shortcut file: " + item.getFilePath());
                }
            }
        } else if (item.getLocation().contains("Run")) {
            boolean isHkcu = item.getLocation().contains("HKCU");
            HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            String keyPath = item.getLocation().contains("(Disabled)") ? REG_RUN_DISABLED : REG_RUN;
            
            if (Advapi32Util.registryValueExists(hive, keyPath, item.getRegistryValueName())) {
                Advapi32Util.registryDeleteValue(hive, keyPath, item.getRegistryValueName());
            }
        }
    }

    // ── Backup / Restore Mechanism ────────────────────────────────────────────

    public Path getBackupsDir() {
        return AppPaths.localAppData().resolve("startup-backups");
    }

    private Path getBackupsIndexFile() {
        return getBackupsDir().resolve("index.json");
    }

    public List<StartupBackupEntry> listBackups() throws IOException {
        Path indexFile = getBackupsIndexFile();
        if (!Files.exists(indexFile)) {
            return new ArrayList<>();
        }
        CollectionType listType = JsonMapper.mapper().getTypeFactory()
                .constructCollectionType(ArrayList.class, StartupBackupEntry.class);
        return JsonMapper.mapper().readValue(indexFile.toFile(), listType);
    }

    private void saveBackupsIndex(List<StartupBackupEntry> list) throws IOException {
        Files.createDirectories(getBackupsDir());
        JsonMapper.mapper().writerWithDefaultPrettyPrinter()
                .writeValue(getBackupsIndexFile().toFile(), list);
    }

    private void createBackup(StartupItem item) throws Exception {
        String backupId = UUID.randomUUID().toString();
        Path backupFolder = getBackupsDir().resolve(backupId);
        Files.createDirectories(backupFolder);

        StartupBackupEntry entry = new StartupBackupEntry(
                backupId,
                item.getName(),
                "",
                item.getPath(),
                item.getLocation(),
                Instant.now().toEpochMilli()
        );

        if ("Scheduled Task".equals(item.getLocation())) {
            entry.setType("Task");
            entry.setTaskPath(item.getTaskPath());
            entry.setBackupXmlName("task.xml");

            // Export task configuration to XML via PowerShell
            Path xmlPath = backupFolder.resolve("task.xml");
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Export-ScheduledTask -TaskName '" + item.getName() + "' -TaskPath '" + item.getTaskPath() + "' | Out-File -FilePath '" + xmlPath.toAbsolutePath().toString() + "' -Encoding utf8"));
            if (!result.success()) {
                throw new IOException("Failed to export Scheduled Task configuration: " + result.combinedOutput());
            }
        } else if (item.getLocation().contains("Startup Folder")) {
            entry.setType("File");
            entry.setOriginalFilePath(item.getFilePath());
            
            File origFile = new File(item.getFilePath());
            if (origFile.exists()) {
                String fileName = origFile.getName();
                entry.setBackupFileName(fileName);
                Files.copy(origFile.toPath(), backupFolder.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
            } else {
                throw new FileNotFoundException("Original shortcut file not found for backup: " + item.getFilePath());
            }
        } else if (item.getLocation().contains("Run")) {
            entry.setType("Registry");
            boolean isHkcu = item.getLocation().contains("HKCU");
            entry.setHive(isHkcu ? "HKCU" : "HKLM");
            entry.setKeyPath(item.getLocation().contains("(Disabled)") ? REG_RUN_DISABLED : REG_RUN);
            entry.setValueName(item.getRegistryValueName());
        }

        // Add to backup index
        List<StartupBackupEntry> index = listBackups();
        index.add(entry);
        saveBackupsIndex(index);
    }

    /**
     * Restores a startup item from a backup entry.
     */
    public void restoreBackup(StartupBackupEntry entry) throws Exception {
        Path backupFolder = getBackupsDir().resolve(entry.getId());

        if ("Registry".equals(entry.getType())) {
            HKEY hive = "HKCU".equals(entry.getHive()) ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            if (!Advapi32Util.registryKeyExists(hive, entry.getKeyPath())) {
                Advapi32Util.registryCreateKey(hive, entry.getKeyPath());
            }
            Advapi32Util.registrySetStringValue(hive, entry.getKeyPath(), entry.getValueName(), entry.getCommand());
        } else if ("File".equals(entry.getType())) {
            Path backupFilePath = backupFolder.resolve(entry.getBackupFileName());
            if (!Files.exists(backupFilePath)) {
                throw new FileNotFoundException("Backup file missing: " + backupFilePath);
            }
            Path origPath = Path.of(entry.getOriginalFilePath());
            Files.createDirectories(origPath.getParent());
            Files.copy(backupFilePath, origPath, StandardCopyOption.REPLACE_EXISTING);
        } else if ("Task".equals(entry.getType())) {
            Path xmlPath = backupFolder.resolve(entry.getBackupXmlName());
            if (!Files.exists(xmlPath)) {
                throw new FileNotFoundException("Backup XML file missing: " + xmlPath);
            }
            
            // Register Scheduled Task from XML config using PowerShell
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Register-ScheduledTask -Xml (Get-Content '" + xmlPath.toAbsolutePath().toString() + "' -Raw) -TaskName '" + entry.getName() + "' -TaskPath '" + entry.getTaskPath() + "' -Force"));
            if (!result.success()) {
                throw new IOException("Failed to restore Scheduled Task: " + result.combinedOutput());
            }
        }

        // Delete backup files and directory
        deleteDirectoryRecursively(backupFolder);

        // Remove from index
        List<StartupBackupEntry> index = listBackups();
        index.removeIf(e -> e.getId().equals(entry.getId()));
        saveBackupsIndex(index);
    }

    public void removeBackup(StartupBackupEntry entry) throws IOException {
        Path backupFolder = getBackupsDir().resolve(entry.getId());
        deleteDirectoryRecursively(backupFolder);

        List<StartupBackupEntry> index = listBackups();
        index.removeIf(e -> e.getId().equals(entry.getId()));
        saveBackupsIndex(index);
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
        }
    }

    // ── Helper Utilities ──────────────────────────────────────────────────────

    public static String extractExecutablePath(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("\"")) {
            int closingQuote = trimmed.indexOf("\"", 1);
            if (closingQuote > 0) {
                return trimmed.substring(1, closingQuote);
            }
        }
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx == -1) {
            return trimmed;
        }
        // Try progressively longer substrings to handle folders with spaces (e.g. C:\Program Files)
        String[] parts = trimmed.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part);
            File file = new File(sb.toString());
            if (file.exists() && file.isFile()) {
                return sb.toString();
            }
        }
        return trimmed.substring(0, spaceIdx);
    }

    public static String getCompanyName(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "";
        }
        try {
            int size = Version.INSTANCE.GetFileVersionInfoSize(filePath, null);
            if (size <= 0) return "";

            Memory dwHandle = new Memory(size);
            if (!Version.INSTANCE.GetFileVersionInfo(filePath, 0, size, dwHandle)) {
                return "";
            }

            PointerByReference lpBuffer = new PointerByReference();
            IntByReference puLen = new IntByReference();
            if (!Version.INSTANCE.VerQueryValue(dwHandle, "\\VarFileInfo\\Translation", lpBuffer, puLen)) {
                return "";
            }

            Pointer translationPointer = lpBuffer.getValue();
            if (translationPointer == null || puLen.getValue() < 4) {
                return "";
            }

            short langId = translationPointer.getShort(0);
            short charsetId = translationPointer.getShort(2);
            String subBlock = String.format("\\StringFileInfo\\%04x%04x\\CompanyName", langId, charsetId);

            if (Version.INSTANCE.VerQueryValue(dwHandle, subBlock, lpBuffer, puLen)) {
                Pointer companyNamePointer = lpBuffer.getValue();
                if (companyNamePointer != null) {
                    return companyNamePointer.getWideString(0);
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }
}
