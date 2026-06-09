package com.sbtools.ui;

import com.sbtools.backup.SystemRestoreService;
import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateService;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;
import java.nio.file.Path;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import javafx.collections.FXCollections;

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
    private TableCell<SoftwareUpdateEntry, Void> currentInstallCell;
    private volatile boolean scanCancelled;
    private volatile Thread scanThread;

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

        TableColumn<SoftwareUpdateEntry, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(280);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button updateBtn = new Button("Update");
            private final Button skipBtn = new Button("Skip");
            private final ProgressBar downloadProgress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            private final Label sizeLabel = new Label("Installing…");
            private final Label installingLabel = new Label("Installing update. Please wait…");
            private final ProgressIndicator spinner = new ProgressIndicator();

            {
                updateBtn.setStyle("-fx-font-family: \"Segoe UI\", sans-serif; -fx-font-size: 12;");
                skipBtn.setStyle("-fx-font-family: \"Segoe UI\", sans-serif; -fx-font-size: 12;");
                spinner.setPrefSize(48, 48);
                spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                spinner.setVisible(false);
                installingLabel.setVisible(false);
                downloadProgress.setPrefWidth(80);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);

                updateBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        updateSingle(entry, this);
                    }
                });

                skipBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        skipEntry(entry);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    updateBtn.setDisable(entry == null || busy.get());
                    HBox container = new HBox(6, updateBtn, skipBtn, sizeLabel, downloadProgress, installingLabel, spinner);
                    container.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(container);
                }
            }

            private void showInstallingState() {
                updateBtn.setVisible(false);
                downloadProgress.setVisible(true);
                downloadProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                sizeLabel.setVisible(true);
                sizeLabel.setText("Installing…");
                installingLabel.setVisible(true);
                spinner.setVisible(true);
            }

            private void hideInstallingState() {
                updateBtn.setVisible(true);
                updateBtn.setDisable(false);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);
                installingLabel.setVisible(false);
                spinner.setVisible(false);
            }
        });

        table.getColumns().addAll(selCol, nameCol, currentCol, availCol, actionCol);

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
                    if (child instanceof Button btn) {
                        btn.setVisible(false);
                    } else if (child instanceof ProgressBar pb) {
                        pb.setVisible(true);
                        pb.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    } else if (child instanceof Label label) {
                        if (label.getText() != null && label.getText().contains("Installing")) {
                            label.setVisible(true);
                        } else {
                            label.setVisible(true);
                            label.setText("Installing…");
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
                    if (child instanceof Button btn) {
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
        scanCancelled = true;
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
        scanCancelled = false;
        busy.set(true);
        progress.setVisible(true);
        scanButton.setDisable(true);
        stopScanButton.setDisable(false);
        statusLabel.setText("Scanning for updates…");
        new Thread(() -> {
            scanThread = Thread.currentThread();
            List<SoftwareUpdateEntry> allUpdates = new java.util.ArrayList<>();
            int wingetCount = 0;
            int wuCount = 0;
            try {
                boolean wingetAvailable = service.isWingetAvailable();
                if (wingetAvailable) {
                    try {
                        if (scanCancelled) return;
                        List<SoftwareUpdateEntry> wingetUpdates = service.scanForUpdates();
                        wingetCount = wingetUpdates.size();
                        allUpdates.addAll(wingetUpdates);
                    } catch (Exception ex) {
                        AppLogger.warning("winget scan failed: " + ex.getMessage());
                    }
                } else {
                    String diag = service.getWingetDiagnostics();
                    final String diagnostics = diag;
                    Platform.runLater(() -> {
                        statusLabel.setText("winget not found. Checking Windows Update…");
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
                    });
                }

                if (scanCancelled) return;

                try {
                    List<SoftwareUpdateEntry> wuUpdates = service.scanForWindowsUpdates();
                    wuCount = wuUpdates.size();
                    allUpdates.addAll(wuUpdates);
                } catch (Exception ex) {
                    AppLogger.warning("Windows Update scan failed: " + ex.getMessage());
                }

                if (scanCancelled) return;

                final int wc = wingetCount;
                final int wuc = wuCount;
                final List<SoftwareUpdateEntry> finalUpdates = allUpdates;
                AppSettings settings = settingsStore.load();
                List<String> skippedIds = settings.skippedSoftwareIds();
                java.util.Set<String> skippedIdSet = skippedIds.stream()
                    .map(s -> { int t = s.lastIndexOf('\t'); return t >= 0 ? s.substring(t + 1) : s; })
                    .collect(java.util.stream.Collectors.toSet());
                final List<SoftwareUpdateEntry> filteredUpdates = finalUpdates.stream()
                    .filter(e -> !skippedIdSet.contains(e.id()))
                    .collect(Collectors.toList());
                if (scanCancelled) return;
                Platform.runLater(() -> {
                    if (scanCancelled) return;
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
                if (!scanCancelled) {
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
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the updates?");
        confirm.setHeaderText(AppInfo.DISPLAY_NAME);
        if (confirm.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
            boolean created = restoreService.createRestorePoint("SB Tools software update");
            if (!created) {
                AppLogger.warning("Restore point creation failed or skipped.");
            }
        }
        busy.set(true);
        int total = selected.size();
        statusLabel.setText("Installing " + total + " update(s) …");
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
            for (SoftwareUpdateEntry e : selected) {
                try {
                    int current = completed;
                    Instant start = Instant.now();
                    Platform.runLater(() -> {
                        statusLabel.setText("Installing " + e.getName() + "…");
                        batchProgressLabel.setText(current + " / " + total);
                        batchProgressBar.setProgress((double) current / total);
                    });
                    ProcessResult res;
                    if ("WindowsUpdate".equals(e.source()) && e.updateId() != null) {
                        res = service.installWindowsUpdate(e.updateId(), 1200);
                    } else {
                        res = service.updatePackage(e.id(), true, 1200);
                    }
                    if (res.success()) {
                        if (!"WindowsUpdate".equals(e.source())) {
                            List<Path> candidates = service.findCandidateInstallersForPackage(e, start);
                            if (candidates != null && !candidates.isEmpty()) {
                                AtomicBoolean userConfirmed = new AtomicBoolean(false);
                                CountDownLatch latch = new CountDownLatch(1);
                                Platform.runLater(() -> {
                                    StringBuilder sb = new StringBuilder();
                                    for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
                                    Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                                            "The following installer files were detected in your Downloads folder:\n\n" + sb.toString() + "\nDelete these files?");
                                    del.setHeaderText("Delete installer files for " + (e.getName() != null ? e.getName() : e.id()));
                                    if (del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                                        userConfirmed.set(true);
                                    }
                                    latch.countDown();
                                });
                                try {
                                    latch.await();
                                    if (userConfirmed.get()) {
                                        service.deleteInstallerFiles(candidates);
                                    }
                                } catch (InterruptedException ie) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                        Platform.runLater(() -> {
                            statusLabel.setText("Update installed for " + e.getName());
                            rows.remove(e);
                        });
                    } else {
                        AppLogger.warning("Update failed for " + e.id() + ": " + res.combinedOutput());
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Exception during update: " + ex.getMessage());
                }
                completed++;
            }
            Platform.runLater(() -> {
                progress.setVisible(false);
                batchProgressBar.setVisible(false);
                batchProgressLabel.setVisible(false);
                scanButton.setDisable(false);
                busy.set(false);
                statusLabel.setText("Selected updates finished. Re-scan to refresh list.");
                scan();
            });
        }, "software-install").start();
    }

    private void updateSingle(SoftwareUpdateEntry entry, TableCell<SoftwareUpdateEntry, Void> cell) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait();
            return;
        }
        Alert confirmRestore = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the update?");
        confirmRestore.setHeaderText(AppInfo.DISPLAY_NAME);
        if (confirmRestore.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
            restoreService.createRestorePoint("SB Tools software update");
        }
        busy.set(true);
        currentInstallCell = cell;
        statusLabel.setText("Installing update for " + entry.getName() + " …");
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
                    res = service.updatePackage(entry.id(), true, 1200);
                }
                if (res.success()) {
                    if (!"WindowsUpdate".equals(entry.source())) {
                        List<Path> candidates = service.findCandidateInstallersForPackage(entry, start);
                        if (candidates != null && !candidates.isEmpty()) {
                            AtomicBoolean userConfirmed = new AtomicBoolean(false);
                            CountDownLatch latch = new CountDownLatch(1);
                            Platform.runLater(() -> {
                                StringBuilder sb = new StringBuilder();
                                for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
                                Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                                        "The following installer files were detected in your Downloads folder:\n\n" + sb.toString() + "\nDelete these files?");
                                del.setHeaderText("Delete installer files for " + (entry.getName() != null ? entry.getName() : entry.id()));
                                if (del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                                    userConfirmed.set(true);
                                }
                                latch.countDown();
                            });
                            try {
                                latch.await();
                                if (userConfirmed.get()) {
                                    service.deleteInstallerFiles(candidates);
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    }
                    Platform.runLater(() -> {
                        statusLabel.setText("Update installed for " + entry.getName());
                        rows.remove(entry);
                    });
                } else {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + res.combinedOutput()).showAndWait());
                }
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    scanButton.setDisable(false);
                    updateSelectedButton.setDisable(false);
                    updateInstallButtonState();
                    if (currentInstallCell != null) {
                        hideCellInstallingState(currentInstallCell);
                    }
                    currentInstallCell = null;
                    busy.set(false);
                });
            }
        }, "software-install-single").start();
    }

    private void skipEntry(SoftwareUpdateEntry entry) {
        try {
            AppSettings current = settingsStore.load();
            List<String> skipped = new java.util.ArrayList<>(current.skippedSoftwareIds());
            String stored = entry.getName() + "\t" + entry.id();
            if (skipped.stream().noneMatch(s -> s.endsWith("\t" + entry.id()))) {
                skipped.add(stored);
            }
            AppSettings updated = new AppSettings(
                current.autoBackupDrivers(),
                current.createSystemRestorePoint(),
                current.eulaAccepted(),
                current.excludedDriverIds(),
                skipped
            );
            settingsStore.save(updated);
        } catch (Exception ex) {
            AppLogger.warning("Failed to skip software entry: " + ex.getMessage());
        }
        rows.remove(entry);
    }

    private void showIgnoredListDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.DISPLAY_NAME);
        dialog.setHeaderText("Ignored Software Updates");

        AppSettings current = settingsStore.load();
        ObservableList<String> skippedIds = FXCollections.observableArrayList(current.skippedSoftwareIds());

        ListView<String> listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    int t = item.lastIndexOf('\t');
                    setText(t >= 0 ? item.substring(0, t) : item);
                }
            }
        });
        listView.setItems(skippedIds);
        listView.setPrefHeight(300);

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                skippedIds.remove(selected);
                try {
                    AppSettings updated = new AppSettings(
                        current.autoBackupDrivers(),
                        current.createSystemRestorePoint(),
                        current.eulaAccepted(),
                        current.excludedDriverIds(),
                        new java.util.ArrayList<>(skippedIds)
                    );
                    settingsStore.save(updated);
                } catch (Exception ex) {
                    AppLogger.warning("Failed to update ignored list: " + ex.getMessage());
                }
            }
        });

        VBox layout = new VBox(10, new Label("Skipped software updates:"), listView, removeBtn);
        layout.setPadding(new Insets(10));
        layout.setPrefWidth(500);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
