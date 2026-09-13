package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.VersionCompare;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DriverCatalogAggregator {

    /** Per-provider budget. Lowered 180s→120s: WU/OEM rarely recover after 2 min; cache covers retries. */
    private static final long PROVIDER_TIMEOUT_SECONDS = 120;

    private final List<DriverCatalogProvider> providers;
    private final ProviderCache cache;
    private final DriverCatalogDatabase catalogDatabase;
    private final ExecutorService pool;

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers) {
        this(providers, new ProviderCache(), null, null);
    }

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers, ProviderCache cache) {
        this(providers, cache, null, null);
    }

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers, ProviderCache cache, DriverCatalogDatabase catalogDatabase) {
        this(providers, cache, catalogDatabase, null);
    }

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers, ProviderCache cache, DriverCatalogDatabase catalogDatabase, ExecutorService pool) {
        this.providers = List.copyOf(providers);
        this.cache = cache;
        this.catalogDatabase = catalogDatabase;
        this.pool = pool;
    }

    public static DriverCatalogAggregator createDefault() {
        DriverCatalogDatabase catalog = DriverCatalogDatabase.load();
        java.time.Instant now = java.time.Instant.now();
        List<DriverCatalogProvider> providers = new ArrayList<>();
        providers.add(new OemNvidiaCatalogProvider(catalog));
        providers.add(new OemIntelCatalogProvider(catalog));
        if (catalog.hasFreshEntriesForProvider("AMD", now)) {
            providers.add(new OemAmdCatalogProvider(catalog));
        }
        if (catalog.hasFreshEntriesForProvider("Realtek", now)) {
            providers.add(new OemRealtekCatalogProvider(catalog));
        }
        if (catalog.hasFreshEntriesForProvider("Broadcom", now)) {
            providers.add(new OemBroadcomCatalogProvider(catalog));
        }
        if (catalog.hasFreshEntriesForProvider("Synaptics", now)) {
            providers.add(new OemSynapticsCatalogProvider(catalog));
        }
        providers.add(new WindowsUpdateCatalogProvider());
        AppLogger.info("CatalogAggregator: active providers: " + providers.size()
                + " (catalog freshness gate applied; configure WINZENITH_CATALOG_URL to extend OEM coverage)");
        return new DriverCatalogAggregator(providers, new ProviderCache(), catalog, com.sbtools.util.AppExecutors.ioPool());
    }

    public int providerCount() {
        return providers.size();
    }

    /**
     * Returns the number of providers that would actually run for the given installed drivers.
     * Used for accurate progress calculation.
     */
    public int relevantProviderCount(List<InstalledDriver> installed) {
        return relevantProviders(installed).size();
    }

    public void clearCache() {
        if (cache != null) {
            cache.clearAll();
        }
    }

    public void clearCacheForProvider(String providerId) {
        if (cache != null) {
            cache.clear(providerId);
        }
    }

    /**
     * Clears the Windows Update cache specifically — used after install attempts
     * so the next scan reflects the current WU state rather than stale 30m cache.
     */
    public void clearWindowsUpdateCache() {
        clearCacheForProvider("WindowsUpdate");
    }

    /**
     * Filters providers to only those relevant to the installed drivers.
     * OEM providers are skipped if no installed driver matches their vendor.
     * Windows Update provider is always included.
     * Public so callers can compute the list once and share it between the
     * progress count and the streaming run (each call re-detects vendors per
     * device with logging).
     */
    public List<DriverCatalogProvider> relevantProviders(List<InstalledDriver> installed) {
        Set<OemVendorHelper> presentVendors = EnumSet.noneOf(OemVendorHelper.class);
        if (installed != null) {
            for (InstalledDriver d : installed) {
                if (d == null) continue;
                try {
                    OemVendorHelper v = OemVendorHelper.detect(d);
                    if (v != null) {
                        presentVendors.add(v);
                    }
                } catch (Exception ignored) {}
            }
        }
        AppLogger.debug("CatalogAggregator: Detected vendors: " + presentVendors);
        List<DriverCatalogProvider> filtered = new ArrayList<>();
        for (DriverCatalogProvider p : providers) {
            if (p instanceof AbstractOemCatalogProvider oem) {
                if (oem.isVendorPresent(presentVendors)) {
                    filtered.add(p);
                }
            } else {
                filtered.add(p);
            }
        }
        AppLogger.debug("CatalogAggregator: " + filtered.size() + "/" + providers.size() + " providers relevant");
        return filtered;
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        return findUpdates(installed, CancellationToken.NONE);
    }

    private static boolean isUsableCandidate(DriverUpdateCandidate c) {
        return c != null && c.installed() != null
                && c.installed().deviceId() != null && !c.installed().deviceId().isBlank()
                && c.availableVersion() != null && !c.availableVersion().isBlank();
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed, CancellationToken token) {
        if (installed == null) return List.of();
        AppLogger.debug("CatalogAggregator: Scanning " + installed.size() + " installed drivers");
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        runProviders(installed, token, null, providerResults -> {
            if (providerResults == null) return;
            for (DriverUpdateCandidate c : providerResults) {
                if (!isUsableCandidate(c)) continue;
                byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
            }
        });
        AppLogger.debug("CatalogAggregator: Found " + byDevice.size() + " driver update candidates");
        return new ArrayList<>(byDevice.values());
    }

    /**
     * Queries each catalog provider in parallel and reports merged results after each provider finishes.
     */
    public void findUpdates(
            List<InstalledDriver> installed,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderFinished) {
        findUpdates(installed, CancellationToken.NONE, onProviderStarted, onProviderFinished);
    }

    /**
     * Cancellation-aware streaming variant. Runs every provider on a per-call
     * virtual-thread executor (efficient for I/O-bound HTTP/PowerShell work),
     * consults the on-disk {@link ProviderCache} before invoking a provider,
     * and writes fresh results back to the cache.
     */
    public void findUpdates(
            List<InstalledDriver> installed,
            CancellationToken token,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderFinished) {
        findUpdates(installed, token, onProviderStarted, onProviderFinished, null);
    }

    /**
     * Streaming variant with a precomputed provider list (see
     * {@link #relevantProviders(List)}): avoids running vendor detection
     * twice per scan. A null list computes it internally.
     */
    public void findUpdates(
            List<InstalledDriver> installed,
            CancellationToken token,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderFinished,
            List<DriverCatalogProvider> precomputedProviders) {
        final CancellationToken effectiveToken = token != null ? token : CancellationToken.NONE;
        if (installed == null) return;
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        runProviders(installed, effectiveToken, onProviderStarted, providerResults -> {
            if (effectiveToken.isCancelled()) {
                return;
            }
            if (providerResults == null) return;
            for (DriverUpdateCandidate c : providerResults) {
                if (!isUsableCandidate(c)) continue;
                byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
            }
            if (onProviderFinished != null) {
                onProviderFinished.accept(List.copyOf(byDevice.values()));
            }
        }, precomputedProviders);
    }

    private void runProviders(
            List<InstalledDriver> installed,
            CancellationToken token,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderResult) {
        runProviders(installed, token, onProviderStarted, onProviderResult, null);
    }

    private void runProviders(
            List<InstalledDriver> installed,
            CancellationToken token,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderResult,
            List<DriverCatalogProvider> precomputedProviders) {
        if (installed == null) return;
        List<DriverCatalogProvider> activeProviders = precomputedProviders != null
                ? List.copyOf(precomputedProviders) : relevantProviders(installed);
        if (activeProviders.isEmpty()) {
            return;
        }
        int maxConcurrent = Math.min(8, activeProviders.size());
        java.util.concurrent.Semaphore rateLimit = new java.util.concurrent.Semaphore(maxConcurrent);
        boolean ownsPool = pool == null;
        ExecutorService effectivePool = ownsPool
                ? Executors.newFixedThreadPool(maxConcurrent, r -> {
                    Thread t = new Thread(r, "catalog-provider");
                    t.setDaemon(true);
                    return t;
                })
                : pool;
        try {
            // Once-only result delivery per provider: a timed-out task that
            // later finishes must not invoke the callback a second time
            // (progress counters would overshoot past providerCount).
            java.util.Set<String> delivered = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.function.BiConsumer<String, List<DriverUpdateCandidate>> deliverOnce =
                    (providerId, results) -> {
                        if (onProviderResult == null || !delivered.add(providerId)) return;
                        try {
                            onProviderResult.accept(results);
                        } catch (Exception ex) {
                            AppLogger.warning("CatalogAggregator: Provider result handler failed for "
                                    + providerId + ": " + ex.getMessage());
                        }
                    };
            record ProviderTask(String id, java.util.concurrent.Future<?> future) {}
            var futures = activeProviders.stream()
                    .map(provider -> new ProviderTask(provider.id(), effectivePool.submit(() -> {
                        if (token.isCancelled()) {
                            return null;
                        }
                        try {
                            rateLimit.acquire();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        try {
                            if (token.isCancelled()) {
                                return null;
                            }
                            if (onProviderStarted != null) {
                                try { onProviderStarted.accept(provider.id()); } catch (Exception ignored) { }
                            }
                            List<DriverUpdateCandidate> results = queryProvider(provider, installed, token);
                            if (token.isCancelled()) {
                                return null;
                            }
                            deliverOnce.accept(provider.id(), results);
                            return null;
                        } finally {
                            rateLimit.release();
                        }
                    })))
                    .toList();
            long deadlineNanos = System.nanoTime()
                    + java.util.concurrent.TimeUnit.SECONDS.toNanos(PROVIDER_TIMEOUT_SECONDS);
            for (var task : futures) {
                var future = task.future();
                if (token.isCancelled() || Thread.currentThread().isInterrupted()) {
                    future.cancel(true);
                    continue;
                }
                long remainingNanos = deadlineNanos - System.nanoTime();
                if (remainingNanos <= 0) {
                    future.cancel(true);
                    if (!token.isCancelled() && !Thread.currentThread().isInterrupted()) {
                        deliverOnce.accept(task.id(), List.of());
                    }
                    continue;
                }
                try {
                    future.get(remainingNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    AppLogger.warning("CatalogAggregator: Provider timed out after " + PROVIDER_TIMEOUT_SECONDS + "s");
                    // Advance streaming progress: without a callback the
                    // Drivers-tab providersDone counter never reaches
                    // providerCount and progress/status freeze mid-scan.
                    // deliverOnce dedups against a late finish of the same
                    // provider so progress never overshoots past 100%.
                    if (!token.isCancelled() && !Thread.currentThread().isInterrupted()) {
                        deliverOnce.accept(task.id(), List.of());
                    }
                } catch (InterruptedException e) {
                    // Caller (e.g. Dashboard per-task timeout) interrupted us:
                    // release every queued provider so ioPool threads are not
                    // held by orphans that would starve the next scan.
                    Thread.currentThread().interrupt();
                    for (var t : futures) {
                        var q = t == null ? null : t.future();
                        if (q != null && !q.isDone()) {
                            try { q.cancel(true); } catch (Exception ignored) {}
                        }
                    }
                    break;
                } catch (Exception e) {
                    if (e.getCause() instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                        for (var t : futures) {
                            var q = t == null ? null : t.future();
                            if (q != null && !q.isDone()) {
                                try { q.cancel(true); } catch (Exception ignored) {}
                            }
                        }
                        break;
                    }
                }
            }
        } finally {
            if (ownsPool) {
                effectivePool.shutdown();
                try {
                    if (!effectivePool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        effectivePool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    effectivePool.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    private List<DriverUpdateCandidate> queryProvider(
            DriverCatalogProvider provider, List<InstalledDriver> installed, CancellationToken token) {
        if (cache != null) {
            Optional<List<DriverUpdateCandidate>> cached = cache.read(provider.id(), installed);
            if (cached.isPresent()) {
                AppLogger.debug("CatalogAggregator: cache hit for " + provider.id()
                        + " (" + cached.get().size() + " candidates)");
                return cached.get();
            }
        }
        if (token.isCancelled()) {
            return List.of();
        }
        List<DriverUpdateCandidate> fresh;
        long startNanos = System.nanoTime();
        try {
            fresh = provider.findUpdates(installed);
        } catch (Exception e) {
            AppLogger.warning("Provider " + provider.id() + " failed: " + e.getMessage());
            return List.of();
        } finally {
            long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000;
            // Per-provider timing pinpoints slow sources (e.g. WU PowerShell) in app.log.
            AppLogger.debug("CatalogAggregator: provider " + provider.id() + " finished in " + elapsedMs + "ms");
        }
        if (fresh == null) {
            fresh = List.of();
        }
        if (cache != null && !token.isCancelled()) {
            cache.write(provider.id(), installed, fresh);
        }
        return fresh;
    }

    private static DriverUpdateCandidate pickBetter(DriverUpdateCandidate existing, DriverUpdateCandidate incoming) {
        if (existing == null) return incoming;
        if (incoming == null) return existing;
        return isBetter(incoming, existing) ? incoming : existing;
    }

    private static boolean isBetter(DriverUpdateCandidate candidate, DriverUpdateCandidate existing) {
        // Version first: hiding a known-newer manual candidate behind an
        // older installable one shows a downgrade as "Available" and the user
        // never learns the newer release exists. Download capability only
        // breaks version ties (batch then reports manual-required with the
        // true newest version instead of auto-installing stale bits).
        int cmp = VersionCompare.compare(candidate.availableVersion(), existing.availableVersion());
        if (cmp != 0) {
            return cmp > 0;
        }

        boolean candidateHasDownload = hasWorkingDownload(candidate);
        boolean existingHasDownload = hasWorkingDownload(existing);

        if (candidateHasDownload && !existingHasDownload) {
            return true;
        }
        if (!candidateHasDownload && existingHasDownload) {
            return false;
        }

        if ("WindowsUpdate".equals(candidate.source()) && !"WindowsUpdate".equals(existing.source())) {
            return false;
        }
        return true;
    }

    private static boolean hasWorkingDownload(DriverUpdateCandidate candidate) {
        if (candidate.downloadUrl() != null && !candidate.downloadUrl().isBlank()) {
            return true;
        }
        if ("WindowsUpdate".equals(candidate.source())
                && candidate.packageId() != null && !candidate.packageId().isBlank()) {
            return true;
        }
        return false;
    }
}
