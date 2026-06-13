package com.sbtools.ui;

import com.sbtools.backup.SystemRestoreService;
import com.sbtools.software.InstallerCleanupHelper;
import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateService;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;

public class SoftwareUpdatesTabView extends BorderPane {

    private final SoftwareUpdateService service = new SoftwareUpdateService();
    private final SystemRestoreService restoreService = new SystemRestoreService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<SoftwareUpdateEntry> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Scan for available app updates via winget.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ProgressBar batchProgressBar = new ProgressBar(0);
    private final Label batchProgressLabel = new Label();
    private final Button scanButton = new Button("Scan");
    private final Button stopScanButton = new Button("Stop scan");
    private final Button updateSelectedButton = new Button("Update Selected");
    private final SettingsStore settingsStore = new SettingsStore();

    private final AtomicBoolean scanCancelled = new AtomicBoolean(false);
    private volatile Thread scanThread;
    private final AtomicBoolean installCancelled = new AtomicBoolean(false);

    public SoftwareUpdatesTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progress.setVisible(false);
        progress.setMaxSize(24, 24);
        batchProgressBar.setVisible(false);
        batchProgressBar.setPrefWidth(150);
        batchProgressLabel.setVisible(false);

        scanButton.setOnAction(e -> scan());
        stopScanButton.setOnAction(e -> stopScan());
        stopScanButton.setDisable(true);
        updateSelectedButton.setOnAction(e -> updateSelected());
        updateSelectedButton.setDisable(true);
        Button ignoredListButton = new Button("Ignored List");
        ignoredListButton.setOnAction(e -> showIgnoredListDialog());

