package com.sbtools.drivers;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists deviceIds that require a reboot to complete driver installation.
 * Prevents the Drivers tab / Dashboard flip-flop where a driver is shown as
 * up-to-date in Drivers but still outdated in Windows Update until reboot.
 */
public class RebootPendingStore {

    private static final String FILE = "pending-reboot.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public record PendingEntry(String deviceId, String friendlyName, Instant timestamp) {}

    public synchronized Set<String> loadPendingIds() {
        List<PendingEntry> entries = loadAll();
        Set<String> ids = new HashSet<>();
        for (PendingEntry e : entries) {
            if (e.deviceId() != null) ids.add(e.deviceId());
        }
        return ids;
    }

    public synchronized List<PendingEntry> loadAll() {
        Path p = path();
        if (!Files.exists(p)) {
            // fallback migration from legacy
            try {
                Path legacy = legacyPath();
                if (!legacy.equals(p) && Files.exists(legacy)) {
                    return readFile(legacy);
                }
            } catch (Exception ignored) {}
            return new ArrayList<>();
        }
        try {
            return readFile(p);
        } catch (Exception e) {
            AppLogger.warning("Failed to load pending-reboot: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    private List<PendingEntry> readFile(Path p) throws IOException {
        if (!Files.exists(p)) return new ArrayList<>();
        byte[] data = Files.readAllBytes(p);
        if (data.length == 0) return new ArrayList<>();
        List<PendingEntry> list = MAPPER.readValue(data, new TypeReference<List<PendingEntry>>() {});
        return list != null ? new ArrayList<>(list) : new ArrayList<>();
    }

    public synchronized void addPending(String deviceId, String friendlyName) {
        if (deviceId == null || deviceId.isBlank()) return;
        List<PendingEntry> entries = loadAll();
        boolean exists = entries.stream().anyMatch(e -> deviceId.equals(e.deviceId()));
        if (exists) return;
        entries.add(new PendingEntry(deviceId, friendlyName, Instant.now()));
        save(entries);
        AppLogger.info("Reboot pending recorded for " + friendlyName + " (" + deviceId + ")");
    }

    public synchronized void clearPending(String deviceId) {
        if (deviceId == null) return;
        List<PendingEntry> entries = loadAll();
        boolean removed = entries.removeIf(e -> deviceId.equals(e.deviceId()));
        if (removed) {
            save(entries);
            AppLogger.info("Reboot pending cleared for " + deviceId);
        }
    }

    public synchronized boolean isPending(String deviceId) {
        if (deviceId == null) return false;
        return loadPendingIds().contains(deviceId);
    }

    public synchronized void clearAll() {
        save(new ArrayList<>());
    }

    private void save(List<PendingEntry> entries) {
        Path p = path();
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("." + p.getFileName().toString() + ".tmp");
            MAPPER.writeValue(tmp.toFile(), entries);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
            // keep legacy in sync if portable
            try {
                Path legacy = legacyPath();
                if (!legacy.equals(p)) {
                    if (legacy.getParent() != null) Files.createDirectories(legacy.getParent());
                    MAPPER.writeValue(legacy.toFile(), entries);
                }
            } catch (Exception ignored) {}
        } catch (IOException e) {
            AppLogger.warning("Failed to save pending-reboot: " + e.getMessage());
        }
    }

    private Path path() {
        try {
            Path portable = com.sbtools.util.AppPaths.portableBaseDir();
            if (portable != null) {
                Path portablePath = portable.resolve(FILE);
                if (Files.exists(portablePath)) return portablePath;
                try {
                    Files.createDirectories(portable);
                    if (Files.isWritable(portable)) {
                        Path legacy = legacyPath();
                        if (Files.exists(legacy) && !Files.exists(portablePath)) {
                            try { Files.copy(legacy, portablePath); AppLogger.info("RebootPendingStore: Migrated to portable " + portablePath); } catch (Exception ignored) {}
                        }
                        return portablePath;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return legacyPath();
    }

    private Path legacyPath() {
        return Path.of(System.getProperty("user.home"), ".winzenith", FILE);
    }
}
