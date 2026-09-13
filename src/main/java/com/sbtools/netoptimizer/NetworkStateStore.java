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
        Map<String, String> m = read();
        if (!Boolean.parseBoolean(m.getOrDefault("rebootRequired", "false"))) return false;
        // The flag is only clearable by an actual reboot (the banner Hide is
        // session-only). Detect a reboot since the mark via the monotonic tick
        // counter, which resets on boot but survives sleep/hibernate.
        if (hasRebootedSinceMark(m)) {
            m.put("rebootRequired", "false");
            m.put("rebootReason", "");
            write(m);
            return false;
        }
        return true;
    }

    public synchronized String rebootReason() {
        return read().getOrDefault("rebootReason", "");
    }

    public synchronized void setRebootRequired(boolean required, String reason) {
        Map<String, String> m = read();
        m.put("rebootRequired", Boolean.toString(required));
        m.put("rebootReason", reason != null ? reason : "");
        if (required) {
            // Wall clock + monotonic tick pair for reboot-since-mark detection.
            m.put("rebootMarkedAtWall", Long.toString(System.currentTimeMillis()));
            long tick = tickMillis();
            if (tick >= 0) m.put("rebootMarkedTick", Long.toString(tick));
        } else {
            m.remove("rebootMarkedAtWall");
            m.remove("rebootMarkedTick");
        }
        write(m);
    }

    public synchronized void clearRebootRequired() {
        setRebootRequired(false, "");
    }

    /**
     * True when the OS has rebooted since the reboot flag was marked: the
     * monotonic tick counter ({@code GetTickCount64}) resets on boot. Sleep /
     * hibernate do not reset it, so they cannot cause a false clear. Missing
     * timestamps (flags written by older versions) never auto-clear.
     */
    private static boolean hasRebootedSinceMark(Map<String, String> m) {
        long markedWall;
        long markedTick;
        try {
            markedWall = Long.parseLong(m.getOrDefault("rebootMarkedAtWall", "-1"));
            markedTick = Long.parseLong(m.getOrDefault("rebootMarkedTick", "-1"));
        } catch (NumberFormatException e) {
            return false;
        }
        if (markedWall < 0 || markedTick < 0) return false;
        long wallElapsed = System.currentTimeMillis() - markedWall;
        if (wallElapsed > 45L * 24 * 3600 * 1000) return false;
        long nowTick = tickMillis();
        if (nowTick < 0) return false;
        if (nowTick < markedTick) {
            return wallElapsed >= 2_000;
        }
        return false;
    }

    /** Monotonic milliseconds since boot, or -1 when unavailable / non-Windows. */
    private static long tickMillis() {
        try {
            if (!com.sbtools.util.AppPaths.isWindows()) return -1;
            return com.sun.jna.platform.win32.Kernel32.INSTANCE.GetTickCount64();
        } catch (Throwable t) {
            return -1;
        }
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
        Path portableCandidate = null;
        try {
            java.nio.file.Path pb = com.sbtools.util.AppPaths.portableBaseDir();
            if (pb != null) portableCandidate = pb.resolve(".winzenith").resolve(FILE);
        } catch (Exception ignored) {}
        if (portableCandidate != null && Files.exists(portableCandidate)) return portableCandidate;
        if (Files.exists(legacy)) return legacy;
        try {
            java.nio.file.Path pb = com.sbtools.util.AppPaths.portableBaseDir();
            if (pb != null) return pb.resolve(".winzenith").resolve(FILE);
        } catch (Exception ignored) {}
        return legacy;
    }
}