        HBox top = new HBox(12, scanButton, stopScanButton, updateSelectedButton, ignoredListButton, progress, batchProgressBar, batchProgressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        TableView<SoftwareUpdateEntry> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(top);
        setCenter(table);

        rows.addListener((ListChangeListener<SoftwareUpdateEntry>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (SoftwareUpdateEntry entry : c.getAddedSubList()) {
                        entry.selectedProperty().addListener((obs, oldVal, newVal) -> updateInstallButtonState());
                    }
                }
            }
            updateInstallButtonState();
        });

        busy.addListener((obs, oldVal, newVal) -> table.refresh());

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            updateSelectedButton.setDisable(true);
            statusLabel.setText("This application requires Windows.");
        }
    }

    private TableView<SoftwareUpdateEntry> buildTable() {
        TableView<SoftwareUpdateEntry> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SoftwareUpdateEntry, Boolean> selCol = new TableColumn<>("Install");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setPrefWidth(60);

        TableColumn<SoftwareUpdateEntry, String> nameCol = new TableColumn<>("Program");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());

        TableColumn<SoftwareUpdateEntry, String> currentCol = new TableColumn<>("Current Version");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(120);

        TableColumn<SoftwareUpdateEntry, String> availCol = new TableColumn<>("Available Version");
        availCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availCol.setPrefWidth(120);

        TableColumn<SoftwareUpdateEntry, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateEntry, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(formatBytes(c.getValue().sizeBytes())));
        sizeCol.setPrefWidth(80);

        TableColumn<SoftwareUpdateEntry, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(350);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton updateBtn = UIButton.small("Update");
            private final UIButton ignoreBtn = UIButton.small("Ignore");
            private final UIButton stopBtn = UIButton.small("Stop");
            private final ProgressBar downloadProgress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            private final Label sizeLabel = new Label("Installing...");
            private final Label installingLabel = new Label("Installing update. Please wait...");
            private final ProgressIndicator spinner = new ProgressIndicator();

            {
                spinner.setPrefSize(48, 48);
                spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                spinner.setVisible(false);
                installingLabel.setVisible(false);
                downloadProgress.setPrefWidth(80);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);

                updateBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        updateSingle(entry, this);
                    }
                });

                ignoreBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        skipEntry(entry);
                    }
                });

                stopBtn.setOnAction(e -> {
                    installCancelled.set(true);
                    stopBtn.setDisable(true);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    boolean disabled = entry == null || busy.get();
                    updateBtn.setDisable(disabled);
                    ignoreBtn.setDisable(disabled);
                    stopBtn.setDisable(true);
                    HBox container = new HBox(6, updateBtn, ignoreBtn, stopBtn, sizeLabel, downloadProgress, installingLabel, spinner);
                    container.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(container);
                }
            }

            private void showInstallingState() {
                updateBtn.setVisible(false);
                ignoreBtn.setVisible(false);
                stopBtn.setVisible(true);
                stopBtn.setDisable(false);
                downloadProgress.setVisible(true);
                downloadProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                sizeLabel.setVisible(true);
                sizeLabel.setText("Installing...");
                installingLabel.setVisible(true);
                spinner.setVisible(true);
            }

            private void hideInstallingState() {
                updateBtn.setVisible(true);
                updateBtn.setDisable(false);
                ignoreBtn.setVisible(true);
                ignoreBtn.setDisable(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);
                installingLabel.setVisible(false);
                spinner.setVisible(false);
            }
        });

        table.getColumns().addAll(selCol, nameCol, currentCol, availCol, sourceCol, sizeCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<SoftwareUpdateEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty()) {
                    SoftwareUpdateEntry entry = row.getItem();
                    entry.selectedProperty().set(!entry.selectedProperty().get());
                }
            });
            return row;
        });

        return table;
    }

    private void updateInstallButtonState() {
        boolean any = rows.stream().anyMatch(r -> r.selectedProperty().get());
        updateSelectedButton.setDisable(!any || busy.get());
    }

    @SuppressWarnings("unchecked")
    private void showCellInstallingState(TableCell<SoftwareUpdateEntry, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox container) {
                for (var child : container.getChildren()) {
                    if (child instanceof UIButton) {
                        child.setVisible(false);
                    } else if (child instanceof ProgressBar pb) {
                        pb.setVisible(true);
                        pb.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    } else if (child instanceof Label label) {
                        if (label.getText() != null && label.getText().contains("Installing")) {
                            label.setVisible(true);
                        } else {
                            label.setVisible(true);
                            label.setText("Installing...");
                        }
                    } else if (child instanceof ProgressIndicator pi) {
                        pi.setVisible(true);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @SuppressWarnings("unchecked")
    private void hideCellInstallingState(TableCell<SoftwareUpdateEntry, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox container) {
                for (var child : container.getChildren()) {
                    if (child instanceof UIButton btn) {
                        btn.setVisible(true);
                        btn.setDisable(false);
                    } else if (child instanceof ProgressBar pb) {
                        pb.setVisible(false);
                    } else if (child instanceof Label label) {
                        label.setVisible(false);
                        label.setText("");
                    } else if (child instanceof ProgressIndicator pi) {
                        pi.setVisible(false);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void stopScan() {
        scanCancelled.set(true);
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
        busy.set(false);
        progress.setVisible(false);
        scanButton.setDisable(false);
        stopScanButton.setDisable(true);
        statusLabel.setText("Scan stopped.");
    }

    private void scan() {
        if (busy.get()) return;
        scanCancelled.set(false);
        busy.set(true);
        progress.setVisible(true);
        scanButton.setDisable(true);
        stopScanButton.setDisable(false);
        statusLabel.setText("Scanning for updates...");
        new Thread(() -> {
            scanThread = Thread.currentThread();
            try {
                // Show winget-not-available dialog if needed (runs on scan thread, posts to FX)
                boolean wingetAvailable = service.isWingetAvailable();
                if (!wingetAvailable) {
                    String diag = service.getWingetDiagnostics();
                    Platform.runLater(() -> showWingetNotAvailableDialog(diag));
                }

                // Run both scans concurrently
                final int[] counts = {0, 0};
                List<SoftwareUpdateEntry> allUpdates = service.scanAllConcurrent(
                        scanCancelled,
                        wc -> counts[0] = wc,
                        wuc -> counts[1] = wuc
                );

                if (scanCancelled.get()) return;

                final int wc = counts[0];
                final int wuc = counts[1];
                AppSettings settings = settingsStore.load();
                List<String> skippedIds = settings.skippedSoftwareIds();
                Set<String> skippedIdSet = skippedIds.stream()
                    .map(s -> { int t = s.lastIndexOf('\t'); return t >= 0 ? s.substring(t + 1) : s; })
                    .collect(Collectors.toSet());
                final List<SoftwareUpdateEntry> filteredUpdates = allUpdates.stream()
                    .filter(e -> !skippedIdSet.contains(e.id()))
                    .collect(Collectors.toList());
                if (scanCancelled.get()) return;
                Platform.runLater(() -> {
                    if (scanCancelled.get()) return;
                    rows.setAll(filteredUpdates);
                    if (wc > 0 && wuc > 0) {
                        statusLabel.setText(filteredUpdates.size() + " outdated item(s) found (" + wc + " app(s), " + wuc + " Windows Update(s)).");
                    } else if (wc > 0) {
                        statusLabel.setText(wc + " outdated app(s) found.");
                    } else if (wuc > 0) {
                        statusLabel.setText(wuc + " Windows Update(s) found.");
                    } else {
                        statusLabel.setText("Everything is up to date.");
                    }
                    updateInstallButtonState();
                });
            } catch (Exception ex) {
                if (!scanCancelled.get()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                scanThread = null;
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    scanButton.setDisable(false);
                    stopScanButton.setDisable(true);
                });
            }
        }, "software-scan").start();
    }

    private void showWingetNotAvailableDialog(String diagnostics) {
        statusLabel.setText("winget not found. Checking Windows Update...");
        TextArea ta = new TextArea(diagnostics);
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(12);
        ta.setPrefColumnCount(80);

        Button storeBtn = new Button("Open App Installer in Microsoft Store");
        storeBtn.setOnAction(evt -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("ms-windows-store://pdp/?productid=9NBLGGH4NNS1"));
            } catch (Exception ex) {
                try {
                    new ProcessBuilder("cmd.exe", "/c", "start", "", "https://www.microsoft.com/store/apps/9NBLGGH4NNS1").start();
                } catch (Exception ex2) {
                    new Alert(Alert.AlertType.ERROR, "Could not open Store: " + ex2.getMessage()).showAndWait();
                }
            }
        });

        Button aliasBtn = new Button("Open App execution aliases settings");
        aliasBtn.setOnAction(evt -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI("ms-settings:appsfeatures"));
            } catch (Exception ex) {
                try {
                    new ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:appsfeatures").start();
                } catch (Exception ex2) {
                    new Alert(Alert.AlertType.ERROR, "Could not open Settings: " + ex2.getMessage()).showAndWait();
                }
            }
        });

        HBox btnBox = new HBox(8, storeBtn, aliasBtn);
        VBox content = new VBox(new Label("winget is not available on this system. Diagnostic output:"), ta, btnBox);
        content.setSpacing(8);
        Alert a = new Alert(Alert.AlertType.ERROR);
        a.setTitle(AppInfo.DISPLAY_NAME);
        a.getDialogPane().setContent(content);
        a.showAndWait();
    }

    private void updateSelected() {
        if (!adminCheck.getAsBoolean()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait());
            return;
        }
        List<SoftwareUpdateEntry> selected = rows.stream().filter(r -> r.selectedProperty().get()).collect(Collectors.toList());
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Select at least one program to update.").showAndWait();
            return;
        }

        maybeCreateRestorePoint();

        busy.set(true);
        installCancelled.set(false);
        int total = selected.size();
        statusLabel.setText("Installing " + total + " update(s)...");
        Platform.runLater(() -> {
            progress.setVisible(true);
            batchProgressBar.setVisible(true);
            batchProgressBar.setProgress(0);
            batchProgressLabel.setVisible(true);
            batchProgressLabel.setText("0 / " + total);
            scanButton.setDisable(true);
            updateSelectedButton.setDisable(true);
        });

        new Thread(() -> {
            int completed = 0;
            List<String> failedPackages = new ArrayList<>();
            List<SoftwareUpdateEntry> techMismatchEntries = new ArrayList<>();
            for (SoftwareUpdateEntry e : selected) {
                if (installCancelled.get()) break;
                try {
                    int current = completed;
                    Platform.runLater(() -> {
                        statusLabel.setText("Installing " + e.getName() + "...");
                        batchProgressLabel.setText(current + " / " + total);
                        batchProgressBar.setProgress((double) current / total);
                    });
                    ProcessResult res;
                    if ("WindowsUpdate".equals(e.source()) && e.updateId() != null) {
                        res = service.installWindowsUpdate(e.updateId(), 1200);
                    } else {
                        res = service.updatePackageWithFallback(e.id(), true, 1200);
                    }
                    if (res.success()) {
                        InstallerCleanupHelper.promptAndCleanup(service, e, Instant.now());
                        Platform.runLater(() -> {
                            statusLabel.setText("Update installed for " + e.getName());
                            rows.remove(e);
                        });
                        if (res.combinedOutput() != null && res.combinedOutput().contains("RebootRequired")) {
                            Platform.runLater(() ->
                                new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation of " + e.getName() + ".").showAndWait());
                        }
                    } else {
                        AppLogger.warning("Update failed for " + e.id() + ": " + res.combinedOutput());
                        failedPackages.add(e.getName() != null ? e.getName() : e.id());
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Exception during update: " + ex.getMessage());
                    if (ex.getMessage() != null && ex.getMessage().contains("INSTALL_TECHNOLOGY_MISMATCH")) {
                        techMismatchEntries.add(e);
                    } else {
                        failedPackages.add(e.getName() != null ? e.getName() : e.id());
                    }
                }
                completed++;
            }
            final int finalCompleted = completed;
            final List<String> finalFailed = failedPackages;
            final List<SoftwareUpdateEntry> finalTechMismatch = techMismatchEntries;
            Platform.runLater(() -> {
                progress.setVisible(false);
                batchProgressBar.setVisible(false);
                batchProgressLabel.setVisible(false);
                scanButton.setDisable(false);
                busy.set(false);
                if (installCancelled.get()) {
                    statusLabel.setText("Update cancelled. " + finalCompleted + " of " + total + " completed.");
                } else if (!finalFailed.isEmpty() || !finalTechMismatch.isEmpty()) {
                    int totalFailed = finalFailed.size() + finalTechMismatch.size();
                    statusLabel.setText("Completed with " + totalFailed + " failure(s). Re-scan to refresh.");
                    showBatchResultDialog(finalFailed, finalTechMismatch);
                } else {
                    statusLabel.setText("Selected updates finished. Re-scan to refresh list.");
                }
                scan();
            });
        }, "software-install").start();
    }

    private void updateSingle(SoftwareUpdateEntry entry, TableCell<SoftwareUpdateEntry, Void> cell) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait();
            return;
        }

        maybeCreateRestorePoint();

        busy.set(true);
        installCancelled.set(false);
        statusLabel.setText("Installing update for " + entry.getName() + "...");
        Platform.runLater(() -> {
            progress.setVisible(true);
            scanButton.setDisable(true);
            updateSelectedButton.setDisable(true);
            showCellInstallingState(cell);
        });

        new Thread(() -> {
            try {
                Instant start = Instant.now();
                ProcessResult res;
                if ("WindowsUpdate".equals(entry.source()) && entry.updateId() != null) {
                    res = service.installWindowsUpdate(entry.updateId(), 1200);
                } else {
                    res = service.updatePackageWithFallback(entry.id(), true, 1200);
                }
                if (res.success()) {
                    InstallerCleanupHelper.promptAndCleanup(service, entry, start);
                    Platform.runLater(() -> {
                        statusLabel.setText("Update installed for " + entry.getName());
                        rows.remove(entry);
                    });
                    if (res.combinedOutput() != null && res.combinedOutput().contains("RebootRequired")) {
                        Platform.runLater(() ->
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait());
                    }
                } else {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Install failed:\n" + res.combinedOutput()).showAndWait());
                }
            } catch (Exception ex) {
                String msg = ex.getMessage();
                if (msg != null && msg.contains("INSTALL_TECHNOLOGY_MISMATCH")) {
                    Platform.runLater(() -> {
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
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + msg).showAndWait());
                }
            } finally {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    scanButton.setDisable(false);
                    updateSelectedButton.setDisable(false);
                    updateInstallButtonState();
                    hideCellInstallingState(cell);
                    busy.set(false);
                });
            }
        }, "software-install-single").start();
    }

    private void maybeCreateRestorePoint() {
        AppSettings settings = settingsStore.load();
        if (settings.createSystemRestorePoint()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the updates?");
            confirm.setHeaderText(AppInfo.DISPLAY_NAME);
            if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                boolean created = restoreService.createRestorePoint("SB Tools software update");
                if (!created) {
                    AppLogger.warning("Restore point creation failed or skipped.");
                }
            }
        }
    }

    private void skipEntry(SoftwareUpdateEntry entry) {
        try {
            AppSettings current = settingsStore.load();
            List<String> skipped = new ArrayList<>(current.skippedSoftwareIds());
            String stored = entry.getName() + "\t" + entry.id();
            if (skipped.stream().noneMatch(s -> s.endsWith("\t" + entry.id()))) {
                skipped.add(stored);
            }
            AppSettings updated = new AppSettings(
                current.autoBackupDrivers(),
                current.createSystemRestorePoint(),
                current.eulaAccepted(),
                current.excludedDriverIds(),
                skipped,
                current.networkOptimizationPreset(),
                current.downloadDirectory()
            );
            settingsStore.save(updated);
        } catch (Exception ex) {
            AppLogger.warning("Failed to skip software entry: " + ex.getMessage());
        }
        rows.remove(entry);
    }

    private void showBatchResultDialog(List<String> failedNames, List<SoftwareUpdateEntry> techMismatchEntries) {
        StringBuilder msg = new StringBuilder();
        if (!failedNames.isEmpty()) {
            msg.append("The following updates failed:\n\n");
            for (String f : failedNames) msg.append("  - ").append(f).append("\n");
        }
        if (!techMismatchEntries.isEmpty()) {
            if (msg.length() > 0) msg.append("\n");
            msg.append("The following programs cannot be updated automatically\n");
            msg.append("(installer technology changed between versions):\n\n");
            for (SoftwareUpdateEntry e : techMismatchEntries) msg.append("  - ").append(e.getName()).append("\n");
            msg.append("\nPlease uninstall them manually, then scan again to install the newer version.");
        }

        Alert a = new Alert(Alert.AlertType.WARNING);
        a.setTitle(AppInfo.DISPLAY_NAME);
        a.setHeaderText("Update results");
        a.setContentText(msg.toString());

        if (!techMismatchEntries.isEmpty()) {
            ButtonType ignoreBtn = new ButtonType("Add to Ignore List");
            ButtonType okBtn = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
            a.getButtonTypes().setAll(ignoreBtn, okBtn);
            if (a.showAndWait().orElse(okBtn) == ignoreBtn) {
                for (SoftwareUpdateEntry e : techMismatchEntries) {
                    skipEntry(e);
                }
            }
        } else {
            a.showAndWait();
        }
    }

    private void showIgnoredListDialog() {
        AppSettings current = settingsStore.load();
        IgnoredListDialog.show("Ignored Software Updates", current.skippedSoftwareIds(), (updated, ignored) -> {
            try {
                AppSettings curr = settingsStore.load();
                AppSettings saved = new AppSettings(
                    curr.autoBackupDrivers(),
                    curr.createSystemRestorePoint(),
                    curr.eulaAccepted(),
                    curr.excludedDriverIds(),
                    updated,
                    curr.networkOptimizationPreset(),
                    curr.downloadDirectory()
                );
                settingsStore.save(saved);
            } catch (Exception ex) {
                AppLogger.warning("Failed to update ignored list: " + ex.getMessage());
            }
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
