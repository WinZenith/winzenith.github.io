package com.sbtools.browserext;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cached {@code tasklist} probe. The extensions tab previously spawned one
 * {@code tasklist} per toggled extension (N processes for N rows). This
 * helper caches the running-exe set for a short TTL so batch toggles cost
 * a single probe. Best-effort only — check {@link #lastProbeOk()}: when false
 * the (empty) result means "unknown", and callers must warn instead of
 * treating it as "not running". Use {@link #runningExesFresh()} for
 * safety-critical re-checks that must not read a stale cache entry.
 */
public final class BrowserProcessProbe {

    /** Cache TTL: short enough to notice a freshly launched browser mid-batch. */
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static volatile Set<String> cachedExes = Set.of();
    private static volatile long cachedAt = 0;
    /** True when the last probe successfully read the process list. */
    private static volatile boolean lastProbeOk = false;

    private BrowserProcessProbe() {
    }

    /** Returns lower-cased running image names (e.g. {@code chrome.exe}). */
    public static synchronized Set<String> runningExes() {
        long now = System.nanoTime();
        if (now - cachedAt < TTL_NANOS) return cachedExes;
        return runningExesFresh();
    }

    /**
     * Bypasses the cache and re-queries immediately. Use for safety-critical
     * re-checks (e.g. per-item mid-batch toggle guard) where a stale entry
     * could miss a browser relaunched inside the TTL window.
     */
    public static synchronized Set<String> runningExesFresh() {
        Set<String> fresh = queryTasklist();
        cachedExes = fresh;
        cachedAt = System.nanoTime();
        return fresh;
    }

    /** False when the last probe failed (empty set is then "unknown", not "none running"). */
    public static boolean lastProbeOk() {
        return lastProbeOk;
    }

    /** For tests: clears the cache so the next call re-queries. */
    static synchronized void resetForTest() {
        cachedExes = Set.of();
        cachedAt = 0;
        lastProbeOk = false;
    }

    private static Set<String> queryTasklist() {
        Set<String> out = new HashSet<>();
        lastProbeOk = false;
        Process p = null;
        boolean timedOut = false;
        try {
            p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (p.isAlive()) {
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    p.destroyForcibly();
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                    return out;
                }
            }
            String output;
            try {
                output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                return out;
            }
            if (output.isEmpty()) return out;
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                if (trimmed.startsWith("\"")) {
                    int end = trimmed.indexOf('"', 1);
                    if (end > 1) out.add(trimmed.substring(1, end).toLowerCase());
                } else {
                    out.add(trimmed.split("[,\\s]")[0].toLowerCase());
                }
            }
            // tasklist always lists dozens of system processes: a non-empty
            // parse without timeout means the probe genuinely succeeded, so an
            // empty browser match below is "not running", not "unknown".
            if (!timedOut && !out.isEmpty()) lastProbeOk = true;
        } catch (Exception ignored) {
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
                try {
                    if (p.isAlive()) p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
        }
        return out;
    }
}
