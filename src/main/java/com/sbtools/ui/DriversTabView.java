package com.sbtools.ui;

import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.DriverHealthService;
import com.sbtools.drivers.DriverInstallService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.UpdateHistoryStore;
import com.sbtools.drivers.model.DriverRow;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancellationToken;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
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
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DriversTabView extends BorderPane {

    private final DriverScanService scanService = new DriverScanService();
    private final DriverCatalogAggregator catalog = DriverCatalogAggregator.createDefault();
    private final DriverInstallService installService = new DriverInstallService();
    private final SettingsStore settingsStore = new SettingsStore();
    private final UpdateHistoryStore historyStore = new UpdateHistoryStore();
    // Scans are I/O-bound; one virtual thread per task scales nicely with provider fan-out.
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "driver-scan"); t.setDaemon(true); return t; });
    // Installs are admin-bound and effectively serial; a dedicated single-thread pool is enough.
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "driver-install"); t.setDaemon(true); return t; });
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<DriverRow> outdatedRows = FXCollections.observableArrayList();
    private final ObservableList<DriverRow> upToDateRows = FXCollections.observableArrayList();
    // Per-row install cell tracking — supports concurrent visual state per row
    // (current execution is still serialized by installExecutor, but the UI is decoupled).
    private final Map<DriverRow, DriverActionCell> installCells = new IdentityHashMap<>();
    private final Label statusLabel = new Label("Click Scan to check for outdated drivers.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label("0%");
    private final Button scanButton = new Button("Scan for outdated drivers");
    private final Button stopScanButton = new Button("Stop scan");
    private final TextField searchField = new TextField();
    private TableView<DriverRow> outdatedTable;
    private volatile CancellationToken scanToken;
    private volatile Future<?> scanFuture;

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);

        searchField.setPromptText("Search drivers...");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTables());

        scanButton.setOnAction(e -> startScan());
        stopScanButton.setOnAction(e -> stopScan());
        stopScanButton.setDisable(true);
        Button ignoredListButton = new Button("Ignored List");
        ignoredListButton.setOnAction(e -> showIgnoredListDialog());
        Button historyButton = new Button("History");
        historyButton.setOnAction(e -> showUpdateHistory());
        HBox top = new HBox(12, scanButton, stopScanButton, ignoredListButton, historyButton,
                searchField, progressBar, progressLabel, statusLabel);
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
        actionCol.setCellFactory(col -> new DriverActionCell());

        table.setPlaceholder(new Label("No outdated drivers — run a scan to check for updates."));
        table.getColumns().addAll(deviceCol, currentCol, availableCol, sourceCol, healthCol, actionCol);
        return table;
    }

    /**
     * Stateful action cell. Replaces the previous per-update rebuild of the {@code HBox}
     * and the brittle {@code instanceof}+text-based helpers. The cell controls visibility
     * directly via an explicit {@link State} enum, so callbacks from the install service
     * mutate this cell rather than poking children by label.
     */
    private final class DriverActionCell extends TableCell<DriverRow, Void> {
        enum State { IDLE, DOWNLOADING, INSTALLING }

        private final UIButton updateBtn = UIButton.small("Update");
        private final UIButton ignoreBtn = UIButton.small("Ignore");
        private final UIButton stopBtn = UIButton.small("Stop");
        private final ProgressBar downloadProgress = new ProgressBar(0);
        private final UILabel sizeLabel = new UILabel("");
        private final Label installingLabel = new Label("Installing driver. Please wait…");
        private final ProgressIndicator spinner = new ProgressIndicator();
        private final HBox container;
        private State state = State.IDLE;

        DriverActionCell() {
            spinner.setPrefSize(24, 24);
            spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            downloadProgress.setPrefWidth(80);
            container = new HBox(6, updateBtn, ignoreBtn, sizeLabel, downloadProgress, stopBtn, installingLabel, spinner);
            container.setAlignment(Pos.CENTER_LEFT);

            updateBtn.setOnAction(e -> {
                DriverRow row = currentRow();
                if (row != null && row.hasUpdate()) {
                    installUpdate(row, this);
                }
            });
            ignoreBtn.setOnAction(e -> {
                DriverRow row = currentRow();
                if (row != null) {
                    excludeDriver(row);
                    getTableView().getItems().remove(row);
                }
            });
            stopBtn.setOnAction(e -> {
                installService.cancel();
                stopBtn.setDisable(true);
            });

            applyVisibility();
        }

        private DriverRow currentRow() {
            int idx = getIndex();
            if (idx < 0 || idx >= getTableView().getItems().size()) {
                return null;
            }
            return getTableView().getItems().get(idx);
        }

        @Override
        protected void updateItem(Void item, boolean empty) {
            super.updateItem(item, empty);
            if (empty) {
                setGraphic(null);
                return;
            }
            DriverRow row = currentRow();
            updateBtn.setDisable(row == null || !row.hasUpdate() || busy.get());
            ignoreBtn.setDisable(busy.get());
            if (row != null && row.candidate() != null && row.candidate().description() != null) {
                updateBtn.setTooltip(new Tooltip(row.candidate().title()));
            }
            setGraphic(container);
        }

        void setDownloading(String size, double progress) {
            state = State.DOWNLOADING;
            sizeLabel.setText(size);
            downloadProgress.setProgress(progress);
            applyVisibility();
        }

        void setInstalling() {
            state = State.INSTALLING;
            applyVisibility();
        }

        void setIdle() {
            state = State.IDLE;
            sizeLabel.setText("");
            downloadProgress.setProgress(0);
            stopBtn.setDisable(true);
            applyVisibility();
        }

        private void applyVisibility() {
            boolean idle = state == State.IDLE;
            boolean downloading = state == State.DOWNLOADING;
            boolean installing = state == State.INSTALLING;
            updateBtn.setVisible(idle);
            updateBtn.setManaged(idle);
            ignoreBtn.setVisible(idle);
            ignoreBtn.setManaged(idle);
            downloadProgress.setVisible(downloading);
            downloadProgress.setManaged(downloading);
            sizeLabel.setVisible(downloading);
            sizeLabel.setManaged(downloading);
            stopBtn.setVisible(downloading);
            stopBtn.setManaged(downloading);
            installingLabel.setVisible(installing);
            installingLabel.setManaged(installing);
            spinner.setVisible(installing);
            spinner.setManaged(installing);
        }
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
                        excludeDriver(row);
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

        table.setPlaceholder(new Label("No up-to-date drivers detected yet — run a scan to populate this list."));
        table.getColumns().addAll(deviceCol, currentCol, healthCol);
        return table;
    }

    private void stopScan() {
        CancellationToken token = scanToken;
        if (token != null) {
            token.cancel();
        }
        if (scanFuture != null) {
            scanFuture.cancel(true);
            scanFuture = null;
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
        final CancellationToken token = new CancellationToken();
        scanToken = token;
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
        scanFuture = scanExecutor.submit(() -> {
            try {
                if (token.isCancelled()) return;
                List<InstalledDriver> installed = scanService.scanInstalled();
                if (token.isCancelled()) return;
                Map<String, DriverRow> rowByDevice = new HashMap<>();
                for (InstalledDriver d : installed) {
                    rowByDevice.put(d.deviceId(), new DriverRow(d));
                }
                Set<String> excludedIdSet = loadExcludedIdSet();
                Platform.runLater(() -> {
                    if (token.isCancelled()) return;
                    progressBar.setProgress(0.2);
                    progressLabel.setText("20%");
                    setStatus("Listed " + installed.size() + " device(s). Checking update sources…");
                    // Seed the up-to-date list immediately so users get feedback before any provider replies.
                    for (DriverRow row : rowByDevice.values()) {
                        if (!excludedIdSet.contains(row.installed().deviceId())) {
                            upToDateRows.add(row);
                        }
                    }
                });

                if (token.isCancelled()) return;
                AtomicInteger providersDone = new AtomicInteger();
                int providerCount = catalog.providerCount();
                catalog.findUpdates(
                        installed,
                        token,
                        providerId -> {
                            if (token.isCancelled()) return;
                            Platform.runLater(() -> {
                                if (!token.isCancelled()) {
                                    setStatus(providerStatus(providerId, installed.size()));
                                }
                            });
                        },
                        candidates -> {
                            if (token.isCancelled()) return;
                            Platform.runLater(() -> {
                                if (token.isCancelled()) return;
                                applyCandidates(rowByDevice, candidates);
                                int done = providersDone.incrementAndGet();
                                double progress = 0.2 + (0.8 * done / providerCount);
                                progressBar.setProgress(progress);
                                progressLabel.setText((int)(progress * 100) + "%");
                                reconcileRows(rowByDevice, excludedIdSet);
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
                if (!token.isCancelled()) {
                    Platform.runLater(() -> {
                        setStatus("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                scanFuture = null;
                scanToken = null;
                Platform.runLater(() -> {
                    busy.set(false);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    scanButton.setDisable(false);
                    stopScanButton.setDisable(true);
                });
            }
        });
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
        Map<String, DriverUpdateCandidate> candidateMap = new HashMap<>();
        for (DriverUpdateCandidate c : candidates) {
            candidateMap.put(c.installed().deviceId(), c);
        }
        for (Map.Entry<String, DriverRow> entry : rowByDevice.entrySet()) {
            DriverRow row = entry.getValue();
            DriverUpdateCandidate newCandidate = candidateMap.get(entry.getKey());
            DriverUpdateCandidate oldCandidate = row.candidate();
            if (newCandidate == null && oldCandidate != null) {
                row.setCandidate(null);
            } else if (newCandidate != null && (oldCandidate == null
                    || !newCandidate.availableVersion().equals(oldCandidate.availableVersion()))) {
                row.setCandidate(newCandidate);
            }
        }
    }

    /**
     * Builds an O(1)-lookup set of excluded device ids once per scan rather than doing the
     * previous per-row linear scan over the persisted list.
     */
    private Set<String> loadExcludedIdSet() {
        AppSettings settings = settingsStore.load();
        Set<String> ids = new HashSet<>();
        for (String e : settings.excludedDriverIds()) {
            int t = e.lastIndexOf('\t');
            ids.add(t >= 0 ? e.substring(t + 1) : e);
        }
        return ids;
    }

    /**
     * Incrementally moves rows between {@link #outdatedRows} and {@link #upToDateRows}
     * based on each row's current candidate. Unlike the old {@code splitRows} this does not
     * clear-and-refill, so selection and scroll positions are preserved across provider
     * callbacks during a scan.
     */
    private void reconcileRows(Map<String, DriverRow> rowByDevice, Set<String> excludedIds) {
        for (DriverRow row : rowByDevice.values()) {
            String deviceId = row.installed().deviceId();
            boolean excluded = excludedIds.contains(deviceId);
            if (excluded) {
                outdatedRows.remove(row);
                upToDateRows.remove(row);
                continue;
            }
            if (row.hasUpdate()) {
                if (upToDateRows.contains(row)) {
                    upToDateRows.remove(row);
                }
                if (!outdatedRows.contains(row)) {
                    outdatedRows.add(row);
                }
            } else {
                if (outdatedRows.contains(row)) {
                    outdatedRows.remove(row);
                }
                if (!upToDateRows.contains(row)) {
                    upToDateRows.add(row);
                }
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
                    settingsStore.save(current.withExcludedDriverIds(new ArrayList<>(excludedIds)));
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

    private void installUpdate(DriverRow row, DriverActionCell cell) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Installing drivers requires administrator rights.").showAndWait();
            return;
        }
        DriverUpdateCandidate c = row.candidate();

        boolean isWuInstall = "WindowsUpdate".equals(c.source())
                && c.packageId() != null && !c.packageId().isBlank();
        if (!isWuInstall && (c.downloadUrl() == null || c.downloadUrl().isBlank())) {
            showManualDownloadDialog(c);
            return;
        }

        installCells.put(row, cell);
        installService.resetCancellation();
        installService.setProgressCallback((bytesReceived, totalBytes, fraction) -> {
            String sizeText = totalBytes > 0
                    ? formatBytes(bytesReceived) + " / " + formatBytes(totalBytes)
                    : formatBytes(bytesReceived);
            Platform.runLater(() -> {
                DriverActionCell live = installCells.get(row);
                if (live != null) {
                    live.setDownloading(sizeText, fraction);
                }
            });
        });
        installService.setStatusCallback(status -> Platform.runLater(() -> {
            DriverActionCell live = installCells.get(row);
            if (live != null) {
                live.setInstalling();
            }
        }));
        busy.set(true);
        statusLabel.setText("Installing update for " + row.installed().friendlyName() + "...");
        cell.setDownloading("Downloading...", 0.0);

        AppSettings settings = settingsStore.load();
        installExecutor.submit(() -> {
            try {
                DriverInstallService.InstallResult result = installService.install(c, settings);
                Platform.runLater(() -> {
                    if (result.installed()) {
                        statusLabel.setText("Update installed for " + row.installed().friendlyName());
                        row.setCandidate(null);
                        outdatedRows.remove(row);
                        if (!upToDateRows.contains(row)) {
                            upToDateRows.add(row);
                        }
                        recordHistory(row, c, true);
                        if (result.rebootRequired()) {
                            new Alert(Alert.AlertType.INFORMATION,
                                    "Restart required to finish installation.").showAndWait();
                        }
                    } else {
                        recordHistory(row, c, false);
                        showErrorWithFallback(result.message(), c.vendorPageUrl());
                    }
                    DriverActionCell live = installCells.remove(row);
                    if (live != null) {
                        live.setIdle();
                    }
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    showErrorWithFallback("Install failed:\n" + ex.getMessage(), c.vendorPageUrl());
                    DriverActionCell live = installCells.remove(row);
                    if (live != null) {
                        live.setIdle();
                    }
                });
            } finally {
                installService.setProgressCallback(null);
                installService.setStatusCallback(null);
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void recordHistory(DriverRow row, DriverUpdateCandidate c, boolean success) {
        try {
            historyStore.recordUpdate(
                    row.installed().deviceId(),
                    row.installed().friendlyName(),
                    row.installed().driverVersion(),
                    c.availableVersion(),
                    c.source(),
                    success);
        } catch (Exception ex) {
            AppLogger.warning("Failed to record update history: " + ex.getMessage());
        }
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
    
    private void excludeDriver(DriverRow row) {
        AppSettings current = settingsStore.load();
        List<String> excluded = new ArrayList<>(current.excludedDriverIds());
        String stored = row.installed().friendlyName() + "\t" + row.installed().deviceId();
        if (excluded.stream().noneMatch(s -> s.endsWith("\t" + row.installed().deviceId()))) {
            excluded.add(stored);
        }
        try {
            settingsStore.save(current.withExcludedDriverIds(excluded));
        } catch (IOException ex) {
            AppLogger.warning("Failed to save excluded driver: " + ex.getMessage());
        }
    }

    private void filterTables() {
        String filter = searchField.getText().toLowerCase().trim();
        if (filter.isEmpty()) {
            outdatedTable.setItems(outdatedRows);
        } else {
            outdatedTable.setItems(new FilteredList<>(outdatedRows, row -> matchesFilter(row, filter)));
        }
    }

    private static boolean matchesFilter(DriverRow row, String filter) {
        String name = row.installed().friendlyName() == null ? "" : row.installed().friendlyName().toLowerCase();
        String version = row.installed().driverVersion() == null ? "" : row.installed().driverVersion().toLowerCase();
        String src = row.sourceProperty().get() == null ? "" : row.sourceProperty().get().toLowerCase();
        return name.contains(filter) || version.contains(filter) || src.contains(filter);
    }

    private void showUpdateHistory() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Update History");
        dialog.setHeaderText("Driver Update History");

        ListView<UpdateHistoryStore.UpdateEntry> listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(UpdateHistoryStore.UpdateEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    String status = item.success() ? "✓" : "✗";
                    setText(status + " " + item.deviceName() + " " + item.oldVersion()
                            + " → " + item.newVersion() + " (" + item.source() + ")");
                }
            }
        });

        try {
            listView.setItems(FXCollections.observableArrayList(historyStore.listAll()));
        } catch (Exception e) {
            AppLogger.warning("Failed to load update history: " + e.getMessage());
        }
        listView.setPrefHeight(400);
        listView.setPrefWidth(600);

        VBox layout = new VBox(10, new Label("Recent updates:"), listView);
        layout.setPadding(new Insets(10));
        layout.setPrefWidth(620);

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
