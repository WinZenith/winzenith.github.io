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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

    private static final long INSTALL_TIMEOUT_SECONDS = 1200;

    private final SoftwareUpdateService service = new SoftwareUpdateService();
    private final SystemRestoreService restoreService = new SystemRestoreService();
    private final SettingsStore settingsStore = new SettingsStore();

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<SoftwareUpdateEntry> rows = FXCollections.observableArrayList();
    private final StringProperty statusText = new SimpleStringProperty("Scan for available app updates via winget.");
    private final DoubleProperty batchProgress = new SimpleDoubleProperty(0);
    private final StringProperty batchProgressText = new SimpleStringProperty();
    private final BooleanProperty showRetryFailed = new SimpleBooleanProperty(false);
    private final BooleanProperty showBatchProgress = new SimpleBooleanProperty(false);

    private static final int MAX_RETRY_ATTEMPTS = 3;

    private final AtomicBoolean scanCancelled = new AtomicBoolean(false);
    private volatile Future<?> scanFuture;
    private final AtomicBoolean installCancelled = new AtomicBoolean(false);
    private final AtomicBoolean installRunning = new AtomicBoolean(false);
    private final AtomicBoolean restorePointCreatedThisBatch = new AtomicBoolean(false);
    private final List<SoftwareUpdateEntry> failedEntries = new ArrayList<>();
    private volatile boolean disposed = false;

    private Consumer<String> onWingetNotAvailable;

    @FunctionalInterface
    public interface BooleanSupplier {
        boolean getAsBoolean();
    }

    public SoftwareUpdateViewModel(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
    }

    public ObservableList<SoftwareUpdateEntry> getRows() { return rows; }
    public StringProperty statusTextProperty() { return statusText; }
    public DoubleProperty batchProgressProperty() { return batchProgress; }
    public StringProperty batchProgressTextProperty() { return batchProgressText; }
    public BooleanProperty showRetryFailedProperty() { return showRetryFailed; }
    public BooleanProperty showBatchProgressProperty() { return showBatchProgress; }
    public BooleanProperty busyProperty() { return busy; }

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
        if (busy.get() || disposed) return;
        scanCancelled.set(false);
        restorePointCreatedThisBatch.set(false);
        Platform.runLater(() -> {
            if (disposed) return;
            busy.set(true);
            showRetryFailed.set(false);
            statusText.set("Scanning for updates...");
        });
        scanFuture = executor.submit(this::scanInternal, "SoftwareUpdate-Scan");
    }

    private void scanInternal() {
        try {
            boolean wingetAvailable = service.isWingetAvailable();
            if (!wingetAvailable && !scanCancelled.get()) {
                String diag = service.getWingetDiagnostics();
                if (onWingetNotAvailable != null) {
                    Platform.runLater(() -> {
                        if (!disposed) onWingetNotAvailable.accept(diag);
                    });
                }
            }

            final int[] counts = {0, 0};
            List<SoftwareUpdateEntry> allUpdates = service.scanAllConcurrent(
                    scanCancelled::get,
                    wc -> counts[0] = wc,
                    wuc -> counts[1] = wuc
            );

            if (scanCancelled.get() || disposed) return;

            final int wc = counts[0];
            final int wuc = counts[1];
            AppSettings settings = settingsStore.load();
            List<String> skippedIds = settings.skippedSoftwareIds();
            Set<String> skippedIdSet = skippedIds.stream()
                    .map(s -> {
                        int t = s.lastIndexOf('\t');
                        return t >= 0 ? s.substring(t + 1) : s;
                    })
                    .collect(Collectors.toSet());
            List<SoftwareUpdateEntry> filteredUpdates = allUpdates.stream()
                    .filter(e -> !skippedIdSet.contains(e.id()))
                    .collect(Collectors.toList());

            if (scanCancelled.get() || disposed) return;
            Platform.runLater(() -> {
                if (disposed) return;
                rows.setAll(filteredUpdates);
                if (wc > 0 && wuc > 0) {
                    statusText.set(filteredUpdates.size() + " outdated item(s) found (" + wc + " app(s), " + wuc + " Windows Update(s)).");
                } else if (wc > 0) {
                    statusText.set(wc + " outdated app(s) found.");
                } else if (wuc > 0) {
                    statusText.set(wuc + " Windows Update(s) found.");
                } else {
                    statusText.set("Everything is up to date.");
                }
            });
        } catch (Exception ex) {
            if (!scanCancelled.get() && !disposed) {
                Platform.runLater(() -> {
                    if (!disposed) {
                        statusText.set("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    }
                });
            }
        } finally {
            scanFuture = null;
            Platform.runLater(() -> {
                if (!disposed) busy.set(false);
            });
        }
    }

    public void stopScan() {
        scanCancelled.set(true);
        if (scanFuture != null) {
            try {
                scanFuture.cancel(true);
            } catch (Exception ignored) {
            }
            scanFuture = null;
        }
        Platform.runLater(() -> {
            if (!disposed && !installRunning.get()) {
                busy.set(false);
            }
            statusText.set("Scan stopped.");
        });
    }

    public void updateSelected(List<SoftwareUpdateEntry> selected) {
        updateSelected(selected, false);
    }

    private void updateSelected(List<SoftwareUpdateEntry> selected, boolean isRetry) {
        if (!adminCheck.getAsBoolean()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait());
            return;
        }
        if (selected.isEmpty()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, "Select at least one program to update.").showAndWait());
            return;
        }

        restorePointCreatedThisBatch.set(false);
        maybeCreateRestorePointAsync().thenRunAsync(() -> {
            synchronized (failedEntries) { failedEntries.clear(); }
            if (!isRetry) retryCount = 0;
            installRunning.set(true);
            int total = selected.size();
            Platform.runLater(() -> {
                if (disposed) return;
                busy.set(true);
                installCancelled.set(false);
                statusText.set("Installing " + total + " update(s)...");
                showBatchProgress.set(true);
                batchProgress.set(0);
                batchProgressText.set("0 / " + total);
                showRetryFailed.set(false);
            });

            executor.submit(() -> runBatchInstall(selected, total), "SoftwareUpdate-BatchOrchestrator");
        }, executor);
    }

    private void runBatchInstall(List<SoftwareUpdateEntry> selected, int total) {
        AtomicInteger completed = new AtomicInteger(0);
        List<SoftwareUpdateEntry> failedPackages = new ArrayList<>();
        List<SoftwareUpdateEntry> techMismatchEntries = new ArrayList<>();

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (SoftwareUpdateEntry e : selected) {
            if (installCancelled.get()) break;
            CompletableFuture<Void> future = CompletableFuture.runAsync(
                    () -> installOne(e, total, completed, failedPackages, techMismatchEntries),
                    installExecutor);
            futures.add(future);
        }

        for (CompletableFuture<Void> f : futures) {
            try {
                f.join();
            } catch (Exception ex) {
                AppLogger.warning("Batch install future completed exceptionally: " + ex.getMessage());
            }
        }

        final int finalCompleted = completed.get();
        List<SoftwareUpdateEntry> finalFailed = new ArrayList<>(failedPackages);
        List<SoftwareUpdateEntry> finalTechMismatch = new ArrayList<>(techMismatchEntries);
        Platform.runLater(() -> {
            if (disposed) return;
            showBatchProgress.set(false);
            installRunning.set(false);
            busy.set(false);
            if (installCancelled.get()) {
                statusText.set("Update cancelled. " + finalCompleted + " of " + total + " completed.");
            } else if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty()) {
                statusText.set("Completed with " + finalFailed.size() + " failure(s). Use \"Retry Failed\" or re-scan.");
                for (SoftwareUpdateEntry fe : finalFailed) {
                    synchronized (failedEntries) { failedEntries.add(fe); }
                    fe.setStatus("Failed");
                    fe.setProgress(0.0);
                    fe.setSelected(false);
                    rows.remove(fe);
                    rows.add(fe);
                }
                showRetryFailed.set(true);
                showBatchResultDialog(failedPackages, finalTechMismatch);
            } else {
                statusText.set("All selected updates installed successfully.");
                showRetryFailed.set(false);
                scan();
            }
        });
    }

    public void updateSingle(SoftwareUpdateEntry entry) {
        if (!adminCheck.getAsBoolean()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait());
            return;
        }

        restorePointCreatedThisBatch.set(false);
        maybeCreateRestorePointAsync().thenRunAsync(() -> {
            synchronized (failedEntries) { failedEntries.clear(); }
            installRunning.set(true);
            Platform.runLater(() -> {
                if (disposed) return;
                busy.set(true);
                installCancelled.set(false);
                statusText.set("Installing update for " + entry.getName() + "...");
            });

            installExecutor.submit(() -> runSingleInstall(entry), "SoftwareUpdate-SingleInstall-" + entry.id());
        }, executor);
    }

    private void runSingleInstall(SoftwareUpdateEntry entry) {
        Platform.runLater(() -> {
            entry.setStatus("Installing...");
            entry.setProgress(-1.0);
        });
        try {
            Instant start = Instant.now();
            ProcessResult res;
            if ("WindowsUpdate".equals(entry.source()) && entry.updateId() != null) {
                try {
                    res = service.installWindowsUpdate(entry.updateId(), INSTALL_TIMEOUT_SECONDS, installCancelled);
                } catch (CancellationException cex) {
                    return;
                }
            } else {
                try {
                    res = service.updatePackageWithStreaming(entry.id(), true, INSTALL_TIMEOUT_SECONDS, entry, installCancelled);
                } catch (CancellationException cex) {
                    return;
                }
            }
            if (res.success()) {
                InstallerCleanupHelper.promptAndCleanup(service, entry, start);
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), true, null);
                Platform.runLater(() -> {
                    if (disposed) return;
                    statusText.set("Update installed for " + entry.getName());
                    rows.remove(entry);
                    entry.setStatus("");
                    entry.setProgress(0.0);
                });
                if (res.combinedOutput() != null && res.combinedOutput().contains("RebootRequired")) {
                    Platform.runLater(() -> {
                        if (!disposed) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait();
                        }
                    });
                }
            } else {
                String errorMsg = res.combinedOutput();
                entry.setLastError(errorMsg);
                synchronized (failedEntries) { failedEntries.add(entry); }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, errorMsg);
                if (isMsiCorruptionError(errorMsg)) {
                    errorMsg = "Windows Installer corruption detected.\n\n"
                            + "The old version of this product cannot be removed because the installer cache is damaged.\n\n"
                            + "To fix this:\n"
                            + "1. Open Settings > Apps > Installed apps\n"
                            + "2. Find and uninstall '" + (entry.getName() != null ? entry.getName() : entry.id()) + "'\n"
                            + "3. Come back here and click 'Scan' to reinstall\n\n"
                            + "Alternatively, download and run the Microsoft Program Install troubleshooter:\n"
                            + "https://support.microsoft.com/en-us/topic/fix-problems-that-block-programs-from-being-installed-or-removed-cca7d1b6-65a9-3d98-426b-e9f927e1eb4d";
                }
                String finalMsg = errorMsg;
                Platform.runLater(() -> {
                    if (disposed) return;
                    new Alert(Alert.AlertType.ERROR, "Install failed:\n" + finalMsg).showAndWait();
                    entry.setStatus("Failed");
                    entry.setProgress(0.0);
                });
            }
        } catch (Exception ex) {
            String msg = ex.getMessage();
            entry.setLastError(msg);
            if (msg != null && msg.contains("INSTALL_TECHNOLOGY_MISMATCH")) {
                synchronized (failedEntries) { failedEntries.add(entry); }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, msg);
                Platform.runLater(() -> {
                    if (disposed) return;
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
                synchronized (failedEntries) { failedEntries.add(entry); }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, msg);
                Platform.runLater(() -> {
                    if (!disposed) {
                        new Alert(Alert.AlertType.ERROR, "Install failed:\n" + msg).showAndWait();
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

    private volatile int retryCount = 0;

    public void retryFailed() {
        synchronized (failedEntries) {
            if (failedEntries.isEmpty()) return;
            if (retryCount >= MAX_RETRY_ATTEMPTS) {
                Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                        "Maximum retry attempts (" + MAX_RETRY_ATTEMPTS + ") reached. Please scan again.").showAndWait());
                return;
            }
            retryCount++;
            List<SoftwareUpdateEntry> toRetry = new ArrayList<>(failedEntries);
            failedEntries.clear();
            for (SoftwareUpdateEntry e : toRetry) {
                e.setStatus("");
                e.setProgress(0.0);
                e.setSelected(true);
            }
            showRetryFailed.set(false);
            updateSelected(toRetry, true);
        }
    }

    public void skipEntry(SoftwareUpdateEntry entry) {
        try {
            AppSettings current = settingsStore.load();
            List<String> skipped = new ArrayList<>(current.skippedSoftwareIds());
            if (skipped.stream().noneMatch(s -> s.endsWith("\t" + entry.id()))) {
                skipped.add(entry.getName() + "\t" + entry.id());
            }
            settingsStore.save(current.toBuilder().skippedSoftwareIds(skipped).build());
        } catch (Exception ex) {
            AppLogger.warning("Failed to skip software entry: " + ex.getMessage());
        }
        Platform.runLater(() -> rows.remove(entry));
    }

    public List<SoftwareUpdateEntry> getFailedEntries() {
        synchronized (failedEntries) {
            return new ArrayList<>(failedEntries);
        }
    }

    public void dispose() {
        disposed = true;
        scanCancelled.set(true);
        installCancelled.set(true);
        installRunning.set(false);
        if (scanFuture != null) {
            try { scanFuture.cancel(true); } catch (Exception ignored) {}
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

    private void installOne(SoftwareUpdateEntry entry, int total,
                             AtomicInteger completed, List<SoftwareUpdateEntry> failedPackages,
                             List<SoftwareUpdateEntry> techMismatchEntries) {
        if (installCancelled.get()) return;
        try {
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
                    res = service.installWindowsUpdate(entry.updateId(), INSTALL_TIMEOUT_SECONDS, installCancelled);
                } catch (CancellationException cex) {
                    return;
                }
            } else {
                try {
                    res = service.updatePackageWithStreaming(entry.id(), true, INSTALL_TIMEOUT_SECONDS, entry, installCancelled);
                } catch (CancellationException cex) {
                    return;
                }
            }

            if (res.success()) {
                InstallerCleanupHelper.promptAndCleanup(service, entry, start);
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), true, null);
                Platform.runLater(() -> {
                    if (disposed) return;
                    statusText.set("Update installed for " + entry.getName());
                    rows.remove(entry);
                    entry.setStatus("");
                    entry.setProgress(0.0);
                });
                if (res.combinedOutput() != null && res.combinedOutput().contains("RebootRequired")) {
                    Platform.runLater(() -> {
                        if (!disposed) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required for " + entry.getName() + ".").showAndWait();
                        }
                    });
                }
            } else {
                String errorMsg = res.combinedOutput();
                AppLogger.warning("Update failed for " + entry.id() + ": " + errorMsg);
                if (isMsiCorruptionError(errorMsg)) {
                    errorMsg = "Windows Installer corruption detected.\n\n"
                            + "The old version of this product cannot be removed because the installer cache is damaged.\n\n"
                            + "To fix this:\n"
                            + "1. Open Settings > Apps > Installed apps\n"
                            + "2. Find and uninstall '" + (entry.getName() != null ? entry.getName() : entry.id()) + "'\n"
                            + "3. Come back here and click 'Scan' to reinstall\n\n"
                            + "Alternatively, download and run the Microsoft Program Install troubleshooter:\n"
                            + "https://support.microsoft.com/en-us/topic/fix-problems-that-block-programs-from-being-installed-or-removed-cca7d1b6-65a9-3d98-426b-e9f927e1eb4d";
                }
                entry.setLastError(errorMsg);
                synchronized (failedPackages) { failedPackages.add(entry); }
                recordHistory(entry, entry.getCurrentVersion(), entry.getAvailableVersion(), false, errorMsg);
                Platform.runLater(() -> {
                    if (disposed) return;
                    entry.setStatus("Failed");
                    entry.setProgress(0.0);
                });
            }
        } catch (CancellationException cex) {
            return;
        } catch (Exception ex) {
            String msg = ex.getMessage();
            AppLogger.warning("Exception during update: " + msg);
            entry.setLastError(msg);
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
                batchProgressText.set(current + " / " + total);
                batchProgress.set((double) current / total);
            });
        }
    }

    private static boolean isMsiCorruptionError(String output) {
        if (output == null) return false;
        String lower = output.toLowerCase();
        if (lower.contains("cannot be removed") || lower.contains("error 1714")) return true;
        if (!lower.contains("exit code: 1603")) return false;
        int logIdx = lower.indexOf("installer log is available at:");
        if (logIdx < 0) return false;
        String afterLog = output.substring(logIdx + "installer log is available at:".length());
        String[] logLines = afterLog.split("\\r?\\n");
        String logPath = "";
        for (String line : logLines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) { logPath = trimmed; break; }
        }
        if (logPath.isEmpty()) return false;
        try {
            String logContent = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(logPath))).toLowerCase();
            return logContent.contains("error 1714") || logContent.contains("cannot be removed");
        } catch (Exception e) {
            return false;
        }
    }

    private CompletableFuture<Void> maybeCreateRestorePointAsync() {
        AppSettings settings = settingsStore.load();
        if (!settings.createSystemRestorePoint()) {
            return CompletableFuture.completedFuture(null);
        }
        if (restorePointCreatedThisBatch.get()) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Would you like to create a System Restore Point before proceeding with the updates?");
            confirm.setHeaderText(AppInfo.DISPLAY_NAME);
            confirm.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK) {
                    boolean created = restoreService.createRestorePoint("WinZenith software update").success();
                    if (!created) AppLogger.warning("Restore point creation failed or skipped.");
                    restorePointCreatedThisBatch.set(true);
                }
            });
            f.complete(null);
        });
        return f;
    }

    private void showBatchResultDialog(List<SoftwareUpdateEntry> failedEntries, List<SoftwareUpdateEntry> techMismatchEntries) {
        StringBuilder msg = new StringBuilder();
        if (!failedEntries.isEmpty()) {
            msg.append("The following updates failed:\n\n");
            for (SoftwareUpdateEntry fe : failedEntries) {
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
        if (!techMismatchEntries.isEmpty()) buttons.add(new ButtonType("Add to Ignore List"));
        buttons.add(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        a.getButtonTypes().setAll(buttons);

        ButtonType result = a.showAndWait().orElse(new ButtonType("OK", ButtonBar.ButtonData.OK_DONE));
        if (result.getText().equals("Retry Failed")) {
            retryFailed();
        } else if (result.getText().equals("Add to Ignore List")) {
            for (SoftwareUpdateEntry e : techMismatchEntries) skipEntry(e);
        }
    }

    private void recordHistory(SoftwareUpdateEntry entry, String oldVersion, String newVersion,
                                boolean success, String errorMessage) {
        try {
            new SoftwareUpdateHistoryStore().add(new SoftwareUpdateHistoryEntry(
                    entry.getName(), entry.id(), oldVersion, newVersion,
                    entry.source(), Instant.now(), success, errorMessage));
        } catch (Exception ex) {
            AppLogger.warning("Failed to record update history: " + ex.getMessage());
        }
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
