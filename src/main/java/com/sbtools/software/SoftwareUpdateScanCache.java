package com.sbtools.software;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JVM-wide in-memory cache of the last successful software-update scan.
 *
 * <p>Session-only: never persisted (honours the no-settings-schema-change constraint).
 * Entries are defensively copied on put/get because {@link SoftwareUpdateEntry} carries
 * mutable JavaFX properties (selected/status/progress) that the Software tab mutates —
 * sharing live objects with the Dashboard reader would race.</p>
 *
 * <p>Usage: {@code SoftwareUpdateService} writes on clean scan success;
 * {@code SoftwareUpdateViewModel} reads as a labelled stale fallback when a live scan
 * fails (avoids a false "Everything is up to date"); installs invalidate.</p>
 */
public final class SoftwareUpdateScanCache {

    /** Freshness budget for stale-fallback use. */
    public static final Duration TTL = Duration.ofMinutes(5);

    /** Immutable snapshot of one cached scan. */
    public record CachedScan(List<SoftwareUpdateEntry> entries, Instant cachedAt,
                             String wingetError, String windowsUpdateError) {}

    private static final Object LOCK = new Object();
    private static CachedScan cached;

    private SoftwareUpdateScanCache() {}

    /** Stores a defensive copy; null/empty lists are ignored (never poison the cache). */
    public static void put(List<SoftwareUpdateEntry> entries, String wingetError, String windowsUpdateError) {
        if (entries == null || entries.isEmpty()) return;
        List<SoftwareUpdateEntry> copy = new ArrayList<>(entries.size());
        for (SoftwareUpdateEntry e : entries) {
            if (e == null || e.id() == null || e.id().isBlank()) continue;
            copy.add(new SoftwareUpdateEntry(e.id(), e.getName(), e.getCurrentVersion(),
                    e.getAvailableVersion(), e.source(), e.updateId(), e.sizeBytes()));
        }
        if (copy.isEmpty()) return;
        synchronized (LOCK) {
            cached = new CachedScan(List.copyOf(copy), Instant.now(), wingetError, windowsUpdateError);
        }
    }

    /** Returns fresh defensive copies, or empty when absent/stale. */
    public static Optional<CachedScan> getIfFresh() {
        synchronized (LOCK) {
            CachedScan snap = cached;
            if (snap == null) return Optional.empty();
            if (snap.cachedAt() == null
                    || Duration.between(snap.cachedAt(), Instant.now()).compareTo(TTL) > 0) {
                return Optional.empty();
            }
            List<SoftwareUpdateEntry> copy = new ArrayList<>(snap.entries().size());
            for (SoftwareUpdateEntry e : snap.entries()) {
                copy.add(new SoftwareUpdateEntry(e.id(), e.getName(), e.getCurrentVersion(),
                        e.getAvailableVersion(), e.source(), e.updateId(), e.sizeBytes()));
            }
            return Optional.of(new CachedScan(List.copyOf(copy), snap.cachedAt(),
                    snap.wingetError(), snap.windowsUpdateError()));
        }
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cached = null;
        }
    }
}
