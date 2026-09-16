package com.sbtools.startup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.sbtools.backup.BackupHealth;
import com.sbtools.util.*;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.Version;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

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
        // Preserves REG_SZ vs REG_EXPAND_SZ across backup/restore (null = legacy backup, assume REG_SZ)
        private String registryValueType;

        // Task specific
        private String taskPath;
        private String backupXmlName;
        // Captured from exported task XML so restore can warn when credentials are required.
        private String taskPrincipalUserId;
        private String taskLogonType;

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
        public String getRegistryValueType() { return registryValueType; }
        public void setRegistryValueType(String registryValueType) { this.registryValueType = registryValueType; }
        public String getTaskPath() { return taskPath; }
        public void setTaskPath(String taskPath) { this.taskPath = taskPath; }
        public String getBackupXmlName() { return backupXmlName; }
        public void setBackupXmlName(String backupXmlName) { this.backupXmlName = backupXmlName; }
        public String getTaskPrincipalUserId() { return taskPrincipalUserId; }
        public void setTaskPrincipalUserId(String v) { this.taskPrincipalUserId = v; }
        public String getTaskLogonType() { return taskLogonType; }
        public void setTaskLogonType(String v) { this.taskLogonType = v; }
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getBackupTime() { return backupTime; }
        public void setBackupTime(long backupTime) { this.backupTime = backupTime; }
    }

    public List<StartupItem> listAll() {
        scanErrors.clear();
        return collectAllSequential(() -> false);
    }

    /**
     * Sequential scan phases. Does not clear {@link #scanErrors}; used by parallel fallback
     * so partial-failure warnings from the parallel attempt are preserved.
     */
    private List<StartupItem> collectAllSequential(BooleanSupplier abortScan) {
        List<StartupItem> items = new ArrayList<>();
        if (!AppPaths.isWindows()) return items;
        if (shouldAbortSequentialScan(abortScan)) return items;

        loadOriginalStartTypes();
        items.addAll(listRegistryApps());
        if (shouldAbortSequentialScan(abortScan)) return items;

        items.addAll(listScheduledTasks());
        if (shouldAbortSequentialScan(abortScan)) return items;

        items.addAll(listWindowsServices());
        if (shouldAbortSequentialScan(abortScan)) return items;

        items.addAll(listStartupFolderItems());

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    private static boolean shouldAbortSequentialScan(BooleanSupplier abortScan) {
        if (Thread.currentThread().isInterrupted()) {
            return true;
        }
        return abortScan != null && abortScan.getAsBoolean();
    }

    /**
     * Parallelized version of listAll(). Scans registry, scheduled tasks, services and startup folder concurrently.
     * Uses shared scanPool to avoid per-scan thread creation; timeouts are applied per-future.
     * Honors thread interruption so the UI Stop button can cancel promptly.
     */
    public List<StartupItem> listAllParallel() {
        return listAllParallel(() -> false);
    }

    /**
     * @param abortScan when true, never falls back to sequential {@link #listAll()} and returns promptly
     */
    public List<StartupItem> listAllParallel(BooleanSupplier abortScan) {
        if (!AppPaths.isWindows()) return Collections.emptyList();

        scanErrors.clear();
        loadOriginalStartTypes();
        long startNanos = System.nanoTime();
        ExecutorService ex = AppExecutors.scanPool();
        // Visible to catch so a cancel during submit still reaps started phases.
        List<Future<List<StartupItem>>> futures = new ArrayList<>();
        try {
            List<Callable<List<StartupItem>>> tasks = Arrays.asList(
                    this::listRegistryApps,
                    this::listScheduledTasks,
                    this::listWindowsServices,
                    this::listStartupFolderItems
            );

            // Submit all and wait with timeout 60s total
            for (Callable<List<StartupItem>> t : tasks) {
                if (abortScan != null && abortScan.getAsBoolean()) {
                    throw new InterruptedException("Startup scan cancelled before submit");
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Startup scan cancelled before submit");
                }
                futures.add(ex.submit(t));
            }
            List<StartupItem> items = new ArrayList<>();
            String[] scanNames = {"Registry", "Scheduled Tasks", "Windows Services", "Startup Folder"};
            long[] phaseMs = new long[scanNames.length];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            for (int i = 0; i < futures.size(); i++) {
                if (abortScan != null && abortScan.getAsBoolean()) {
                    cancelAll(futures);
                    Thread.currentThread().interrupt();
                    return items;
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                    scanErrors.add(scanNames[i] + ": timed out after 60s (partial listing)");
                    futures.get(i).cancel(true);
                    continue;
                }
                Future<List<StartupItem>> f = futures.get(i);
                try {
                    long phaseStart = System.nanoTime();
                    List<StartupItem> part = f.get(remaining, TimeUnit.NANOSECONDS);
                    phaseMs[i] = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - phaseStart);
                    if (part != null) items.addAll(part);
                } catch (TimeoutException e) {
                    f.cancel(true);
                    AppLogger.warning("Startup scan timed out: " + scanNames[i]);
                    scanErrors.add(scanNames[i] + ": timed out (partial listing)");
                } catch (CancellationException e) {
                    AppLogger.warning("Startup scan cancelled: " + scanNames[i]);
                    scanErrors.add(scanNames[i] + ": scan stopped by user (partial listing)");
                    cancelAll(futures);
                    Thread.currentThread().interrupt();
                    return items;
                } catch (ExecutionException e) {
                    AppLogger.error("Startup scan failed: " + scanNames[i], e);
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    scanErrors.add(scanNames[i] + ": " + cause.getMessage() + " (partial listing)");
                } catch (InterruptedException e) {
                    // Stop pressed: cancel still-running phases (incl. child PS
                    // processes via thread interrupt) and return what we have.
                    // Never fall back to sequential listAll() here — that would
                    // restart a full scan on the interrupted thread and defeat Stop.
                    cancelAll(futures);
                    Thread.currentThread().interrupt();
                    return items;
                }
            }

            items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
            long totalMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            AppLogger.info(String.format("Startup scan complete: %d items in %dms (Registry=%dms, Tasks=%dms, Services=%dms, Folder=%dms)",
                    items.size(), totalMs, phaseMs[0], phaseMs[1], phaseMs[2], phaseMs[3]));
            return items;
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                // Cancelled before/during submit: reap started phases, never restart sequentially.
                cancelAll(futures);
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
            cancelAll(futures);
            if (abortScan != null && abortScan.getAsBoolean()) {
                Thread.currentThread().interrupt();
                return Collections.emptyList();
            }
            if (Thread.currentThread().isInterrupted()) {
                return Collections.emptyList();
            }
            AppLogger.error("Parallel scan failed, falling back to sequential", e);
            scanErrors.add("Startup scan: parallel phase failed, retrying sequentially ("
                    + e.getMessage() + ")");
            return collectAllSequential(abortScan);
        }
    }

    private static void cancelAll(List<Future<List<StartupItem>>> futures) {
        if (futures == null) return;
        for (Future<List<StartupItem>> f : futures) {
            try {
                if (f != null && !f.isDone()) f.cancel(true);
            } catch (Exception ignored) {}
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
                Path backupsDir = StartupBackupValidation.requireRealBackupRoot(getBackupsDir());
                Files.createDirectories(backupsDir);
                Path file = getOriginalStartTypesFile();
                StartupBackupValidation.refuseReparseFile(file, "Original start-types file");
                Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
                StartupBackupValidation.refuseReparseFile(tmp, "Original start-types temp file");
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
        // Probe actual registry to avoid fragile location parsing (fixes legacy RunOnce moved to Run).
        // Order is location-aware: the same value name can exist in both Run and
        // RunOnce as two distinct entries — probing Run first for a RunOnce item
        // would resolve (and later delete) the wrong sibling entry.
        if (valName != null && !valName.isBlank()) {
            String runKey = is32bit ? REG_WOW6432_RUN : REG_RUN;
            String runOnceKey = is32bit ? REG_WOW6432_RUN_ONCE : REG_RUN_ONCE;
            String disabledKey = is32bit ? REG_WOW6432_RUN_DISABLED : REG_RUN_DISABLED;
            String[] candidates;
            if (location.contains("RunOnce")) {
                candidates = new String[]{runOnceKey, runKey, disabledKey};
            } else if (location.contains("(Disabled)")) {
                candidates = new String[]{disabledKey, runKey, runOnceKey};
            } else {
                candidates = new String[]{runKey, disabledKey, runOnceKey};
            }
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

    /** Registry-free fallback mapping from a location label to {keyPath, approvedPath}. */
    private static String[] fallbackPathsForLocation(String location) {
        String loc = location == null ? "" : location;
        boolean is32bit = loc.contains("32-bit");
        boolean isRunOnce = loc.contains("RunOnce");
        boolean isDisabled = loc.contains("(Disabled)");
        if (is32bit) {
            if (isRunOnce) {
                return new String[]{REG_WOW6432_RUN_ONCE, REG_WOW6432_APPROVED_RUNONCE};
            } else if (isDisabled) {
                return new String[]{REG_WOW6432_RUN_DISABLED, REG_WOW6432_APPROVED};
            } else {
                return new String[]{REG_WOW6432_RUN, REG_WOW6432_APPROVED};
            }
        } else {
            if (isRunOnce) {
                return new String[]{REG_RUN_ONCE, REG_STARTUP_APPROVED_RUNONCE};
            } else if (isDisabled) {
                return new String[]{REG_RUN_DISABLED, REG_STARTUP_APPROVED};
            } else {
                return new String[]{REG_RUN, REG_STARTUP_APPROVED};
            }
        }
    }

    /**
     * Writes the StartupApproved state byte while preserving the timestamp tail.
     * Falls back to the fixed 12-byte template when no prior value exists.
     */
    private static void writeApprovedState(HKEY hive, String approvedPath, String valueName, boolean enable) {
        byte[] existing = null;
        try {
            if (Advapi32Util.registryValueExists(hive, approvedPath, valueName)) {
                Object v = Advapi32Util.registryGetValue(hive, approvedPath, valueName);
                if (v instanceof byte[] bytes) {
                    existing = bytes;
                }
            }
        } catch (Exception ignored) {
            // read failure → use template below
        }
        byte[] next = StartupConstants.withStatePreservingTimestamp(existing, enable);
        Advapi32Util.registrySetBinaryValue(hive, approvedPath, valueName, next);
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

        // Stale StartupApproved-only values (no Run value) are Explorer metadata, not
        // executable startup items — intentionally not listed.

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
            // RunOnce has no StartupApproved overlay consulted by Windows — Approved
            // bytes under RunOnce (including legacy orphans from older versions) must
            // be ignored, otherwise we show false "Disabled" while Windows still runs it.
            boolean isRunOnce = StartupConstants.isRunOnceKey(keyPath);
            Map<String, Object> approvedValues = new HashMap<>();
            if (!isRunOnce) {
                try {
                    if (Advapi32Util.registryKeyExists(hive, approvedPath)) {
                        approvedValues.putAll(Advapi32Util.registryGetValues(hive, approvedPath));
                    }
                } catch (Exception ignored) {}
            }

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
                if (!isRunOnce) {
                    Object approvedData = approvedValues.get(valName);
                    if (approvedData instanceof byte[] bytes && bytes.length > 0) {
                        enabled = StartupConstants.isEnabledByte(bytes);
                    }
                } else {
                    // RunOnce always runs once then auto-deletes; cannot be "disabled"
                    // via Approved. Show Enabled so toggle/delete logic stays honest.
                    enabled = true;
                }
                String exePath = extractExecutablePath(cmd);
                String publisher = getCompanyName(exePath);
                if (publisher == null || publisher.isBlank()) publisher = "Unknown";
                items.add(new StartupItem(valName, publisher, cmd, enabled, locationLabel, valName, "", "", StartupItemType.REGISTRY, null));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
            String msg = "Failed to scan registry for " + locationLabel + " " + keyPath + ": " + e.getMessage();
            AppLogger.warning(msg);
            scanErrors.add(locationLabel + ": " + e.getMessage() + " (partial listing)");
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
                String scriptError = root.has("Error") ? root.path("Error").asText("") : "";
                String scriptWarning = root.has("Warning") ? root.path("Warning").asText("") : "";

                JsonNode tasksNode = root.path("ScheduledTasks");
                java.util.List<JsonNode> taskNodes = new java.util.ArrayList<>();
                if (tasksNode.isArray()) {
                    tasksNode.forEach(taskNodes::add);
                } else if (tasksNode.isObject()) {
                    // PowerShell ConvertTo-Json unwraps single-element arrays as a lone object
                    taskNodes.add(tasksNode);
                }
                for (JsonNode node : taskNodes) {
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
                if (!scriptWarning.isBlank()) {
                    AppLogger.warning("Scheduled tasks schtasks fallback: " + scriptWarning);
                    scanErrors.add("Scheduled Tasks: " + scriptWarning);
                } else if (!scriptError.isBlank() && items.isEmpty()) {
                    String msg = "Scheduled Tasks: enumeration failed — " + scriptError
                            + " (no logon/startup tasks could be listed; try Run as administrator)";
                    AppLogger.warning(msg);
                    scanErrors.add(msg);
                } else if (!scriptError.isBlank()) {
                    AppLogger.warning("Scheduled tasks partial warning: " + scriptError);
                    scanErrors.add("Scheduled Tasks: " + scriptError + " (partial listing)");
                }
            } else {
                String msg = "Failed to run scheduled task scan script: " + result.combinedOutput();
                AppLogger.warning(msg);
                scanErrors.add("Scheduled Tasks: " + msg);
            }
        } catch (InterruptedException e) {
            // Stop pressed: preserve interrupt so parallel-scan cancellation works.
            Thread.currentThread().interrupt();
            return items;
        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return items;
            }
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
            java.util.List<JsonNode> serviceNodes = new java.util.ArrayList<>();
            if (servicesNode.isArray()) {
                servicesNode.forEach(serviceNodes::add);
            } else if (servicesNode.isObject()) {
                // PowerShell single-element array unwrapped as object
                serviceNodes.add(servicesNode);
            }
            if (!serviceNodes.isEmpty()) {
                for (JsonNode node : serviceNodes) {
                    String serviceName = node.path("Name").asText("");
                    String displayName = node.path("DisplayName").asText("");
                    String binaryPath = node.path("BinaryPath").asText("");
                    String startType = node.path("StartType").asText("Manual");
                    String state = node.path("State").asText("");
                    // Enabled means "not Disabled" (Automatic* + Manual). Manual is kept
                    // Enabled to preserve toggle semantics (Manual->Disabled on disable,
                    // Disabled->original on enable). Boot impact for Manual is 0 (see
                    // StartupImpactService) so the total is not inflated.
                    boolean enabled = !"Disabled".equalsIgnoreCase(startType);

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
                            startType,
                            getOriginalServiceStartType(serviceName, startType)
                    );
                    item.setServiceState(state);
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return items;
        } catch (Exception e) {
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return items;
            }
            AppLogger.warning("Failed to enumerate Windows services: " + e.getMessage());
            scanErrors.add("Windows Services: Failed to enumerate services: " + e.getMessage());
        }

        items.sort(Comparator.comparing(StartupItem::getName, String.CASE_INSENSITIVE_ORDER));
        return items;
    }

    public void toggleStatus(StartupItem item) throws Exception {
        toggleStatus(item, false);
    }

    /**
     * Toggles an item with explicit critical-service consent.
     *
     * <p>Defense-in-depth: disabling a boot-critical service via
     * {@link StartupSafety#isCriticalDisable} is refused unless
     * {@code allowCriticalDisable} is true. UI must only pass true after an
     * explicit user confirmation (single + bulk dialogs). Future callers using
     * {@link #toggleStatus(StartupItem)} remain safe by default.</p>
     */
    public void toggleStatus(StartupItem item, boolean allowCriticalDisable) throws Exception {
        if (item == null) throw new IllegalArgumentException("Startup item must not be null.");
        if (!allowCriticalDisable && StartupSafety.isCriticalDisable(item)) {
            String risk = StartupSafety.describeRisk(item);
            throw new SecurityException(risk != null ? risk
                    : "Refusing to disable boot-critical service \"" + item.getName() + "\" without explicit confirmation.");
        }
        if (item.getType() == null) throw new IllegalArgumentException("Startup item type must not be null.");
        boolean wasEnabled = item.isEnabled();
        String before = item.getType() + ":" + item.getName() + " enabled=" + wasEnabled;
        if (item.getType() == StartupItemType.TASK) {
            String taskName = item.getName();
            String taskPath = item.getTaskPath();
            if (taskPath == null || taskPath.isBlank()) taskPath = "\\";
            String action = item.isEnabled() ? "Disable" : "Enable";
            Path script = PowerShellScripts.resolve("set-startup-task-state.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                    script.toString(), taskStateArgs(taskName, taskPath, action)));
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
                String loc = location == null ? "" : location;

                boolean success = false;
                if (loc.contains("RunOnce")) {
                    success = toggleRunOnceItem(item, paths);
                } else if (loc.contains("(Disabled)")) {
                    success = toggleDisabledItem(item, paths);
                } else {
                    success = toggleRegularItem(item, paths);
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
            String scStartValue;
            String newStartType;
            if (item.isEnabled()) {
                // Save original before disabling if not yet saved (and not Disabled)
                if (!ORIGINAL_SERVICE_START_TYPES.containsKey(serviceName) && item.getServiceStartType() != null
                        && !"Disabled".equalsIgnoreCase(item.getServiceStartType())) {
                    recordServiceStartType(serviceName, item.getServiceStartType());
                }
                scStartValue = "disabled";
                newStartType = "Disabled";
            } else {
                String original = item.getOriginalServiceStartType();
                if (original == null || original.isBlank() || "Disabled".equalsIgnoreCase(original)) {
                    original = "Manual";
                }
                scStartValue = startTypeToScArg(original);
                newStartType = original;
            }

            ProcessResult result = processRunner.run(scConfigCommand(serviceName, scStartValue));
            String errMsg = result.combinedOutput();
            if (!result.success()) {
                String lower = errMsg.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("577") || lower.contains("access") || lower.contains("denied") || lower.contains("privileg") || lower.contains("740")) {
                    throw new IOException("Access denied. Please run as administrator to modify service start types. Details: " + errMsg);
                }
                throw new IOException("Failed to toggle service start type: " + errMsg);
            }
            Integer applied = queryServiceStartCode(serviceName);
            if (applied == null || !startCodeMatchesScArg(applied, scStartValue)) {
                throw new IOException("Service start type was not applied (SCM reports "
                        + (applied == null ? "unknown" : applied) + ", expected " + scStartValue
                        + "). " + errMsg);
            }

            item.setServiceStartType(newStartType);
            item.setLocation("Start Type: " + newStartType);
            item.setEnabled(!item.isEnabled());
        }
        invalidateCache();
        StartupAuditLog.record(StartupAuditLog.Action.TOGGLE, item,
                "before: " + before + " -> after enabled=" + item.isEnabled());
    }

    public void deleteItem(StartupItem item) throws Exception {
        deleteItem(item, false);
    }

    public void deleteItem(StartupItem item, boolean allowSystemTaskDelete) throws Exception {
        if (item == null) throw new IllegalArgumentException("Startup item must not be null.");
        if (item.getType() == null) throw new IllegalArgumentException("Startup item type must not be null.");
        if (item.getType() == StartupItemType.SERVICE) {
            throw new UnsupportedOperationException("Windows services cannot be deleted.");
        }
        if (item.getType() == StartupItemType.TASK && StartupSafety.isSystemTask(item) && !allowSystemTaskDelete) {
            throw new SecurityException("Refusing to delete system scheduled task \"" + item.getName()
                    + "\" without explicit confirmation.");
        }

        createBackup(item);

        if (item.getType() == StartupItemType.TASK) {
            String tp = item.getTaskPath();
            if (tp == null || tp.isBlank()) tp = "\\";
            ProcessResult result = processRunner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Unregister-ScheduledTask -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(tp) + " -Confirm:$false"));
            if (!result.success()) {
                throw new IOException("Failed to delete Scheduled Task: " + result.combinedOutput());
            }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            String location = item.getLocation();
            if (location != null && location.startsWith("Startup Folder")) {
                deleteStartupFolderItemRequired(item);
            } else {
                RegistryPaths paths = resolveRegistryPaths(item);
                deleteRegistryStartupItem(item, location, paths);
            }
        }

        invalidateCache();
        StartupAuditLog.record(StartupAuditLog.Action.DELETE, item, "backup created automatically");
    }

    private void deleteStartupFolderItemRequired(StartupItem item) throws IOException {
        Path p = StartupBackupValidation.resolveConfinedLiveStartupFile(item.getFilePath());
        Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
        boolean deleted = false;
        try {
            if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                if (BackupHealth.isReparseOrSymlink(p) || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Failed to delete startup folder item: path is not a regular file.");
                }
                Files.delete(p);
                deleted = true;
            } else if (Files.exists(disabled, LinkOption.NOFOLLOW_LINKS)) {
                if (BackupHealth.isReparseOrSymlink(disabled) || Files.isDirectory(disabled, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Failed to delete startup folder item: path is not a regular file.");
                }
                Files.delete(disabled);
                deleted = true;
            }
        } catch (IOException e) {
            throw new IOException("Failed to delete startup folder item '" + item.getName() + "': " + e.getMessage(), e);
        }
        if (!deleted) {
            throw new IOException("Failed to delete startup folder item: file '" + item.getName() + "' was not found.");
        }
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) || Files.exists(disabled, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Startup folder item '" + item.getName() + "' still exists after deletion.");
        }
    }

    private void deleteRegistryStartupItem(StartupItem item, String location, RegistryPaths paths) throws IOException {
        String valName = item.getRegistryValueName();
        if (valName == null || valName.isBlank()) {
            throw new IOException("Failed to delete registry startup item: value name is missing.");
        }
        String loc = location == null ? "" : location;
        if (loc.contains("(Disabled)")) {
            String[] expected = fallbackPathsForLocation(loc);
            String expectedKey = expected[0];
            String primaryKey = null;
            if (registryValueExistsSafe(paths.hive(), expectedKey, valName)) {
                primaryKey = expectedKey;
            } else if (registryValueExistsSafe(paths.hive(), paths.keyPath(), valName)) {
                primaryKey = paths.keyPath();
            }
            if (primaryKey != null) {
                deleteRegistryValueRequired(paths.hive(), primaryKey, valName);
                deleteApprovedBestEffort(paths.hive(), StartupConstants.toApprovedPath(primaryKey), valName);
                return;
            }
            throw new IOException("Failed to delete registry startup item: value '" + valName
                    + "' was not found under Run (Disabled) keys.");
        }
        if (registryValueExistsSafe(paths.hive(), paths.keyPath(), valName)) {
            deleteRegistryValueRequired(paths.hive(), paths.keyPath(), valName);
            if (!StartupConstants.isRunOnceKey(paths.keyPath())) {
                deleteApprovedBestEffort(paths.hive(), StartupConstants.toApprovedPath(paths.keyPath()), valName);
            }
            return;
        }
        throw new IOException("Failed to delete registry startup item: registry value '" + valName
                + "' was not found at " + paths.keyPath() + ".");
    }

    private static boolean registryValueExistsSafe(HKEY hive, String keyPath, String valName) {
        try {
            return Advapi32Util.registryValueExists(hive, keyPath, valName);
        } catch (Exception e) {
            return false;
        }
    }

    private static void deleteRegistryValueRequired(HKEY hive, String keyPath, String valName) throws IOException {
        try {
            if (!Advapi32Util.registryValueExists(hive, keyPath, valName)) {
                return;
            }
            Advapi32Util.registryDeleteValue(hive, keyPath, valName);
        } catch (Exception e) {
            throw new IOException("Failed to delete registry value '" + valName + "' at " + keyPath + ": " + e.getMessage(), e);
        }
        try {
            if (Advapi32Util.registryValueExists(hive, keyPath, valName)) {
                throw new IOException("Registry value '" + valName + "' still exists after deletion (access denied?).");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to verify registry deletion for '" + valName + "': " + e.getMessage(), e);
        }
    }

    private static void deleteApprovedBestEffort(HKEY hive, String approvedPath, String valName) {
        try {
            if (Advapi32Util.registryValueExists(hive, approvedPath, valName)) {
                Advapi32Util.registryDeleteValue(hive, approvedPath, valName);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to clean StartupApproved for " + valName + ": " + e.getMessage());
        }
    }

    private boolean scheduledTaskExists(String taskName, String taskPath) throws IOException {
        if (taskPath == null || taskPath.isBlank()) {
            taskPath = "\\";
        }
        try {
            Path script = PowerShellScripts.resolve("set-startup-task-state.ps1");
            ProcessResult result = processRunner.run(ProcessRunner.powershellScriptNonInteractive(
                    script.toString(), taskStateArgs(taskName, taskPath, "TestExists")));
            if (!result.success() || result.stdout() == null || result.stdout().isBlank()) {
                throw new IOException("Failed to query scheduled task existence: " + result.combinedOutput());
            }
            JsonNode root = JsonMapper.parseTree(result.stdout());
            return root.path("Exists").asBoolean(false);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Task existence check interrupted.");
        }
    }

    private boolean toggleRegularItem(StartupItem item, RegistryPaths paths) throws Exception {
        String valName = item.getRegistryValueName();
        // Fail fast if the Run value was deleted externally instead of creating an orphan Approved entry
        boolean exists;
        try {
            exists = Advapi32Util.registryValueExists(paths.hive(), paths.keyPath(), valName);
        } catch (Exception e) {
            exists = false;
        }
        if (!exists) {
            return false;
        }
        if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
            Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
        }
        // Preserve Explorer timestamp tail (bytes 1..n); only flip byte[0] 02<->03.
        writeApprovedState(paths.hive(), paths.approvedPath(), valName, !item.isEnabled());
        return true;
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
            writeApprovedState(paths.hive(), paths.approvedPath(), valName, false);
            return true;
        }

        // Enable: the value can live in RunDisabled (disabled by the OS or other tools)
        // or in Run (RunOnce items disabled via toggleRunOnceItem).
        String cmd = getRegistryString(paths.hive(), disabledKeyPath, valName);
        boolean inRun = false;
        boolean sourceIsExpandSz = false;
        if (cmd != null) {
            sourceIsExpandSz = isRegistryExpandSz(paths.hive(), disabledKeyPath, valName);
        }
        if (cmd == null) {
            cmd = getRegistryString(paths.hive(), enableKeyPath, valName);
            inRun = cmd != null;
            if (inRun) {
                sourceIsExpandSz = isRegistryExpandSz(paths.hive(), enableKeyPath, valName);
            }
        }
        if (cmd == null) {
            return false;
        }

        if (!inRun) {
            if (!Advapi32Util.registryKeyExists(paths.hive(), enableKeyPath)) {
                Advapi32Util.registryCreateKey(paths.hive(), enableKeyPath);
            }
            setRegistryStringPreservingType(paths.hive(), enableKeyPath, valName, cmd, sourceIsExpandSz);
            Advapi32Util.registryDeleteValue(paths.hive(), disabledKeyPath, valName);
        }
        if (!Advapi32Util.registryKeyExists(paths.hive(), paths.approvedPath())) {
            Advapi32Util.registryCreateKey(paths.hive(), paths.approvedPath());
        }
        writeApprovedState(paths.hive(), paths.approvedPath(), valName, true);
        return true;
    }

    private boolean toggleRunOnceItem(StartupItem item, RegistryPaths paths) throws Exception {
        // Windows does not consult StartupApproved for RunOnce keys — writing an
        // Approved byte is a silent no-op (item still runs once, UI falsely shows
        // Disabled). Fail honest instead of fake success. Users must Delete (with
        // automatic backup) to prevent a RunOnce entry from running.
        String valName = item.getRegistryValueName();
        String location = item.getLocation() == null ? "" : item.getLocation();
        boolean is32bit = location.contains("32-bit");
        String runKeyPath = is32bit ? REG_WOW6432_RUN : REG_RUN;
        String runOnceKeyPath = is32bit ? REG_WOW6432_RUN_ONCE : REG_RUN_ONCE;
        boolean runExists = false;
        boolean runOnceExists = false;
        try {
            runOnceExists = Advapi32Util.registryValueExists(paths.hive(), runOnceKeyPath, valName);
        } catch (Exception ignored) {}
        try {
            runExists = Advapi32Util.registryValueExists(paths.hive(), runKeyPath, valName);
        } catch (Exception ignored) {}
        if (!runExists && !runOnceExists) {
            return false;
        }
        throw new IOException("RunOnce entry \"" + valName + "\" runs once at next logon then auto-deletes; "
                + "Windows ignores disable flags for RunOnce. Use Delete (a backup is created) to prevent it from running.");
    }

    private boolean toggleStartupFolderItem(StartupItem item) throws Exception {
        Path p = StartupBackupValidation.resolveConfinedLiveStartupFile(item.getFilePath());
        Path disabled = p.resolveSibling(p.getFileName() + ".disabled");
        if (item.isEnabled()) {
            if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS) || BackupHealth.isReparseOrSymlink(p)
                    || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) {
                return false;
            }
            Files.move(p, disabled);
            return true;
        }
        if (Files.exists(disabled, LinkOption.NOFOLLOW_LINKS) && !BackupHealth.isReparseOrSymlink(disabled)
                && !Files.isDirectory(disabled, LinkOption.NOFOLLOW_LINKS)) {
            Files.move(disabled, p);
            return true;
        }
        return Files.exists(p, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
    }

    private static String getRegistryString(HKEY hive, String keyPath, String valueName) {
        if (!Advapi32Util.registryValueExists(hive, keyPath, valueName)) {
            return null;
        }
        Object valData = Advapi32Util.registryGetValue(hive, keyPath, valueName);
        return valData instanceof String s ? s : null;
    }

    static boolean isRegistryExpandSz(HKEY hive, String keyPath, String valueName) {
        return queryRegistryValueType(hive, keyPath, valueName) == WinNT.REG_EXPAND_SZ;
    }

    static int queryRegistryValueType(HKEY hive, String keyPath, String valueName) {
        WinReg.HKEYByReference phkKey = new WinReg.HKEYByReference();
        int rc = Advapi32.INSTANCE.RegOpenKeyEx(hive, keyPath, 0, WinNT.KEY_READ, phkKey);
        if (rc != WinError.ERROR_SUCCESS) return -1;
        try {
            IntByReference lpType = new IntByReference();
            IntByReference lpcbData = new IntByReference();
            rc = Advapi32.INSTANCE.RegQueryValueEx(phkKey.getValue(), valueName, 0, lpType, (Pointer) null, lpcbData);
            if (rc != WinError.ERROR_SUCCESS && rc != WinError.ERROR_MORE_DATA) return -1;
            return lpType.getValue();
        } catch (Exception e) {
            return -1;
        } finally {
            try { Advapi32.INSTANCE.RegCloseKey(phkKey.getValue()); } catch (Exception ignored) {}
        }
    }

    static void setRegistryStringPreservingType(HKEY hive, String keyPath, String valueName, String data, boolean expandSz) {
        if (expandSz) {
            try {
                Advapi32Util.registrySetExpandableStringValue(hive, keyPath, valueName, data);
                return;
            } catch (Exception ignored) {
                // Fall back to REG_SZ if expandable write is unavailable
            }
        }
        Advapi32Util.registrySetStringValue(hive, keyPath, valueName, data);
    }

    /**
     * Extracts {@code [UserId, LogonType]} from an exported Scheduled-Task XML.
     * Best-effort regex over the {@code <Principals>} block; returns empty strings
     * when absent. Never throws.
     */
    static String[] parseTaskPrincipal(String xml) {
        String userId = "";
        String logonType = "";
        if (xml == null || xml.isBlank()) return new String[]{"", ""};
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "<Principals>(.*?)</Principals>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(xml);
            String block = m.find() ? m.group(1) : xml;
            java.util.regex.Matcher u = java.util.regex.Pattern.compile(
                    "<UserId>(.*?)</UserId>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(block);
            if (u.find()) userId = u.group(1).trim();
            java.util.regex.Matcher l = java.util.regex.Pattern.compile(
                    "<LogonType>(.*?)</LogonType>",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL).matcher(block);
            if (l.find()) logonType = l.group(1).trim();
        } catch (Exception ignored) {
        }
        return new String[]{userId, logonType};
    }

    // ── Backup / Restore Mechanism ────────────────────────────────────────────

    public Path getBackupsDir() {
        Path portable = AppPaths.portableBaseDir();
        if (portable != null) {
            Path portableBackups = portable.resolve("startup-backups");
            if (StartupBackupValidation.isUsableBackupRoot(portableBackups)) {
                return portableBackups;
            }
        }
        Path local = AppPaths.localAppData().resolve("startup-backups");
        if (StartupBackupValidation.isUsableBackupRoot(local)) {
            return local;
        }
        return local;
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
        Path backupsDir = StartupBackupValidation.requireRealBackupRoot(getBackupsDir());
        Files.createDirectories(backupsDir);
        Path indexFile = getBackupsIndexFile();
        StartupBackupValidation.refuseReparseFile(indexFile, "Backup index");
        Path tmp = indexFile.resolveSibling("." + indexFile.getFileName() + ".tmp");
        StartupBackupValidation.refuseReparseFile(tmp, "Backup index temp file");
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
        Path backupFolder = StartupBackupValidation.resolveConfinedBackupFolder(getBackupsDir(), backupId);
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
                String tp = item.getTaskPath();
                if (tp == null || tp.isBlank()) tp = "\\";
                entry.setTaskPath(tp);
                entry.setBackupXmlName("task.xml");

                Path xmlPath = backupFolder.resolve("task.xml");
                ProcessResult result = processRunner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                        "Export-ScheduledTask -TaskName " + ProcessRunner.psQuote(item.getName()) + " -TaskPath " + ProcessRunner.psQuote(tp) + " | Out-File -FilePath " + ProcessRunner.psQuote(xmlPath.toAbsolutePath().toString()) + " -Encoding utf8"));
                if (!result.success()) {
                    throw new IOException("Failed to export Scheduled Task configuration: " + result.combinedOutput());
                }
                // Capture principal/logon-type so restore can give an actionable error
                // when a password is required (export strips passwords by design).
                try {
                    String xml = Files.readString(xmlPath);
                    String[] principal = parseTaskPrincipal(xml);
                    entry.setTaskPrincipalUserId(principal[0]);
                    entry.setTaskLogonType(principal[1]);
                } catch (Exception ignored) {
                    // principal capture is best-effort; restore still attempted
                }
        } else if (item.getType() == StartupItemType.REGISTRY) {
            String location = item.getLocation();
            if (location != null && location.startsWith("Startup Folder")) {
                entry.setType("Folder");
                entry.setHive(location.contains("(Common)") ? "COMMON" : "USER");
                Path live = StartupBackupValidation.resolveConfinedLiveStartupFile(item.getFilePath());
                entry.setKeyPath(live.toString());
                entry.setValueName(item.getRegistryValueName());
                Path src = null;
                Path disabled = live.resolveSibling(live.getFileName() + ".disabled");
                if (StartupBackupValidation.isSafeBackupPayloadFile(live)) {
                    src = live;
                } else if (StartupBackupValidation.isSafeBackupPayloadFile(disabled)) {
                    src = disabled;
                }
                if (src == null) {
                    throw new IOException("Cannot create backup: startup folder file is missing.");
                }
                Path dest = backupFolder.resolve(src.getFileName().toString());
                Files.copy(src, dest, LinkOption.NOFOLLOW_LINKS);
                entry.setBackupXmlName(src.getFileName().toString());
                entry.setCommand(src.toAbsolutePath().toString());
            } else {
                entry.setType("Registry");
                RegistryPaths paths = resolveRegistryPaths(item);
                entry.setHive(paths.hive() == WinReg.HKEY_CURRENT_USER ? "HKCU" : "HKLM");
                entry.setKeyPath(paths.keyPath());
                entry.setValueName(item.getRegistryValueName());
                String live = getRegistryString(paths.hive(), paths.keyPath(), item.getRegistryValueName());
                if (live == null || live.isBlank()) {
                    throw new IOException("Cannot create backup: registry value is missing or empty.");
                }
                entry.setCommand(live);
                // Preserve REG_SZ vs REG_EXPAND_SZ so restore does not break %VAR% expansion
                try {
                    int regType = queryRegistryValueType(paths.hive(), paths.keyPath(), item.getRegistryValueName());
                    if (regType == WinNT.REG_EXPAND_SZ) {
                        entry.setRegistryValueType("REG_EXPAND_SZ");
                    } else {
                        entry.setRegistryValueType("REG_SZ");
                    }
                } catch (Exception ignored) {
                    entry.setRegistryValueType("REG_SZ");
                }
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
            StartupBackupValidation.validateEntryMetadata(entry);
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
        if (entry == null) {
            throw new IllegalArgumentException("Backup entry must not be null.");
        }
        StartupBackupValidation.validateEntryMetadata(entry);
        if (StartupBackupValidation.requiresAdmin(entry) && !AdminCheck.isRunningAsAdmin()) {
            throw new IOException("Restore requires administrator privileges. Please run the application as administrator.");
        }
        Path backupFolder = StartupBackupValidation.resolveConfinedBackupFolder(getBackupsDir(), entry.getId());

        if ("Registry".equals(entry.getType())) {
            String keyPath = StartupConstants.canonicalizeRestoreRunKey(entry.getKeyPath());
            if (keyPath == null) {
                throw new IOException("Backup entry is corrupt (registry key is not a Run/RunOnce key). Backup kept.");
            }
            HKEY hive = "HKCU".equals(entry.getHive()) ? WinReg.HKEY_CURRENT_USER : WinReg.HKEY_LOCAL_MACHINE;
            if (registryValueExistsSafe(hive, keyPath, entry.getValueName())) {
                throw new IOException("Cannot restore: registry value \"" + entry.getValueName()
                        + "\" already exists. Remove it first. Backup kept.");
            }
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) {
                Advapi32Util.registryCreateKey(hive, keyPath);
            }
            boolean expandSz = "REG_EXPAND_SZ".equalsIgnoreCase(entry.getRegistryValueType());
            setRegistryStringPreservingType(hive, keyPath, entry.getValueName(), entry.getCommand(), expandSz);

            // RunOnce has no Approved overlay consulted by Windows; writing one only
            // creates a legacy orphan — and for 32-bit the target IS the Run32
            // overlay, corrupting a same-named Run entry. Skip for RunOnce.
            if (!StartupConstants.isRunOnceKey(keyPath)) {
                String approvedKeyPath = StartupConstants.toApprovedPath(keyPath);
                if (!approvedKeyPath.equals(keyPath)) {
                    if (!Advapi32Util.registryKeyExists(hive, approvedKeyPath)) {
                        Advapi32Util.registryCreateKey(hive, approvedKeyPath);
                    }
                    writeApprovedState(hive, approvedKeyPath, entry.getValueName(), entry.isEnabled());
                }
            }
        } else if ("Folder".equals(entry.getType())) {
            Path src = StartupBackupValidation.resolveConfinedFolderPayload(backupFolder, entry);
            Path dest = StartupBackupValidation.resolveConfinedFolderDest(entry.getKeyPath());
            StartupBackupValidation.assertFolderRestoreTargetAbsent(dest);
            Files.createDirectories(dest.getParent());
            Files.copy(src, dest, LinkOption.NOFOLLOW_LINKS);
            if (!entry.isEnabled()) {
                Path disabled = dest.resolveSibling(dest.getFileName() + ".disabled");
                Files.move(dest, disabled);
            }
        } else if ("Task".equals(entry.getType())) {
            Path xmlName = entry.getBackupXmlName() == null || entry.getBackupXmlName().isBlank()
                    ? Path.of("task.xml") : Path.of(entry.getBackupXmlName());
            String xmlFileName = xmlName.getFileName().toString();
            if (!StartupBackupValidation.isSafePayloadName(xmlFileName)) {
                throw new IOException("Backup entry is corrupt (invalid task payload name). Backup kept.");
            }
            Path xmlRoot = backupFolder.toAbsolutePath().normalize();
            Path xmlPath = xmlRoot.resolve(xmlFileName).normalize();
            if (!xmlPath.startsWith(xmlRoot) || !StartupBackupValidation.isSafeBackupPayloadFile(xmlPath)) {
                throw new FileNotFoundException("Backup XML file missing: " + xmlPath);
            }
            String tp = entry.getTaskPath();
            if (tp == null || tp.isBlank()) tp = "\\";
            String xml;
            try {
                xml = Files.readString(xmlPath);
            } catch (IOException e) {
                throw new IOException("Failed to read backup XML: " + e.getMessage(), e);
            }
            if (!StartupBackupValidation.taskXmlUriMatches(xml, tp, entry.getName())) {
                throw new IOException("Backup XML task URI does not match \"" + entry.getName()
                        + "\". Backup kept.");
            }

            // Export strips passwords by design. A Password-logon task cannot be
            // restored without re-entering credentials — fail fast with an actionable
            // message instead of a cryptic Register error, and keep the backup.
            String logonType = entry.getTaskLogonType();
            String principalUser = entry.getTaskPrincipalUserId();
            if (logonType == null || logonType.isBlank()) {
                String[] parsed = parseTaskPrincipal(xml);
                if (!parsed[0].isBlank()) principalUser = parsed[0];
                if (!parsed[1].isBlank()) logonType = parsed[1];
            }
            if (logonType != null && logonType.toLowerCase(java.util.Locale.ROOT).contains("password")) {
                throw new IOException("Cannot restore task \"" + entry.getName() + "\" automatically: "
                        + "it runs as \"" + (principalUser == null || principalUser.isBlank() ? "a user account" : principalUser)
                        + "\" with password logon (export strips passwords). "
                        + "Re-create it manually in Task Scheduler (import " + xmlPath.getFileName()
                        + " and re-enter credentials). Backup kept.");
            }
            if (scheduledTaskExists(entry.getName(), tp)) {
                throw new IOException("Cannot restore: scheduled task \"" + entry.getName()
                        + "\" already exists. Remove it first. Backup kept.");
            }

            ProcessResult result = processRunner.run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command",
                    "Register-ScheduledTask -Xml (Get-Content " + ProcessRunner.psQuote(xmlPath.toAbsolutePath().toString()) + " -Raw) -TaskName " + ProcessRunner.psQuote(entry.getName()) + " -TaskPath " + ProcessRunner.psQuote(tp)));
            if (!result.success()) {
                String out = result.combinedOutput();
                String lower = out.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("password") || lower.contains("logon") || lower.contains("account")
                        || lower.contains("credentials") || lower.contains("no mapping")) {
                    throw new IOException("Failed to restore task \"" + entry.getName()
                            + "\": stored credentials are required. Re-import the XML manually in Task Scheduler "
                            + "and re-enter the password. Backup kept. Details: " + out);
                }
                throw new IOException("Failed to restore Scheduled Task: " + out);
            }
        } else {
            throw new IOException("Backup entry has unsupported type \"" + entry.getType() + "\". Backup kept.");
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
        StartupAuditLog.record(StartupAuditLog.Action.RESTORE, entry, "restored successfully");
    }

    public void removeBackup(StartupBackupEntry entry) throws IOException {
        StartupBackupValidation.validateEntryMetadata(entry);
        Path backupFolder = StartupBackupValidation.resolveConfinedBackupFolder(getBackupsDir(), entry.getId());
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
        StartupAuditLog.record(StartupAuditLog.Action.RESTORE_BACKUP_DELETED, entry, "backup entry deleted");
    }

    private void deleteDirectoryRecursively(Path path) throws IOException {
        BackupHealth.deleteTree(path);
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
            if (e instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                return;
            }
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
            ProcessResult r = new ProcessRunner(15).run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", sb.toString()));
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
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            } else {
                AppLogger.warning("Batch resolve lnk failed: " + e.getMessage());
            }
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
            ProcessResult r = new ProcessRunner(10).run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps));
            if (r.success() && r.stdout() != null && !r.stdout().isBlank()) {
                String target = r.stdout().trim().split("\\R")[0].trim();
                if (!target.isBlank()) {
                    String args = "";
                    try {
                        String psArgs = "$sh = New-Object -COM WScript.Shell; $sc = $sh.CreateShortcut('"
                                + lnk.toAbsolutePath().toString().replace("'", "''")
                                + "'); Write-Output $sc.Arguments";
                        ProcessResult ra = new ProcessRunner(10).run(List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psArgs));
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

    /**
     * {@code sc.exe} treats {@code start=} as the option name and the next argv
     * as the value. A single {@code "start= disabled"} token is quoted by
     * ProcessBuilder and ignored.
     */
    public static List<String> scConfigCommand(String serviceName, String scStartValue) {
        if (!isSafeScServiceName(serviceName)) {
            throw new IllegalArgumentException("Invalid service name.");
        }
        if (!isAllowedScStartValue(scStartValue)) {
            throw new IllegalArgumentException("Unsupported service start type.");
        }
        return List.of("sc.exe", "config", serviceName, "start=", scStartValue);
    }

    /** Rejects sc.exe remote-server syntax ({@code \\host}) and flag-like names. */
    public static boolean isSafeScServiceName(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        if (serviceName.startsWith("-") || serviceName.startsWith("\\\\")) {
            return false;
        }
        for (int i = 0; i < serviceName.length(); i++) {
            char c = serviceName.charAt(i);
            if (c == '\\' || c == '/' || c < 32) {
                return false;
            }
        }
        return true;
    }

    /**
     * Colon-bound {@code -File} parameters so a task named {@code -Action} cannot
     * steal the next switch.
     */
    public static String[] taskStateArgs(String taskName, String taskPath, String action) {
        if (taskName == null || taskName.isBlank()) {
            throw new IllegalArgumentException("Task name and action must not be blank.");
        }
        if (!"Enable".equals(action) && !"Disable".equals(action) && !"TestExists".equals(action)) {
            throw new IllegalArgumentException("Unsupported scheduled-task action.");
        }
        if (taskPath == null || taskPath.isBlank()) {
            taskPath = "\\";
        }
        return new String[] {
                "-TaskName:" + taskName,
                "-TaskPath:" + taskPath,
                "-Action:" + action
        };
    }

    private static boolean isAllowedScStartValue(String scStartValue) {
        return "auto".equals(scStartValue) || "delayed-auto".equals(scStartValue)
                || "demand".equals(scStartValue) || "disabled".equals(scStartValue)
                || "boot".equals(scStartValue) || "system".equals(scStartValue);
    }

    public static Integer parseScQueryStartCode(String qcOutput) {
        if (qcOutput == null || qcOutput.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("START_TYPE\\s*:\\s*(\\d+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(qcOutput);
        if (!m.find()) {
            return null;
        }
        try {
            return Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean startCodeMatchesScArg(int code, String scStartValue) {
        if (scStartValue == null) {
            return false;
        }
        return switch (scStartValue) {
            case "disabled" -> code == 4;
            case "demand" -> code == 3;
            case "auto", "delayed-auto" -> code == 2;
            case "system" -> code == 1;
            case "boot" -> code == 0;
            default -> false;
        };
    }

    private Integer queryServiceStartCode(String serviceName) throws IOException, InterruptedException {
        if (!isSafeScServiceName(serviceName)) {
            throw new IOException("Invalid service name.");
        }
        ProcessResult qc = processRunner.run(List.of("sc.exe", "qc", serviceName));
        return parseScQueryStartCode(qc.combinedOutput());
    }

    static String startTypeToScArg(String startType) {
        if (startType == null) return "demand";
        return switch (startType.toLowerCase(Locale.ROOT)) {
            case "automatic" -> "auto";
            case "automatic (delayed start)" -> "delayed-auto";
            case "manual" -> "demand";
            case "disabled" -> "disabled";
            case "boot" -> "boot";
            case "system" -> "system";
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
