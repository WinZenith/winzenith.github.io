package com.sbtools.software;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class SoftwareUpdateHistoryStore {

    private static final int MAX_ENTRIES = 500;
    private static final String DIR = ".winzenith";
    private static final String FILE = "software-update-history.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Object lock = new Object();

    public void add(SoftwareUpdateHistoryEntry entry) {
        synchronized (lock) {
            try {
                if (entry == null || entry.installedAt() == null) {
                    AppLogger.warning("Skipping software update history entry with missing timestamp");
                    if (entry == null) return;
                }
                List<SoftwareUpdateHistoryEntry> history = load();
                history.add(entry);
                if (history.size() > MAX_ENTRIES) {
                    history = history.subList(history.size() - MAX_ENTRIES, history.size());
                }
                save(history);
                AppLogger.info("Software update history recorded: " + entry.packageName()
                        + " " + entry.oldVersion() + " -> " + entry.newVersion());
            } catch (Exception ex) {
                AppLogger.warning("Failed to record software update history: " + ex.getMessage());
            }
        }
    }

    public List<SoftwareUpdateHistoryEntry> listAll() {
        synchronized (lock) {
            return load().stream()
                    // Corrupt/legacy entries without a timestamp must not crash sorting or the dialog.
                    .filter(e -> e != null && e.installedAt() != null)
                    .sorted(Comparator.comparing(SoftwareUpdateHistoryEntry::installedAt).reversed())
                    .toList();
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                save(new ArrayList<>());
            } catch (Exception ex) {
                AppLogger.warning("Failed to clear software update history: " + ex.getMessage());
            }
        }
    }

    private List<SoftwareUpdateHistoryEntry> load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            JsonNode root = mapper.readTree(p.toFile());
            if (root.isArray()) {
                List<SoftwareUpdateHistoryEntry> parsed =
                        mapper.convertValue(root, new TypeReference<List<SoftwareUpdateHistoryEntry>>() {});
                if (parsed == null) return new ArrayList<>();
                // Drop corrupt entries (e.g. legacy records without installedAt) instead of letting
                // a null timestamp NPE the history sort/dialog later.
                int before = parsed.size();
                List<SoftwareUpdateHistoryEntry> sane = new ArrayList<>(parsed.size());
                for (SoftwareUpdateHistoryEntry e : parsed) {
                    if (e != null && e.installedAt() != null) sane.add(e);
                }
                if (sane.size() != before) {
                    AppLogger.warning("Filtered " + (before - sane.size())
                            + " corrupt software-update history entr(ies) with missing timestamp");
                }
                return sane;
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to load software update history: " + e.getMessage());
        } catch (Exception e) {
            AppLogger.warning("Failed to parse software update history (dropping corrupt file content): " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private void save(List<SoftwareUpdateHistoryEntry> history) throws IOException {
        Path target = path();
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        } else {
            dir = Path.of(System.getProperty("user.home"), DIR);
            Files.createDirectories(dir);
        }
        Path temp = dir.resolve(".software-update-history-" + ProcessHandle.current().pid() + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), history);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path path() {
        // Portable-first, mirroring SettingsStore: keep history next to the app when running
        // portably (USB stick) instead of always leaving traces in %USERPROFILE%.
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) {
                Path portablePath = portable.resolve(FILE);
                if (Files.exists(portablePath)) {
                    return portablePath;
                }
                try {
                    Files.createDirectories(portable);
                    if (Files.isWritable(portable)) {
                        Path legacy = legacyPath();
                        if (Files.exists(legacy) && !Files.exists(portablePath)) {
                            try {
                                Files.copy(legacy, portablePath);
                                AppLogger.info("SoftwareUpdateHistoryStore: Migrated history to portable location " + portablePath);
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
