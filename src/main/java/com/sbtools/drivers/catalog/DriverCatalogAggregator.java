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

    private static final long PROVIDER_TIMEOUT_SECONDS = 180;

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
        ), new ProviderCache(), catalog, com.sbtools.util.AppExecutors.ioPool());
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

    /**
     * Filters providers to only those relevant to the installed drivers.
     * OEM providers are skipped if no installed driver matches their vendor.
     * Windows Update provider is always included.
     */
    private List<DriverCatalogProvider> relevantProviders(List<InstalledDriver> installed) {
        Set<OemVendorHelper> presentVendors = EnumSet.noneOf(OemVendorHelper.class);
        for (InstalledDriver d : installed) {
            OemVendorHelper v = OemVendorHelper.detect(d);
            if (v != null) {
                presentVendors.add(v);
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
        List<DriverCatalogProvider> activeProviders = relevantProviders(installed);
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
            var futures = activeProviders.stream()
                    .map(provider -> effectivePool.submit(() -> {
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
                    future.get(PROVIDER_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    future.cancel(true);
                    AppLogger.warning("CatalogAggregator: Provider timed out after " + PROVIDER_TIMEOUT_SECONDS + "s");
                } catch (Exception e) {
                    if (e.getCause() instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
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

        int cmp = VersionCompare.compare(candidate.availableVersion(), existing.availableVersion());
        if (cmp != 0) {
            return cmp > 0;
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
