package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.VersionCompare;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public class DriverCatalogAggregator {

    private final List<DriverCatalogProvider> providers;

    public DriverCatalogAggregator(List<DriverCatalogProvider> providers) {
        this.providers = List.copyOf(providers);
    }

    public static DriverCatalogAggregator createDefault() {
        return new DriverCatalogAggregator(List.of(
                new OemNvidiaCatalogProvider(),
                new OemAmdCatalogProvider(),
                new OemIntelCatalogProvider(),
                new OemRealtekCatalogProvider(),
                new OemBroadcomCatalogProvider(),
                new OemQualcommCatalogProvider(),
                new OemSynapticsCatalogProvider(),
                new OemLenovoCatalogProvider(),
                new OemDellCatalogProvider(),
                new OemHpCatalogProvider(),
                new OemAsusCatalogProvider(),
                new WindowsUpdateCatalogProvider()
        ));
    }

    public int providerCount() {
        return providers.size();
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        AppLogger.debug("CatalogAggregator: Scanning " + installed.size() + " installed drivers");
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        int poolSize = Math.min(providers.size(), 8);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            var futures = providers.stream()
                    .map(provider -> pool.submit(() -> {
                        for (DriverUpdateCandidate c : provider.findUpdates(installed)) {
                            byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
                        }
                        return null;
                    }))
                    .toList();
            for (var future : futures) {
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
        Map<String, DriverUpdateCandidate> byDevice = new ConcurrentHashMap<>();
        int poolSize = Math.min(providers.size(), 8);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            var futures = providers.stream()
                    .map(provider -> pool.submit(() -> {
                        if (onProviderStarted != null) {
                            onProviderStarted.accept(provider.id());
                        }
                        for (DriverUpdateCandidate c : provider.findUpdates(installed)) {
                            byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
                        }
                        if (onProviderFinished != null) {
                            onProviderFinished.accept(List.copyOf(byDevice.values()));
                        }
                        return null;
                    }))
                    .toList();
            for (var future : futures) {
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

    private static DriverUpdateCandidate pickBetter(DriverUpdateCandidate existing, DriverUpdateCandidate incoming) {
        return isBetter(incoming, existing) ? incoming : existing;
    }

    private static boolean isBetter(DriverUpdateCandidate candidate, DriverUpdateCandidate existing) {
        if ("WindowsUpdate".equals(candidate.source())) {
            return !"WindowsUpdate".equals(existing.source())
                    || VersionCompare.compare(candidate.availableVersion(), existing.availableVersion()) > 0;
        }
        return VersionCompare.compare(candidate.availableVersion(), existing.availableVersion()) > 0;
    }
}
