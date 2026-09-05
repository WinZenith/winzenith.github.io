package com.sbtools.uninstaller;

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

/**
 * Portable-first JSON store for uninstall history. Mirrors
 * SoftwareUpdateHistoryStore semantics (atomic writes, 500-entry cap,
 * tolerant loading that drops corrupt entries instead of failing).
 */
public class UninstallHistoryStore {

    private static final int MAX_ENTRIES = 500;
    private static final String DIR = ".winzenith";
    private static final String FILE = "uninstall-history.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final Object lock = new Object();

    public void add(UninstallHistoryEntry entry) {
        synchronized (lock) {
            try {
                if (entry == null) return;
                List<UninstallHistoryEntry> history = load();
                history.add(entry.uninstalledAt() == null
                        ? new UninstallHistoryEntry(entry.appName(), entry.version(),
                                entry.publisher(), entry.appType(), entry.mode(),
                                entry.success(), entry.exitCode(), entry.leftoversDeleted(),
                                entry.detail())
                        : entry);
                if (history.size() > MAX_ENTRIES) {
                    history = new ArrayList<>(history.subList(history.size() - MAX_ENTRIES, history.size()));
                }
                save(history);
                AppLogger.info("Uninstall history recorded: " + entry.appName()
                        + " (" + entry.mode() + ", success=" + entry.success() + ")");
            } catch (Exception ex) {
                AppLogger.warning("Failed to record uninstall history: " + ex.getMessage());
            }
        }
    }

    public List<UninstallHistoryEntry> listAll() {
        synchronized (lock) {
            return load().stream()
                    .filter(e -> e != null && e.uninstalledAt() != null)
                    .sorted(Comparator.comparing(UninstallHistoryEntry::uninstalledAt).reversed())
                    .toList();
        }
    }

    public void clear() {
        synchronized (lock) {
            try {
                save(new ArrayList<>());
            } catch (Exception ex) {
                AppLogger.warning("Failed to clear uninstall history: " + ex.getMessage());
            }
        }
    }

    private List<UninstallHistoryEntry> load() {
        Path p = path();
        if (!Files.exists(p)) return new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(p.toFile());
            if (root.isArray()) {
                List<UninstallHistoryEntry> parsed =
                        mapper.convertValue(root, new TypeReference<List<UninstallHistoryEntry>>() {});
                if (parsed == null) return new ArrayList<>();
                List<UninstallHistoryEntry> sane = new ArrayList<>(parsed.size());
                for (UninstallHistoryEntry e : parsed) {
                    if (e != null && e.uninstalledAt() != null) sane.add(e);
                }
                return sane;
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to load uninstall history: " + e.getMessage());
        } catch (Exception e) {
            AppLogger.warning("Failed to parse uninstall history: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private void save(List<UninstallHistoryEntry> history) throws IOException {
        Path target = path();
        Path dir = target.getParent();
        if (dir != null) {
            Files.createDirectories(dir);
        } else {
            dir = Path.of(System.getProperty("user.home"), DIR);
            Files.createDirectories(dir);
        }
        Path temp = dir.resolve(".uninstall-history-" + ProcessHandle.current().pid() + ".tmp");
        try {
            mapper.writeValue(temp.toFile(), history);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
    }

    private Path path() {
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) {
                Path portablePath = portable.resolve(FILE);
                if (Files.exists(portablePath)) return portablePath;
                try {
                    Files.createDirectories(portable);
                    if (Files.isWritable(portable)) {
                        Path legacy = legacyPath();
                        if (Files.exists(legacy) && !Files.exists(portablePath)) {
                            try {
                                Files.copy(legacy, portablePath);
                                AppLogger.info("UninstallHistoryStore: Migrated history to portable location "
                                        + portablePath);
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
