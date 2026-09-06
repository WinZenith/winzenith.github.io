package com.sbtools.browserext;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cached {@code tasklist} probe. The extensions tab previously spawned one
 * {@code tasklist} per toggled extension (N processes for N rows). This
 * helper caches the running-exe set for a short TTL so batch toggles cost
 * a single probe. Best-effort only — returns empty set on any failure
 * (callers treat "unknown" as "not running" and rely on the PS file-lock
 * + verify-after-write guards as well).
 */
public final class BrowserProcessProbe {

    /** Cache TTL: short enough to notice a freshly launched browser mid-batch. */
    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(2);

    private static volatile Set<String> cachedExes = Set.of();
    private static volatile long cachedAt = 0;

    private BrowserProcessProbe() {
    }

    /** Returns lower-cased running image names (e.g. {@code chrome.exe}). */
    public static synchronized Set<String> runningExes() {
        long now = System.nanoTime();
        if (now - cachedAt < TTL_NANOS) return cachedExes;
        Set<String> fresh = queryTasklist();
        cachedExes = fresh;
        cachedAt = now;
        return fresh;
    }

    /** For tests: clears the cache so the next call re-queries. */
    static synchronized void resetForTest() {
        cachedExes = Set.of();
        cachedAt = 0;
    }

    private static Set<String> queryTasklist() {
        Set<String> out = new HashSet<>();
        Process p = null;
        try {
            p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (p.isAlive()) {
                if (System.nanoTime() > deadline) {
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
