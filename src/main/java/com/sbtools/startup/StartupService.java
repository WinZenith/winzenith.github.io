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

    private static final String REG_RUN = StartupConstants.REG_RUN;
    private static final String REG_RUN_ONCE = StartupConstants.REG_RUN_ONCE;
    private static final String REG_RUN_DISABLED = StartupConstants.REG_RUN_DISABLED;
    private static final String REG_STARTUP_APPROVED = StartupConstants.REG_STARTUP_APPROVED;
    private static final String REG_WOW6432_RUN = StartupConstants.REG_WOW6432_RUN;
    private static final String REG_WOW6432_RUN_ONCE = StartupConstants.REG_WOW6432_RUN_ONCE;
    private static final String REG_WOW6432_RUN_DISABLED = StartupConstants.REG_WOW6432_RUN_DISABLED;
    private static final String REG_WOW6432_APPROVED = StartupConstants.REG_WOW6432_APPROVED;
    private static final String REG_STARTUP_APPROVED_RUNONCE = StartupConstants.REG_STARTUP_APPROVED_RUNONCE;
    private static final String REG_WOW6432_APPROVED_RUNONCE = StartupConstants.REG_WOW6432_APPROVED_RUNONCE;

    private final ProcessRunner processRunner = new ProcessRunner(60);
    private final ReentrantLock backupIndexLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<String> scanErrors = new ConcurrentLinkedQueue<>();
    private static final Object ORIGINAL_FILE_LOCK = new Object();

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
        // Include startup folder items merged into registry view
        items.addAll(listStartupFolderItems());

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    /**
     * Parallelized version of listAll(). Scans registry, scheduled tasks, services and startup folder concurrently.
     * Uses shared scanPool to avoid per-scan thread creation; timeouts are applied per-future.
     */
    public List<StartupItem> listAllParallel() {
        if (!AppPaths.isWindows()) return Collections.emptyList();

        scanErrors.clear();
        loadOriginalStartTypes();
        ExecutorService ex = AppExecutors.scanPool();
        try {
            List<Callable<List<StartupItem>>> tasks = Arrays.asList(
                    this::listRegistryApps,
                    this::listScheduledTasks,
                    this::listWindowsServices,
                    this::listStartupFolderItems
            );

            // Submit all and wait with timeout 60s total
            List<Future<List<StartupItem>>> futures = new ArrayList<>();
            for (Callable<List<StartupItem>> t : tasks) {
                futures.add(ex.submit(t));
            }
            List<StartupItem> items = new ArrayList<>();
            String[] scanNames = {"Registry", "Scheduled Tasks", "Windows Services", "Startup Folder"};
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (int i = 0; i < futures.size(); i++) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                    futures.get(i).cancel(true);
                    continue;
                }
                Future<List<StartupItem>> f = futures.get(i);
                try {
                    List<StartupItem> part = f.get(remaining, TimeUnit.NANOSECONDS);
                    if (part != null) items.addAll(part);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                } catch (CancellationException e) {
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                } catch (ExecutionException e) {
                    AppLogger.error("Startup scan failed: " + scanNames[i], e);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return listAll();
                }
            }

            items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
            return items;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
                return listAll();
            }
            AppLogger.error("Parallel scan failed, falling back to sequential", e);
            return listAll();
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
        Map<String, String> loaded = new HashMap<>();
        Path file = getOriginalStartTypesFile();
        String content;
        synchronized (ORIGINAL_FILE_LOCK) {
            if (!Files.exists(file)) {
                synchronized (ORIGINAL_SERVICE_START_TYPES) {
                    ORIGINAL_SERVICE_START_TYPES.clear();
                }
                return;
            }
            try {
                content = Files.readString(file);
            } catch (Exception e) {
                AppLogger.warning("Failed to load original service start types: " + e.getMessage());
                return;
            }
        }
        try {
            JsonNode root = JsonMapper.parseTree(content);
            root.fields().forEachRemaining(entry -> {
                String v = entry.getValue().asText();
                if (!"Disabled".equalsIgnoreCase(v)) {
                    loaded.put(entry.getKey(), v);
                }
            });
        } catch (Exception e) {
            AppLogger.warning("Failed to load original service start types: " + e.getMessage());
            return;
        }
        synchronized (ORIGINAL_SERVICE_START_TYPES) {
            ORIGINAL_SERVICE_START_TYPES.clear();
            ORIGINAL_SERVICE_START_TYPES.putAll(loaded);
        }
    }

    private void saveOriginalStartTypes() {
        Map<String, String> snapshot;
        synchronized (ORIGINAL_SERVICE_START_TYPES) {
            snapshot = new HashMap<>(ORIGINAL_SERVICE_START_TYPES);
            snapshot.entrySet().removeIf(e -> "Disabled".equalsIgnoreCase(e.getValue()));
        }
        synchronized (ORIGINAL_FILE_LOCK) {
            try {
                Files.createDirectories(getBackupsDir());
                Path file = getOriginalStartTypesFile();
                Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
                JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snapshot);
                try {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                AppLogger.warning("Failed to save original service start types: " + e.getMessage());
            }
        }
    }

    public String getOriginalServiceStartType(String serviceName, String currentStartType) {
        String saved = ORIGINAL_SERVICE_START_TYPES.get(serviceName);
        if (saved != null && !"Disabled".equalsIgnoreCase(saved)) return saved;
        if (saved != null && "Disabled".equalsIgnoreCase(saved)) {
            // Service was Disabled at first observation – cannot restore to Disabled when enabling
            return "Manual";
        }
        if ("Disabled".equalsIgnoreCase(currentStartType)) return "Manual";
        return currentStartType;
    }

    public void recordServiceStartType(String serviceName, String startType) {
        if ("Disabled".equalsIgnoreCase(startType)) return;
        String existing = ORIGINAL_SERVICE_START_TYPES.get(serviceName);
        if (existing != null && !"Disabled".equalsIgnoreCase(existing)) return;
        ORIGINAL_SERVICE_START_TYPES.put(serviceName, startType);
        saveOriginalStartTypes();
    }

    private record RegistryPaths(HKEY hive, String keyPath, String approvedPath) {}

    private RegistryPaths resolveRegistryPaths(StartupItem item) {
        // Startup folder items use filePath; registry items use registryValueName + location
        if (item.getLocation() != null && item.getLocation().startsWith("Startup Folder")) {
            // Not a registry item – return dummy paths (caller should handle folder separately)
            HKEY hive = item.getLocation().contains("HKCU") ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            return new RegistryPaths(hive, StartupConstants.REG_RUN, StartupConstants.REG_STARTUP_APPROVED);
        }
        String location = item.getLocation() == null ? "" : item.getLocation();
        boolean isHkcu = location.contains("HKCU");
        HKEY hive = isHkcu ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
        boolean is32bit = location.contains("32-bit");
        String valName = item.getRegistryValueName();
        // Probe actual registry to avoid fragile location parsing (fixes legacy RunOnce moved to Run)
        if (valName != null && !valName.isBlank()) {
            String[] candidates = is32bit
                    ? new String[]{REG_WOW6432_RUN, REG_WOW6432_RUN_ONCE, REG_WOW6432_RUN_DISABLED}
                    : new String[]{REG_RUN, REG_RUN_ONCE, REG_RUN_DISABLED};
            for (String cand : candidates) {
                try {
                    if (Advapi32Util.registryValueExists(hive, cand, valName)) {
                        return new RegistryPaths(hive, cand, StartupConstants.toApprovedPath(cand));
                    }
                } catch (Exception ignored) {}
            }
        }
        // Fallback: derive from location label (for orphan approved entries where value is missing)
        boolean isRunOnce = location.contains("RunOnce");
        boolean isDisabled = location.contains("(Disabled)");

        if (is32bit) {
            if (isRunOnce) {
                return new RegistryPaths(hive, REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE);
            } else if (isDisabled) {
                return new RegistryPaths(hive, REG_WOW6432_RUN_DISABLED, REG_WOW6432_APPROVED);
            } else {
                return new RegistryPaths(hive, REG_WOW6432_RUN, REG_WOW6432_APPROVED);
            }
        } else {
            if (isRunOnce) {
                return new RegistryPaths(hive, REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE);
            } else if (isDisabled) {
                return new RegistryPaths(hive, REG_RUN_DISABLED, REG_STARTUP_APPROVED);
            } else {
                return new RegistryPaths(hive, REG_RUN, REG_STARTUP_APPROVED);
            }
        }
    }

    public List<StartupItem> listRegistryApps() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        Map<String, StartupItem> seen = new LinkedHashMap<>();

        // Unified scanning – explicit keyPath + approvedPath + label
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU Run", REG_RUN, REG_STARTUP_APPROVED, true, items);
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run", REG_RUN, REG_STARTUP_APPROVED, true, items);
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU RunOnce", REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE, true, items);
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM RunOnce", REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE, true, items);
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU Run (Disabled)", REG_RUN_DISABLED, REG_STARTUP_APPROVED, false, items);
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run (Disabled)", REG_RUN_DISABLED, REG_STARTUP_APPROVED, false, items);

        // 32-bit (WOW6432) – both HKLM and HKCU
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run", REG_WOW6432_RUN, REG_WOW6432_APPROVED, true, items);
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) RunOnce", REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE, true, items);
        scanRegistryUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run (Disabled)", REG_WOW6432_RUN_DISABLED, REG_WOW6432_APPROVED, false, items);
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU (32-bit) Run", REG_WOW6432_RUN, REG_WOW6432_APPROVED, true, items);
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU (32-bit) RunOnce", REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE, true, items);
        scanRegistryUnified(WinReg.HKEY_CURRENT_USER, "HKCU (32-bit) Run (Disabled)", REG_WOW6432_RUN_DISABLED, REG_WOW6432_APPROVED, false, items);

        // Orphaned Approved entries – regular and 32-bit, Run and RunOnce
        scanOrphanedApprovedUnified(WinReg.HKEY_CURRENT_USER, "HKCU Run", REG_RUN, REG_STARTUP_APPROVED, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM Run", REG_RUN, REG_STARTUP_APPROVED, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_CURRENT_USER, "HKCU RunOnce", REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM RunOnce", REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) Run", REG_WOW6432_RUN, REG_WOW6432_APPROVED, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_LOCAL_MACHINE, "HKLM (32-bit) RunOnce", REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_CURRENT_USER, "HKCU (32-bit) Run", REG_WOW6432_RUN, REG_WOW6432_APPROVED, items);
        scanOrphanedApprovedUnified(WinReg.HKEY_CURRENT_USER, "HKCU (32-bit) RunOnce", REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE, items);

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

    private void scanRegistryUnified(HKEY hive, String locationLabel, String keyPath, String approvedPath, boolean activeDefault, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                return;
            }
            Map<String, Object> approvedValues = new HashMap<>();
            try {
                if (Advapi32Util.registryKeyExists(hive, approvedPath)) {
                    approvedValues.putAll(Advapi32Util.registryGetValues(hive, approvedPath));
                }
            } catch (Exception ignored) {}

            Map<String, Object> values = Advapi32Util.registryGetValues(hive, keyPath);
            for (Map.Entry<String, Object> entry : values.entrySet()) {
                String valName = entry.getKey();
                Object valData = entry.getValue();
                // Support REG_SZ and REG_EXPAND_SZ (both returned as String by JNA)
                if (!(valData instanceof String cmd)) {
                    if (valData != null) {
                        // Sometimes REG_EXPAND_SZ returns String already; else skip non-string types
                        continue;
                    } else continue;
                }
                boolean enabled = activeDefault;
                Object approvedData = approvedValues.get(valName);
                if (approvedData instanceof byte[] bytes && bytes.length > 0) {
                    enabled = StartupConstants.isEnabledByte(bytes);
                }
                String exePath = extractExecutablePath(cmd);
                String publisher = getCompanyName(exePath);
                if (publisher == null || publisher.isBlank()) publisher = "Unknown";
                items.add(new StartupItem(valName, publisher, cmd, enabled, locationLabel, valName, "", "", StartupItemType.REGISTRY, null));
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan registry for " + locationLabel + " " + keyPath + ": " + e.getMessage());
        }
    }

    private void scanOrphanedApprovedUnified(HKEY hive, String locationLabel, String keyPath, String approvedPath, List<StartupItem> items) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, approvedPath)) return;
            Set<String> existing = new HashSet<>();
            if (Advapi32Util.registryKeyExists(hive, keyPath)) {
                existing.addAll(Advapi32Util.registryGetValues(hive, keyPath).keySet());
            }
            Map<String, Object> approved = Advapi32Util.registryGetValues(hive, approvedPath);
            for (Map.Entry<String, Object> e : approved.entrySet()) {
                String valName = e.getKey();
                if (existing.contains(valName)) continue;
                Object v = e.getValue();
                if (v instanceof byte[] bytes && bytes.length > 0) {
                    boolean enabled = StartupConstants.isEnabledByte(bytes);
                    items.add(new StartupItem(valName, "Unknown", "", enabled, locationLabel, valName, "", "", StartupItemType.REGISTRY, null));
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan orphaned " + approvedPath + " for " + locationLabel + ": " + e.getMessage());
        }
    }

    // Legacy wrappers retained for compatibility (delegates)
    private void scanRegistryWithApproval(HKEY hive, String hivePrefix, String keyPath, List<StartupItem> items) {
        String approved = StartupConstants.toApprovedPath(keyPath);
        String label = hivePrefix + " Run";
        scanRegistryUnified(hive, label, keyPath, approved, true, items);
    }
    private void scanOrphanedApproved(HKEY hive, String hivePrefix, List<StartupItem> items) {
        scanOrphanedApprovedUnified(hive, hivePrefix + " Run", REG_RUN, REG_STARTUP_APPROVED, items);
    }
    private void scanOrphanedApprovedRunOnce(HKEY hive, String hivePrefix, List<StartupItem> items) {
        scanOrphanedApprovedUnified(hive, hivePrefix + " RunOnce", REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE, items);
    }
    private void scanRegistry(HKEY hive, String locationLabel, String keyPath, boolean active, List<StartupItem> items) {
        String approved = StartupConstants.toApprovedPath(keyPath);
        scanRegistryUnified(hive, locationLabel, keyPath, approved, active, items);
    }
    private void scanRegistry32bit(HKEY hive, String locationLabel, String keyPath, List<StartupItem> items, boolean activeDefault) {
        String fullPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\" + keyPath.substring(keyPath.lastIndexOf('\\') + 1);
        String approvedPath = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\" + keyPath.substring(keyPath.lastIndexOf('\\') + 1);
        scanRegistryUnified(hive, locationLabel, fullPath, approvedPath, activeDefault, items);
    }

    public List<StartupItem> listScheduledTasks() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;

        try {
            Path script = PowerShellScripts.resolve("get-startup-details.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
            if (result.success() && result.stdout() != null && !result.stdout().isBlank()) {
                JsonNode root = JsonMapper.parseTree(result.stdout());
                if (root.has("Error") && !root.path("Error").asText("").isBlank()) {
                    String err = root.path("Error").asText("");
                    String msg = "Scheduled Tasks WMI warning: " + err;
                    AppLogger.warning(msg);
                    scanErrors.add("Scheduled Tasks: " + err + " (partial listing, requires admin)");
                }

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
            // Check for JSON-level error (e.g., WMI access denied without process failure)
            if (root.has("Error") && !root.path("Error").asText("").isBlank()) {
                String err = root.path("Error").asText("");
                String msg = "Windows Services WMI warning: " + err;
                AppLogger.warning(msg);
                scanErrors.add("Windows Services: " + err + " (partial listing, requires admin)");
            }
            JsonNode servicesNode = root.path("Services");
            Map<String, String> batchNewTypes = new HashMap<>();
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
                    if (!ORIGINAL_SERVICE_START_TYPES.containsKey(serviceName) && !"Disabled".equalsIgnoreCase(startType)) {
                        batchNewTypes.put(serviceName, startType);
                    }
                    if (!deps.isEmpty()) {
                        item.setDependencies(deps);
                    }
                    items.add(item);
                }
                if (!batchNewTypes.isEmpty()) {
                    synchronized (ORIGINAL_SERVICE_START_TYPES) {
                        for (Map.Entry<String, String> e : batchNewTypes.entrySet()) {
                            if (!ORIGINAL_SERVICE_START_TYPES.containsKey(e.getKey())) {
                                ORIGINAL_SERVICE_START_TYPES.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    saveOriginalStartTypes();
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to enumerate Windows services: " + e.getMessage());
            scanErrors.add("Windows Services: Failed to enumerate services: " + e.getMessage());
        }

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    public void toggleStatus(StartupItem item) throws Exception {
        if (item.getType() == StartupItemType.TASK) {
            String cmd = item.isEnabled() ? "Disable-ScheduledTask" : "Enable-ScheduledTask";
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    cmd + " -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(item.getTaskPath())));
            if (!result.success()) {
                String err = result.combinedOutput();
                String lower = err.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("access") || lower.contains("denied") || lower.contains("privileg") || err.contains("740") || err.contains("577")) {
                    throw new IOException("Access denied. Please run as administrator to modify scheduled tasks. Details: " + err);
                }
                throw new IOException("Failed to toggle Scheduled Task: " + err);
            }
            item.setEnabled(!item.isEnabled());
        } else if (item.getType() == StartupItemType.REGISTRY) {
            String location = item.getLocation();
            // Startup Folder items (merged) – toggle by renaming file
            if (location != null && location.startsWith("Startup Folder")) {
                boolean success = toggleStartupFolderItem(item);
                if (!success) {
                    throw new IOException("Failed to toggle startup folder item: file '" + item.getName() + "' may have been deleted externally.");
                }
                item.setEnabled(!item.isEnabled());
                if (item.isEnabled() && location.contains("(Disabled)")) {
                    item.setLocation(location.replace(" (Disabled)", ""));
                } else if (!item.isEnabled() && !location.contains("(Disabled)")) {
                    item.setLocation(location + " (Disabled)");
                }
            } else {
                RegistryPaths paths = resolveRegistryPaths(item);
                String valName = item.getRegistryValueName();

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
                if (item.isEnabled() && item.getLocation().contains("Run (Disabled)")) {
                    item.setLocation(item.getLocation().replace("Run (Disabled)", "Run"));
                }
            }
        } else if (item.getType() == StartupItemType.SERVICE) {
            String serviceName = item.getName();
            String scConfigArg;
            String newStartType;
            if (item.isEnabled()) {
                // Save original before disabling if not yet saved (and not Disabled)
                if (!ORIGINAL_SERVICE_START_TYPES.containsKey(serviceName) && item.getServiceStartType() != null
                        && !"Disabled".equalsIgnoreCase(item.getServiceStartType())) {
                    recordServiceStartType(serviceName, item.getServiceStartType());
                }
                scConfigArg = "start= disabled";
                newStartType = "Disabled";
            } else {
                String original = item.getOriginalServiceStartType();
                if (original == null || original.isBlank() || "Disabled".equalsIgnoreCase(original)) {
                    original = "Manual";
                }
                scConfigArg = "start= " + startTypeToScArg(original);
                newStartType = original;
            }

            ProcessResult result = processRunner.run(List.of("sc.exe", "config", serviceName, scConfigArg));
            if (!result.success()) {
                String errMsg = result.combinedOutput();
                String lower = errMsg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("577") || lower.contains("access") || lower.contains("denied") || lower.contains("privileg") || lower.contains("740")) {
                    throw new IOException("Access denied. Please run as administrator to modify service start types. Details: " + errMsg);
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

        createBackup(item);

        if (item.getType() == StartupItemType.TASK) {
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-Command",
                    "Unregister-ScheduledTask -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(item.getTaskPath()) + " -Confirm:$false"));
            if (!result.success()) {
                throw new IOException("Failed to delete Scheduled Task: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            String location = item.getLocation();
            if (location != null && location.startsWith("Startup Folder")) {
                // Delete file (or .disabled variant)
                Path p = item.getFilePath() != null && !item.getFilePath().isBlank() ? Path.of(item.getFilePath()) : null;
                if (p != null) {
                    Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
                    if (Files.exists(p)) {
                        Files.deleteIfExists(p);
                    } else if (Files.exists(disabled)) {
                        Files.deleteIfExists(disabled);
                    } else {
                        // Try alternative name without extension handling
                        Path alt = Path.of(item.getPath());
                        if (alt != null && Files.exists(alt)) Files.deleteIfExists(alt);
                    }
                }
                // Clear approved entry if any (should not exist for folder)
            } else {
                RegistryPaths paths = resolveRegistryPaths(item);
                String valName = item.getRegistryValueName();

                // A disabled item can live in RunDisabled or Run (RunOnce disabled); check all relevant keys
                if (location.contains("(Disabled)")) {
                    List<String> valueKeys;
                    boolean is32 = location.contains("32-bit");
                    boolean isRunOnceDisabled = location.contains("Run (Disabled)") && item.getPath() != null && !item.getPath().isBlank() && location.contains("Run (Disabled)");
                    // For simplicity check all possible locations for this hive
                    if (is32) {
                        valueKeys = List.of(REG_WOW6432_RUN_DISABLED, REG_WOW6432_RUN, REG_WOW6432_RUN_ONCE);
                    } else {
                        valueKeys = List.of(REG_RUN_DISABLED, REG_RUN, REG_RUN_ONCE);
                    }
                    for (String keyPath : valueKeys) {
                        try {
                            if (Advapi32Util.registryValueExists(paths.hive(), keyPath, valName)) {
                                Advapi32Util.registryDeleteValue(paths.hive(), keyPath, valName);
                            }
                        } catch (Exception ignored) {}
                    }
                    // Clean both approved paths
                    for (String ap : List.of(StartupConstants.REG_STARTUP_APPROVED, StartupConstants.REG_STARTUP_APPROVED_RUNONCE,
                            StartupConstants.REG_WOW6432_APPROVED, StartupConstants.REG_WOW6432_APPROVED_RUNONCE)) {
                        try {
                            if (Advapi32Util.registryValueExists(paths.hive(), ap, valName)) {
                                Advapi32Util.registryDeleteValue(paths.hive(), ap, valName);
                            }
                        } catch (Exception ignored) {}
                    }
                } else {
                    try {
                        if (Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName)) {
                            Advapi32Util.registryDeleteValue(paths.hive(), paths.keyPath(), valName);
                        }
                    } catch (Exception ignored) {}
                    String approved = StartupConstants.toApprovedPath(paths.keyPath());
                    try {
                        if (Advapi32Util.registryValueExists(paths.hive(), approved, valName)) {
                            Advapi32Util.registryDeleteValue(paths.hive(), approved, valName);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }

        invalidateCache();
    }

    private void toggleRegularItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
            Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
        }
        if (item.isEnabled()) {
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, StartupConstants.disabledBytes());
        } else {
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, StartupConstants.enabledBytes());
        }
    }

    private boolean toggleDisabledItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        String location = item.getLocation();
        boolean is32bit = location.contains("32-bit");
        String enableKeyPath = is32bit ? REG_WOW6432_RUN : REG_RUN;
        String disabledKeyPath = is32bit ? REG_WOW6432_RUN_DISABLED : REG_RUN_DISABLED;

        if (item.isEnabled()) {
            if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
                Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
            }
            Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, StartupConstants.disabledBytes());
            return true;
        }

        // Enable: the value can live in RunDisabled (disabled by the OS or other tools)
        // or in Run (RunOnce items disabled via toggleRunOnceItem).
        String cmd = getRegistryString(paths.hive(), disabledKeyPath, valName);
        boolean inRun = false;
        if (cmd == null) {
            cmd = getRegistryString(paths.hive(), enableKeyPath, valName);
            inRun = cmd != null;
        }
        if (cmd == null) {
            return false;
        }

        if (!inRun) {
            if (!Advapi32Util.registryKeyExists(paths.hive(), enableKeyPath)) {
                Advapi32Util.registryCreateKey(paths.hive(), enableKeyPath);
            }
            Advapi32Util.registrySetStringValue(paths.hive(), enableKeyPath, valName, cmd);
            Advapi32Util.registryDeleteValue(paths.hive(), disabledKeyPath, valName);
        }
        if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
            Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
        }
        Advapi32Util.registrySetBinaryValue(paths.hive(), paths.approvedPath(), valName, StartupConstants.enabledBytes());
        return true;
    }

    private boolean toggleRunOnceItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        String location = item.getLocation();
        boolean is32bit = location.contains("32-bit");
        String runKeyPath = is32bit ? REG_WOW6432_RUN : REG_RUN;
        String runOnceKeyPath = is32bit ? REG_WOW6432_RUN_ONCE : REG_RUN_ONCE;
        String runApprovedPath = is32bit ? REG_WOW6432_APPROVED : REG_STARTUP_APPROVED;
        String runOnceApprovedPath = is32bit ? REG_WOW6432_APPROVED_RUNONCE : REG_STARTUP_APPROVED_RUNONCE;

        // Fix B1: Do not move values between Run and RunOnce. Use StartupApproved\RunOnce bytes only.
        // This preserves RunOnce semantics (run once vs every logon). Legacy corrupted entries that were
        // previously moved to Run are healed: if value is in Run but location indicates RunOnce, move back.
        String cmdInRun = getRegistryString(paths.hive(), runKeyPath, valName);
        String cmdInRunOnce = getRegistryString(paths.hive(), runOnceKeyPath, valName);
        // Heal legacy: value in Run but not in RunOnce and location is RunOnce (should be RunOnce)
        if (cmdInRun != null && cmdInRunOnce == null && location.contains("RunOnce")) {
            // Check if approved indicates disabled RunOnce that was moved
            boolean legacyMoved = false;
            try {
                if (Advapi32Util.registryValueExists(paths.hive(), runApprovedPath, valName)) {
                    Object v = Advapi32Util.registryGetValue(paths.hive(), runApprovedPath, valName);
                    if (v instanceof byte[] b && b.length > 0 && b[0] == 0x03) legacyMoved = true;
                }
            } catch (Exception ignored) {}
            if (legacyMoved) {
                if (!Advapi32Util.registryKeyExists(paths.hive(), runOnceKeyPath)) {
                    Advapi32Util.registryCreateKey(paths.hive(), runOnceKeyPath);
                }
                Advapi32Util.registrySetStringValue(paths.hive(), runOnceKeyPath, valName, cmdInRun);
                Advapi32Util.registryDeleteValue(paths.hive(), runKeyPath, valName);
                if (Advapi32Util.registryValueExists(paths.hive(), runApprovedPath, valName)) {
                    Advapi32Util.registryDeleteValue(paths.hive(), runApprovedPath, valName);
                }
                // After heal, the approved path to toggle is RunOnce
                paths = new RegistryPaths(paths.hive(), runOnceKeyPath, runOnceApprovedPath);
                cmdInRunOnce = cmdInRun;
            }
        }

        // Determine correct approved path: prefer the one matching actual key existence, fallback to RunOnce
        String approvedPath;
        if (Advapi32Util.registryValueExists(paths.hive(), runOnceKeyPath, valName)) {
            approvedPath = runOnceApprovedPath;
        } else if (Advapi32Util.registryValueExists(paths.hive(), runKeyPath, valName) && !location.contains("RunOnce")) {
            approvedPath = runApprovedPath;
        } else {
            approvedPath = paths.approvedPath();
            // Ensure it's RunOnce approved for RunOnce locations
            if (location.contains("RunOnce")) approvedPath = runOnceApprovedPath;
            else if (is32bit) approvedPath = runOnceApprovedPath.contains("Wow6432Node") ? runOnceApprovedPath : runApprovedPath;
        }
        // Normalize to RunOnce approved if location indicates RunOnce
        if (location.contains("RunOnce")) approvedPath = runOnceApprovedPath;

        if (!Advapi32Util.registryKeyExists(paths.hive(), approvedPath)) {
            Advapi32Util.registryCreateKey(paths.hive(), approvedPath);
        }
        if (item.isEnabled()) {
            Advapi32Util.registrySetBinaryValue(paths.hive(), approvedPath, valName, StartupConstants.disabledBytes());
        } else {
            Advapi32Util.registrySetBinaryValue(paths.hive(), approvedPath, valName, StartupConstants.enabledBytes());
        }
        // Keep location stable (RunOnce), do not mutate to Run (Disabled)
        return true;
    }

    private boolean toggleStartupFolderItem(StartupItem item) throws Exception {
        String filePath = item.getFilePath();
        if (filePath == null || filePath.isBlank()) {
            // Fallback use path
            filePath = item.getPath();
            if (filePath == null || filePath.isBlank()) return false;
        }
        Path p = Path.of(filePath);
        Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
        if (item.isEnabled()) {
            if (!Files.exists(p)) return false;
            Files.move(p, disabled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return true;
        } else {
            // Enable: look for .disabled file
            if (Files.exists(disabled)) {
                Files.move(disabled, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return true;
            }
            if (Files.exists(p)) {
                // Already enabled but location said disabled
                return true;
            }
            return false;
        }
    }

    private static String getRegistryString(HKEY hive, String keyPath, String valueName) {
        if (!Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
            return null;
        }
        Object valData = Advapi32Util.registryGetValue(hive, keyPath, valueName);
        return valData instanceof String s ? s : null;
    }

    // ── Backup / Restore Mechanism ────────────────────────────────────────────

    public Path getBackupsDir() {
        // Portable-aware: prefer portable dir if available and writable
        Path portable = AppPaths.portableBaseDir();
        if (portable != null) {
            try {
                Path portableBackups = portable.resolve("startup-backups");
                Files.createDirectories(portableBackups);
                if (Files.isWritable(portableBackups)) {
                    return portableBackups;
                }
            } catch (Exception ignored) {}
        }
        return AppPaths.localAppData().resolve("startup-backups");
    }

    private Path getBackupsIndexFile() {
        return getBackupsDir().resolve("index.json");
    }

    public List<StartupBackupEntry> listBackups() throws IOException {
        backupIndexLock.lock();
        try {
            Path indexFile = getBackupsIndexFile();
            if (!Files.exists(indexFile)) {
                return new ArrayList<>();
            }
            CollectionType listType = JsonMapper.mapper().getTypeFactory()
                    .constructCollectionType(ArrayList.class, StartupBackupEntry.class);
            return JsonMapper.mapper().readValue(indexFile.toFile(), listType);
        } finally {
            backupIndexLock.unlock();
        }
    }

    private void saveBackupsIndex(List<StartupBackupEntry> list) throws IOException {
        // Caller must hold backupIndexLock
        Files.createDirectories(getBackupsDir());
        Path indexFile = getBackupsIndexFile();
        Path tmp = indexFile.resolveSibling("." + indexFile.getFileName() + ".tmp");
        JsonMapper.mapper().writerWithDefaultPrettyPrinter()
                .writeValue(tmp.toFile(), list);
        try {
            Files.move(tmp, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, indexFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void createBackup(StartupItem item) throws Exception {
        String backupId = UUID.randomUUID().toString();
        Path backupFolder = getBackupsDir().resolve(backupId);
        Files.createDirectories(backupFolder);
        boolean backupSucceeded = false;
        try {

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
            String location = item.getLocation();
            if (location != null && location.startsWith("Startup Folder")) {
                entry.setType("Folder");
                entry.setHive(location.contains("(Common)") ? "COMMON" : "USER");
                entry.setKeyPath(item.getFilePath() != null ? item.getFilePath() : item.getPath());
                entry.setValueName(item.getRegistryValueName());
                // Copy file to backup
                String fp = item.getFilePath();
                Path src = null;
                if (fp != null && !fp.isBlank()) {
                    Path p = Path.of(fp);
                    Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
                    if (Files.exists(p)) src = p;
                    else if (Files.exists(disabled)) src = disabled;
                }
                if (src != null && Files.exists(src)) {
                    Path dest = backupFolder.resolve(src.getFileName().toString());
                    Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    entry.setBackupXmlName(src.getFileName().toString());
                    entry.setCommand(src.toAbsolutePath().toString());
                }
                // Also handle .disabled variant already
            } else {
                entry.setType("Registry");
                RegistryPaths paths = resolveRegistryPaths(item);
                entry.setHive(paths.hive() == WinReg.HKEY_CURRENT_USER ? "HKCU" : "HKLM");
                entry.setKeyPath(paths.keyPath());
                entry.setValueName(item.getRegistryValueName());
            }
        }

        backupIndexLock.lock();
        try {
            // Avoid nested lock deadlock – read without acquiring again by direct file read
            List<StartupBackupEntry> index;
            Path indexFile = getBackupsIndexFile();
            if (!Files.exists(indexFile)) {
                index = new ArrayList<>();
            } else {
                CollectionType listType = JsonMapper.mapper().getTypeFactory()
                        .constructCollectionType(ArrayList.class, StartupBackupEntry.class);
                index = JsonMapper.mapper().readValue(indexFile.toFile(), listType);
            }
            index.add(entry);
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
            backupSucceeded = true;
        } catch (Exception e) {
            if (!backupSucceeded) {
                try { deleteDirectoryRecursively(backupFolder); } catch (Exception ignored) {}
            }
            throw e;
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

            String approvedKeyPath = StartupConstants.toApprovedPath(entry.getKeyPath());
            if (!approvedKeyPath.equals(entry.getKeyPath())) {
                if (!Advapi32Util.registryKeyExists(hive, approvedKeyPath)) {
                    Advapi32Util.registryCreateKey(hive, approvedKeyPath);
                }
                byte[] approvedBytes = entry.isEnabled() ? StartupConstants.enabledBytes() : StartupConstants.disabledBytes();
                Advapi32Util.registrySetBinaryValue(hive, approvedKeyPath, entry.getValueName(), approvedBytes);
            }
        } else if ("Folder".equals(entry.getType())) {
            // Restore startup folder file
            String backupXml = entry.getBackupXmlName();
            if (backupXml == null || backupXml.isBlank()) backupXml = entry.getValueName();
            Path src = backupFolder.resolve(backupXml);
            if (!Files.exists(src)) {
                // Try alternative – maybe backup folder contains the file
                try (var s = Files.list(backupFolder)) {
                    var opt = s.filter(Files::isRegularFile).findFirst();
                    if (opt.isPresent()) src = opt.get();
                }
            }
            if (src != null && Files.exists(src)) {
                Path dest = Path.of(entry.getKeyPath());
                // dest is original file path (maybe without .disabled)
                Files.createDirectories(dest.getParent());
                Files.copy(src, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                // If backup was enabled=true we leave as is; if disabled, rename to .disabled? Original file had enabled state; folder item enabled means file exists without .disabled
                // Ensure correct enabled state – if backup says disabled, need to disable after restore
                if (!entry.isEnabled()) {
                    Path disabled = dest.resolveSibling(dest.getFileName() + ".disabled");
                    if (Files.exists(dest)) Files.move(dest, disabled, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                throw new FileNotFoundException("Backup file missing for startup folder item: " + backupXml);
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

        // Update index first, then delete folder – ensures index/folder consistency on failure
        backupIndexLock.lock();
        try {
            Path indexFile = getBackupsIndexFile();
            List<StartupBackupEntry> index;
            if (!Files.exists(indexFile)) index = new ArrayList<>();
            else {
                CollectionType listType = JsonMapper.mapper().getTypeFactory()
                        .constructCollectionType(ArrayList.class, StartupBackupEntry.class);
                index = JsonMapper.mapper().readValue(indexFile.toFile(), listType);
            }
            index.removeIf(e -> e.getId().equals(entry.getId()));
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
        try {
            deleteDirectoryRecursively(backupFolder);
        } catch (Exception ex) {
            AppLogger.warning("Failed to delete backup folder after restore: " + ex.getMessage());
        }
    }

    public void removeBackup(StartupBackupEntry entry) throws IOException {
        Path backupFolder = getBackupsDir().resolve(entry.getId());
        // Remove from index first for consistency
        backupIndexLock.lock();
        try {
            Path indexFile = getBackupsIndexFile();
            List<StartupBackupEntry> index;
            if (!Files.exists(indexFile)) index = new ArrayList<>();
            else {
                CollectionType listType = JsonMapper.mapper().getTypeFactory()
                        .constructCollectionType(ArrayList.class, StartupBackupEntry.class);
                index = JsonMapper.mapper().readValue(indexFile.toFile(), listType);
            }
            index.removeIf(e -> e.getId().equals(entry.getId()));
            saveBackupsIndex(index);
        } finally {
            backupIndexLock.unlock();
        }
        try {
            deleteDirectoryRecursively(backupFolder);
        } catch (Exception ex) {
            AppLogger.warning("Failed to delete backup folder: " + ex.getMessage());
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

    // ── Startup Folder Support (merged into REGISTRY) ─────────────────────────

    private List<StartupItem> listStartupFolderItems() {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;
        // User startup folder
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            Path userStartup = Path.of(appData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
            scanStartupFolder(userStartup, "Startup Folder (User)", items);
        }
        // Common startup folder
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            Path commonStartup = Path.of(programData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup");
            scanStartupFolder(commonStartup, "Startup Folder (Common)", items);
        } else {
            // Fallback via known path
            String windir = System.getenv("WINDIR");
            if (windir != null) {
                Path commonAlt = Path.of(windir).getParent().resolve("ProgramData/Microsoft/Windows/Start Menu/Programs/Startup");
                scanStartupFolder(commonAlt, "Startup Folder (Common)", items);
            }
        }
        return items;
    }

    private void scanStartupFolder(Path folder, String locationLabel, List<StartupItem> items) {
        try {
            if (!Files.isDirectory(folder)) return;
            List<Path> allPaths;
            try (var stream = Files.list(folder)) {
                allPaths = stream.filter(p -> !Files.isDirectory(p)).toList();
            }
            // Batch resolve .lnk targets in single PS invocation to avoid per-file process spawn
            Map<String, String> lnkTargetCache = new HashMap<>();
            List<Path> lnkPaths = new ArrayList<>();
            for (Path p : allPaths) {
                if (Thread.currentThread().isInterrupted()) return;
                String fn = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (fn.equals("desktop.ini") || fn.startsWith(".")) continue;
                boolean isDisabled = fn.endsWith(".disabled");
                String eff = isDisabled ? fn.substring(0, fn.length() - ".disabled".length()) : fn;
                if (eff.endsWith(".lnk")) {
                    lnkPaths.add(p);
                } else if (isDisabled && fn.endsWith(".lnk.disabled")) {
                    lnkPaths.add(p);
                }
            }
            if (!lnkPaths.isEmpty()) {
                try {
                    lnkTargetCache.putAll(batchResolveLnkTargets(lnkPaths));
                } catch (Exception e) {
                    AppLogger.warning("Batch .lnk resolve failed: " + e.getMessage());
                }
            }
            for (Path p : allPaths) {
                try {
                        if (Thread.currentThread().isInterrupted()) return;
                        if (Files.isDirectory(p)) continue;
                        String fileName = p.getFileName().toString();
                        String lower = fileName.toLowerCase(Locale.ROOT);
                        // Skip hidden/system files like desktop.ini
                        if (lower.equals("desktop.ini") || fileName.startsWith(".")) continue;
                        boolean isDisabledFile = lower.endsWith(".disabled");
                        String effectiveFileName = fileName;
                        String displayLocation = locationLabel;
                        boolean enabled = true;
                        Path effectivePath = p;
                        if (isDisabledFile) {
                            enabled = false;
                            displayLocation = locationLabel + " (Disabled)";
                            // Strip .disabled suffix for display name
                            effectiveFileName = fileName.substring(0, fileName.length() - ".disabled".length());
                            // The actual file path to store as enabled path (without .disabled) for toggle handling
                            // Keep disabled path as p, but filePath field should be enabled path
                            effectivePath = p.getParent().resolve(effectiveFileName);
                            // For disabled items, try to resolve target via disabled file
                            // but fallback to effective path
                        }
                        String lowerEff = effectiveFileName.toLowerCase(Locale.ROOT);
                        // If .disabled file, effectiveFileName may still end with .lnk
                        String targetPath = null;
                        if (lowerEff.endsWith(".lnk") || isDisabledFile) {
                            // Resolve using batch cache
                            String cached = lnkTargetCache.get(p.toAbsolutePath().toString());
                            if (cached != null && !cached.isBlank()) targetPath = cached;
                            else targetPath = lnkTargetCache.get(p.toString());
                            if (targetPath == null || targetPath.isBlank()) {
                                // Fallback per-file (should rarely happen)
                                targetPath = resolveLnkTarget(p);
                            }
                        }
                        if (targetPath == null || targetPath.isBlank()) {
                            // For non-lnk or failed resolve, use effective path
                            targetPath = effectivePath.toAbsolutePath().toString();
                        }
                        String exeForPublisher = extractExecutablePath(targetPath);
                        if (exeForPublisher.isBlank()) exeForPublisher = targetPath;
                        String publisher = getCompanyName(exeForPublisher);
                        if (publisher == null || publisher.isBlank()) publisher = "Unknown";
                        String itemName = effectiveFileName;
                        int dot = itemName.lastIndexOf('.');
                        if (dot > 0 && lowerEff.endsWith(".lnk")) {
                            itemName = itemName.substring(0, dot);
                        }
                        // Handle double extension .lnk.disabled -> already stripped disabled, now strip .lnk
                        if (itemName.toLowerCase(Locale.ROOT).endsWith(".lnk")) {
                            itemName = itemName.substring(0, itemName.length() - 4);
                        }
                        // For display, keep original effective name without path
                        // filePath stored as enabled path (without .disabled) so toggle can find it
                        String storedFilePath = effectivePath.toAbsolutePath().toString();
                        items.add(new StartupItem(
                                itemName,
                                publisher,
                                targetPath,
                                enabled,
                                displayLocation,
                                effectiveFileName,
                                storedFilePath,
                                "",
                                StartupItemType.REGISTRY,
                                null
                        ));
                    } catch (Exception e) {
                        AppLogger.warning("Failed to scan startup folder entry " + p + ": " + e.getMessage());
                    }
            }
        } catch (Exception e) {
            String msg = "Failed to scan startup folder " + folder + ": " + e.getMessage();
            AppLogger.warning(msg);
            scanErrors.add(locationLabel + ": " + msg);
        }
    }

    private static Map<String, String> batchResolveLnkTargets(List<Path> lnkPaths) {
        Map<String, String> result = new HashMap<>();
        if (lnkPaths == null || lnkPaths.isEmpty()) return result;
        if (Thread.currentThread().isInterrupted()) return result;
        try {
            // Build single PS script to resolve all shortcuts in one process
            StringBuilder sb = new StringBuilder();
            sb.append("$sh = New-Object -COM WScript.Shell; $res=@(); ");
            sb.append("$paths = @(");
            for (int i = 0; i < lnkPaths.size(); i++) {
                if (i > 0) sb.append(",");
                String p = lnkPaths.get(i).toAbsolutePath().toString().replace("'", "''");
                sb.append("'").append(p).append("'");
            }
            sb.append("); ");
            sb.append("foreach($p in $paths){ try{ $sc=$sh.CreateShortcut($p); $t=$sc.TargetPath; $a=$sc.Arguments; if($a){$t=\"$t $a\"} $res+=@{Path=$p; Target=$t} } catch{ $res+=@{Path=$p; Target=''} } } ");
            sb.append("$res | ConvertTo-Json -Depth 3");
            ProcessResult r = new ProcessRunner(15).run(List.of("powershell.exe", "-NoProfile", "-Command", sb.toString()));
            if (r.success() && r.stdout() != null && !r.stdout().isBlank()) {
                String out = r.stdout().trim();
                try {
                    JsonNode arr = JsonMapper.parseTree(out);
                    if (arr.isArray()) {
                        for (JsonNode node : arr) {
                            String path = node.path("Path").asText("");
                            String target = node.path("Target").asText("");
                            if (!path.isBlank() && target != null && !target.isBlank()) {
                                result.put(path, target);
                                // Also put without absolute to handle both lookups
                                try { result.put(Path.of(path).toString(), target); } catch (Exception ignored) {}
                            }
                        }
                    } else if (arr.isObject()) {
                        // Single object case (only one lnk)
                        String path = arr.path("Path").asText("");
                        String target = arr.path("Target").asText("");
                        if (!path.isBlank() && target != null && !target.isBlank()) {
                            result.put(path, target);
                        }
                    }
                } catch (Exception e) {
                    AppLogger.warning("Failed to parse batch lnk JSON: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Batch resolve lnk failed: " + e.getMessage());
        }
        return result;
    }

    private static String resolveLnkTarget(Path lnk) {
        String fileName = lnk.getFileName().toString();
        String lower = fileName.toLowerCase(Locale.ROOT);
        // Handle .lnk.disabled files – strip .disabled for check
        if (lower.endsWith(".lnk.disabled")) {
            lower = lower.substring(0, lower.length() - ".disabled".length());
        }
        if (!lower.endsWith(".lnk")) return null;
        // Try PowerShell COM for .lnk target resolution
        try {
            String ps = "$sh = New-Object -COM WScript.Shell; $sc = $sh.CreateShortcut('"
                    + lnk.toAbsolutePath().toString().replace("'", "''")
                    + "'); Write-Output $sc.TargetPath";
            ProcessResult r = new ProcessRunner(10).run(List.of("powershell.exe", "-NoProfile", "-Command", ps));
            if (r.success() && r.stdout() != null && !r.stdout().isBlank()) {
                String target = r.stdout().trim().split("\\R")[0].trim();
                if (!target.isBlank()) {
                    String args = "";
                    try {
                        String psArgs = "$sh = New-Object -COM WScript.Shell; $sc = $sh.CreateShortcut('"
                                + lnk.toAbsolutePath().toString().replace("'", "''")
                                + "'); Write-Output $sc.Arguments";
                        ProcessResult ra = new ProcessRunner(10).run(List.of("powershell.exe", "-NoProfile", "-Command", psArgs));
                        if (ra.success() && ra.stdout() != null && !ra.stdout().isBlank()) {
                            args = ra.stdout().trim().split("\\R")[0].trim();
                        }
                    } catch (Exception ignored) {}
                    if (!args.isBlank()) target = target + " " + args;
                    return target;
                }
            }
        } catch (Exception ignored) {}
        // Fallback: binary .lnk parsing is complex – return null to use path itself
        return null;
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

    public static String expandEnvVars(String s) {
        if (s == null || s.isBlank()) return s;
        // Expand %VAR% patterns using System.getenv
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("%([^%]+)%").matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String var = m.group(1);
            String val = System.getenv(var);
            if (val == null) val = System.getenv(var.toUpperCase(Locale.ROOT));
            if (val == null) val = m.group(0);
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(val));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String extractExecutablePath(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String expanded = expandEnvVars(command);
        String trimmed = expanded.trim();
        // Strip common prefixes: cmd /c, rundll32, etc. – take first quoted or exe pattern
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("rundll32")) {
            // rundll32 <dll>,EntryPoint ... – extract dll
            java.util.regex.Matcher rm = java.util.regex.Pattern.compile("rundll32\\s+\"?([^\"\\s]+\\.(dll|exe))", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(trimmed);
            if (rm.find()) return rm.group(1);
        }
        if (lower.startsWith("\"rundll32") || lower.contains("rundll32")) {
            // generic fallback
        }

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
        StringBuilder sb2 = new StringBuilder();
        for (String part : parts) {
            if (sb2.length() > 0) sb2.append(" ");
            sb2.append(part);
            java.io.File file = new java.io.File(sb2.toString());
            if (file.exists() && file.isFile()) {
                return sb2.toString();
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
