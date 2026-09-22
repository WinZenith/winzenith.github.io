package com.sbtools.ui;

import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.catalog.DriverCatalogProvider;
import com.sbtools.drivers.catalog.ProviderCache;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.ui.DashboardTabView.IssueCategory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks the two Dashboard critical fixes. Run as a main class. */
public final class DashboardCriticalFixCheck {

    public static void main(String[] args) throws Exception {
        checkCatalogFailureIsNotHealthy();
        checkConfirmedEmptyIsNotAFailure();
        checkCancelIsNotCachedAsFailure();
        checkPartialCleanupIsIncomplete();
        System.out.println("DashboardCriticalFixCheck ok");
    }

    private static void checkCatalogFailureIsNotHealthy() throws Exception {
        Path dir = Files.createTempDirectory("wz-dash-fail");
        ProviderCache cache = new ProviderCache(dir, 3600);
        AtomicInteger calls = new AtomicInteger();
        DriverCatalogProvider failing = new DriverCatalogProvider() {
            @Override public String id() { return "WindowsUpdate"; }
            @Override public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
                calls.incrementAndGet();
                throw new IllegalStateException("Windows Update driver search failed");
            }
        };
        DriverCatalogAggregator agg = new DriverCatalogAggregator(List.of(failing), cache, null, null);
        List<InstalledDriver> installed = List.of(device());
        List<DriverUpdateCandidate> found = agg.findUpdates(installed);
        check(found.isEmpty(), "failed search must not invent updates");
        String note = agg.providerFailureNote();
        check(note != null && note.contains("WindowsUpdate"), "failure must be visible: " + note);
        ProviderCache.CacheHit hit = cache.readHit("WindowsUpdate", installed).orElse(null);
        check(hit != null && hit.failed() && hit.candidates().isEmpty(), "failure must be a negative cache, not a clean miss");

        List<DriverUpdateCandidate> cached = agg.findUpdates(installed);
        check(cached.isEmpty(), "cached failure must stay empty");
        check(calls.get() == 1, "failed cache must not hammer the provider");
        check(agg.providerFailureNote() != null, "cached failure must still surface");

        agg.dropFailedProviderCache();
        check(cache.readHit("WindowsUpdate", installed).isEmpty(), "drop must forget the failed entry");
        agg.findUpdates(installed);
        check(calls.get() == 2, "after drop the next scan must query again");
    }

    private static void checkConfirmedEmptyIsNotAFailure() {
        Path dir;
        try {
            dir = Files.createTempDirectory("wz-dash-empty");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ProviderCache cache = new ProviderCache(dir, 3600);
        DriverCatalogProvider empty = new DriverCatalogProvider() {
            @Override public String id() { return "EmptyOk"; }
            @Override public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
                return List.of();
            }
        };
        DriverCatalogAggregator agg = new DriverCatalogAggregator(List.of(empty), cache, null, null);
        List<InstalledDriver> installed = List.of(device());
        check(agg.findUpdates(installed).isEmpty(), "no offers is empty");
        check(agg.providerFailureNote() == null, "confirmed empty must not look like a failure");
        ProviderCache.CacheHit hit = cache.readHit("EmptyOk", installed).orElseThrow();
        check(!hit.failed(), "confirmed empty cache must not be flagged failed");
        agg.dropFailedProviderCache();
        check(cache.readHit("EmptyOk", installed).isPresent(), "drop must keep confirmed-empty cache");
    }

    private static void checkCancelIsNotCachedAsFailure() throws Exception {
        Path dir = Files.createTempDirectory("wz-dash-cancel");
        ProviderCache cache = new ProviderCache(dir, 3600);
        DriverCatalogProvider cancelled = new DriverCatalogProvider() {
            @Override public String id() { return "WindowsUpdate"; }
            @Override public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
                throw new CancellationException("Windows Update driver search interrupted");
            }
        };
        DriverCatalogAggregator agg = new DriverCatalogAggregator(List.of(cancelled), cache, null, null);
        check(agg.findUpdates(List.of(device())).isEmpty(), "cancel returns no rows");
        check(agg.providerFailureNote() == null, "cancel must not be reported as a catalog failure");
        check(cache.readHit("WindowsUpdate", List.of(device())).isEmpty(), "cancel must not poison the cache");
    }

    private static void checkPartialCleanupIsIncomplete() {
        IssueCategory partial = new IssueCategory(
                "Temporary files", "12 MB (3 files)", "12 MB", "Cleanup", 12_000_000L);
        check(!DashboardTabView.hasTimeoutPlaceholderTarget(List.of(partial), "System Cleanup"),
                "a finished cleanup row must not hide the timeout");
        IssueCategory timeout = IssueCategory.error(
                "System Cleanup", "Timed out — press Retry", "", "Cleanup", 0);
        check(DashboardTabView.hasTimeoutPlaceholderTarget(List.of(timeout), "System Cleanup"),
                "an existing timeout row is the placeholder");
        List<IssueCategory> rows = List.of(partial, timeout);
        check(DashboardTabView.cleanupTimedOut(rows), "timeout row marks cleanup incomplete");
        String status = DashboardTabView.scanFinishedStatus(0, 1, 1, true);
        check(status.startsWith("Scan incomplete"), "partial cleanup must not say scan complete: " + status);
        check(DashboardTabView.scanFinishedStatus(0, 1, 0, false).startsWith("Scan complete"),
                "full scan stays complete");
        String summary = DashboardTabView.scanFinishedSummary(0, 1, "12 MB", 1, true);
        check(summary.contains("partial"), "summary must say the reclaimable total is partial: " + summary);
        check(!DashboardTabView.scanFinishedSummary(0, 1, "12 MB", 0, false).contains("partial"),
                "complete summary must not say partial");
    }

    private static InstalledDriver device() {
        return new InstalledDriver(
                "PCI\\VEN_10DE&DEV_1C82", "Test Device", "PCI\\VEN_10DE&DEV_1C82", "NVIDIA",
                "1.0.0.0", "oem1.inf", "", "OK", null, Boolean.TRUE);
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
