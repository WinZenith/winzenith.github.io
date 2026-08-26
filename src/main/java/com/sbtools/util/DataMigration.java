package com.sbtools.util;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class DataMigration {

    private static final String OLD_APP_DIR = "BasicSDriverUpdate";
    private static final String OLD_SETTINGS_DIR = ".basic-s-driver-update";

    private DataMigration() {
    }

    public static void migrateIfNeeded() {
        migrateLocalAppData();
        migrateSettingsDir();
        migrateBackupsToPortable();
        migrateStartupBackupsToPortable();
    }

    private static void migrateStartupBackupsToPortable() {
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable == null) return;
            Path portableStartup = portable.resolve("startup-backups");
            Path legacyStartup = AppPaths.localAppData().resolve("startup-backups");
            if (!Files.isDirectory(legacyStartup)) return;
            boolean portableEmpty = !Files.exists(portableStartup);
            if (!portableEmpty) {
                try (var stream = Files.list(portableStartup)) {
                    if (stream.findFirst().isPresent()) return;
                }
            }
            boolean hasData = false;
            try (var stream = Files.list(legacyStartup)) {
                if (stream.findFirst().isPresent()) hasData = true;
            }
            if (!hasData) return;
            Files.createDirectories(portableStartup);
            copyDirectory(legacyStartup, portableStartup);
            AppLogger.info("Migrated startup-backups from " + legacyStartup + " to portable " + portableStartup);
        } catch (IOException e) {
            AppLogger.warning("Failed to migrate startup-backups to portable: " + e.getMessage());
        }
    }

    private static void migrateBackupsToPortable() {
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable == null) return;
            Path portableBackups = portable.resolve("backups");
            Path legacyBackups = AppPaths.legacyBackupsRoot();
            if (!Files.isDirectory(legacyBackups)) return;
            // Only migrate if portable backups does not yet exist or is empty and legacy has data
            boolean portableEmpty = !Files.exists(portableBackups);
            if (!portableEmpty) {
                try (var stream = Files.list(portableBackups)) {
                    if (stream.findFirst().isPresent()) return; // already has data
                }
            }
            // Check legacy has index or any content
            boolean hasData = false;
            try (var stream = Files.list(legacyBackups)) {
                if (stream.findFirst().isPresent()) hasData = true;
            }
            if (!hasData) return;
            Files.createDirectories(portableBackups);
            copyDirectory(legacyBackups, portableBackups);
            AppLogger.info("Migrated backups from " + legacyBackups + " to portable " + portableBackups);
        } catch (IOException e) {
            AppLogger.warning("Failed to migrate backups to portable: " + e.getMessage());
        }
    }

    private static void migrateLocalAppData() {
        String local = System.getenv("LOCALAPPDATA");
        if (local == null || local.isBlank()) {
            return;
        }
        Path oldDir = Path.of(local, OLD_APP_DIR);
        Path newDir = AppPaths.localAppData();
        if (Files.exists(oldDir) && !Files.exists(newDir)) {
            try {
                copyDirectory(oldDir, newDir);
                deleteDirectory(oldDir);
                AppLogger.info("Migrated data from " + oldDir + " to " + newDir);
            } catch (IOException e) {
                AppLogger.warning("Failed to migrate data from " + oldDir + ": " + e.getMessage());
            }
        }
    }

    private static void migrateSettingsDir() {
        Path oldDir = Path.of(System.getProperty("user.home"), OLD_SETTINGS_DIR);
        Path newDir = Path.of(System.getProperty("user.home"), ".winzenith");
        if (Files.exists(oldDir) && !Files.exists(newDir)) {
            try {
                copyDirectory(oldDir, newDir);
                deleteDirectory(oldDir);
                AppLogger.info("Migrated settings from " + oldDir + " to " + newDir);
            } catch (IOException e) {
                AppLogger.warning("Failed to migrate settings from " + oldDir + ": " + e.getMessage());
            }
        }
    }

    private static void copyDirectory(Path source, Path target) throws IOException {
        Files.createDirectories(target);
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path dest = target.resolve(source.relativize(dir));
                Files.createDirectories(dest);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteDirectory(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
