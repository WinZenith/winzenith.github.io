package com.basicsdriverupdate.ui;

import com.basicsdriverupdate.drivers.catalog.DriverCatalogAggregator;
import com.basicsdriverupdate.drivers.DriverInstallService;
import com.basicsdriverupdate.drivers.DriverScanService;
import com.basicsdriverupdate.drivers.model.DriverRow;
import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.settings.SettingsStore;
import com.basicsdriverupdate.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

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
    private TableCell<DriverRow, Void> currentInstallCell;

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);

        scanButton.setOnAction(e -> startScan());
        HBox top = new HBox(12, scanButton, progressBar, progressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        VBox tablesContainer = buildTablesContainer();
        setTop(top);
        setCenter(tablesContainer);
        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    private VBox buildTablesContainer() {
        Label outdatedLabel = new Label("Outdated Drivers");
        outdatedLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 5 0 5 0;");
        
        Label upToDateLabel = new Label("Up to Date Drivers");
        upToDateLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 14px; -fx-padding: 5 0 5 0;");
        
        TableView<DriverRow> outdatedTable = buildTable(outdatedRows);
        TableView<DriverRow> upToDateTable = buildTable(upToDateRows);
        
        VBox.setVgrow(outdatedTable, Priority.ALWAYS);
        VBox.setVgrow(upToDateTable, Priority.ALWAYS);
        
        VBox container = new VBox(5, outdatedLabel, outdatedTable, upToDateLabel, upToDateTable);
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

        TableColumn<DriverRow, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(150);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button updateBtn = new Button("Update");
            private final Button stopBtn = new Button("Stop");
            private final ProgressBar downloadProgress = new ProgressBar(0);
            private final Label sizeLabel = new Label();

            {
                updateBtn.setOnAction(e -> {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    if (row != null && row.hasUpdate()) {
                        installUpdate(row, this);
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
                sizeLabel.setStyle("-fx-font-size: 11px;");
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    updateBtn.setDisable(row == null || !row.hasUpdate() || busy.get());
                    if (row != null && row.candidate() != null && row.candidate().description() != null) {
                        updateBtn.setTooltip(new Tooltip(row.candidate().title()));
                    }
                    
                    HBox container = new HBox(5, updateBtn, sizeLabel, downloadProgress, stopBtn);
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
            }
            
            private void hideDownloadProgress() {
                downloadProgress.setVisible(false);
                stopBtn.setVisible(false);
                stopBtn.setDisable(true);
                sizeLabel.setText("");
                updateBtn.setVisible(true);
            }
        });

        table.getColumns().addAll(deviceCol, currentCol, availableCol, sourceCol, actionCol);
        return table;
    }

    private void startScan() {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        setStatus("Enumerating installed drivers…");
        scanButton.setDisable(true);
        outdatedRows.clear();
        upToDateRows.clear();
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        new Thread(() -> {
            try {
                List<InstalledDriver> installed = scanService.scanInstalled();
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

                AtomicInteger providersDone = new AtomicInteger();
                int providerCount = catalog.providerCount();
                catalog.findUpdates(
                        installed,
                        providerId -> Platform.runLater(() ->
                                setStatus(providerStatus(providerId, installed.size()))),
                        candidates -> Platform.runLater(() -> {
                            applyCandidates(rowByDevice, candidates);
                            int done = providersDone.incrementAndGet();
                            double progress = 0.2 + (0.8 * done / providerCount);
                            progressBar.setProgress(progress);
                            progressLabel.setText((int)(progress * 100) + "%");
                            
                            // Split rows into outdated and up-to-date
                            splitRows(rowByDevice);
                            
                            int outdated = outdatedRows.size();
                            if (done < providerCount) {
                                setStatus("Checked " + done + "/" + providerCount + " sources — "
                                        + outdated + " update(s) found so far…");
                            } else {
                                setStatus("Found " + outdated + " outdated driver(s) out of "
                                        + installed.size() + " device(s).");
                            }
                        })
                );
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    setStatus("Scan failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    scanButton.setDisable(false);
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

    private void splitRows(Map<String, DriverRow> rowByDevice) {
        outdatedRows.clear();
        upToDateRows.clear();
        for (DriverRow row : rowByDevice.values()) {
            if (row.hasUpdate()) {
                outdatedRows.add(row);
            } else {
                upToDateRows.add(row);
            }
        }
    }

    private void installUpdate(DriverRow row, TableCell<DriverRow, Void> cell) {
        if (!adminCheck.getAsBoolean()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Installing drivers requires administrator rights.");
            a.showAndWait();
            return;
        }
        DriverUpdateCandidate c = row.candidate();
        
        // Calculate file size display
        String sizeDisplay = "";
        if ("WindowsUpdate".equals(c.source())) {
            sizeDisplay = "~50 MB";
        } else {
            sizeDisplay = "Vendor site";
        }
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update driver for:\n" + row.installed().friendlyName()
                        + "\n\nCurrent: " + row.installed().driverVersion()
                        + "\nNew: " + c.availableVersion() + " (" + c.source() + ")"
                        + "\n\nA backup will be saved before installation.");
        if (confirm.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        
        currentInstallCell = cell;
        installService.resetCancellation();
        busy.set(true);
        statusLabel.setText("Installing update for " + row.installed().friendlyName() + "…");
        
        // Show download progress in the cell
        final TableCell<DriverRow, Void> finalCell = cell;
        final String finalSizeDisplay = sizeDisplay;
        Platform.runLater(() -> {
            if (finalCell != null) {
                showCellDownloadProgress(finalCell, finalSizeDisplay, 0.0);
            }
        });
        
        AppSettings settings = settingsStore.load();
        new Thread(() -> {
            try {
                // Simulate download progress
                for (int i = 0; i <= 100 && !installService.isCancelled(); i += 10) {
                    Thread.sleep(200);
                    final double progress = i / 100.0;
                    Platform.runLater(() -> {
                        if (currentInstallCell != null) {
                            showCellDownloadProgress(currentInstallCell, finalSizeDisplay, progress);
                        }
                    });
                }
                
                if (installService.isCancelled()) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Installation cancelled for " + row.installed().friendlyName());
                        if (currentInstallCell != null) {
                            hideCellDownloadProgress(currentInstallCell);
                        }
                        currentInstallCell = null;
                    });
                    return;
                }
                
                DriverInstallService.InstallResult result = installService.install(c, settings);
                Platform.runLater(() -> {
                    if (result.installed()) {
                        statusLabel.setText("Update installed for " + row.installed().friendlyName());
                        row.setCandidate(null);
                        splitRows(new java.util.HashMap<>());
                        if (result.rebootRequired()) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait();
                        }
                    } else {
                        new Alert(Alert.AlertType.INFORMATION, result.message()).showAndWait();
                    }
                    if (currentInstallCell != null) {
                        hideCellDownloadProgress(currentInstallCell);
                    }
                    currentInstallCell = null;
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, "Install failed:\n" + ex.getMessage()).showAndWait();
                    if (currentInstallCell != null) {
                        hideCellDownloadProgress(currentInstallCell);
                    }
                    currentInstallCell = null;
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "driver-install").start();
    }
    
    private void showCellDownloadProgress(TableCell<DriverRow, Void> cell, String size, double progress) {
        try {
            // Find the progress bar and label in the cell's graphic
            if (cell.getGraphic() instanceof HBox) {
                HBox container = (HBox) cell.getGraphic();
                for (var child : container.getChildren()) {
                    if (child instanceof ProgressBar) {
                        ((ProgressBar) child).setVisible(true);
                        ((ProgressBar) child).setProgress(progress);
                    } else if (child instanceof Label) {
                        ((Label) child).setText(size);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Stop")) {
                        ((Button) child).setVisible(true);
                        ((Button) child).setDisable(false);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Update")) {
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
                    } else if (child instanceof Button && ((Button) child).getText().equals("Stop")) {
                        ((Button) child).setVisible(false);
                        ((Button) child).setDisable(true);
                    } else if (child instanceof Button && ((Button) child).getText().equals("Update")) {
                        ((Button) child).setVisible(true);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }
}
