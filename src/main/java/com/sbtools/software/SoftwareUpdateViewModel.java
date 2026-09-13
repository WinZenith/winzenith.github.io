package com.sbtools.software;

import com.sbtools.backup.SystemRestoreService;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.geometry.HPos;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TitledPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class SoftwareUpdateViewModel {

    private static final long INSTALL_TIMEOUT_WINGET_SECONDS = 1200;
    private static final long INSTALL_TIMEOUT_WU_SECONDS = 3600;

    private final SoftwareUpdateService service = new SoftwareUpdateService();
    private final SystemRestoreService restoreService = new SystemRestoreService();
    private final SettingsStore settingsStore = new SettingsStore();

    private final BooleanProperty globalBusy;
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;

    private final ObservableList<SoftwareUpdateEntry> rows = FXCollections.observableArrayList();
    private final StringProperty statusText = new SimpleStringProperty("Scan for winget app + Windows updates (Store apps not checked).");
    private final DoubleProperty batchProgress = new SimpleDoubleProperty(0);
    private final StringProperty batchProgressText = new SimpleStringProperty();
    private final BooleanProperty showRetryFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty showBatchProgress = new SimpleBooleanProperty(false);

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final AtomicBoolean scanCancelled = new AtomicBoolean(false);
    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private final AtomicInteger scanGeneration = new AtomicInteger(0);
    private final Object scanLock = new Object();
    private volatile Future<?> scanFuture;
    /** Set when a WU install was cancelled: killing PowerShell does not abort WUA/CBS. */
    private final AtomicBoolean wuServicingUnacked = new AtomicBoolean(false);
    private static final String WU_SERVICING_WARNING =
            "Stopping this app does not cancel Windows Update Agent. The update may still download or install "
                    + "in the background and can leave a pending reboot.\n\n"
                    + "Reboot before installing more Windows Updates.";
    private final AtomicBoolean installCancelled = new AtomicBoolean(false);
    private final AtomicBoolean installRunning = new AtomicBoolean(false);
    private final AtomicBoolean restorePointCreatedThisBatch = new AtomicBoolean(false);
    private final List<SoftwareUpdateEntry> failedEntries = new ArrayList<>();
    private final Map<String, SoftwareInstallFailure.Result> lastFailureByPackageId = new ConcurrentHashMap<>();

    private record RepairFailureRecord(SoftwareUpdateEntry entry, SoftwareInstallFailure.Result result) {}
    // Ownership epoch for the failure/retry state: every full invalidation (fresh scan,
    // new batch, new retry owner) bumps it. Delayed retry dispatches carry the epoch they
    // captured and die silently when it no longer matches instead of resurrecting stale rows.
    private final java.util.concurrent.atomic.AtomicLong failureEpoch = new java.util.concurrent.atomic.AtomicLong(0);
    private volatile boolean disposed = false;

    private Consumer<String> onWingetNotAvailable;

    @FunctionalInterface
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }

    public SoftwareUpdateViewModel(BooleanProperty globalBusy, BooleanSupplier adminCheck) {
        this.globalBusy = globalBusy;
        this.adminCheck = adminCheck;
        // Mirror local busy to global busy
        this.busy.addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                globalBusy.set(true);
            } else {
                if (!installRunning.get()) {
                    globalBusy.set(false);
                }
            }
        });
    }

    public ObservableList<SoftwareUpdateEntry> getRows() { return rows; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty batchProgressProperty() { return batchProgress; }
    public StringProperty batchProgressTextProperty() { return batchProgressText; }
    public BooleanProperty showRetryFailedProperty() { return showRetryFailed; }
    public BooleanProperty showBatchProgressProperty() { return showBatchProgress; }
    public BooleanProperty busyProperty() { return busy; }

    public boolean isInstallRunning() {
        return installRunning.get();
    }

    public void setOnWingetNotAvailable(Consumer<String> handler) {
        this.onWingetNotAvailable = handler;
    }

    public boolean isWingetAvailable() {
        return service.isWingetAvailable();
    }

    public String getWingetDiagnostics() {
        return service.getWingetDiagnostics();
    }

    public void scan() {
        if (disposed) return;
        // Block concurrent scan/install: check both busy and installRunning synchronously on caller thread.
        // busy/installRunning are set synchronously below, so rapid double-clicks cannot start overlapping scans.
        // globalBusy covers other tabs' operations (their buttons are disabled the same way).
        if (busy.get() || installRunning.get() || globalBusy.get()) return;
        final int gen;
        synchronized (scanLock) {
            if (disposed || busy.get() || installRunning.get() || globalBusy.get()) return;
            gen = scanGeneration.incrementAndGet();
            scanCancelled.set(false);
            scanRunning.set(true);
        }
        restorePointCreatedThisBatch.set(false);
        // A fresh scan invalidates any previous failure state. Without this, Retry Failed
        // after a re-scan would reinstall orphaned entries from the old scan (wrong versions,
        // rows no longer displayed) and a maxed-out retryCount would block retries forever.
        synchronized (failedEntries) { failedEntries.clear(); }
        lastFailureByPackageId.clear();
        failureEpoch.incrementAndGet();
        retryCount.set(0);
        // Set busy synchronously when already on FX thread to close the race where a second
        // Scan click arrives before the async runLater from the first click executes.
        if (Platform.isFxApplicationThread()) {
            if (disposed || !isCurrentScan(gen)) {
                synchronized (scanLock) {
                    if (gen == scanGeneration.get()) scanRunning.set(false);
                }
                return;
            }
            busy.set(true);
            showRetryFailed.set(false);
            statusText.set("Scanning for updates...");
        } else {
            Platform.runLater(() -> {
                if (disposed || !isCurrentScan(gen)) return;
                busy.set(true);
                showRetryFailed.set(false);
                statusText.set("Scanning for updates...");
            });
        }
        try {
            Future<?> submitted = executor.submit(() -> scanInternal(gen), "SoftwareUpdate-Scan");
            synchronized (scanLock) {
                if (!isCurrentScan(gen)) {
                    submitted.cancel(true);
                    return;
                }
                scanFuture = submitted;
            }
        } catch (Exception ex) {
            AppLogger.warning("Failed to submit scan (shutting down?): " + ex.getMessage());
            synchronized (scanLock) {
                if (isCurrentScan(gen)) {
                    scanRunning.set(false);
                    if (scanGeneration.get() == gen) scanFuture = null;
                }
            }
            Platform.runLater(() -> {
                if (!disposed && isCurrentScan(gen) && !installRunning.get()) busy.set(false);
            });
        }
    }

    private boolean isCurrentScan(int gen) {
        return !disposed && gen == scanGeneration.get();
    }

    private void scanInternal(int gen) {
        try {
            if (!isCurrentScan(gen)) return;
            boolean wingetAvailable = service.isWingetAvailable();
            if (!wingetAvailable && isCurrentScan(gen)) {
                String diag = service.getWingetDiagnostics();
                if (onWingetNotAvailable != null) {
                    Platform.runLater(() -> {
                        if (!disposed && isCurrentScan(gen)) onWingetNotAvailable.accept(diag);
                    });
                }
            }

            final int[] counts = {0, 0};
            List<SoftwareUpdateEntry> allUpdates = service.scanAllConcurrent(
                    () -> !isCurrentScan(gen),
                    wc -> counts[0] = wc,
                    wuc -> counts[1] = wuc,
                    SoftwareUpdateService.DEFAULT_SCAN_ALL_TIMEOUT_SECONDS
            );

            if (!isCurrentScan(gen)) return;

            AppSettings settings = settingsStore.load();
            List<String> skippedIds = settings.skippedSoftwareIds();
            if (skippedIds == null) skippedIds = List.of();
            // Winget ids (and WU GUIDs) are case-insensitive, and dedupeById is
            // case-insensitive — the ignore filter must match that or an ignored
            // "Google.Chrome" reappears as "google.chrome" on the next scan.
            Set<String> skippedIdSet = skippedIds.stream()
                    .map(s -> {
                        int t = s.lastIndexOf('\t');
                        String id = t >= 0 ? s.substring(t + 1) : s;
                        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            List<SoftwareUpdateEntry> filteredUpdates = allUpdates.stream()
                    .filter(e -> {
                        if (e == null) return false;
                        // WindowsUpdate rows are only actionable when the WU updateId exists.
                        // Reject synthetic/placeholder ids and blank identifiers outright so phantom
                        // rows (which install validation would always fail) never reach the table.
                        if ("WindowsUpdate".equals(e.source())) {
                            if (e.updateId() == null || e.updateId().isBlank()) return false;
                            if (e.id() == null || e.id().isBlank()) return false;
                            return !skippedIdSet.contains(e.id().trim().toLowerCase(java.util.Locale.ROOT));
                        }
                        // winget rows: entries with blank id were already filtered in
                        // SoftwareUpdateService, but guard here as defense-in-depth.
                        if (e.id() == null || e.id().isBlank()) {
                            return false;
                        }
                        return !skippedIdSet.contains(e.id().trim().toLowerCase(java.util.Locale.ROOT));
                    })
                    .collect(Collectors.toList());
            // Deduplicate by id (case-insensitive, keep-first = winget wins since winget
            // results precede WU in scanAllConcurrent). Prevents double rows when both
            // parsers/scans surface the same package.
            final List<SoftwareUpdateEntry> dedupedUpdates = dedupeById(filteredUpdates);

            // Recompute counts after filtering for accurate UI message
            long filteredWc = dedupedUpdates.stream().filter(e -> !"WindowsUpdate".equals(e.source())).count();
            long filteredWu = dedupedUpdates.stream().filter(e -> "WindowsUpdate".equals(e.source())).count();
            final int wc = counts[0];
            final int wuc = counts[1];

            if (!isCurrentScan(gen)) return;
            String wuError = service.getLastWindowsUpdateError();
            String wingetError = service.getLastWingetError();
            boolean wuFailed = wuError != null && !wuError.isBlank();
            boolean wingetFailed = wingetError != null && !wingetError.isBlank();
            // Stale fallback: live scan empty + source error(s) + fresh cache -> show cached
            // (ignored-filtered) with an explicit stale label instead of a false "up to date".
            List<SoftwareUpdateEntry> displayUpdates = dedupedUpdates;
            boolean showingStale = false;
            java.time.Instant staleAt = null;
            if (dedupedUpdates.isEmpty() && (wuFailed || wingetFailed)) {
                try {
                    var cachedOpt = SoftwareUpdateScanCache.getIfFresh();
                    if (cachedOpt.isPresent() && cachedOpt.get().entries() != null
                            && !cachedOpt.get().entries().isEmpty()) {
                        List<SoftwareUpdateEntry> cachedFiltered = cachedOpt.get().entries().stream()
                                .filter(e -> {
                                    if (e == null || e.id() == null || e.id().isBlank()) return false;
                                    String key = e.id().trim().toLowerCase(java.util.Locale.ROOT);
                                    if ("WindowsUpdate".equals(e.source())) {
                                        return e.updateId() != null && !e.updateId().isBlank()
                                                && !skippedIdSet.contains(key);
                                    }
                                    return !skippedIdSet.contains(key);
                                })
                                .collect(Collectors.toList());
                        cachedFiltered = dedupeById(cachedFiltered);
                        if (!cachedFiltered.isEmpty()) {
                            displayUpdates = cachedFiltered;
                            showingStale = true;
                            staleAt = cachedOpt.get().cachedAt();
                        }
                    }
                } catch (Exception ignored) {}
            }
            final List<SoftwareUpdateEntry> finalDisplay = displayUpdates;
            final boolean finalStale = showingStale;
            final java.time.Instant finalStaleAt = staleAt;
            Platform.runLater(() -> {
                if (disposed || !isCurrentScan(gen)) return;
                rows.setAll(finalDisplay);
                if (finalStale) {
                    long mins = finalStaleAt == null ? -1
                            : java.time.Duration.between(finalStaleAt, java.time.Instant.now()).toMinutes();
                    String age = mins < 0 ? "" : mins < 1 ? " (just now)" : " (" + mins + " min ago)";
                    statusText.set("Live scan had warnings — showing cached results" + age + ": "
                            + finalDisplay.size() + " item(s). Press Scan to retry.");
                    AppLogger.warning("Showing stale software cache (" + finalDisplay.size() + " items)");
                    return;
                }
                if (filteredWc > 0 && filteredWu > 0) {
                    statusText.set(dedupedUpdates.size() + " outdated item(s) found (" + filteredWc + " winget app(s), " + filteredWu + " Windows Update(s)). Store apps are not checked.");
                } else if (filteredWc > 0) {
                    if (wuFailed) {
                        statusText.set(filteredWc + " outdated winget app(s) found. (Windows Update check failed; Store apps not checked)");
                        AppLogger.warning("WU error surfaced to UI: " + wuError);
                    } else {
                        statusText.set(filteredWc + " outdated winget app(s) found. (Store apps not checked)");
                    }
                } else if (filteredWu > 0) {
                    if (wingetFailed) {
                        statusText.set(filteredWu + " Windows Update(s) found. (winget check had errors; Store apps not checked)");
                    } else {
                        statusText.set(filteredWu + " Windows Update(s) found. (Store apps not checked)");
                    }
                } else if (wc > 0 || wuc > 0) {
                    // All found were ignored
                    statusText.set("No updates to install. (" + (wc + wuc - dedupedUpdates.size()) + " ignored; Store apps not checked)");
                } else {
                    if (wuFailed || wingetFailed) {
                        String err = SoftwareUpdateService.formatScanSourceError(wingetError, wuError);
                        String shortErr = err.length() > 120 ? err.substring(0, 120) + "..." : err;
                        statusText.set("Scan completed with warnings: " + shortErr);
                        AppLogger.warning("Scan warning surfaced: " + err);
                    } else {
                        statusText.set("No winget app or Windows updates found. (Microsoft Store apps are not checked here)");
                    }
                }
                // If WU failed but winget succeeded with 0 results, surface as warning not false "up to date"
                if (wuFailed && filteredWu == 0 && filteredWc == 0 && (wc + wuc == 0)) {
                    // Only if truly no updates but WU error occurred, keep warning already set
                }
            });
        } catch (Exception ex) {
            if (isCurrentScan(gen)) {
                Platform.runLater(() -> {
                    if (disposed || !isCurrentScan(gen)) return;
                    statusText.set("Scan failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                });
            }
        } finally {
            synchronized (scanLock) {
                if (gen == scanGeneration.get()) {
                    scanFuture = null;
                    scanRunning.set(false);
                }
            }
            Platform.runLater(() -> {
                if (!disposed && gen == scanGeneration.get() && !installRunning.get()) {
                    busy.set(false);
                }
            });
        }
    }

    public void stopScan() {
        final Future<?> toCancel;
        final boolean wasScanning;
        final int stopGen;
        synchronized (scanLock) {
            scanCancelled.set(true);
            wasScanning = scanRunning.getAndSet(false);
            // Invalidate the in-flight worker even if Scan is pressed before finally runs.
            stopGen = wasScanning ? scanGeneration.incrementAndGet() : scanGeneration.get();
            toCancel = scanFuture;
            scanFuture = null;
        }
        if (toCancel != null) {
            try {
                toCancel.cancel(true);
            } catch (Exception ignored) {
            }
        }
        if (!wasScanning) return;
        Platform.runLater(() -> {
            if (disposed || installRunning.get()) return;
            if (scanGeneration.get() != stopGen || scanRunning.get()) return;
            busy.set(false);
            statusText.set("Scan stopped.");
        });
    }

    public void cancelInstall() {
        Platform.runLater(() -> {
            if (disposed) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Cancel remaining updates?");
            confirm.setHeaderText("Cancel Install");
            confirm.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK) {
                    installCancelled.set(true);
                    statusText.set("Cancelling updates...");
                }
            });
        });
    }

    public void updateSelected(List<SoftwareUpdateEntry> selected) {
        updateSelected(selected, false);
    }

    private void updateSelected(List<SoftwareUpdateEntry> selected, boolean isRetry) {
        if (disposed) return;
        // Synchronous mutual exclusion: block overlapping batch/single installs and scans.
        // Claim installRunning immediately (before the async restore-point dialog) so rapid
        // double-clicks or Update-Selected + per-row Update cannot start parallel winget/MSI runs.
        // globalBusy covers other tabs' operations.
        if (installRunning.get() || busy.get() || globalBusy.get()) {
            Platform.runLater(() -> {
                if (!disposed) new Alert(Alert.AlertType.INFORMATION, "Another operation is already in progress. Please wait.").showAndWait();
            });
            return;
        }
        // Validate selection first so an empty selection shows only the
        // "select at least one" hint instead of stacking the admin notice + hint.
        if (selected == null || selected.isEmpty()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, "Select at least one program to update.").showAndWait());
            return;
        }
        if (blockIfWuServicingUnacked(selected)) return;
        // Non-admin users can still update per-user winget packages. Do NOT hard-block:
        // show a one-shot notice and let the install attempt run; system-level and
        // Windows Update items will fail gracefully with access-denied if elevated
        // rights are truly required.
        try {
            boolean isAdmin = adminCheck.getAsBoolean();
            if (!isAdmin) {
                boolean hasWu = selected != null && selected.stream().anyMatch(e -> e != null && "WindowsUpdate".equals(e.source()));
                String notice = hasWu
                        ? "Running without administrator rights. Per-user apps can still update, but Windows Update / system items will likely fail with access denied."
                        : "Running without administrator rights. Per-user apps can still update; system-level apps may fail with access denied.";
                AppLogger.info("Non-admin batch update attempt (" + (selected == null ? 0 : selected.size()) + " item(s))");
                Platform.runLater(() -> {
                    if (!disposed) new Alert(Alert.AlertType.INFORMATION, notice).showAndWait();
                });
            }
        } catch (Exception ex) {
            AppLogger.warning("Admin check failed, proceeding as non-admin: " + ex.getMessage());
        }
        if (!installRunning.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                if (!disposed) new Alert(Alert.AlertType.INFORMATION, "Another operation is already in progress. Please wait.").showAndWait();
            });
            return;
        }
        installCancelled.set(false);
        // Defensive snapshot: scan may replace rows while the restore-point dialog is open.
        List<SoftwareUpdateEntry> snapshot = new ArrayList<>(selected);
        // Disable UI immediately to close the race before the async chain sets busy.
        if (Platform.isFxApplicationThread()) {
            busy.set(true);
            statusText.set("Preparing to install " + snapshot.size() + " update(s)...");
        } else {
            Platform.runLater(() -> {
                if (!disposed) {
                    busy.set(true);
                    statusText.set("Preparing to install " + snapshot.size() + " update(s)...");
                }
            });
        }

        restorePointCreatedThisBatch.set(false);
        try {
            maybeCreateRestorePointAsync()
                    .thenComposeAsync(this::finalizePrepareOutcome, executor)
                    .thenAcceptAsync(proceed -> {
            if (!proceed || shouldAbortInstallPreparation()) {
                abortInstallPreparation(installCancelled.get()
                        ? "Update cancelled."
                        : "Update aborted (restore point not created).");
                return;
            }
            synchronized (failedEntries) { failedEntries.clear(); }
            failureEpoch.incrementAndGet();
            if (!isRetry) retryCount.set(0);
            int total = snapshot.size();
            Platform.runLater(() -> {
                if (disposed) return;
                busy.set(true);
                statusText.set("Installing " + total + " update(s)...");
                showBatchProgress.set(true);
                batchProgress.set(0);
                batchProgressText.set("0 / " + total);
                showRetryFailed.set(false);
            });

            try {
                executor.submit(() -> runBatchInstall(snapshot, total), "SoftwareUpdate-BatchOrchestrator");
            } catch (Exception ex) {
                AppLogger.warning("Failed to submit batch install: " + ex.getMessage());
                abortInstallPreparation(null);
            }
            }, executor);
        } catch (Exception ex) {
            AppLogger.warning("Failed to start batch install (shutting down?): " + ex.getMessage());
            installRunning.set(false);
            Platform.runLater(() -> {
                showBatchProgress.set(false);
                if (!disposed) busy.set(false);
            });
        }
    }

    private void runBatchInstall(List<SoftwareUpdateEntry> selected, int total) {
        AtomicInteger completed = new AtomicInteger(0);
        List<SoftwareUpdateEntry> failedPackages = new ArrayList<>();
        List<SoftwareUpdateEntry> manualRepairEntries = new ArrayList<>();
        List<RepairFailureRecord> manualRepairDetails = new ArrayList<>();
        List<SoftwareUpdateEntry> techMismatchEntries = new ArrayList<>();
        List<SoftwareUpdateEntry> successfulEntries = new ArrayList<>();
        Instant batchStartTime = Instant.now();
        AtomicBoolean rebootRequiredAbort = new AtomicBoolean(false);

        // Sequential install – MSI global mutex makes parallel unsafe and installExecutor=1 already serialized.
        // Loop directly so cancellation between items is immediate and progress is deterministic.
        for (SoftwareUpdateEntry e : selected) {
            if (installCancelled.get() || disposed || rebootRequiredAbort.get()) {
                if (rebootRequiredAbort.get()) AppLogger.info("Batch aborted after reboot-required: skipping " + e.id());
                else AppLogger.info("Batch install cancelled before " + e.id());
                break;
            }
            try {
                boolean needsReboot = installOne(e, total, completed, failedPackages, manualRepairEntries,
                        manualRepairDetails, techMismatchEntries, successfulEntries, batchStartTime);
                if (needsReboot) {
                    rebootRequiredAbort.set(true);
                    AppLogger.info("Reboot required after " + e.id() + " – aborting remaining batch items");
                    // Show reboot prompt once (installOne already queued an alert); break after current
                }
            } catch (Exception ex) {
                AppLogger.warning("Batch install step failed for " + e.id() + ": " + ex.getMessage());
            }
            if (installCancelled.get() || disposed || rebootRequiredAbort.get()) break;
        }

        final List<SoftwareUpdateEntry> finalSuccessful = new ArrayList<>(successfulEntries);
        final int finalCompleted = completed.get();
        List<SoftwareUpdateEntry> finalFailed = new ArrayList<>(failedPackages);
        List<SoftwareUpdateEntry> finalManualRepair = new ArrayList<>(manualRepairEntries);
        List<RepairFailureRecord> finalManualRepairDetails = new ArrayList<>(manualRepairDetails);
        List<SoftwareUpdateEntry> finalTechMismatch = new ArrayList<>(techMismatchEntries);

        final boolean rebootAbort = rebootRequiredAbort.get();
        final int skippedDueToReboot = rebootAbort ? (selected.size() - finalCompleted) : 0;
        // System state changed — cached scan results are stale from here on.
        if (!finalSuccessful.isEmpty()) {
            try { SoftwareUpdateScanCache.invalidate(); } catch (Exception ignored) {}
        }
        InstallerCleanupHelper.promptAndCleanupBatchAsync(service, finalSuccessful, batchStartTime)
                .exceptionally(ex -> {
                    AppLogger.warning("Batch cleanup failed: " + ex.getMessage());
                    return false;
                })
                .orTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                .exceptionally(ex -> {
                    AppLogger.warning("Batch cleanup timed out or failed: " + ex.getMessage());
                    return false;
                })
                .whenComplete((v, ex) -> {
                    // Always clear busy/progress even if disposed or timed out – prevents stuck globalBusy (B8)
                    installRunning.set(false);
                    Platform.runLater(() -> {
                        showBatchProgress.set(false);
                        busy.set(false);
                    });
                })
                .thenRunAsync(() -> Platform.runLater(() -> {
                    if (disposed) {
                        // Ensure global busy cleared even after dispose
                        busy.set(false);
                        return;
                    }
                    if (installCancelled.get()) {
                        if (wuServicingUnacked.get()) {
                            statusText.set("Update cancelled. Windows Update may still be applying — reboot before installing more.");
                            showWuServicingInfoDialog();
                        } else {
                            statusText.set("Update cancelled. " + finalCompleted + " of " + total + " completed.");
                        }
                        // Items that failed BEFORE the cancel still deserve a retry path:
                        // without this their rows show Failed but Retry Failed stays hidden.
                        if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty() || !finalManualRepair.isEmpty()) {
                            refreshFailedRows(finalFailed, finalManualRepair);
                            for (SoftwareUpdateEntry te : finalTechMismatch) {
                                synchronized (failedEntries) { if (!failedEntries.contains(te)) failedEntries.add(te); }
                            }
                            showRetryFailed.set(!finalFailed.isEmpty());
                            if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty() || !finalManualRepair.isEmpty()) {
                                showBatchResultDialog(finalFailed, finalTechMismatch, finalManualRepair, finalManualRepairDetails);
                            }
                        }
                    } else if (rebootAbort) {
                        statusText.set("Reboot required – " + finalCompleted + " installed, " + skippedDueToReboot + " skipped. Please reboot and re-scan.");
                        if (!finalFailed.isEmpty() || !finalManualRepair.isEmpty()) {
                            refreshFailedRows(finalFailed, finalManualRepair);
                            showRetryFailed.set(!finalFailed.isEmpty());
                        }
                        if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty() || !finalManualRepair.isEmpty()) {
                            showBatchResultDialog(finalFailed, finalTechMismatch, finalManualRepair, finalManualRepairDetails);
                        } else {
                            new Alert(Alert.AlertType.INFORMATION, "A restart is required to finish installation. Remaining updates were skipped – please reboot first.").showAndWait();
                        }
                    } else if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty() || !finalManualRepair.isEmpty()) {
                        int problemCount = finalFailed.size() + finalManualRepair.size();
                        statusText.set("Completed with " + problemCount + " failure(s). "
                                + (finalFailed.isEmpty() ? "Repair required items and re-scan." : "Use \"Retry Failed\" or re-scan."));
                        refreshFailedRows(finalFailed, finalManualRepair);
                        showRetryFailed.set(!finalFailed.isEmpty());
                        showBatchResultDialog(finalFailed, finalTechMismatch, finalManualRepair, finalManualRepairDetails);
                    } else {
                        statusText.set("All selected updates installed successfully.");
                        showRetryFailed.set(false);
                        scan();
                    }
                }), executor);
    }

    public void updateSingle(SoftwareUpdateEntry entry) {
        if (disposed || entry == null) return;
        if (SoftwareUpdateEntry.requiresManualRepair(entry.getStatus())) {
            SoftwareInstallFailure.Result cached = lastFailureByPackageId.get(entry.id());
            Platform.runLater(() -> {
                if (disposed) return;
                if (cached != null) {
                    showInstallFailureDialog(entry, cached);
                } else {
                    new Alert(Alert.AlertType.WARNING,
                            entry.getLastError() != null && !entry.getLastError().isBlank()
                                    ? entry.getLastError()
                                    : "Manual repair is required before this update can run. Repair the installed product, then press Scan.")
                            .showAndWait();
                }
            });
            return;
        }
        if (installRunning.get() || busy.get() || globalBusy.get()) {
            Platform.runLater(() -> {
                if (!disposed) new Alert(Alert.AlertType.INFORMATION, "Another operation is already in progress. Please wait.").showAndWait();
            });
            return;
        }
        // Same non-blocking policy as batch: warn but allow per-user installs.
        try {
            boolean isAdmin = adminCheck.getAsBoolean();
            if (!isAdmin) {
                boolean isWu = "WindowsUpdate".equals(entry.source());
                String notice = isWu
                        ? "Running without administrator rights. This Windows Update will likely fail with access denied."
                        : "Running without administrator rights. Per-user apps can still update; system-level apps may fail with access denied.";
                AppLogger.info("Non-admin single update attempt for " + entry.id());
                Platform.runLater(() -> {
                    if (!disposed) new Alert(Alert.AlertType.INFORMATION, notice).showAndWait();
                });
            }
        } catch (Exception ex) {
            AppLogger.warning("Admin check failed, proceeding as non-admin: " + ex.getMessage());
        }
        if (blockIfWuServicingUnacked(List.of(entry))) return;
        if (!installRunning.compareAndSet(false, true)) {
            Platform.runLater(() -> {
                if (!disposed) new Alert(Alert.AlertType.INFORMATION, "Another operation is already in progress. Please wait.").showAndWait();
            });
            return;
        }
        installCancelled.set(false);
        if (Platform.isFxApplicationThread()) {
            busy.set(true);
            statusText.set("Preparing to install update for " + entry.getName() + "...");
        } else {
            Platform.runLater(() -> {
                if (!disposed) {
                    busy.set(true);
                    statusText.set("Preparing to install update for " + entry.getName() + "...");
                }
            });
        }

        restorePointCreatedThisBatch.set(false);
        try {
            maybeCreateRestorePointAsync()
                    .thenComposeAsync(this::finalizePrepareOutcome, executor)
                    .thenAcceptAsync(proceed -> {
            if (!proceed || shouldAbortInstallPreparation()) {
                abortInstallPreparation(installCancelled.get()
                        ? "Update cancelled."
                        : "Update aborted (restore point not created).");
                return;
            }
            synchronized (failedEntries) { failedEntries.clear(); }
            Platform.runLater(() -> {
                if (disposed) return;
                busy.set(true);
                statusText.set("Installing update for " + entry.getName() + "...");
            });

            try {
                installExecutor.submit(() -> runSingleInstall(entry), "SoftwareUpdate-SingleInstall-" + entry.id());
            } catch (Exception ex) {
                AppLogger.warning("Failed to submit single install: " + ex.getMessage());
                abortInstallPreparation(null);
            }
            }, executor);
        } catch (Exception ex) {
            AppLogger.warning("Failed to start single install (shutting down?): " + ex.getMessage());
            installRunning.set(false);
            Platform.runLater(() -> {
                if (!disposed) busy.set(false);
            });
        }
    }

    private void runSingleInstall(SoftwareUpdateEntry entry) {
        // Validate before touching UI to avoid NPE.
        // installRunning was claimed by updateSingle(); validation failures must release it,
        // otherwise all future installs stay blocked.
        if ("WindowsUpdate".equals(entry.source())) {
            if (entry.updateId() == null || entry.updateId().isBlank()) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, "Missing Windows Update identifier for " + entry.getName()).showAndWait();
                    entry.setStatus("Failed");
                    entry.setProgress(0.0);
                    if (!disposed) statusText.set("Update failed for " + entry.getName() + ": missing identifier.");
                });
                installRunning.set(false);
                Platform.runLater(() -> {
                    if (!disposed) busy.set(false);
                });
                return;
            }
        } else {
            if (entry.id() == null || entry.id().isBlank()) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, "Missing package identifier for " + entry.getName()).showAndWait();
                    entry.setStatus("Failed");
                    entry.setProgress(0.0);
                    if (!disposed) statusText.set("Update failed for " + entry.getName() + ": missing identifier.");
                });
                installRunning.set(false);
                Platform.runLater(() -> {
                    if (!disposed) busy.set(false);
                });
                return;
            }
        }
        Platform.runLater(() -> {
            entry.setStatus("Installing...");
            entry.setProgress(-1.0);
        });
        try {
            Instant start = Instant.now();
            ProcessResult res;
            if ("WindowsUpdate".equals(entry.source()) && entry.updateId() != null) {
                try {
                    res = service.installWindowsUpdate(entry.updateId(), INSTALL_TIMEOUT_WU_SECONDS, installCancelled, entry);
                } catch (CancellationException cex) {
                    handleInstallCancellation(entry, true);
                    return;
                }
            } else {
                try {
                    res = service.updatePackageWithStreaming(entry.id(), true, INSTALL_TIMEOUT_WINGET_SECONDS, entry, installCancelled);
                } catch (CancellationException cex) {
                    handleInstallCancellation(entry, true);
                    return;
                }
            }
            // Exit 0 or MSI 3010/1641 counts as installed; reboot phrasing on a
            // failed exit must not.
            if (isInstallSuccess(entry, res)) {
                // Non-blocking cleanup: the old synchronous promptAndCleanup() held the
                // single install worker on a 60s latch while busy/installRunning stayed
                // true (app appeared hung, Stop/shutdown delayed). Fire-and-forget async
                // lets the install finish immediately; the dialog appears while UI is free.
                if (!disposed) {
                    try {
                        InstallerCleanupHelper.promptAndCleanupAsync(service, entry, start)
                                .exceptionally(ex -> {
                                    AppLogger.warning("Single cleanup failed: " + ex.getMessage());
                                    return false;
                                });
                    } catch (Exception ex) {
                        AppLogger.warning("Single cleanup scheduling failed: " + ex.getMessage());
                    }
                }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), true, null);
                try { SoftwareUpdateScanCache.invalidate(); } catch (Exception ignored) {}
                Platform.runLater(() -> {
                    if (disposed) return;
                    statusText.set("Update installed for " + entry.getName());
                    rows.remove(entry);
                    entry.setStatus("");
                    entry.setProgress(0.0);
                });
                if (isInstallRebootRequired(entry, res)) {
                    Platform.runLater(() -> {
                        if (!disposed) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait();
                        }
                    });
                }
            } else {
                handleInstallFailure(entry, res, failedEntries, new ArrayList<>(), null, true);
            }
        } catch (Exception ex) {
            String msg = ex.getMessage();
            String safeMsg = msg;
            Platform.runLater(() -> entry.setLastError(safeMsg));
            if (msg != null && msg.contains("INSTALL_TECHNOLOGY_MISMATCH")) {
                synchronized (failedEntries) {
                    if (!failedEntries.contains(entry)) failedEntries.add(entry);
                }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, msg);
                Platform.runLater(() -> {
                    if (disposed) return;
                    showRetryFailed.set(true);
                    Alert a = new Alert(Alert.AlertType.WARNING);
                    a.setTitle(AppInfo.DISPLAY_NAME);
                    a.setHeaderText("Cannot update " + entry.getName());
                    a.setContentText("The installer technology changed between versions. "
                            + "Please uninstall the current version manually, then scan again to install the newer version.");
                    ButtonType ignoreBtn = new ButtonType("Add to Ignore List");
                    ButtonType okBtn = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
                    a.getButtonTypes().setAll(ignoreBtn, okBtn);
                    if (a.showAndWait().orElse(okBtn) == ignoreBtn) {
                        skipEntry(entry);
                    }
                });
            } else {
                synchronized (failedEntries) {
                    if (!failedEntries.contains(entry)) failedEntries.add(entry);
                }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, msg);
                Platform.runLater(() -> {
                    if (!disposed) {
                        new Alert(Alert.AlertType.ERROR, "Install failed:\n" + msg).showAndWait();
                        showRetryFailed.set(true);
                    }
                });
            }
        } finally {
            installRunning.set(false);
            Platform.runLater(() -> {
                if (!disposed) busy.set(false);
            });
        }
    }

    private final AtomicInteger retryCount = new AtomicInteger(0);

    public void retryFailed() {
        List<SoftwareUpdateEntry> toRetry;
        long capturedEpoch;
        synchronized (failedEntries) {
            if (failedEntries.isEmpty()) return;
            if (retryCount.get() >= MAX_RETRY_ATTEMPTS) {
                Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                        "Maximum retry attempts (" + MAX_RETRY_ATTEMPTS + ") reached. Please scan again.").showAndWait());
                return;
            }
            retryCount.incrementAndGet();
            toRetry = new ArrayList<>(failedEntries);
            failedEntries.clear();
            capturedEpoch = failureEpoch.incrementAndGet();
            final int attempt = retryCount.get();
            final List<SoftwareUpdateEntry> retrySnapshot = toRetry;
            // JavaFX properties must change on the FX thread (this runs on a worker).
            Platform.runLater(() -> {
                for (SoftwareUpdateEntry e : retrySnapshot) {
                    e.setStatus("");
                    e.setProgress(0.0);
                    e.setSelected(true);
                }
                showRetryFailed.set(false);
                if (!disposed) statusText.set(
                        "Retrying " + retrySnapshot.size() + " failed update(s) (attempt " + attempt + "/" + MAX_RETRY_ATTEMPTS + ")...");
            });
        }
        final List<SoftwareUpdateEntry> retryList = toRetry;
        final long epoch = capturedEpoch;
        try {
            executor.submit(() -> {
                try { Thread.sleep(2000); } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
                Platform.runLater(() -> {
                    // Superseded by a newer scan/batch: the state this retry captured
                    // was intentionally invalidated - die silently, never resurrect it.
                    if (epoch != failureEpoch.get()) return;
                    // Drop entries the user ignored during the delay: retrying them
                    // would reinstall explicitly ignored packages.
                    List<SoftwareUpdateEntry> live = filterOutIgnored(retryList);
                    if (live.isEmpty()) {
                        showRetryFailed.set(false);
                        if (!disposed) statusText.set("Nothing left to retry (remaining items were ignored).");
                        return;
                    }
                    // The tab (or another tab via shared busy) may have become busy
                    // during the delay: postpone instead of losing the retry. The
                    // attempt is refunded so a postponement never burns a retry.
                    if (disposed || installRunning.get() || busy.get() || globalBusy.get()) {
                        retryCount.decrementAndGet();
                        synchronized (failedEntries) {
                            for (SoftwareUpdateEntry e : live) {
                                if (!failedEntries.contains(e)) failedEntries.add(e);
                            }
                        }
                        for (SoftwareUpdateEntry e : live) {
                            e.setStatus("Failed");
                            e.setProgress(0.0);
                            e.setSelected(false);
                            // List nudge so the table/filter refresh like the other failure paths.
                            rows.remove(e);
                            rows.add(e);
                        }
                        showRetryFailed.set(true);
                        if (!disposed) statusText.set("Retry postponed \u2013 another operation is running. Press \"Retry Failed\" to try again.");
                        return;
                    }
                    updateSelected(live, true);
                });
            });
        } catch (Exception ex) {
            AppLogger.warning("Failed to schedule retry (shutting down?): " + ex.getMessage());
            // Restore entries so the retry is not silently lost.
            synchronized (failedEntries) { failedEntries.addAll(toRetry); }
            Platform.runLater(() -> {
                if (!disposed) showRetryFailed.set(true);
            });
        }
    }

    public void skipEntry(SoftwareUpdateEntry entry) {
        if (entry == null) return;
        try {
            String id = entry.id() == null ? "" : entry.id().trim();
            if (id.isEmpty()) {
                AppLogger.warning("skipEntry with blank id ignored");
            } else {
                String safeName = entry.getName() == null ? id : entry.getName().replace("\t", " ").replace("\n", " ").replace("\r", " ");
                String stored = safeName + "\t" + id;
                String idLower = id.toLowerCase(java.util.Locale.ROOT);
                // Atomic RMW so concurrent saves from other tabs cannot lose updates.
                settingsStore.update(curr -> {
                    List<String> cur = curr.skippedSoftwareIds();
                    List<String> skipped = cur == null ? new ArrayList<>() : new ArrayList<>(cur);
                    boolean already = skipped.stream().anyMatch(s -> {
                        if (s == null) return false;
                        int t = s.lastIndexOf('\t');
                        String existing = t >= 0 ? s.substring(t + 1) : s;
                        return existing != null && existing.trim().equalsIgnoreCase(idLower);
                    });
                    if (!already) skipped.add(stored);
                    return curr.toBuilder().skippedSoftwareIds(skipped).build();
                });
            }
        } catch (Exception ex) {
            AppLogger.warning("Failed to skip software entry: " + ex.getMessage());
        }
        // An ignored entry must never be retried: drop it (and any case-variant
        // duplicate) from the failure list, otherwise Retry Failed reinstalls it.
        boolean nowEmpty;
        synchronized (failedEntries) {
            failedEntries.removeIf(e -> e == entry
                    || (e != null && e.id() != null && entry.id() != null
                        && e.id().equalsIgnoreCase(entry.id())));
            nowEmpty = failedEntries.isEmpty();
        }
        Platform.runLater(() -> {
            rows.remove(entry);
            if (nowEmpty) showRetryFailed.set(false);
        });
    }

    public List<SoftwareUpdateEntry> getFailedEntries() {
        synchronized (failedEntries) {
            return new ArrayList<>(failedEntries);
        }
    }

    /**
     * Drops entries the user moved to the ignore list (same id-key convention as the
     * scan filter), so a delayed retry never reinstalls explicitly ignored packages.
     */
    private List<SoftwareUpdateEntry> filterOutIgnored(List<SoftwareUpdateEntry> entries) {
        if (entries == null || entries.isEmpty()) return List.of();
        try {
            AppSettings settings = settingsStore.load();
            List<String> skipped = settings == null ? null : settings.skippedSoftwareIds();
            if (skipped == null || skipped.isEmpty()) return new ArrayList<>(entries);
            Set<String> skippedSet = skipped.stream()
                    .map(s -> {
                        int t = s.lastIndexOf('\t');
                        String id = t >= 0 ? s.substring(t + 1) : s;
                        return id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT);
                    })
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            if (skippedSet.isEmpty()) return new ArrayList<>(entries);
            return entries.stream()
                    .filter(e -> e != null && e.id() != null
                            && !skippedSet.contains(e.id().trim().toLowerCase(java.util.Locale.ROOT)))
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            AppLogger.warning("Retry ignore-filter failed, keeping full list: " + ex.getMessage());
            return new ArrayList<>(entries);
        }
    }

    /** Deduplicates by package id (case-insensitive); winget list is merged before WU so winget wins ties. */
    private static List<SoftwareUpdateEntry> dedupeById(List<SoftwareUpdateEntry> entries) {
        if (entries == null || entries.size() < 2) return entries == null ? List.of() : entries;
        java.util.LinkedHashMap<String, SoftwareUpdateEntry> byId = new java.util.LinkedHashMap<>();
        for (SoftwareUpdateEntry e : entries) {
            if (e == null || e.id() == null) continue;
            String key = e.id().toLowerCase(java.util.Locale.ROOT);
            if (!byId.containsKey(key)) {
                byId.put(key, e);
            } else {
                AppLogger.info("Dropping duplicate software update entry for id=" + e.id());
            }
        }
        return new ArrayList<>(byId.values());
    }

    public void dispose() {
        disposed = true;
        synchronized (scanLock) {
            scanCancelled.set(true);
            scanGeneration.incrementAndGet();
            scanRunning.set(false);
            Future<?> f = scanFuture;
            scanFuture = null;
            if (f != null) {
                try { f.cancel(true); } catch (Exception ignored) {}
            }
        }
        installCancelled.set(true);
        installRunning.set(false);
        // Ensure UI busy flags are cleared immediately so globalBusy doesn't stick (B8/B9)
        try {
            Platform.runLater(() -> {
                showBatchProgress.set(false);
                busy.set(false);
            });
        } catch (Exception ignored) {
            // Toolkit may be shutting down – set directly
            try { busy.set(false); } catch (Exception ignored2) {}
            try { showBatchProgress.set(false); } catch (Exception ignored2) {}
        }
        shutdownExecutor(installExecutor);
        shutdownExecutor(executor);
        service.shutdown();
    }

    // --- Executors ---

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "SoftwareUpdate-Worker");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService installExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "SoftwareUpdate-Install");
        t.setDaemon(true);
        return t;
    });

    // --- Internal helpers ---

    private boolean installOne(SoftwareUpdateEntry entry, int total,
                             AtomicInteger completed, List<SoftwareUpdateEntry> failedPackages,
                             List<SoftwareUpdateEntry> manualRepairPackages,
                             List<RepairFailureRecord> manualRepairDetails,
                             List<SoftwareUpdateEntry> techMismatchEntries,
                             List<SoftwareUpdateEntry> successfulEntries, Instant batchStartTime) {
        if (installCancelled.get()) return false;
        AtomicBoolean rebootFlag = new AtomicBoolean(false);
        try {
            // Validate identifiers before launching process (prevents List.of NPE)
            if ("WindowsUpdate".equals(entry.source())) {
                if (entry.updateId() == null || entry.updateId().isBlank()) {
                    AppLogger.warning("Skipping WU install with missing updateId: " + entry.getName());
                    Platform.runLater(() -> { entry.setStatus("Failed"); entry.setProgress(0.0); });
                    synchronized (failedPackages) { failedPackages.add(entry); }
                    Platform.runLater(() -> entry.setLastError("Missing Windows Update identifier"));
                    recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, "Missing updateId");
                    return false;
                }
            } else {
                if (entry.id() == null || entry.id().isBlank()) {
                    AppLogger.warning("Skipping winget install with missing id: " + entry.getName());
                    Platform.runLater(() -> { entry.setStatus("Failed"); entry.setProgress(0.0); });
                    synchronized (failedPackages) { failedPackages.add(entry); }
                    Platform.runLater(() -> entry.setLastError("Missing package identifier"));
                    recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, "Missing package id");
                    return false;
                }
            }
            
            Instant start = Instant.now();
            Platform.runLater(() -> {
                if (disposed) return;
                statusText.set("Installing " + entry.getName() + "...");
                entry.setStatus("Installing...");
                entry.setProgress(-1.0);
            });

            ProcessResult res;
            if ("WindowsUpdate".equals(entry.source()) && entry.updateId() != null) {
                try {
                    res = service.installWindowsUpdate(entry.updateId(), INSTALL_TIMEOUT_WU_SECONDS, installCancelled, entry);
                } catch (CancellationException cex) {
                    handleInstallCancellation(entry, false);
                    return false;
                }
            } else {
                try {
                    res = service.updatePackageWithStreaming(entry.id(), true, INSTALL_TIMEOUT_WINGET_SECONDS, entry, installCancelled);
                } catch (CancellationException cex) {
                    handleInstallCancellation(entry, false);
                    return false;
                }
            }

            // Exit 0 or MSI 3010/1641 counts as installed; a 3010 must not be
            // reported as Failed, and the batch must abort so later installs are
            // not layered over a pending reboot. Reboot phrasing on a failed exit
            // is not success.
            if (isInstallSuccess(entry, res)) {
                synchronized (successfulEntries) { successfulEntries.add(entry); }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), true, null);
                Platform.runLater(() -> {
                    if (disposed) return;
                    statusText.set("Update installed for " + entry.getName());
                    rows.remove(entry);
                    entry.setStatus("");
                    entry.setProgress(0.0);
                });
                if (isInstallRebootRequired(entry, res)) {
                    rebootFlag.set(true);
                    Platform.runLater(() -> {
                        if (!disposed) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required for " + entry.getName() + ".\nRemaining updates will be skipped – please reboot first.").showAndWait();
                        }
                    });
                }
            } else {
                handleInstallFailure(entry, res, failedPackages, manualRepairPackages, manualRepairDetails, false);
            }
        } catch (CancellationException cex) {
            // Mid-item cancel must clear the "Installing..." row state, otherwise the
            // row claims to install forever (single-install path already does this).
            handleInstallCancellation(entry, false);
            return false;
        } catch (Exception ex) {
            String msg = ex.getMessage();
            AppLogger.warning("Exception during update: " + msg);
            String safeMsg = msg;
            Platform.runLater(() -> entry.setLastError(safeMsg));
            if (ex.getMessage() != null && ex.getMessage().contains("INSTALL_TECHNOLOGY_MISMATCH")) {
                synchronized (techMismatchEntries) { techMismatchEntries.add(entry); }
            } else {
                synchronized (failedPackages) { failedPackages.add(entry); }
            }
            recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, ex.getMessage());
            Platform.runLater(() -> {
                if (disposed) return;
                entry.setStatus("Failed");
                entry.setProgress(0.0);
            });
        } finally {
            int current = completed.incrementAndGet();
            Platform.runLater(() -> {
                if (disposed) return;
                batchProgressText.set(entry.getName() + " (" + current + " / " + total + ")");
                batchProgress.set((double) current / total);
            });
        }
        return rebootFlag.get();
    }

    private static void resetEntryUiState(SoftwareUpdateEntry entry) {
        Platform.runLater(() -> {
            if (entry == null) return;
            entry.setStatus("");
            entry.setProgress(0.0);
        });
    }

    private void refreshFailedRows(List<SoftwareUpdateEntry> retryable, List<SoftwareUpdateEntry> manualRepair) {
        for (SoftwareUpdateEntry fe : retryable) {
            synchronized (failedEntries) { if (!failedEntries.contains(fe)) failedEntries.add(fe); }
            fe.setStatus(SoftwareUpdateEntry.STATUS_FAILED);
            fe.setProgress(0.0);
            fe.setSelected(false);
            rows.remove(fe);
            rows.add(fe);
        }
        for (SoftwareUpdateEntry me : manualRepair) {
            me.setSelected(false);
            rows.remove(me);
            rows.add(me);
        }
    }

    private void handleInstallFailure(SoftwareUpdateEntry entry, ProcessResult res,
                                      List<SoftwareUpdateEntry> retryList,
                                      List<SoftwareUpdateEntry> manualRepairList,
                                      List<RepairFailureRecord> manualRepairDetails,
                                      boolean showDialog) {
        SoftwareInstallFailure.Result failure = SoftwareInstallFailure.classify(entry, res);
        AppLogger.warning("Update failed for " + entry.id() + " (" + failure.kind() + "): "
                + (failure.installerExitCode() != null ? "msi " + failure.installerExitCode() : "no msi code"));
        if (entry.id() != null && !entry.id().isBlank()) {
            lastFailureByPackageId.put(entry.id(), failure);
        }
        recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false,
                SoftwareInstallFailure.historyPayload(failure));
        String status = failure.immediateRetryUseful()
                ? SoftwareUpdateEntry.STATUS_FAILED
                : SoftwareUpdateEntry.STATUS_MANUAL_REPAIR;
        if (failure.immediateRetryUseful()) {
            synchronized (retryList) { retryList.add(entry); }
        } else {
            synchronized (manualRepairList) { manualRepairList.add(entry); }
            if (manualRepairDetails != null) {
                synchronized (manualRepairDetails) {
                    manualRepairDetails.add(new RepairFailureRecord(entry, failure));
                }
            }
        }
        Platform.runLater(() -> {
            if (disposed) return;
            entry.setLastError(failure.formattedUserMessage());
            entry.setStatus(status);
            entry.setProgress(0.0);
            if (showDialog) {
                showInstallFailureDialog(entry, failure);
                if (failure.immediateRetryUseful()) showRetryFailed.set(true);
            }
        });
    }

    private void showInstallFailureDialog(SoftwareUpdateEntry entry, SoftwareInstallFailure.Result failure) {
        String name = entry.getName() != null ? entry.getName() : entry.id();
        String header = name + " — " + failure.title();
        if (failure.installerExitCode() != null) {
            header += " (installer exit " + failure.installerExitCode() + ")";
        }
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < failure.recoverySteps().size(); i++) {
            steps.append(i + 1).append(". ").append(failure.recoverySteps().get(i)).append("\n");
        }
        Label explain = new Label(failure.explanation());
        explain.setWrapText(true);
        Label stepsLbl = new Label(steps.toString().trim());
        stepsLbl.setWrapText(true);
        TextArea details = new TextArea(failure.rawOutput() != null ? failure.rawOutput() : "");
        details.setEditable(false);
        details.setWrapText(true);
        details.setPrefRowCount(10);
        TitledPane detailsPane = new TitledPane("Technical details", details);
        detailsPane.setExpanded(false);
        final int dialogWidth = 760;
        FlowPane actions = new FlowPane(10, 8);
        actions.setPrefWrapLength(dialogWidth);
        actions.setColumnHalignment(HPos.LEFT);
        actions.setAlignment(Pos.CENTER_LEFT);
        Button copyBtn = new Button("Copy details");
        copyBtn.setOnAction(e -> copyInstallFailureDetails(failure));
        actions.getChildren().add(copyBtn);
        if (failure.trustedLogPath() != null) {
            Button logBtn = new Button("Open installer log");
            logBtn.setOnAction(e -> openTrustedInstallerLog(failure.trustedLogPath()));
            actions.getChildren().add(logBtn);
        }
        if (failure.showInstalledAppsSettings()) {
            Button appsBtn = new Button("Open Installed apps");
            appsBtn.setOnAction(e -> openInstalledAppsSettings());
            actions.getChildren().add(appsBtn);
        }
        if (failure.showTroubleshooter()) {
            Button troubleBtn = new Button("Open Microsoft troubleshooter");
            troubleBtn.setOnAction(e -> openSupportUrl(SoftwareInstallFailure.MICROSOFT_INSTALL_TROUBLESHOOTER_URL));
            actions.getChildren().add(troubleBtn);
        }

        VBox content = new VBox(10, explain, new Label("Suggested steps:"), stepsLbl, detailsPane, actions);
        content.setPrefWidth(dialogWidth);
        content.setMinWidth(dialogWidth);

        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(AppInfo.DISPLAY_NAME);
        a.setHeaderText(header);
        a.getDialogPane().setContent(content);
        a.getDialogPane().setMinWidth(dialogWidth);
        a.getButtonTypes().setAll(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        a.showAndWait();
    }

    private static void copyInstallFailureDetails(SoftwareInstallFailure.Result failure) {
        String copy = failure.formattedUserMessage() + "\n\n---\n\n"
                + (failure.rawOutput() != null ? failure.rawOutput() : "");
        ClipboardContent cc = new ClipboardContent();
        cc.putString(copy);
        Clipboard.getSystemClipboard().setContent(cc);
        new Alert(Alert.AlertType.INFORMATION, "Details copied to clipboard.").showAndWait();
    }

    private void showRepairStepsForBatch(List<RepairFailureRecord> records) {
        if (records == null || records.isEmpty()) return;
        if (records.size() == 1) {
            RepairFailureRecord r = records.get(0);
            showInstallFailureDialog(r.entry(), r.result());
            return;
        }
        List<String> choices = new ArrayList<>();
        for (RepairFailureRecord r : records) {
            SoftwareUpdateEntry e = r.entry();
            String name = e.getName() != null ? e.getName() : e.id();
            choices.add(name);
        }
        ChoiceDialog<String> picker = new ChoiceDialog<>(choices.get(0), choices);
        picker.setTitle(AppInfo.DISPLAY_NAME);
        picker.setHeaderText("Select a program to view repair steps");
        picker.setContentText("Program:");
        picker.showAndWait().ifPresent(selected -> {
            for (RepairFailureRecord r : records) {
                SoftwareUpdateEntry e = r.entry();
                String name = e.getName() != null ? e.getName() : e.id();
                if (name.equals(selected)) {
                    showInstallFailureDialog(e, r.result());
                    break;
                }
            }
        });
    }

    private static void openInstalledAppsSettings() {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(SoftwareInstallFailure.INSTALLED_APPS_SETTINGS_URI));
            } else {
                new Alert(Alert.AlertType.INFORMATION, "Open Settings > Apps > Installed apps.").showAndWait();
            }
        } catch (Exception ex) {
            try {
                new ProcessBuilder("cmd.exe", "/c", "start", "", SoftwareInstallFailure.INSTALLED_APPS_SETTINGS_URI).start();
            } catch (Exception ex2) {
                new Alert(Alert.AlertType.WARNING,
                        "Could not open Installed apps settings. Open Settings > Apps > Installed apps manually.")
                        .showAndWait();
            }
        }
    }

    private static void openTrustedInstallerLog(Path path) {
        Path trusted = SoftwareInstallFailure.revalidateLogForOpen(path);
        if (trusted == null) {
            new Alert(Alert.AlertType.WARNING, "Installer log is no longer available or is not in a trusted location.").showAndWait();
            return;
        }
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().open(trusted.toFile());
            } else {
                new Alert(Alert.AlertType.WARNING, "Cannot open files on this system.").showAndWait();
            }
        } catch (Exception ex) {
            new Alert(Alert.AlertType.WARNING, "Could not open installer log: " + ex.getMessage()).showAndWait();
        }
    }

    private static void openSupportUrl(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            } else {
                new Alert(Alert.AlertType.INFORMATION, url).showAndWait();
            }
        } catch (Exception ex) {
            new Alert(Alert.AlertType.WARNING, "Could not open link. Copy this URL:\n" + url).showAndWait();
        }
    }

    private enum PrepareOutcome { PROCEED, CANCELLED, RESTORE_FAILED }

    private boolean shouldAbortInstallPreparation() {
        return disposed || installCancelled.get();
    }

    private void abortInstallPreparation(String statusMessage) {
        installRunning.set(false);
        Platform.runLater(() -> {
            showBatchProgress.set(false);
            busy.set(false);
            if (!disposed && statusMessage != null && !statusMessage.isBlank()) {
                statusText.set(statusMessage);
            }
        });
    }

    private CompletableFuture<Boolean> finalizePrepareOutcome(PrepareOutcome outcome) {
        if (outcome == PrepareOutcome.CANCELLED) {
            return CompletableFuture.completedFuture(false);
        }
        if (outcome == PrepareOutcome.PROCEED) {
            return CompletableFuture.completedFuture(true);
        }
        CompletableFuture<Boolean> proceed = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    if (disposed || installCancelled.get()) {
                        proceed.complete(false);
                        return;
                    }
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "System Restore Point creation failed or was skipped.\n\n"
                                    + "Continue installing updates without a restore point?");
                    confirm.setHeaderText("Restore point unavailable");
                    confirm.showAndWait().ifPresentOrElse(
                            result -> proceed.complete(result == ButtonType.OK),
                            () -> proceed.complete(false));
                    if (!proceed.isDone()) {
                        proceed.complete(false);
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Restore failure confirmation failed: " + ex.getMessage());
                    proceed.complete(false);
                }
            });
        } catch (Exception ex) {
            proceed.complete(false);
        }
        return proceed;
    }

    private static boolean isInstallSuccess(SoftwareUpdateEntry entry, ProcessResult res) {
        if (entry != null && "WindowsUpdate".equals(entry.source())) {
            return SoftwareUpdateService.isWindowsUpdateInstallSuccess(res);
        }
        return SoftwareUpdateService.isWingetInstallSuccess(res);
    }

    private static boolean isWindowsUpdateEntry(SoftwareUpdateEntry entry) {
        return entry != null && "WindowsUpdate".equals(entry.source());
    }

    /**
     * Killing the PowerShell host does not abort WUA/CBS. Record that and optionally
     * warn immediately (single-install). Batch warns once at the end.
     */
    private void handleInstallCancellation(SoftwareUpdateEntry entry, boolean showWuDialog) {
        resetEntryUiState(entry);
        if (isWindowsUpdateEntry(entry)) {
            wuServicingUnacked.set(true);
            AppLogger.warning("Windows Update install cancelled: host killed, WUA may still be applying"
                    + (entry.id() != null ? " for " + entry.id() : ""));
        }
        if (!showWuDialog) return;
        Platform.runLater(() -> {
            if (disposed) return;
            if (wuServicingUnacked.get()) {
                statusText.set("Update cancelled. Windows Update may still be applying — reboot before installing more.");
                showWuServicingInfoDialog();
            } else if (entry != null) {
                statusText.set("Update cancelled for " + entry.getName() + ".");
            }
        });
    }

    private void showWuServicingInfoDialog() {
        Alert a = new Alert(Alert.AlertType.WARNING, WU_SERVICING_WARNING);
        a.setHeaderText("Windows Update may still be applying");
        a.showAndWait();
    }

    /**
     * @return true when the caller must abort this install attempt
     */
    private boolean blockIfWuServicingUnacked(List<SoftwareUpdateEntry> selected) {
        if (!wuServicingUnacked.get()) return false;
        boolean includesWu = false;
        if (selected != null) {
            for (SoftwareUpdateEntry e : selected) {
                if (isWindowsUpdateEntry(e)) { includesWu = true; break; }
            }
        }
        if (!includesWu) return false;
        if (!Platform.isFxApplicationThread()) {
            AppLogger.warning("WU servicing gate blocked off FX thread");
            return true;
        }
        Alert a = new Alert(Alert.AlertType.WARNING,
                WU_SERVICING_WARNING + "\n\nContinue only if you already rebooted or the update has finished.");
        a.setHeaderText("Windows Update may still be applying");
        ButtonType continueBtn = new ButtonType("Continue anyway", ButtonBar.ButtonData.OK_DONE);
        a.getButtonTypes().setAll(continueBtn, ButtonType.CANCEL);
        ButtonType result = a.showAndWait().orElse(ButtonType.CANCEL);
        if (result == continueBtn) {
            wuServicingUnacked.set(false);
            AppLogger.info("User acknowledged pending Windows Update servicing");
            return false;
        }
        return true;
    }

    private static boolean isInstallRebootRequired(SoftwareUpdateEntry entry, ProcessResult res) {
        if (entry != null && "WindowsUpdate".equals(entry.source())) {
            return SoftwareUpdateService.isWindowsUpdateRebootRequired(res);
        }
        return SoftwareUpdateService.isWingetRebootRequired(res);
    }

    private CompletableFuture<PrepareOutcome> maybeCreateRestorePointAsync() {
        AppSettings settings = settingsStore.load();
        if (!settings.createSystemRestorePoint()) {
            return CompletableFuture.completedFuture(PrepareOutcome.PROCEED);
        }
        if (restorePointCreatedThisBatch.get()) {
            return CompletableFuture.completedFuture(PrepareOutcome.PROCEED);
        }
        // Stage 1 (FX thread only): ask the user. Stage 2 (background): run the blocking
        // restore-point creation. Never run ProcessRunner on the FX thread (UI freeze, #2).
        // Bounded: auto-declines after 120s and immediately on Stop/dispose so a
        // walk-away never holds globalBusy (+ installRunning) indefinitely.
        CompletableFuture<Boolean> confirmed = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    if (disposed) {
                        confirmed.complete(false);
                        return;
                    }
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                            "Would you like to create a System Restore Point before proceeding with the updates?\n\n"
                                    + "(Auto-declines after 2 minutes. You can press Stop to cancel.)");
                    confirm.setHeaderText(AppInfo.DISPLAY_NAME);
                    // Watcher: auto-close on timeout or Stop/dispose so the install
                    // chain can never hang forever on an unanswered modal.
                    Thread watcher = new Thread(() -> {
                        try {
                            long deadline = System.currentTimeMillis() + 120_000L;
                            while (!confirmed.isDone() && System.currentTimeMillis() < deadline) {
                                if (disposed || installCancelled.get() || scanCancelled.get()) {
                                    Platform.runLater(() -> {
                                        try {
                                            if (!confirmed.isDone()) {
                                                AppLogger.info("Restore prompt auto-declined (cancel/dispose)");
                                                confirm.setResult(ButtonType.CANCEL);
                                                confirm.hide();
                                            }
                                        } catch (Exception ignored) {}
                                    });
                                    return;
                                }
                                try {
                                    Thread.sleep(500);
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                    return;
                                }
                            }
                            if (!confirmed.isDone()) {
                                Platform.runLater(() -> {
                                    try {
                                        if (!confirmed.isDone()) {
                                            AppLogger.warning("Restore point prompt timed out after 120s - auto-declining");
                                            confirm.setResult(ButtonType.CANCEL);
                                            confirm.hide();
                                        }
                                    } catch (Exception ignored) {}
                                });
                            }
                        } catch (Exception ignored) {}
                    }, "restore-prompt-watcher");
                    watcher.setDaemon(true);
                    watcher.start();
                    confirm.showAndWait().ifPresent(result -> {
                        confirmed.complete(result == ButtonType.OK);
                    });
                    if (!confirmed.isDone()) {
                        // Dialog closed without a button (window X / timeout hide): decline, not hang.
                        confirmed.complete(false);
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Restore point prompt failed: " + ex.getMessage());
                    confirmed.complete(false);
                }
            });
        } catch (Exception ex) {
            AppLogger.warning("Restore point prompt scheduling failed: " + ex.getMessage());
            return CompletableFuture.completedFuture(PrepareOutcome.CANCELLED);
        }
        try {
            return confirmed.thenApplyAsync(wantsRestore -> {
                if (installCancelled.get() || disposed) {
                    AppLogger.info("Restore skipped: install was cancelled during prompt");
                    return PrepareOutcome.CANCELLED;
                }
                if (!Boolean.TRUE.equals(wantsRestore)) {
                    return PrepareOutcome.PROCEED;
                }
                try {
                    boolean created = restoreService.createRestorePoint("WinZenith software update").success();
                    if (created) {
                        restorePointCreatedThisBatch.set(true);
                        return PrepareOutcome.PROCEED;
                    }
                    AppLogger.warning("Restore point creation failed or skipped.");
                    return PrepareOutcome.RESTORE_FAILED;
                } catch (Exception ex) {
                    AppLogger.warning("Restore point creation failed: " + ex.getMessage());
                    return PrepareOutcome.RESTORE_FAILED;
                }
            }, executor);
        } catch (Exception ex) {
            AppLogger.warning("Restore point background stage rejected (shutting down?): " + ex.getMessage());
            return CompletableFuture.completedFuture(PrepareOutcome.CANCELLED);
        }
    }

    private void showBatchResultDialog(List<SoftwareUpdateEntry> failedEntries, List<SoftwareUpdateEntry> techMismatchEntries,
                                       List<SoftwareUpdateEntry> manualRepairEntries,
                                       List<RepairFailureRecord> manualRepairDetails) {
        StringBuilder msg = new StringBuilder();
        if (!failedEntries.isEmpty()) {
            msg.append("The following updates failed (you can use Retry Failed):\n\n");
            // Cap the dialog list: mass failures would otherwise build a giant modal.
            // Full per-item errors stay in Update History.
            int shown = Math.min(failedEntries.size(), 10);
            for (SoftwareUpdateEntry fe : failedEntries.subList(0, shown)) {
                String displayName = fe.getName() != null ? fe.getName() : fe.id();
                msg.append("  - ").append(displayName).append("\n");
                String error = fe.getLastError();
                if (error != null && !error.isBlank()) {
                    String shortError = error.strip();
                    if (shortError.length() > 200) shortError = shortError.substring(0, 200) + "...";
                    msg.append("    Error: ").append(shortError).append("\n");
                }
                msg.append("\n");
            }
            if (failedEntries.size() > shown) {
                msg.append("  ...and ").append(failedEntries.size() - shown)
                        .append(" more (see Update History for details).\n\n");
            }
        }
        if (!manualRepairEntries.isEmpty()) {
            if (!msg.isEmpty()) msg.append("\n");
            msg.append("Manual repair required before these can update (Retry Failed will not re-run them):\n\n");
            int shown = Math.min(manualRepairEntries.size(), 10);
            for (SoftwareUpdateEntry me : manualRepairEntries.subList(0, shown)) {
                String displayName = me.getName() != null ? me.getName() : me.id();
                msg.append("  - ").append(displayName).append("\n");
                String error = me.getLastError();
                if (error != null && !error.isBlank()) {
                    String shortError = error.strip();
                    if (shortError.length() > 200) shortError = shortError.substring(0, 200) + "...";
                    msg.append("    ").append(shortError).append("\n");
                }
                msg.append("\n");
            }
            if (manualRepairEntries.size() > shown) {
                msg.append("  ...and ").append(manualRepairEntries.size() - shown)
                        .append(" more (see Update History for details).\n\n");
            }
        }
        if (!techMismatchEntries.isEmpty()) {
            if (!msg.isEmpty()) msg.append("\n");
            msg.append("The following programs cannot be updated automatically\n");
            msg.append("(installer technology changed between versions):\n\n");
            for (SoftwareUpdateEntry e : techMismatchEntries) msg.append("  - ").append(e.getName()).append("\n");
            msg.append("\nPlease uninstall them manually, then scan again to install the newer version.");
        }

        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle(AppInfo.DISPLAY_NAME);
        a.setHeaderText("Update results");
        a.setContentText(msg.toString());

        List<ButtonType> buttons = new ArrayList<>();
        if (!failedEntries.isEmpty()) buttons.add(new ButtonType("Retry Failed"));
        if (!manualRepairEntries.isEmpty()) buttons.add(new ButtonType("Show repair steps"));
        if (!techMismatchEntries.isEmpty()) buttons.add(new ButtonType("Add to Ignore List"));
        buttons.add(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        a.getButtonTypes().setAll(buttons);

        ButtonType result = a.showAndWait().orElse(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        if (result.getText().equals("Retry Failed")) {
            retryFailed();
        } else if (result.getText().equals("Show repair steps")) {
            showRepairStepsForBatch(manualRepairDetails);
        } else if (result.getText().equals("Add to Ignore List")) {
            for (SoftwareUpdateEntry e : techMismatchEntries) skipEntry(e);
        }
    }

    private void recordHistory(SoftwareUpdateEntry entry, String oldVersion, String newVersion,
                                boolean success, String errorMessage) {
        try {
            new SoftwareUpdateHistoryStore().add(new SoftwareUpdateHistoryEntry(
                    entry.getName(), entry.id(), oldVersion, newVersion,
                    entry.source(), Instant.now(), success, capHistoryError(errorMessage)));
        } catch (Exception ex) {
            AppLogger.warning("Failed to record update history: " + ex.getMessage());
        }
    }

    /**
     * Failure payloads are full process outputs (unbounded for long winget/WU runs).
     * Persisting them verbatim bloats the 500-entry history file and the History dialog.
     */
    private static String capHistoryError(String errorMessage) {
        final int max = 8000;
        if (errorMessage == null || errorMessage.length() <= max) return errorMessage;
        return errorMessage.substring(0, max) + "\n...[truncated, full output in logs]";
    }

    private static void shutdownExecutor(ExecutorService executor) {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
