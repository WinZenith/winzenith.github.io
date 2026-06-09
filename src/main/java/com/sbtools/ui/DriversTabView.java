package com.sbtools.ui;

import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.DriverHealthService;
import com.sbtools.drivers.DriverInstallService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.model.DriverRow;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DriversTabView extends BorderPane {

    private final DriverScanService scanService = new DriverScanService();
    private final DriverCatalogAggregator catalog = DriverCatalogAggregator.createDefault();
    private final DriverInstallService installService = new DriverInstallService();
    private final SettingsStore settingsStore = new SettingsStore();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<DriverRow> outdatedRows = FXCollections.observableArrayList();
    private final ObservableList<DriverRow> upToDateRows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Click Scan to check for outdated drivers.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label("0%");
    private final Button scanButton = new Button("Scan for outdated drivers");
    private final Button stopScanButton = new Button("Stop scan");
    private TableCell<DriverRow, Void> currentInstallCell;
    private TableView<DriverRow> outdatedTable;
    private volatile boolean scanCancelled;
    private volatile Thread scanThread;

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);

        scanButton.setOnAction(e -> startScan());
        stopScanButton.setOnAction(e -> stopScan());
        stopScanButton.setDisable(true);
        Button ignoredListButton = new Button("Ignored List");
        ignoredListButton.setOnAction(e -> showIgnoredListDialog());
        HBox top = new HBox(12, scanButton, stopScanButton, ignoredListButton, progressBar, progressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        VBox tablesContainer = buildTablesContainer();
        setTop(top);
        setCenter(tablesContainer);
        busy.addListener((obs, oldVal, newVal) -> {
            if (outdatedTable != null) {
                outdatedTable.refresh();
            }
        });
        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    private VBox buildTablesContainer() {
        UILabel outdatedLabel = UILabel.sectionTitle("Outdated Drivers");
        UILabel upToDateLabel = UILabel.sectionTitle("Up to Date Drivers");
        
        outdatedTable = buildTable(outdatedRows);
        TableView<DriverRow> upToDateTable = buildUpToDateTable(upToDateRows);
        
        VBox.setVgrow(outdatedTable, Priority.ALWAYS);
        VBox.setVgrow(upToDateTable, Priority.ALWAYS);
        
        VBox container = new VBox(8, outdatedLabel, outdatedTable, upToDateLabel, upToDateTable);
        container.setPadding(new Insets(12, 16, 12, 16));
        return container;
    }

    private TableView<DriverRow> buildTable(ObservableList<DriverRow> items) {
        TableView<DriverRow> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriverRow, String> deviceCol = new TableColumn<>("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());
        deviceCol.setPrefWidth(220);

        TableColumn<DriverRow, String> currentCol = new TableColumn<>("Current");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(100);

        TableColumn<DriverRow, String> availableCol = new TableColumn<>("Available");
        availableCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availableCol.setPrefWidth(100);

        TableColumn<DriverRow, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(90);

        TableColumn<DriverRow, DriverHealthService.DriverHealthScore> healthCol = new TableColumn<>("Health");
        healthCol.setCellValueFactory(c -> c.getValue().healthScoreProperty());
        healthCol.setPrefWidth(80);
        healthCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(DriverHealthService.DriverHealthScore item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    Label label = new Label(item.getLabel());
                    label.setStyle(item.getColorStyle());
                    label.setTooltip(new Tooltip(item.details()));
                    setGraphic(label);
                }
            }
        });

        TableColumn<DriverRow, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(320);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton updateBtn = UIButton.small("Update");
            private final UIButton ignoreBtn = UIButton.small("Ignore");
            private final UIButton stopBtn = UIButton.small("Stop");
            private final ProgressBar downloadProgress = new ProgressBar(0);
            private final UILabel sizeLabel = new UILabel("");
            private final Label installingLabel = new Label("Installing driver. Please wait…");
            private final ProgressIndicator spinner = new ProgressIndicator();

            {
                spinner.setPrefSize(48, 48);
                spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                installingLabel.setVisible(false);
                spinner.setVisible(false);

                updateBtn.setOnAction(e -> {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    if (row != null && row.hasUpdate()) {
                        installUpdate(row, this);
                    }
                });

                ignoreBtn.setOnAction(e -> {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        AppSettings current = settingsStore.load();
                        List<String> excluded = new ArrayList<>(current.excludedDriverIds());
                        String stored = row.installed().friendlyName() + "\t" + row.installed().deviceId();
                        if (excluded.stream().noneMatch(s -> s.endsWith("\t" + row.installed().deviceId()))) {
                            excluded.add(stored);
                        }
                        AppSettings updated = new AppSettings(
                            current.autoBackupDrivers(),
                            current.createSystemRestorePoint(),
                            current.eulaAccepted(),
                            excluded,
                            current.skippedSoftwareIds()
                        );
                        try {
                            settingsStore.save(updated);
                        } catch (IOException ex) {
                            AppLogger.warning("Failed to save excluded driver: " + ex.getMessage());
                        }
                        getTableView().getItems().remove(row);
                    }
                });
                
                stopBtn.setOnAction(e -> {
                    installService.cancel();
                    stopBtn.setDisable(true);
                });
                
                downloadProgress.setPrefWidth(80);
                downloadProgress.setVisible(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    updateBtn.setDisable(row == null || !row.hasUpdate() || busy.get());
                    ignoreBtn.setDisable(busy.get());
                    if (row != null && row.candidate() != null && row.candidate().description() != null) {
                        updateBtn.setTooltip(new Tooltip(row.candidate().title()));
                    }
                    
                    HBox container = new HBox(6, ignoreBtn, updateBtn, sizeLabel, downloadProgress, stopBtn, installingLabel, spinner);
                    container.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(container);
                }
            }
            
            private void showDownloadProgress(String size, double progress) {
                downloadProgress.setVisible(true);
                downloadProgress.setProgress(progress);
                sizeLabel.setText(size);
                stopBtn.setVisible(true);
                stopBtn.setDisable(false);
                updateBtn.setVisible(false);
                ignoreBtn.setVisible(false);
                installingLabel.setVisible(false);
                spinner.setVisible(false);
            }
            
            private void showInstallingState() {
                updateBtn.setVisible(false);
                ignoreBtn.setVisible(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);
                downloadProgress.setVisible(false);
                sizeLabel.setText("");
                installingLabel.setVisible(true);
                spinner.setVisible(true);
            }
            
            private void hideDownloadProgress() {
                downloadProgress.setVisible(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);
                sizeLabel.setText("");
                installingLabel.setVisible(false);
                spinner.setVisible(false);
                updateBtn.setVisible(true);
                ignoreBtn.setVisible(true);
            }
        });

        table.getColumns().addAll(deviceCol, currentCol, availableCol, sourceCol, healthCol, actionCol);
        return table;
    }

    private TableView<DriverRow> buildUpToDateTable(ObservableList<DriverRow> items) {
        TableView<DriverRow> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriverRow, String> deviceCol = new TableColumn<>("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());
        deviceCol.setPrefWidth(220);

        TableColumn<DriverRow, String> currentCol = new TableColumn<>("Current");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(100);

        TableColumn<DriverRow, DriverHealthService.DriverHealthScore> healthCol = new TableColumn<>("Health");
        healthCol.setCellValueFactory(c -> c.getValue().healthScoreProperty());
        healthCol.setPrefWidth(80);
        healthCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(DriverHealthService.DriverHealthScore item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    Label label = new Label(item.getLabel());
                    label.setStyle(item.getColorStyle());
                    label.setTooltip(new Tooltip(item.details()));
                    setGraphic(label);
                }
            }
        });

        TableColumn<DriverRow, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(120);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton ignoreBtn = UIButton.small("Ignore");

            {
                ignoreBtn.setOnAction(e -> {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        AppSettings current = settingsStore.load();
                        List<String> excluded = new ArrayList<>(current.excludedDriverIds());
                        String stored = row.installed().friendlyName() + "\t" + row.installed().deviceId();
                        if (excluded.stream().noneMatch(s -> s.endsWith("\t" + row.installed().deviceId()))) {
                            excluded.add(stored);
                        }
                        AppSettings updated = new AppSettings(
                            current.autoBackupDrivers(),
                            current.createSystemRestorePoint(),
                            current.eulaAccepted(),
                            excluded,
                            current.skippedSoftwareIds()
                        );
                        try {
                            settingsStore.save(updated);
                        } catch (IOException ex) {
                            AppLogger.warning("Failed to save excluded driver: " + ex.getMessage());
                        }
                        getTableView().getItems().remove(row);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    setGraphic(ignoreBtn);
                }
            }
        });

        table.getColumns().addAll(deviceCol, currentCol, healthCol);
        return table;
    }

    private void stopScan() {
        scanCancelled = true;
        if (scanThread != null) {
            scanThread.interrupt();
            scanThread = null;
        }
        busy.set(false);
        progressBar.setVisible(false);
        progressLabel.setVisible(false);
        scanButton.setDisable(false);
        stopScanButton.setDisable(true);
        setStatus("Scan stopped.");
    }

    private void startScan() {
        if (busy.get()) {
            return;
        }
        scanCancelled = false;
        busy.set(true);
        setStatus("Enumerating installed drivers…");
        scanButton.setDisable(true);
        stopScanButton.setDisable(false);
        outdatedRows.clear();
        upToDateRows.clear();
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        new Thread(() -> {
            scanThread = Thread.currentThread();
            try {
                if (scanCancelled) return;
                List<InstalledDriver> installed = scanService.scanInstalled();
                if (scanCancelled) return;
                Map<String, DriverRow> rowByDevice = new HashMap<>();
                ObservableList<DriverRow> initialRows = FXCollections.observableArrayList();
                for (InstalledDriver d : installed) {
                    DriverRow row = new DriverRow(d);
                    rowByDevice.put(d.deviceId(), row);
                    initialRows.add(row);
                }
                Platform.runLater(() -> {
                    progressBar.setProgress(0.2);
                    progressLabel.setText("20%");
                    setStatus("Listed " + installed.size() + " device(s). Checking update sources…");
                });

                if (scanCancelled) return;
                AtomicInteger providersDone = new AtomicInteger();
                int providerCount = catalog.providerCount();
                catalog.findUpdates(
                        installed,
                        providerId -> {
                            if (scanCancelled) return;
                            Platform.runLater(() ->
                                    setStatus(providerStatus(providerId, installed.size())));
                        },
                        candidates -> {
                            if (scanCancelled) return;
                            Platform.runLater(() -> {
                                if (scanCancelled) return;
                                applyCandidates(rowByDevice, candidates);
                                int done = providersDone.incrementAndGet();
                                double progress = 0.2 + (0.8 * done / providerCount);
                                progressBar.setProgress(progress);
                                progressLabel.setText((int)(progress * 100) + "%");
                                
                                // Split rows into outdated and up-to-date, filtering excluded
                                splitRows(rowByDevice);
                                
                                int outdated = outdatedRows.size();
                                if (done < providerCount) {
                                    setStatus("Checked " + done + "/" + providerCount + " sources — "
                                            + outdated + " update(s) found so far…");
                                } else {
                                    setStatus("Found " + outdated + " outdated driver(s) out of "
                                            + installed.size() + " device(s).");
                                }
                            });
                        }
                );
            } catch (Exception ex) {
                if (!scanCancelled) {
                    Platform.runLater(() -> {
                        setStatus("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                scanThread = null;
                Platform.runLater(() -> {
                    busy.set(false);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    scanButton.setDisable(false);
                    stopScanButton.setDisable(true);
                });
            }
        }, "driver-scan").start();
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static String providerStatus(String providerId, int deviceCount) {
        return switch (providerId) {
            case "WindowsUpdate" -> "Checking Windows Update (" + deviceCount + " devices)…";
            case "Nvidia" -> "Checking NVIDIA catalog…";
            case "AMD" -> "Checking AMD catalog…";
            case "Intel" -> "Checking Intel catalog…";
            default -> "Checking " + providerId + "…";
        };
    }

    private static void applyCandidates(Map<String, DriverRow> rowByDevice, List<DriverUpdateCandidate> candidates) {
        for (DriverRow row : rowByDevice.values()) {
            row.setCandidate(null);
        }
        for (DriverUpdateCandidate c : candidates) {
            DriverRow row = rowByDevice.get(c.installed().deviceId());
            if (row != null) {
                row.setCandidate(c);
            }
        }
    }

    private boolean isExcluded(List<String> excludedIds, String deviceId) {
        return excludedIds.stream().anyMatch(e -> {
            int t = e.lastIndexOf('\t');
            return (t >= 0 ? e.substring(t + 1) : e).equals(deviceId);
        });
    }

    private void splitRows(Map<String, DriverRow> rowByDevice) {
        AppSettings settings = settingsStore.load();
        List<String> excludedIds = settings.excludedDriverIds();
        outdatedRows.clear();
        upToDateRows.clear();
        for (DriverRow row : rowByDevice.values()) {
            if (isExcluded(excludedIds, row.installed().deviceId())) {
                continue;
            }
            if (row.hasUpdate()) {
                outdatedRows.add(row);
            } else {
                upToDateRows.add(row);
            }
        }
    }

    private void showIgnoredListDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Ignored Drivers");
        dialog.setHeaderText("Ignored Drivers");

        AppSettings current = settingsStore.load();
        ObservableList<String> excludedIds = FXCollections.observableArrayList(current.excludedDriverIds());

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
        listView.setItems(excludedIds);
        listView.setPrefHeight(300);

        Button removeBtn = new Button("Remove Selected");
        removeBtn.setOnAction(e -> {
            String selected = listView.getSelectionModel().getSelectedItem();
            if (selected != null) {
                excludedIds.remove(selected);
                try {
                    AppSettings updated = new AppSettings(
                        current.autoBackupDrivers(),
                        current.createSystemRestorePoint(),
                        current.eulaAccepted(),
                        new java.util.ArrayList<>(excludedIds),
                        current.skippedSoftwareIds()
                    );
                    settingsStore.save(updated);
                } catch (IOException ex) {
                    AppLogger.warning("Failed to update ignored list: " + ex.getMessage());
                }
            }
        });

        VBox layout = new VBox(10, new Label("Excluded drivers:"), listView, removeBtn);
        layout.setPadding(new Insets(10));
        layout.setPrefWidth(500);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private void installUpdate(DriverRow row, TableCell<DriverRow, Void> cell) {
        if (!adminCheck.getAsBoolean()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Installing drivers requires administrator rights.");
            a.showAndWait();
            return;
        }
        DriverUpdateCandidate c = row.candidate();

        boolean isWuInstall = "WindowsUpdate".equals(c.source())
                && c.packageId() != null && !c.packageId().isBlank();

        if (!isWuInstall && (c.downloadUrl() == null || c.downloadUrl().isBlank())) {
            showManualDownloadDialog(c);
            return;
        }
        
        String sizeDisplay = "Downloading...";
        
        currentInstallCell = cell;
        installService.resetCancellation();
        installService.setProgressCallback((bytesReceived, totalBytes, fraction) -> {
            String sizeText;
            if (totalBytes > 0) {
                sizeText = formatBytes(bytesReceived) + " / " + formatBytes(totalBytes);
            } else {
                sizeText = formatBytes(bytesReceived);
            }
            Platform.runLater(() -> {
                if (currentInstallCell != null) {
                    showCellDownloadProgress(currentInstallCell, sizeText, fraction);
                }
            });
        });
        installService.setStatusCallback(status -> Platform.runLater(() -> {
            if (currentInstallCell != null) {
                showCellInstallingState(currentInstallCell);
            }
        }));
        busy.set(true);
        statusLabel.setText("Installing update for " + row.installed().friendlyName() + "...");
        
        final TableCell<DriverRow, Void> finalCell = cell;
        Platform.runLater(() -> {
            if (finalCell != null) {
                showCellDownloadProgress(finalCell, sizeDisplay, 0.0);
            }
        });
        
        AppSettings settings = settingsStore.load();
        new Thread(() -> {
            try {
                DriverInstallService.InstallResult result = installService.install(c, settings);
                Platform.runLater(() -> {
                    if (result.installed()) {
                        statusLabel.setText("Update installed for " + row.installed().friendlyName());
                        row.setCandidate(null);
                        outdatedRows.remove(row);
                        upToDateRows.add(row);
                        if (result.rebootRequired()) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait();
                        }
                    } else {
                        showErrorWithFallback(result.message(), c.vendorPageUrl());
                    }
                    if (currentInstallCell != null) {
                        hideCellDownloadProgress(currentInstallCell);
                    }
                    currentInstallCell = null;
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    showErrorWithFallback("Install failed:\n" + ex.getMessage(), c.vendorPageUrl());
                    if (currentInstallCell != null) {
                        hideCellDownloadProgress(currentInstallCell);
                    }
                    currentInstallCell = null;
                });
            } finally {
                installService.setProgressCallback(null);
                installService.setStatusCallback(null);
                Platform.runLater(() -> busy.set(false));
            }
        }, "driver-install").start();
    }

    private void showErrorWithFallback(String message, String vendorPageUrl) {
        if (vendorPageUrl != null && !vendorPageUrl.isBlank()) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION, message + "\n\nYou can try downloading manually from the vendor website.",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.setTitle("Download Failed");
            alert.setHeaderText("Manual download available");

            Button openWebsiteBtn = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
            openWebsiteBtn.setText("Open Website");
            openWebsiteBtn.setOnAction(e -> {
                try {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(vendorPageUrl));
                } catch (Exception ex) {
                    AppLogger.warning("Failed to open browser: " + ex.getMessage());
                }
                alert.close();
            });

            alert.showAndWait();
        } else {
            new Alert(Alert.AlertType.INFORMATION, message).showAndWait();
        }
    }

    private void showManualDownloadDialog(DriverUpdateCandidate candidate) {
        String source = candidate.source() != null ? candidate.source() : "this provider";
        String deviceName = candidate.installed().friendlyName();
        String vendorUrl = candidate.vendorPageUrl();
        if (vendorUrl == null || vendorUrl.isBlank()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "No automatic download is available for " + source + " drivers.").showAndWait();
            return;
        }

        Alert alert = new Alert(Alert.AlertType.INFORMATION,
                "Automatic download is not available for " + deviceName + ".\n\n"
                        + "Please use the button below to go to the " + source
                        + " website, download the driver, and install it manually.",
                ButtonType.OK, ButtonType.CANCEL);
        alert.setTitle("Manual Download Required");
        alert.setHeaderText(source + " driver update");

        Button openWebsiteBtn = (Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL);
        openWebsiteBtn.setText("Open " + source + " Website");
        openWebsiteBtn.setOnAction(e -> {
            try {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(vendorUrl));
            } catch (Exception ex) {
                AppLogger.warning("Failed to open browser: " + ex.getMessage());
            }
            alert.close();
        });

        alert.getDialogPane().lookupButton(ButtonType.OK).addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
            alert.close();
        });

        alert.showAndWait();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private void showCellDownloadProgress(TableCell<DriverRow, Void> cell, String size, double progress) {
        try {
            if (cell.getGraphic() instanceof HBox) {
                HBox container = (HBox) cell.getGraphic();
                for (var child : container.getChildren()) {
                    if (child instanceof ProgressBar) {
                        ((ProgressBar) child).setVisible(true);
                        ((ProgressBar) child).setProgress(progress);
                    } else if (child instanceof UILabel) {
                        ((UILabel) child).setText(size);
                    } else if (child instanceof Label label) {
                        label.setText(size);
                    } else if (child instanceof ProgressIndicator) {
                        ((ProgressIndicator) child).setVisible(false);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Stop")) {
                        ((Button) child).setVisible(true);
                        ((Button) child).setDisable(false);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Update")) {
                        ((Button) child).setVisible(false);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Ignore")) {
                        ((Button) child).setVisible(false);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    private void hideCellDownloadProgress(TableCell<DriverRow, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox) {
                HBox container = (HBox) cell.getGraphic();
                for (var child : container.getChildren()) {
                    if (child instanceof ProgressBar) {
                        ((ProgressBar) child).setVisible(false);
                    } else if (child instanceof Label) {
                        ((Label) child).setText("");
                        ((Label) child).setVisible(false);
                    } else if (child instanceof ProgressIndicator) {
                        ((ProgressIndicator) child).setVisible(false);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Stop")) {
                        ((Button) child).setVisible(false);
                        ((Button) child).setDisable(true);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Update")) {
                        ((Button) child).setVisible(true);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Ignore")) {
                        ((Button) child).setVisible(true);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    private void showCellInstallingState(TableCell<DriverRow, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox) {
                HBox container = (HBox) cell.getGraphic();
                for (var child : container.getChildren()) {
                    if (child instanceof UIButton) {
                        child.setVisible(false);
                    } else if (child instanceof ProgressBar) {
                        ((ProgressBar) child).setVisible(false);
                    } else if (child instanceof UILabel) {
                        ((UILabel) child).setVisible(false);
                    } else if (child instanceof Label label) {
                        label.setText("Installing driver. Please wait…");
                        label.setVisible(true);
                    } else if (child instanceof ProgressIndicator) {
                        ((ProgressIndicator) child).setVisible(true);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
