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

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public class StartupService {

    private static final String REG_RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String REG_RUN_ONCE = "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    private static final String REG_RUN_DISABLED = "Software\\Microsoft\\Windows\\CurrentVersion\\RunDisabled";
    private static final String REG_STARTUP_APPROVED = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";

    private final ProcessRunner processRunner = new ProcessRunner(60);

    private volatile long lastScanTime = 0;
    private volatile List<StartupItem> cachedItems = null;

    public static class StartupBackupEntry {
        private String id;
        private String name;
        private String type; // "Registry", "Task"
        private String command;
        private String location;

        // Registry specific
        private String hive;
        private String keyPath;
        private String valueName;

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
        public String getTaskPath() { return taskPath; }
        public void setTaskPath(String taskPath) { this.taskPath = taskPath; }
        public String getBackupXmlName() { return backupXmlName; }
        public void setBackupXmlName(String backupXmlName) { this.backupXmlName = backupXmlName; }
        public long getBackupTime() { return backupTime; }
        public void setBackupTime(long backupTime) { this.backupTime = backupTime; }
    }

    public List<StartupItem> listAll() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        items.addAll(listRegistryApps());
        items.addAll(listScheduledTasks());
        items.addAll(listWindowsServices());

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    public List<StartupItem> listAllCached(long maxAgeMs) {
        List<StartupItem> snap = cachedItems;
        if (snap != null && (System.currentTimeMillis() - lastScanTime) < maxAgeMs) {
            return new ArrayList<>(snap);
        }
        List<StartupItem> fresh = listAll();
        cachedItems = fresh;
        lastScanTime = System.currentTimeMillis();
        return new ArrayList<>(fresh);
    }

    public void invalidateCache() {
        cachedItems = null;
        lastScanTime = 0;
    }

    public List<StartupItem> listRegistryApps() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        scanRegistryWithApproval(WinReg.HKEY_CURRENT_USER, "HKCU", REG_RUN, items);
        scanRegistryWithApproval(WinReg.HKEY_LOCAL_MACHINE, "HKLM", REG_RUN, items);
        scanOrphanedApproved(WinReg.HKEY_CURRENT_USER, "HKCU", items);
        scanOrphanedApproved(WinReg.HKEY_LOCAL_MACHINE, "HKLM", items);

        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU RunOnce", REG_RUN_ONCE, true, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM RunOnce", REG_RUN_ONCE, true, items);

        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU Run (Disabled)", REG_RUN_DISABLED, false, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run (Disabled)", REG_RUN_DISABLED, false, items);

        scanRegistry32bit(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run", REG_RUN, items);

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private void scanRegistryWithApproval(HKEY hive, String hivePrefix, String keyPath, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                return;
            }

            Map<String, Object> approvedValues = new HashMap<>();
            try {
                if (Advapi32Util.registryKeyExists(hive, REG_STARTUP_APPROVED)) {
                    Map<String, Object> allApproved = Advapi32Util.registryGetValues(hive, REG_STARTUP_APPROVED);
                    approvedValues.putAll(allApproved);
                }
            } catch (Exception ignored) {}

            Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String valName = entry.getKey();
                Object valData = entry.getValue();
                if (valData instanceof String cmd) {
                    boolean enabled;

                    Object approvedData = approvedValues.get(valName);
                    if (approvedData instanceof byte[] bytes && bytes.length > 0) {
                        enabled = bytes[0] == 0x02;
                    } else {
                        enabled = true;
                    }

                    String exePath = extractExecutablePath(cmd);
                    String publisher = getCompanyName(exePath);
                    if (publisher == null || publisher.isBlank()) {
                        publisher = "Unknown";
                    }
                    items.add(new StartupItem(
                            valName,
                            publisher,
                            cmd,
                            enabled,
                            hivePrefix + " Run",
                            valName,
                            "",
                            "",
                            StartupItemType.REGISTRY,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry for " + hivePrefix + " " + keyPath + ": " + e.getMessage());
        }
    }

    private void scanOrphanedApproved(HKEY hive, String hivePrefix, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, REG_STARTUP_APPROVED)) {
                return;
            }

            Set<String> existingNames = new HashSet<>();
            if (Advapi32Util.registryKeyExists(hive, REG_RUN)) {
                existingNames.addAll(Advapi32Util.registryGetValues(hive, REG_RUN).keySet());
            }

            Map<String, Object> approvedValues = Advapi32Util.registryGetValues(hive, REG_STARTUP_APPROVED);
            for (Map.Entry<String, Object> entry : approvedValues.entrySet()) {
                String valName = entry.getKey();
                if (existingNames.contains(valName)) continue;

                Object valData = entry.getValue();
                if (valData instanceof byte[] bytes && bytes.length > 0) {
                    boolean enabled = bytes[0] == 0x02;
                    items.add(new StartupItem(
                            valName,
                            "Unknown",
                            "",
                            enabled,
                            hivePrefix + " Run",
                            valName,
                            "",
                            "",
                            StartupItemType.REGISTRY,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan orphaned StartupApproved for " + hivePrefix + ": " + e.getMessage());
        }
    }

    private void scanRegistry32bit(HKEY hive, String locationLabel, String keyPath, List<StartupItem> items) {
        try {
            String fullPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Run";
            if (!Advapi32Util.registryKeyExists(hive, fullPath)) {
                return;
            }

            Map<String, Object> approvedValues = new HashMap<>();
            String approvedPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
            try {
                if (Advapi32Util.registryKeyExists(hive, approvedPath)) {
                    Map<String, Object> allApproved = Advapi32Util.registryGetValues(hive, approvedPath);
                    approvedValues.putAll(allApproved);
                }
            } catch (Exception ignored) {}

            Map<String, Object> values = Advapi32Util.registryGetValues(hive, fullPath);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String valName = entry.getKey();
                Object valData = entry.getValue();
                if (valData instanceof String cmd) {
                    boolean enabled = true;
                    Object approvedData = approvedValues.get(valName);
                    if (approvedData instanceof byte[] bytes && bytes.length > 0) {
                        enabled = bytes[0] == 0x02;
                    }

                    String exePath = extractExecutablePath(cmd);
                    String publisher = getCompanyName(exePath);
                    if (publisher == null || publisher.isBlank()) {
                        publisher = "Unknown";
                    }
                    items.add(new StartupItem(
                            valName,
                            publisher,
                            cmd,
                            enabled,
                            locationLabel,
                            valName,
                            "",
                            "",
                            StartupItemType.REGISTRY,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan 32-bit registry for " + locationLabel + ": " + e.getMessage());
        }
    }

    public List<StartupItem> listScheduledTasks() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        try {
            Path script = PowerShellScripts.resolve("get-startup-details.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
            if (result.success() && result.stdout() != null && !result.stdout().isBlank()) {
                JsonNode root = JsonMapper.parseTree(result.stdout());

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
                                "",
                                "",
                                taskPath,
                                StartupItemType.TASK,
                                null
                        ));
                    }
                }
            } else {
                AppLogger.warning("Failed to run startup detail script: " + result.combinedOutput());
            }
        } catch (Exception e) {
            AppLogger.error("Error running startup detail script", e);
        }

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    public List<StartupItem> listWindowsServices() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        try {
            Path script = PowerShellScripts.resolve("get-windows-services.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()), 30);
            if (!result.success() || result.stdout() == null || result.stdout().isBlank()) {
                AppLogger.warning("Failed to query services via WMI: " + result.combinedOutput());
                return items;
            }

            JsonNode root = JsonMapper.parseTree(result.stdout());
            JsonNode servicesNode = root.path("Services");
            if (servicesNode.isArray()) {
                for (JsonNode node : servicesNode) {
                    String serviceName = node.path("Name").asText("");
                    String displayName = node.path("DisplayName").asText("");
                    String binaryPath = node.path("BinaryPath").asText("");
                    String startType = node.path("StartType").asText("Manual");
                    boolean enabled = !"Disabled".equals(startType);

                    List<String> deps = new ArrayList<>();
                    JsonNode depsNode = node.path("Dependencies");
                    if (depsNode.isArray()) {
                        for (JsonNode dep : depsNode) {
                            deps.add(dep.asText(""));
                        }
                    }

                    StartupItem item = new StartupItem(
                            serviceName,
                            displayName.isEmpty() ? "Unknown" : displayName,
                            binaryPath,
                            enabled,
                            "Start Type: " + startType,
                            "",
                            "",
                            "",
                            StartupItemType.SERVICE,
                            startType
                    );
                    if (!deps.isEmpty()) {
                        item.setDependencies(deps);
                    }
                    items.add(item);
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to enumerate Windows services: " + e.getMessage());
        }

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
                            "",
                            "",
                            StartupItemType.REGISTRY,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry for location " + locationLabel + ": " + e.getMessage());
        }
    }

    public void toggleStatus(StartupItem item) throws Exception {
        if (item.getType() == StartupItemType.TASK) {
            String cmd = item.isEnabled() ? "Disable-ScheduledTask" : "Enable-ScheduledTask";
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    cmd + " -TaskName '" + escapePowerShellSingleQuote(item.getName()) + "' -TaskPath '" + escapePowerShellSingleQuote(item.getTaskPath()) + "'"));
            if (!result.success()) {
                throw new IOException("Failed to toggle Scheduled Task: " + result.combinedOutput());
            }
            item.setEnabled(!item.isEnabled());
        } else if (item.getType() == StartupItemType.REGISTRY) {
            boolean isHkcu = item.getLocation().contains("HKCU");
            HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;

            if (!Advapi32Util.registryKeyExists(hive, REG_STARTUP_APPROVED)) {
                Advapi32Util.registryCreateKey(hive, REG_STARTUP_APPROVED);
            }

            if (item.isEnabled()) {
                // Disable: write 03 to StartupApproved\Run
                byte[] disableBytes = new byte[]{0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                Advapi32Util.registrySetBinaryValue(hive, REG_STARTUP_APPROVED, item.getRegistryValueName(), disableBytes);
            } else {
                // Enable: write 02 to StartupApproved\Run
                byte[] enableBytes = new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                Advapi32Util.registrySetBinaryValue(hive, REG_STARTUP_APPROVED, item.getRegistryValueName(), enableBytes);
            }
            item.setEnabled(!item.isEnabled());
        } else if (item.getType() == StartupItemType.SERVICE) {
            String serviceName = item.getName();
            String scConfigArg;
            String newStartType;
            if (item.isEnabled()) {
                scConfigArg = "start= disabled";
                newStartType = "Disabled";
            } else {
                // Restore to original start type
                String original = item.getServiceStartType();
                scConfigArg = "start= " + startTypeToScArg(original);
                newStartType = original;
            }

            ProcessResult result = processRunner.run(List.of("sc.exe", "config", serviceName, scConfigArg));
            if (!result.success()) {
                throw new IOException("Failed to toggle service start type: " + result.combinedOutput());
            }

            item.setServiceStartType(newStartType);
            item.setLocation("Start Type: " + newStartType);
            item.setEnabled(!item.isEnabled());
        }
        invalidateCache();
    }

    public void deleteItem(StartupItem item) throws Exception {
        if (item.getType() == StartupItemType.SERVICE) {
            throw new UnsupportedOperationException("Windows services cannot be deleted.");
        }
        createBackup(item);

        if (item.getType() == StartupItemType.TASK) {
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Unregister-ScheduledTask -TaskName '" + escapePowerShellSingleQuote(item.getName()) + "' -TaskPath '" + escapePowerShellSingleQuote(item.getTaskPath()) + "' -Confirm:$false"));
            if (!result.success()) {
                throw new IOException("Failed to delete Scheduled Task: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            boolean isHkcu = item.getLocation().contains("HKCU");
            HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            String keyPath = item.getLocation().contains("(Disabled)") ? REG_RUN_DISABLED : REG_RUN;

            if (Advapi32Util.registryValueExists(hive, keyPath, item.getRegistryValueName())) {
                Advapi32Util.registryDeleteValue(hive, keyPath, item.getRegistryValueName());
            }
        }
        invalidateCache();
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

        if (item.getType() == StartupItemType.TASK) {
            entry.setType("Task");
            entry.setTaskPath(item.getTaskPath());
            entry.setBackupXmlName("task.xml");

            Path xmlPath = backupFolder.resolve("task.xml");
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Export-ScheduledTask -TaskName '" + escapePowerShellSingleQuote(item.getName()) + "' -TaskPath '" + escapePowerShellSingleQuote(item.getTaskPath()) + "' | Out-File -FilePath '" + xmlPath.toAbsolutePath().toString() + "' -Encoding utf8"));
            if (!result.success()) {
                throw new IOException("Failed to export Scheduled Task configuration: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            entry.setType("Registry");
            boolean isHkcu = item.getLocation().contains("HKCU");
            entry.setHive(isHkcu ? "HKCU" : "HKLM");
            entry.setKeyPath(item.getLocation().contains("(Disabled)") ? REG_RUN_DISABLED : REG_RUN);
            entry.setValueName(item.getRegistryValueName());
        }

        List<StartupBackupEntry> index = listBackups();
        index.add(entry);
        saveBackupsIndex(index);
    }

    public void restoreBackup(StartupBackupEntry entry) throws Exception {
        Path backupFolder = getBackupsDir().resolve(entry.getId());

        if ("Registry".equals(entry.getType())) {
            HKEY hive = "HKCU".equals(entry.getHive()) ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            if (!Advapi32Util.registryKeyExists(hive, entry.getKeyPath())) {
                Advapi32Util.registryCreateKey(hive, entry.getKeyPath());
            }
            Advapi32Util.registrySetStringValue(hive, entry.getKeyPath(), entry.getValueName(), entry.getCommand());
        } else if ("Task".equals(entry.getType())) {
            Path xmlPath = backupFolder.resolve(entry.getBackupXmlName());
            if (!Files.exists(xmlPath)) {
                throw new FileNotFoundException("Backup XML file missing: " + xmlPath);
            }

            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Register-ScheduledTask -Xml (Get-Content '" + xmlPath.toAbsolutePath().toString() + "' -Raw) -TaskName '" + escapePowerShellSingleQuote(entry.getName()) + "' -TaskPath '" + escapePowerShellSingleQuote(entry.getTaskPath()) + "' -Force"));
            if (!result.success()) {
                throw new IOException("Failed to restore Scheduled Task: " + result.combinedOutput());
            }
        }

        deleteDirectoryRecursively(backupFolder);

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

    private static String escapePowerShellSingleQuote(String s) {
        if (s == null) return "";
        return s.replace("'", "''");
    }

    private static String startTypeToScArg(String startType) {
        if (startType == null) return "demand";
        return switch (startType.toLowerCase()) {
            case "automatic" -> "auto";
            case "manual" -> "demand";
            case "disabled" -> "disabled";
            default -> "demand";
        };
    }

    public static String extractExecutablePath(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String trimmed = command.trim();

        // Quoted path: extract content between first pair of quotes
        if (trimmed.startsWith("\"")) {
            int closingQuote = trimmed.indexOf("\"", 1);
            if (closingQuote > 0) {
                return trimmed.substring(1, closingQuote);
            }
        }

        // Try regex: match up to first .exe/.dll/.com/.bat extension (case-insensitive)
        java.util.regex.Matcher extMatcher = java.util.regex.Pattern
                .compile("^([^\"]*?\\.(exe|dll|com|bat|cmd))", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(trimmed);
        if (extMatcher.find()) {
            return extMatcher.group(1).trim();
        }

        // Fallback: split on spaces, check file existence (original heuristic)
        int spaceIdx = trimmed.indexOf(' ');
        if (spaceIdx == -1) {
            return trimmed;
        }
        String[] parts = trimmed.split(" ");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(part);
            java.io.File file = new java.io.File(sb.toString());
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
        } catch (Exception ignored) {}
        return "";
    }
}
