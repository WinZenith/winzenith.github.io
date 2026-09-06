package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.util.AppLogger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny portable state file for cross-launch UI hints (e.g. reboot required
 * after Winsock / stack reset). Kept separate from {@code AppSettings} to
 * avoid settings-schema migration risk.
 */
public class NetworkStateStore {

    private static final String FILE = "network-state.json";
    private static final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public synchronized boolean isRebootRequired() {
        return Boolean.parseBoolean(read().getOrDefault("rebootRequired", "false"));
    }

    public synchronized String rebootReason() {
        return read().getOrDefault("rebootReason", "");
    }

    public synchronized void setRebootRequired(boolean required, String reason) {
        Map<String, String> m = read();
        m.put("rebootRequired", Boolean.toString(required));
        m.put("rebootReason", reason != null ? reason : "");
        write(m);
    }

    public synchronized void clearRebootRequired() {
        setRebootRequired(false, "");
    }

    private Map<String, String> read() {
        Path p = path();
        if (!Files.exists(p)) return new HashMap<>();
        try {
            Map<String, String> m = mapper.readValue(p.toFile(),
                    new TypeReference<Map<String, String>>() {});
            return m != null ? new HashMap<>(m) : new HashMap<>();
        } catch (Exception e) {
            AppLogger.warning("Failed to read network state: " + e.getMessage());
            return new HashMap<>();
        }
    }

    private void write(Map<String, String> m) {
        try {
            Path p = path();
            Path dir = p.getParent();
            if (dir != null) Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "." + FILE + ".", ".tmp");
            try {
                mapper.writeValue(tmp.toFile(), m);
                try {
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to write network state: " + e.getMessage());
        }
    }

    private Path path() {
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
        Path legacy = Path.of(System.getProperty("user.home"), ".winzenith", FILE);
        try {
            java.nio.file.Path pb = com.sbtools.util.AppPaths.portableBaseDir();
            if (pb != null) {
                Path cand = pb.resolve(".winzenith").resolve(FILE);
                if (Files.exists(cand)) return cand;
                return cand;
            }
        } catch (Exception ignored) {}
        return legacy;
    }
}
