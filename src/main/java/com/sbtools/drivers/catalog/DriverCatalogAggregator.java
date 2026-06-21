package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.VersionCompare;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DriverCatalogAggregator {

    private final List<DriverCatalogProvider> providers;
    private final ProviderCache cache;
    private final DriverCatalogDatabase catalogDatabase;

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers) {
        this(providers, new ProviderCache(), null);
    }

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers, ProviderCache cache) {
        this(providers, cache, null);
    }

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers, ProviderCache cache, DriverCatalogDatabase catalogDatabase) {
        this.providers = List.copyOf(providers);
        this.cache = cache;
        this.catalogDatabase = catalogDatabase;
    }

    public static DriverCatalogAggregator createDefault() {
        DriverCatalogDatabase catalog = DriverCatalogDatabase.load();
        return new DriverCatalogAggregator(List.of(
                new OemNvidiaCatalogProvider(catalog),
                new OemAmdCatalogProvider(catalog),
                new OemIntelCatalogProvider(catalog),
                new OemRealtekCatalogProvider(catalog),
                new OemBroadcomCatalogProvider(catalog),
                new OemQualcommCatalogProvider(catalog),
                new OemSynapticsCatalogProvider(catalog),
                new OemLenovoCatalogProvider(catalog),
                new OemDellCatalogProvider(catalog),
                new OemHpCatalogProvider(catalog),
                new OemAsusCatalogProvider(catalog),
                new WindowsUpdateCatalogProvider()
        ), new ProviderCache(), catalog);
    }

    public int providerCount() {
        return providers.size();
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        return findUpdates(installed, CancellationToken.NONE);
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed, CancellationToken token) {
        AppLogger.debug("CatalogAggregator: Scanning " + installed.size() + " installed drivers");
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        runProviders(installed, token, null, providerResults -> {
            for (DriverUpdateCandidate c : providerResults) {
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
        final CancellationToken effectiveToken = token != null ? token : CancellationToken.NONE;
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        runProviders(installed, effectiveToken, onProviderStarted, providerResults -> {
            if (effectiveToken.isCancelled()) {
                return;
            }
            for (DriverUpdateCandidate c : providerResults) {
                byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
            }
            if (onProviderFinished != null) {
                onProviderFinished.accept(List.copyOf(byDevice.values()));
            }
        });
    }

    private void runProviders(
            List<InstalledDriver> installed,
            CancellationToken token,
            Consumer<String> onProviderStarted,
            Consumer<List<DriverUpdateCandidate>> onProviderResult) {
        // Rate-limit concurrent vendor requests to avoid HTTP 429 throttling.
        // 4 concurrent providers is a good balance between speed and politeness.
        int maxConcurrent = Math.min(4, providers.size());
        java.util.concurrent.Semaphore rateLimit = new java.util.concurrent.Semaphore(maxConcurrent);
        ExecutorService pool = Executors.newFixedThreadPool(maxConcurrent, r -> {
            Thread t = new Thread(r, "catalog-provider");
            t.setDaemon(true);
            return t;
        });
        try {
            var futures = providers.stream()
                    .map(provider -> pool.submit(() -> {
                        if (token.isCancelled()) {
                            return null;
                        }
                        rateLimit.acquire();
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
                            if (onProviderResult != null) {
                                try { onProviderResult.accept(results); } catch (Exception ignored) { }
                            }
                            return null;
                        } finally {
                            rateLimit.release();
                        }
                    }))
                    .toList();
            for (var future : futures) {
                if (token.isCancelled()) {
                    future.cancel(true);
                    continue;
                }
                try {
                    future.get();
                } catch (Exception e) {
                    if (e.getCause() instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            pool.shutdown();
        }
    }

    private List<DriverUpdateCandidate> queryProvider(
            DriverCatalogProvider provider, List<InstalledDriver> installed, CancellationToken token) {
        if (cache != null) {
            Optional<List<DriverUpdateCandidate>> cached = cache.read(provider.id(), installed);
            if (cached.isPresent()) {
                AppLogger.debug("CatalogAggregator: cache hit for " + provider.id());
                return cached.get();
            }
        }
        if (token.isCancelled()) {
            return List.of();
        }
        List<DriverUpdateCandidate> fresh;
        try {
            fresh = provider.findUpdates(installed);
        } catch (Exception e) {
            AppLogger.warning("Provider " + provider.id() + " failed: " + e.getMessage());
            return List.of();
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
        return isBetter(incoming, existing) ? incoming : existing;
    }

    private static boolean isBetter(DriverUpdateCandidate candidate, DriverUpdateCandidate existing) {
        boolean candidateHasDownload = hasWorkingDownload(candidate);
        boolean existingHasDownload = hasWorkingDownload(existing);

        if (candidateHasDownload && !existingHasDownload) {
            return true;
        }
        if (!candidateHasDownload && existingHasDownload) {
            return false;
        }

        if ("WindowsUpdate".equals(candidate.source())) {
            return !"WindowsUpdate".equals(existing.source())
                    || VersionCompare.compare(candidate.availableVersion(), existing.availableVersion()) > 0;
        }
        return VersionCompare.compare(candidate.availableVersion(), existing.availableVersion()) > 0;
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
