package com.sbtools.startup;

import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Informational boot-time header: last boot time + uptime.
 *
 * <p>Display-only. Does not change the estimated-impact heuristic. A single
 * CIM query, cached per process, with graceful fallback when WMI is
 * unavailable (non-admin, non-Windows, CI).</p>
 */
public final class BootTimeService {

    private static volatile CachedBootInfo cache;
    private static final Object LOCK = new Object();

    private BootTimeService() {
    }

    public record BootInfo(Instant bootTime, String display) {
    }

    private record CachedBootInfo(BootInfo info, long cachedAtMs) {
    }

    /**
     * Returns cached boot info when fresh (&lt; 60s), otherwise queries WMI.
     * Never throws — returns a placeholder on failure.
     */
    public static BootInfo getBootInfo() {
        CachedBootInfo c = cache;
        long now = System.currentTimeMillis();
        if (c != null && now - c.cachedAtMs() < 60_000 && c.info() != null) {
            return c.info();
        }
        synchronized (LOCK) {
            c = cache;
            if (c != null && now - c.cachedAtMs() < 60_000 && c.info() != null) {
                return c.info();
            }
            BootInfo fresh = queryBootInfo();
            cache = new CachedBootInfo(fresh, now);
            return fresh;
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cache = null;
        }
    }

    private static BootInfo queryBootInfo() {
        try {
            String os = System.getProperty("os.name", "");
            if (!os.toLowerCase().contains("win")) {
                return new BootInfo(null, "Boot time unavailable (Windows only)");
            }
            Path script = PowerShellScripts.resolve("get-boot-time.ps1");
            ProcessResult r = new ProcessRunner(15).run(ProcessRunner.powershellScript(script.toString()), 15);
            if (r.success() && r.stdout() != null && !r.stdout().isBlank()) {
                BootInfo parsed = parseBootJson(r.stdout().trim());
                if (parsed != null) {
                    return parsed;
                }
            }
            AppLogger.warning("Boot time query returned no data");
        } catch (Exception e) {
            AppLogger.warning("Boot time query failed: " + e.getMessage());
        }
        return new BootInfo(null, "Boot time unavailable");
    }

    /**
     * Parses {@code {"BootTime":"2026-09-01T08:00:00+02:00"}} into a display string.
     * Pure and unit-testable. Returns null when unparseable.
     */
    static BootInfo parseBootJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            var root = JsonMapper.parseTree(json);
            String raw = root.path("BootTime").asText("");
            if (raw == null || raw.isBlank() || "null".equalsIgnoreCase(raw)) {
                return null;
            }
            Instant boot;
            try {
                boot = Instant.parse(raw);
            } catch (Exception e) {
                // Fallback: offset datetime like 2026-09-01T08:00:00+02:00
                boot = java.time.OffsetDateTime.parse(raw).toInstant();
            }
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .withZone(ZoneId.systemDefault());
            String uptime = formatUptime(java.time.Duration.between(boot, Instant.now()));
            return new BootInfo(boot, "Last boot: " + fmt.format(boot) + " (" + uptime + " ago)");
        } catch (Exception e) {
            AppLogger.warning("Failed to parse boot time JSON: " + e.getMessage());
            return null;
        }
    }

    static String formatUptime(java.time.Duration d) {
        if (d == null || d.isNegative()) {
            return "0m";
        }
        long mins = d.toMinutes();
        if (mins < 60) {
            return mins + "m";
        }
        long hours = mins / 60;
        if (hours < 24) {
            return hours + "h " + (mins % 60) + "m";
        }
        long days = hours / 24;
        return days + "d " + (hours % 24) + "h";
    }
}
