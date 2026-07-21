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
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

public class StartupService {

    private static final String REG_RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String REG_RUN_ONCE = "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    private static final String REG_RUN_DISABLED = "Software\\Microsoft\\Windows\\CurrentVersion\\RunDisabled";
    private static final String REG_STARTUP_APPROVED = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    private static final String REG_WOW6432_RUN = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Run";
    private static final String REG_WOW6432_RUN_ONCE = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    private static final String REG_WOW6432_APPROVED = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    private static final String REG_STARTUP_APPROVED_RUNONCE = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\RunOnce";

    private final ProcessRunner processRunner = new ProcessRunner(60);
    private final ReentrantLock backupIndexLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<String> scanErrors = new ConcurrentLinkedQueue<>();

    // Cache company name lookups to avoid repeated expensive native version queries
    private static final ConcurrentHashMap<String, String> COMPANY_NAME_CACHE = new ConcurrentHashMap<>();

    // Persist original service start types across rescans
    private static final ConcurrentHashMap<String, String> ORIGINAL_SERVICE_START_TYPES = new ConcurrentHashMap<>();

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

        private boolean enabled;
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
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getBackupTime() { return backupTime; }
        public void setBackupTime(long backupTime) { this.backupTime = backupTime; }
    }

    public List<StartupItem> listAll() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        scanErrors.clear();
        loadOriginalStartTypes();
        items.addAll(listRegistryApps());
        items.addAll(listScheduledTasks());
        items.addAll(listWindowsServices());

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    /**
     * Parallelized version of listAll(). Scans registry, scheduled tasks and services concurrently.
     */
    public List<StartupItem> listAllParallel() {
        if (!AppPaths.isWindows()) return Collections.emptyList();

        scanErrors.clear();
        loadOriginalStartTypes();
        ExecutorService ex = Executors.newFixedThreadPool(3);
        try {
            List<Callable<List<StartupItem>>> tasks = Arrays.asList(
                    this::listRegistryApps,
                    this::listScheduledTasks,
                    this::listWindowsServices
            );

            List<Future<List<StartupItem>>> futures = ex.invokeAll(tasks, 60, TimeUnit.SECONDS);
            List<StartupItem> items = new ArrayList<>();
            String[] scanNames = {"Registry", "Scheduled Tasks", "Windows Services"};
            for (int i = 0; i < futures.size(); i++) {
                Future<List<StartupItem>> f = futures.get(i);
                try {
                    List<StartupItem> part = f.get();
                    if (part != null) items.addAll(part);
                } catch (CancellationException e) {
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                } catch (ExecutionException e) {
                    AppLogger.error("Startup scan failed: " + scanNames[i], e);
                }
            }

            items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
            return items;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return listAll();
        } finally {
            ex.shutdownNow();
        }
    }

    public void invalidateCache() {
        COMPANY_NAME_CACHE.clear();
    }

    public List<String> drainScanErrors() {
        List<String> errors = new ArrayList<>();
        String err;
        while ((err = scanErrors.poll()) != null) {
            errors.add(err);
        }
        return errors;
    }

    private Path getOriginalStartTypesFile() {
        return getBackupsDir().resolve("original-start-types.json");
    }

    private void loadOriginalStartTypes() {
        ORIGINAL_SERVICE_START_TYPES.clear();
        Path file = getOriginalStartTypesFile();
        if (!Files.exists(file)) return;
        try {
            JsonNode root = JsonMapper.parseTree(Files.readString(file));
            root.fields().forEachRemaining(entry ->
                    ORIGINAL_SERVICE_START_TYPES.put(entry.getKey(), entry.getValue().asText()));
        } catch (Exception e) {
            AppLogger.warning("Failed to load original service start types: " + e.getMessage());
        }
    }

    private void saveOriginalStartTypes() {
        try {
            Files.createDirectories(getBackupsDir());
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : ORIGINAL_SERVICE_START_TYPES.entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(entry.getKey().replace("\"", "\\\""))
                  .append("\":\"").append(entry.getValue().replace("\"", "\\\"")).append("\"");
                first = false;
            }
            sb.append("}");
            Files.writeString(getOriginalStartTypesFile(), sb.toString());
        } catch (Exception e) {
            AppLogger.warning("Failed to save original service start types: " + e.getMessage());
        }
    }

    public String getOriginalServiceStartType(String serviceName, String currentStartType) {
        String saved = ORIGINAL_SERVICE_START_TYPES.get(serviceName);
        if (saved != null) return saved;
        return currentStartType;
    }

    public void recordServiceStartType(String serviceName, String startType) {
        ORIGINAL_SERVICE_START_TYPES.put(serviceName, startType);
        saveOriginalStartTypes();
    }

    private record RegistryPaths(HKEY hive, String keyPath, String approvedPath) {}

    private RegistryPaths resolveRegistryPaths(StartupItem item) {
        String location = item.getLocation();
        boolean isHkcu = location.contains("HKCU");
        HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;

        if (location.contains("32-bit") && location.contains("RunOnce")) {
            return new RegistryPaths(hive, REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED);
        } else if (location.contains("32-bit")) {
            return new RegistryPaths(hive, REG_WOW6432_RUN, REG_WOW6432_APPROVED);
        } else if (location.contains("RunOnce")) {
            return new RegistryPaths(hive, REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE);
        } else if (location.contains("(Disabled)")) {
            return new RegistryPaths(hive, REG_RUN_DISABLED, REG_STARTUP_APPROVED);
        } else {
            return new RegistryPaths(hive, REG_RUN, REG_STARTUP_APPROVED);
        }
    }

    public List<StartupItem> listRegistryApps() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        Map<String, StartupItem> seen = new LinkedHashMap<>();

        scanRegistryWithApproval(WinReg.HKEY_CURRENT_USER, "HKCU", REG_RUN, items);
        scanRegistryWithApproval(WinReg.HKEY_LOCAL_MACHINE, "HKLM", REG_RUN, items);
        scanOrphanedApproved(WinReg.HKEY_CURRENT_USER, "HKCU", items);
        scanOrphanedApproved(WinReg.HKEY_LOCAL_MACHINE, "HKLM", items);
        scanOrphanedApprovedRunOnce(WinReg.HKEY_CURRENT_USER, "HKCU", items);
        scanOrphanedApprovedRunOnce(WinReg.HKEY_LOCAL_MACHINE, "HKLM", items);

        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU RunOnce", REG_RUN_ONCE, true, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM RunOnce", REG_RUN_ONCE, true, items);

        scanRegistry(WinReg.HKEY_CURRENT_USER, "HKCU Run (Disabled)", REG_RUN_DISABLED, false, items);
        scanRegistry(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run (Disabled)", REG_RUN_DISABLED, false, items);

        scanRegistry32bit(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run", REG_RUN, items, true);
        scanRegistry32bit(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) RunOnce", REG_RUN_ONCE, items, true);
        scanRegistry32bit(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run (Disabled)", REG_RUN_DISABLED, items, false);

        for (StartupItem item : items) {
            String key = item.getName() + "|" + item.getLocation();
            if (!seen.containsKey(key)) {
                seen.put(key, item);
            }
        }

        List<StartupItem> result = new ArrayList<>(seen.values());
        result.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return result;
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

    private void scanOrphanedApprovedRunOnce(HKEY hive, String hivePrefix, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, REG_STARTUP_APPROVED_RUNONCE)) {
                return;
            }

            Set<String> existingNames = new HashSet<>();
            if (Advapi32Util.registryKeyExists(hive, REG_RUN_ONCE)) {
                existingNames.addAll(Advapi32Util.registryGetValues(hive, REG_RUN_ONCE).keySet());
            }

            Map<String, Object> approvedValues = Advapi32Util.registryGetValues(hive, REG_STARTUP_APPROVED_RUNONCE);
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
                            hivePrefix + " RunOnce",
                            valName,
                            "",
                            "",
                            StartupItemType.REGISTRY,
                            null
                    ));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan orphaned StartupApproved\\RunOnce for " + hivePrefix + ": " + e.getMessage());
        }
    }

    private void scanRegistry32bit(HKEY hive, String locationLabel, String keyPath, List<StartupItem> items, boolean activeDefault) {
        try {
            String fullPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\" + keyPath.substring(keyPath.lastIndexOf('\\') + 1);
            if (!Advapi32Util.registryKeyExists(hive, fullPath)) {
                return;
            }

            Map<String, Object> approvedValues = new HashMap<>();
            String approvedPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\" + keyPath.substring(keyPath.lastIndexOf('\\') + 1);
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
                    boolean enabled = activeDefault;
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
                String msg = "Failed to run scheduled task scan script: " + result.combinedOutput();
                AppLogger.warning(msg);
                scanErrors.add("Scheduled Tasks: " + msg);
            }
        } catch (Exception e) {
            AppLogger.error("Error running startup detail script", e);
            scanErrors.add("Scheduled Tasks: Failed to enumerate scheduled tasks: " + e.getMessage());
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
                String msg = "Failed to query services via WMI: " + result.combinedOutput();
                AppLogger.warning(msg);
                scanErrors.add("Windows Services: " + msg + " (requires admin for full listing)");
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
                            getOriginalServiceStartType(serviceName, startType)
                    );
                    if (!ORIGINAL_SERVICE_START_TYPES.containsKey(serviceName)) {
                        recordServiceStartType(serviceName, startType);
                    }
                    if (!deps.isEmpty()) {
                        item.setDependencies(deps);
                    }
                    items.add(item);
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to enumerate Windows services: " + e.getMessage());
            scanErrors.add("Windows Services: Failed to enumerate services: " + e.getMessage());
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
                    cmd + " -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(item.getTaskPath())));
            if (!result.success()) {
                throw new IOException("Failed to toggle Scheduled Task: " + result.combinedOutput());
            }
            item.setEnabled(!item.isEnabled());
        } else if (item.getType() == StartupItemType.REGISTRY) {
            RegistryPaths paths = resolveRegistryPaths(item);
            String valName = item.getRegistryValueName();
            String location = item.getLocation();

            boolean success = false;
            if (location.contains("RunOnce")) {
                success = toggleRunOnceItem(item, paths);
            } else if (location.contains("(Disabled)")) {
                success = toggleDisabledItem(item, paths);
            } else {
                toggleRegularItem(item, paths);
                success = true;
            }
            if (!success) {
                throw new IOException("Failed to toggle startup item: registry value '" + valName + "' may have been deleted externally.");
            }
            item.setEnabled(!item.isEnabled());
            if (item.isEnabled() && item.getLocation().contains("Run (disabled)")) {
                item.setLocation(item.getLocation().replace("Run (disabled)", "Run"));
            }
        } else if (item.getType() == StartupItemType.SERVICE) {
            String serviceName = item.getName();
            String scConfigArg;
            String newStartType;
            if (item.isEnabled()) {
                scConfigArg = "start= disabled";
                newStartType = "Disabled";
            } else {
                String original = item.getOriginalServiceStartType();
                scConfigArg = "start= " + startTypeToScArg(original);
                newStartType = original;
            }

            ProcessResult result = processRunner.run(List.of("sc.exe", "config", serviceName, scConfigArg));
            if (!result.success()) {
                String errMsg = result.combinedOutput();
                if (errMsg.contains("577") || errMsg.contains("access") || errMsg.contains("denied")) {
                    throw new IOException("Access denied. Please run as administrator to modify service start types.");
                }
                throw new IOException("Failed to toggle service start type: " + errMsg);
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

        if (item.getType() == StartupItemType.TASK) {
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Unregister-ScheduledTask -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(item.getTaskPath()) + " -Confirm:$false"));
            if (!result.success()) {
                throw new IOException("Failed to delete Scheduled Task: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            RegistryPaths paths = resolveRegistryPaths(item);
            String valName = item.getRegistryValueName();

            if (Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName)) {
                Advapi32Util.registryDeleteValue(paths.hive(), paths.keyPath(), valName);
            }

            if (Advapi32Util.registryValueExists(paths.hive(), paths.approvedPath(), valName)) {
                Advapi32Util.registryDeleteValue(paths.hive(), paths.approvedPath(), valName);
            }
        }

        createBackup(item);
        invalidateCache();
    }

    private void toggleRegularItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
            Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
        }
        if (item.isEnabled()) {
            byte[] disableBytes = new byte[]{0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, disableBytes);
        } else {
            byte[] enableBytes = new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, enableBytes);
        }
    }

    private boolean toggleDisabledItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        String location = item.getLocation();
        boolean is32bit = location.contains("32-bit");
        String enableKeyPath = is32bit ? REG_WOW6432_RUN : REG_RUN;

        if (item.isEnabled()) {
            byte[] disableBytes = new byte[]{0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, disableBytes);
            return true;
        } else {
            if (Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName)) {
                Object valData = Advapi32Util.registryGetValue(paths.hive(), paths.keyPath(), valName);
                if (valData instanceof String cmd) {
                    if (!Advapi32Util.registryKeyExists(paths.hive(), enableKeyPath)) {
                        Advapi32Util.registryCreateKey(paths.hive(), enableKeyPath);
                    }
                    Advapi32Util.registrySetStringValue(paths.hive(), enableKeyPath, valName, cmd);
                    Advapi32Util.registryDeleteValue(paths.hive(), paths.keyPath(), valName);
                    byte[] enableBytes = new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                    Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, enableBytes);
                    return true;
                }
            }
            return false;
        }
    }

    private boolean toggleRunOnceItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        String location = item.getLocation();
        boolean is32bit = location.contains("32-bit");
        String runKeyPath = is32bit ? REG_WOW6432_RUN : REG_RUN;
        String runOnceKeyPath = is32bit ? REG_WOW6432_RUN_ONCE : REG_RUN_ONCE;

        if (item.isEnabled()) {
            if (Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName)) {
                Object valData = Advapi32Util.registryGetValue(paths.hive(), paths.keyPath(), valName);
                if (valData instanceof String cmd) {
                    if (!Advapi32Util.registryKeyExists(paths.hive(), runKeyPath)) {
                        Advapi32Util.registryCreateKey(paths.hive(), runKeyPath);
                    }
                    Advapi32Util.registrySetStringValue(paths.hive(), runKeyPath, valName, cmd);
                    Advapi32Util.registryDeleteValue(paths.hive(), paths.keyPath(), valName);
                    String prefix = is32bit ? "HKLM (32-bit)" : (location.contains("HKCU") ? "HKCU" : "HKLM");
                    item.setLocation(prefix + " Run (disabled)");
                    return true;
                }
            }
            return false;
        } else {
            if (Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName)) {
                Object valData = Advapi32Util.registryGetValue(paths.hive(), paths.keyPath(), valName);
                if (valData instanceof String cmd) {
                    if (!Advapi32Util.registryKeyExists(paths.hive(), runOnceKeyPath)) {
                        Advapi32Util.registryCreateKey(paths.hive(), runOnceKeyPath);
                    }
                    Advapi32Util.registrySetStringValue(paths.hive(), runOnceKeyPath, valName, cmd);
                    Advapi32Util.registryDeleteValue(paths.hive(), paths.keyPath(), valName);
                    String prefix = is32bit ? "HKLM (32-bit)" : (location.contains("HKCU") ? "HKCU" : "HKLM");
                    item.setLocation(prefix + " RunOnce");
                    return true;
                }
            }
            return false;
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
        entry.setEnabled(item.isEnabled());

        if (item.getType() == StartupItemType.TASK) {
            entry.setType("Task");
            entry.setTaskPath(item.getTaskPath());
            entry.setBackupXmlName("task.xml");

            Path xmlPath = backupFolder.resolve("task.xml");
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Export-ScheduledTask -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(item.getTaskPath()) + " | Out-File -FilePath " + ProcessRunner.psQuote(xmlPath.toAbsolutePath().toString()) + " -Encoding utf8"));
            if (!result.success()) {
                throw new IOException("Failed to export Scheduled Task configuration: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            entry.setType("Registry");
            RegistryPaths paths = resolveRegistryPaths(item);
            entry.setHive(paths.hive() == WinReg.HKEY_CURRENT_USER ? "HKCU" : "HKLM");
            entry.setKeyPath(paths.keyPath());
            entry.setValueName(item.getRegistryValueName());
        }

        backupIndexLock.lock();
        try {
            List<StartupBackupEntry> index = listBackups();
            index.add(entry);
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
    }

    public void restoreBackup(StartupBackupEntry entry) throws Exception {
        Path backupFolder = getBackupsDir().resolve(entry.getId());

        if ("Registry".equals(entry.getType())) {
            HKEY hive = "HKCU".equals(entry.getHive()) ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            if (!Advapi32Util.registryKeyExists(hive, entry.getKeyPath())) {
                Advapi32Util.registryCreateKey(hive, entry.getKeyPath());
            }
            Advapi32Util.registrySetStringValue(hive, entry.getKeyPath(), entry.getValueName(), entry.getCommand());

            String approvedKeyPath = entry.getKeyPath().replace(
                    "CurrentVersion\\Run", "CurrentVersion\\Explorer\\StartupApproved\\Run");
            if (!approvedKeyPath.equals(entry.getKeyPath())) {
                if (!Advapi32Util.registryKeyExists(hive, approvedKeyPath)) {
                    Advapi32Util.registryCreateKey(hive, approvedKeyPath);
                }
                byte[] approvedBytes = entry.isEnabled()
                        ? new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00}
                        : new byte[]{0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
                Advapi32Util.registrySetBinaryValue(hive, approvedKeyPath, entry.getValueName(), approvedBytes);
            }
        } else if ("Task".equals(entry.getType())) {
            Path xmlPath = backupFolder.resolve(entry.getBackupXmlName());
            if (!Files.exists(xmlPath)) {
                throw new FileNotFoundException("Backup XML file missing: " + xmlPath);
            }

            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Register-ScheduledTask -Xml (Get-Content " + ProcessRunner.psQuote(xmlPath.toAbsolutePath().toString()) + " -Raw) -TaskName " + ProcessRunner.psQuote(entry.getName()) + " -TaskPath " + ProcessRunner.psQuote(entry.getTaskPath()) + " -Force"));
            if (!result.success()) {
                throw new IOException("Failed to restore Scheduled Task: " + result.combinedOutput());
            }
        }

        deleteDirectoryRecursively(backupFolder);

        backupIndexLock.lock();
        try {
            List<StartupBackupEntry> index = listBackups();
            index.removeIf(e -> e.getId().equals(entry.getId()));
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
    }

    public void removeBackup(StartupBackupEntry entry) throws IOException {
        Path backupFolder = getBackupsDir().resolve(entry.getId());
        deleteDirectoryRecursively(backupFolder);

        backupIndexLock.lock();
        try {
            List<StartupBackupEntry> index = listBackups();
            index.removeIf(e -> e.getId().equals(entry.getId()));
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(Comparator.reverseOrder())
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException e) {
                                AppLogger.warning("Failed to delete backup file: " + p + ": " + e.getMessage());
                            }
                        });
            }
        }
    }

    // ── Helper Utilities ──────────────────────────────────────────────────────

    private static String startTypeToScArg(String startType) {
        if (startType == null) return "demand";
        return switch (startType.toLowerCase(Locale.ROOT)) {
            case "automatic" -> "auto";
            case "automatic (delayed start)" -> "delayed-auto";
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

        String key;
        try {
            key = new java.io.File(filePath).getAbsolutePath().toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            key = filePath;
        }

        String cached = COMPANY_NAME_CACHE.get(key);
        if (cached != null) return cached;

        String result = "";
        try {
            int size = Version.INSTANCE.GetFileVersionInfoSize(filePath, null);
            if (size <= 0) {
                COMPANY_NAME_CACHE.put(key, "");
                return "";
            }

            Memory dwHandle = new Memory(size);
            if (!Version.INSTANCE.GetFileVersionInfo(filePath, 0, size, dwHandle)) {
                COMPANY_NAME_CACHE.put(key, "");
                return "";
            }

            PointerByReference lpBuffer = new PointerByReference();
            IntByReference puLen = new IntByReference();
            if (!Version.INSTANCE.VerQueryValue(dwHandle, "\\VarFileInfo\\Translation", lpBuffer, puLen)) {
                COMPANY_NAME_CACHE.put(key, "");
                return "";
            }

            Pointer translationPointer = lpBuffer.getValue();
            if (translationPointer == null || puLen.getValue() < 4) {
                COMPANY_NAME_CACHE.put(key, "");
                return "";
            }

            short langId = translationPointer.getShort(0);
            short charsetId = translationPointer.getShort(2);
            String subBlock = String.format("\\StringFileInfo\\%04x%04x\\CompanyName", langId, charsetId);

            if (Version.INSTANCE.VerQueryValue(dwHandle, subBlock, lpBuffer, puLen)) {
                Pointer companyNamePointer = lpBuffer.getValue();
                if (companyNamePointer != null) {
                    result = companyNamePointer.getWideString(0);
                }
            }
        } catch (Exception ignored) {}

        if (result == null) result = "";
        COMPANY_NAME_CACHE.put(key, result);
        return result;
    }
}
