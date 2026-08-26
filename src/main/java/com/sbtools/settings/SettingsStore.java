package com.sbtools.settings;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class SettingsStore {

    private static final String DIR = ".winzenith";
    private static final String FILE = "settings.json";

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public AppSettings load() {
        Path p = path();
        Path legacy = legacyPath();
        AppSettings primary = null;
        boolean primaryExists = Files.exists(p);
        if (primaryExists) {
            try {
                AppSettings s = mapper.readValue(p.toFile(), AppSettings.class);
                if (s != null) primary = normalize(s);
            } catch (IOException e) {
                AppLogger.warning("Failed to load settings from " + p + ": " + e.getMessage());
            }
        }
        // Merge from legacy if different location and legacy exists
        if (!p.equals(legacy) && Files.exists(legacy)) {
            try {
                AppSettings legacySettings = mapper.readValue(legacy.toFile(), AppSettings.class);
                if (legacySettings != null) {
                    legacySettings = normalize(legacySettings);
                    if (primary == null) {
                        return legacySettings;
                    }
                    // Merge excluded/skipped lists without duplicates
                    java.util.Set<String> ex = new java.util.LinkedHashSet<>(primary.excludedDriverIds());
                    ex.addAll(legacySettings.excludedDriverIds());
                    java.util.Set<String> sk = new java.util.LinkedHashSet<>(primary.skippedSoftwareIds());
                    sk.addAll(legacySettings.skippedSoftwareIds());
                    java.util.Set<String> br = new java.util.LinkedHashSet<>(primary.ignoredBrowserExtensionIds());
                    br.addAll(legacySettings.ignoredBrowserExtensionIds());
                    java.util.Set<String> cl = new java.util.LinkedHashSet<>(primary.ignoredCleanupCategories());
                    cl.addAll(legacySettings.ignoredCleanupCategories());
                    return primary.toBuilder()
                            .excludedDriverIds(new java.util.ArrayList<>(ex))
                            .skippedSoftwareIds(new java.util.ArrayList<>(sk))
                            .ignoredBrowserExtensionIds(new java.util.ArrayList<>(br))
                            .ignoredCleanupCategories(new java.util.ArrayList<>(cl))
                            .build();
                }
            } catch (IOException ex) {
                AppLogger.warning("Failed to load legacy settings: " + ex.getMessage());
            }
        }
        if (primary != null) return primary;
        return AppSettings.defaults();
    }

    private static AppSettings normalize(AppSettings s) {
        AppSettings base = s;
        boolean needsFix = s.excludedDriverIds() == null || s.skippedSoftwareIds() == null
                || s.ignoredBrowserExtensionIds() == null || s.ignoredCleanupCategories() == null;
        if (needsFix) {
            base = s.toBuilder()
                    .excludedDriverIds(s.excludedDriverIds() == null ? java.util.Collections.emptyList() : s.excludedDriverIds())
                    .skippedSoftwareIds(s.skippedSoftwareIds() == null ? java.util.Collections.emptyList() : s.skippedSoftwareIds())
                    .ignoredBrowserExtensionIds(s.ignoredBrowserExtensionIds() == null ? java.util.Collections.emptyList() : s.ignoredBrowserExtensionIds())
                    .ignoredCleanupCategories(s.ignoredCleanupCategories() == null ? java.util.Collections.emptyList() : s.ignoredCleanupCategories())
                    .build();
        }
        // Sanitize backupDirectory - reject dangerous roots like C:\ or Windows
        if (base.backupDirectory() != null && !base.backupDirectory().isBlank()) {
            try {
                java.nio.file.Path p = java.nio.file.Path.of(base.backupDirectory().trim());
                if (!com.sbtools.util.AppPaths.isValidCustomBackupDir(p)) {
                    com.sbtools.util.AppLogger.warning("Sanitizing invalid backupDirectory: " + base.backupDirectory());
                    base = base.toBuilder().backupDirectory("").build();
                }
            } catch (Exception e) {
                base = base.toBuilder().backupDirectory("").build();
            }
        }
        return base;
    }

    private static final Object SAVE_LOCK = new Object();

    public void save(AppSettings settings) throws IOException {
        synchronized (SAVE_LOCK) {
            Path p = path();
            Path dir = p.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            // Re-read latest to merge if file changed concurrently (mitigates last-writer-wins)
            // Caller is responsible for merging, but we ensure atomicity via lock
            Path tmp = p.resolveSibling("." + p.getFileName().toString() + ".tmp");
            mapper.writeValue(tmp.toFile(), settings);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Atomic read-modify-write to avoid lost updates when multiple tabs save concurrently.
     */
    public void update(java.util.function.UnaryOperator<AppSettings> mutator) throws IOException {
        synchronized (SAVE_LOCK) {
            AppSettings current = load();
            AppSettings updated = mutator.apply(current);
            // Direct write without re-entering save() lock recursion (already held)
            Path p = path();
            Path dir = p.getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = p.resolveSibling("." + p.getFileName().toString() + ".tmp");
            mapper.writeValue(tmp.toFile(), updated);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        }
    }

    private Path path() {
        // Portable-first: try next-to-exe / portableBaseDir before falling back to user.home
        try {
            java.nio.file.Path portable = com.sbtools.util.AppPaths.portableBaseDir();
            if (portable != null) {
                java.nio.file.Path portablePath = portable.resolve(FILE);
                // If portable file already exists, use it. Otherwise prefer portable if writable.
                if (java.nio.file.Files.exists(portablePath)) {
                    return portablePath;
                }
                // Probe writability without creating file yet
                try {
                    java.nio.file.Files.createDirectories(portable);
                    if (java.nio.file.Files.isWritable(portable)) {
                        // Migrate legacy file if exists and portable doesn't
                        java.nio.file.Path legacy = legacyPath();
                        if (java.nio.file.Files.exists(legacy) && !java.nio.file.Files.exists(portablePath)) {
                            try {
                                java.nio.file.Files.copy(legacy, portablePath);
                                com.sbtools.util.AppLogger.info("SettingsStore: Migrated settings to portable location " + portablePath);
                            } catch (Exception ignored) {}
                        }
                        return portablePath;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return legacyPath();
    }

    private Path legacyPath() {
        return Path.of(System.getProperty("user.home"), DIR, FILE);
    }
}
