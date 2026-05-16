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

    private final ObservableList<DriverRow> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Click Scan to check for outdated drivers.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button scanButton = new Button("Scan for outdated drivers");

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        scanButton.setOnAction(e -> startScan());
        HBox top = new HBox(12, scanButton, progress, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        TableView<DriverRow> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(top);
        setCenter(table);
        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    private TableView<DriverRow> buildTable() {
        TableView<DriverRow> table = new TableView<>(rows);
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
        actionCol.setPrefWidth(90);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button updateBtn = new Button("Update");

            {
                updateBtn.setOnAction(e -> {
                    DriverRow row = getTableView().getItems().get(getIndex());
                    if (row != null && row.hasUpdate()) {
                        installUpdate(row);
                    }
                });
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
                    setGraphic(updateBtn);
                }
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
        progress.setVisible(true);
        setStatus("Enumerating installed drivers…");
        scanButton.setDisable(true);
        rows.clear();
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
                    rows.setAll(initialRows);
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
                            int outdated = countOutdated(rows);
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
                    progress.setVisible(false);
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

    private static int countOutdated(ObservableList<DriverRow> rows) {
        int n = 0;
        for (DriverRow row : rows) {
            if (row.hasUpdate()) {
                n++;
            }
        }
        return n;
    }

    private void installUpdate(DriverRow row) {
        if (!adminCheck.getAsBoolean()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Installing drivers requires administrator rights. Click Elevate in the title bar, then try again.");
            a.showAndWait();
            return;
        }
        DriverUpdateCandidate c = row.candidate();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update driver for:\n" + row.installed().friendlyName()
                        + "\n\nCurrent: " + row.installed().driverVersion()
                        + "\nNew: " + c.availableVersion() + " (" + c.source() + ")"
                        + "\n\nA backup will be saved before installation.");
        if (confirm.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        busy.set(true);
        statusLabel.setText("Installing update for " + row.installed().friendlyName() + "…");
        AppSettings settings = settingsStore.load();
        new Thread(() -> {
            try {
                DriverInstallService.InstallResult result = installService.install(c, settings);
                Platform.runLater(() -> {
                    if (result.installed()) {
                        statusLabel.setText("Update installed for " + row.installed().friendlyName());
                        row.setCandidate(null);
                        if (result.rebootRequired()) {
                            new Alert(Alert.AlertType.INFORMATION, "Restart required to finish installation.").showAndWait();
                        }
                    } else {
                        new Alert(Alert.AlertType.INFORMATION, result.message()).showAndWait();
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "driver-install").start();
    }
}
