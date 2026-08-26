package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class NetworkChangeLog {

    private static final int MAX_ENTRIES = 100;
    private static final String FILE = "network-changelog.json";

    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public synchronized void append(NetworkChangeEntry entry) {
        List<NetworkChangeEntry> entries = load();
        entries.add(0, entry);
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
        save(entries);
    }

    public synchronized List<NetworkChangeEntry> load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(p.toFile(), new TypeReference<List<NetworkChangeEntry>>() {});
        } catch (IOException e) {
            AppLogger.warning("Failed to load network change log: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    public synchronized void clear() {
        save(new ArrayList<>());
    }

    private void save(List<NetworkChangeEntry> entries) {
        try {
            Path p = path();
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);
            // Atomic write via unique temp file to avoid collision
            Path tmp = Files.createTempFile(dir, "." + FILE + ".", ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), entries);
                try {
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (IOException e) {
            AppLogger.warning("Failed to save network change log: " + e.getMessage());
        }
    }

    private Path path() {
        // Prefer portable dir if writable, fallback to legacy user.home/.winzenith
        try {
            java.nio.file.Path portable = com.sbtools.util.AppPaths.portableBaseDir();
            if (portable != null) {
                java.nio.file.Path portableDir = portable.resolve(".winzenith");
                try {
                    Files.createDirectories(portableDir);
                    if (Files.isWritable(portableDir)) return portableDir.resolve(FILE);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        // Legacy + migration check
        Path legacy = Path.of(System.getProperty("user.home"), ".winzenith", FILE);
        Path portableCandidate = null;
        try {
            java.nio.file.Path pb = com.sbtools.util.AppPaths.portableBaseDir();
            if (pb != null) portableCandidate = pb.resolve(".winzenith").resolve(FILE);
        } catch (Exception ignored) {}
        // If portable exists and legacy exists, prefer portable but don't hide data - migration handled lazily
        if (portableCandidate != null && Files.exists(portableCandidate)) return portableCandidate;
        if (Files.exists(legacy)) return legacy;
        // Default to portable if available else legacy
        try {
            java.nio.file.Path pb = com.sbtools.util.AppPaths.portableBaseDir();
            if (pb != null) return pb.resolve(".winzenith").resolve(FILE);
        } catch (Exception ignored) {}
        return legacy;
    }
}
