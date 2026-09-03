package com.sbtools.util;

import java.nio.file.Files;
import java.nio.file.Path;

public final class AppPaths {

    private static final String APP_DIR = "WinZenith";

    private AppPaths() {
    }

    public static Path localAppData() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, APP_DIR);
        }
        return Path.of(System.getProperty("user.home"), APP_DIR);
    }

    public static Path settingsPath() {
        Path portable = portableBaseDir();
        if (portable != null) {
            try {
                Files.createDirectories(portable);
                if (Files.isWritable(portable)) {
                    Path p = portable.resolve("settings.json");
                    if (Files.exists(p)) return p;
                    // Prefer portable if writable and no legacy file conflicts
                    return p;
                }
            } catch (Exception ignored) {}
        }
        return Path.of(System.getProperty("user.home"), ".winzenith", "settings.json");
    }

    public static Path catalogCacheDir() {
        Path portable = portableBaseDir();
        if (portable != null) {
            try {
                Path pc = portable.resolve("catalog-cache");
                Files.createDirectories(pc);
                if (Files.isWritable(pc)) return pc;
            } catch (Exception ignored) {}
        }
        return localAppData().resolve("catalog-cache");
    }

    /**
     * True portable mode: try to store scripts/logs next to the executable (or JAR)
     * so the app can run from a USB stick without writing to %LOCALAPPDATA%.
     * Falls back to localAppData() if the exe directory is not writable or
     * not determinable (e.g., IDE run).
     */
    public static Path portableBaseDir() {
        try {
            // 1. Explicit override via system property / env
            String portable = System.getProperty("app.portable.dir");
            if (portable != null && !portable.isBlank()) {
                Path p = Path.of(portable);
                if (isWritableDirOrParent(p)) return p;
            }
            String envPortable = System.getenv("WINZENITH_PORTABLE_DIR");
            if (envPortable != null && !envPortable.isBlank()) {
                Path p = Path.of(envPortable);
                if (isWritableDirOrParent(p)) return p;
            }
            // 2. Try to locate executable / JAR directory via CodeSource
            try {
                var cs = AppPaths.class.getProtectionDomain().getCodeSource();
                if (cs != null && cs.getLocation() != null) {
                    Path loc = Path.of(cs.getLocation().toURI());
                    Path base = loc;
                    if (java.nio.file.Files.isRegularFile(base)) {
                        base = base.getParent();
                    }
                    // When running from IDE (target/classes) walk up to project root
                    if (base != null && base.toString().endsWith("target" + java.io.File.separator + "classes")) {
                        base = base.getParent().getParent();
                    } else if (base != null && base.getFileName() != null && "classes".equals(base.getFileName().toString())) {
                        base = base.getParent();
                    }
                    // Check for portable marker or simply test writability next to exe
                    Path candidate = base.resolve("ps-scripts").getParent(); // base itself
                    // Candidate is the app base; test if we can write there
                    if (candidate != null && isWritableDirOrParent(candidate)) {
                        // If we're in a jpackage app-image, executable is via ProcessHandle command
                        String cmd = ProcessHandle.current().info().command().orElse("");
                        if (!cmd.isBlank()) {
                            Path exePath = Path.of(cmd).getParent();
                            if (exePath != null && isWritableDirOrParent(exePath)) {
                                return exePath;
                            }
                        }
                        return candidate;
                    }
                }
            } catch (Exception ignored) {
            }
            // 3. Fallback to ProcessHandle command parent
            String cmd = ProcessHandle.current().info().command().orElse("");
            if (!cmd.isBlank()) {
                Path exeParent = Path.of(cmd).getParent();
                if (exeParent != null && isWritableDirOrParent(exeParent)) {
                    return exeParent;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isWritableDirOrParent(Path dir) {
        try {
            if (dir == null) return false;
            if (java.nio.file.Files.isDirectory(dir)) {
                return java.nio.file.Files.isWritable(dir);
            }
            // Walk up to the nearest existing ancestor and test writability.
            // Never assume writable for non-existent paths (Program Files /
            // protected exe dirs would otherwise be picked as portable root
            // and fail later with no fallback).
            Path probe = dir;
            while (probe != null && !java.nio.file.Files.exists(probe)) {
                probe = probe.getParent();
            }
            if (probe != null && java.nio.file.Files.isDirectory(probe)) {
                return java.nio.file.Files.isWritable(probe);
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public static Path scriptBaseDir() {
        Path portable = portableBaseDir();
        if (portable != null) {
            try {
                Path portableScripts = portable.resolve("ps-scripts");
                // Probe writability
                java.nio.file.Files.createDirectories(portableScripts);
                if (java.nio.file.Files.isWritable(portableScripts)) {
                    return portableScripts;
                }
            } catch (Exception ignored) {
                // fall through to localAppData
            }
        }
        return localAppData().resolve("ps-scripts");
    }

    public static Path portableLogsDir() {
        Path portable = portableBaseDir();
        if (portable != null) {
            try {
                Path dir = portable.resolve("logs");
                java.nio.file.Files.createDirectories(dir);
                if (java.nio.file.Files.isWritable(dir)) return dir;
            } catch (Exception ignored) {}
        }
        return logsDir();
    }

    public static Path backupsRoot() {
        return backupsRootNoCreate();
    }

    /**
     * Pure path resolution without side-effects (no directory creation). Use this for
     * index lookups, isSafeToDelete checks and UI display to avoid splitting storage.
     */
    public static Path backupsRootNoCreate() {
        Path portable = portableBaseDir();
        if (portable != null) {
            try {
                Path portableBackups = portable.resolve("backups");
                // Do NOT create directory here - only check writability of parent to avoid side-effects
                if (isWritableDirOrParent(portableBackups) || Files.isDirectory(portableBackups)) {
                    return portableBackups;
                }
            } catch (Exception ignored) {
            }
        }
        return localAppData().resolve("backups");
    }

    /**
     * Ensures the backups directory exists (creates it). Use only when actually writing.
     */
    public static Path ensureBackupsRoot() {
        Path p = backupsRootNoCreate();
        try { Files.createDirectories(p); } catch (Exception ignored) {}
        return p;
    }

    /**
     * Portable-aware overload: if settings specifies a custom backupDirectory, that wins.
     * Otherwise same portable-first logic as {@link #backupsRoot()}.
     */
    public static Path backupsRoot(com.sbtools.settings.AppSettings settings) {
        if (settings != null && settings.backupDirectory() != null && !settings.backupDirectory().isBlank()) {
            String raw = settings.backupDirectory().trim();
            Path custom = Path.of(raw);
            if (isValidCustomBackupDir(custom)) {
                return custom;
            } else {
                // Fall back to portable logic if custom is invalid (e.g. C:\)
                return backupsRootNoCreate();
            }
        }
        return backupsRootNoCreate();
    }

    public static boolean isValidCustomBackupDir(Path custom) {
        try {
            if (custom == null) return false;
            Path norm = custom.toAbsolutePath().normalize();
            String s = norm.toString().toLowerCase().replace('/', '\\');
            if (s.matches("^[a-z]:\\\\?$") || s.matches("^[a-z]:$")) return false;
            if (s.equals("\\") || s.equals("/")) return false;
            if (s.contains("\\windows\\") || s.endsWith("\\windows") || s.equals("c:\\windows")) return false;
            if (s.contains("\\program files") || s.contains("\\programdata")) return false;
            if (norm.getNameCount() < 1) return false;
            // Disallow too shallow like C:\ or D:\ alone
            String normStr = norm.toString();
            if (normStr.length() <= 3) return false;
            return true;
        } catch (Exception e) { return false; }
    }

    public static Path backupIndex() {
        return backupsRootNoCreate().resolve("index.json");
    }

    public static Path backupIndexNoCreate() {
        return backupsRootNoCreate().resolve("index.json");
    }

    public static Path backupIndex(com.sbtools.settings.AppSettings settings) {
        return backupsRoot(settings).resolve("index.json");
    }

    /**
     * Returns the legacy LOCALAPPDATA backups location, useful for migration checks.
     */
    public static Path legacyBackupsRoot() {
        return localAppData().resolve("backups");
    }

    public static Path logsDir() {
        return localAppData().resolve("logs");
    }

    public static Path logFile() {
        return logsDir().resolve("app.log");
    }

    public static Path dataDir() {
        return localAppData();
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
