package com.basicsdriverupdate.util;

import java.nio.file.Path;

public final class AppPaths {

    private static final String APP_DIR = "BasicSDriverUpdate";

    private AppPaths() {
    }

    public static Path localAppData() {
        String local = System.getenv("LOCALAPPDATA");
        if (local != null && !local.isBlank()) {
            return Path.of(local, APP_DIR);
        }
        return Path.of(System.getProperty("user.home"), APP_DIR);
    }

    public static Path backupsRoot() {
        return localAppData().resolve("backups");
    }

    public static Path backupIndex() {
        return backupsRoot().resolve("index.json");
    }

    public static Path logsDir() {
        return localAppData().resolve("logs");
    }

    public static Path logFile() {
        return logsDir().resolve("app.log");
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
