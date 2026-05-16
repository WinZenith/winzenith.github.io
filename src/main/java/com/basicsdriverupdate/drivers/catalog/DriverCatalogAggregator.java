package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.VersionCompare;

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
                new WindowsUpdateCatalogProvider()
        ));
    }

    public int providerCount() {
        return providers.size();
    }

    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        Map<String, DriverUpdateCandidate> byDevice = new HashMap<>();
        mergeProviderResults(byDevice, installed);
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
        int poolSize = Math.min(providers.size(), 4);
        try (ExecutorService pool = Executors.newFixedThreadPool(poolSize)) {
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
        }
    }

    private void mergeProviderResults(Map<String, DriverUpdateCandidate> byDevice, List<InstalledDriver> installed) {
        for (DriverCatalogProvider provider : providers) {
            for (DriverUpdateCandidate c : provider.findUpdates(installed)) {
                byDevice.merge(c.installed().deviceId(), c, DriverCatalogAggregator::pickBetter);
            }
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
