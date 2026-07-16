package com.sbtools.ui;

import com.sbtools.backup.DriverBackupService;
import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.DriverHealthService;
import com.sbtools.drivers.DriverInstallService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.UpdateHistoryStore;
import com.sbtools.drivers.model.DriverRow;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.drivers.model.UpdateSeverity;
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
import javafx.scene.control.CheckBox;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.Dialog;
import javafx.scene.control.Hyperlink;
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
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
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
    private final DriverBackupService backupService = new DriverBackupService();
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
    private final Button scanButton = new Button("Scan");
    private final Button forceScanButton = new Button("Force Scan");
    private final Button stopScanButton = new Button("Stop");
    private final Button updateAllButton = new Button("Update All");
    private final Button updateSelectedButton = new Button("Update Selected");
    private final Button backupButton = new Button("Backup");
    private final Button stopBackupButton = new Button("Stop Backup");
    private final TextField searchField = new TextField();
    private TableView<DriverRow> outdatedTable;
    private TableView<DriverRow> upToDateTable;
    private javafx.collections.ListChangeListener<DriverRow> selectedListener;
    private volatile CancellationToken scanToken;
    private volatile Future<?> scanFuture;
    private volatile CancellationToken backupToken;

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopBackupButton.setVisible(false);
        stopBackupButton.setManaged(false);
        stopBackupButton.setDisable(true);

        searchField.setPromptText("Search...");
        searchField.setPrefWidth(160);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTables());

        scanButton.setOnAction(e -> startScan());
        stopScanButton.setOnAction(e -> stopScan());
        stopScanButton.setDisable(true);
        forceScanButton.setOnAction(e -> startForceScan());
        forceScanButton.setTooltip(new Tooltip("Clear cache and scan for outdated drivers"));

        updateAllButton.setDisable(true);
        updateAllButton.setOnAction(e -> startBatchUpdate());
        updateSelectedButton.setDisable(true);
        updateSelectedButton.setOnAction(e -> startBatchUpdateSelected());
        backupButton.setOnAction(e -> startBackupAll());
        stopBackupButton.setOnAction(e -> stopBackup());

        Button ignoredListButton = new Button("Ignored");
        ignoredListButton.setOnAction(e -> showIgnoredListDialog());
        Button historyButton = new Button("History");
        historyButton.setOnAction(e -> showUpdateHistory());
        Button detailsButton = new Button("Details");
        detailsButton.setOnAction(e -> {
            DriverRow row = getSelectedRow();
            if (row != null) {
                showDriverDetails(row);
            } else {
                new Alert(Alert.AlertType.INFORMATION, "Select a driver row first.").showAndWait();
            }
        });
        scanButton.setTooltip(new Tooltip("Scan for outdated drivers"));
        stopScanButton.setTooltip(new Tooltip("Stop the current scan"));
        updateAllButton.setTooltip(new Tooltip("Install all available driver updates"));
        updateSelectedButton.setTooltip(new Tooltip("Install updates for checked drivers only"));
        backupButton.setTooltip(new Tooltip("Back up all installed drivers"));
        stopBackupButton.setTooltip(new Tooltip("Cancel the backup operation"));
        ignoredListButton.setTooltip(new Tooltip("Manage ignored/excluded drivers"));
        historyButton.setTooltip(new Tooltip("View past driver update history"));
        detailsButton.setTooltip(new Tooltip("View details of the selected driver"));

        HBox row1 = new HBox(8, scanButton, forceScanButton, stopScanButton, updateAllButton, updateSelectedButton,
                backupButton, stopBackupButton, ignoredListButton, historyButton, detailsButton);
        row1.setAlignment(Pos.CENTER_LEFT);
        row1.setPadding(new Insets(8, 16, 0, 16));
        row1.getStyleClass().add("toolbar");

        HBox row2 = new HBox(8, searchField, progressBar, progressLabel, statusLabel);
        row2.setAlignment(Pos.CENTER_LEFT);
        row2.setPadding(new Insets(0, 16, 8, 16));
        row2.getStyleClass().add("toolbar");

        VBox top = new VBox(0, row1, row2);
        setTop(top);

        VBox tablesContainer = buildTablesContainer();
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

    private DriverRow getSelectedRow() {
        if (outdatedTable != null) {
            DriverRow row = outdatedTable.getSelectionModel().getSelectedItem();
            if (row != null) return row;
        }
        if (upToDateTable != null) {
            DriverRow row = upToDateTable.getSelectionModel().getSelectedItem();
            if (row != null) return row;
        }
        return null;
    }

    private void updateButtonStates() {
        boolean hasOutdated = !outdatedRows.isEmpty();
        boolean hasSelected = outdatedRows.stream().anyMatch(DriverRow::isSelected);
        updateAllButton.setDisable(!hasOutdated || busy.get());
        updateSelectedButton.setDisable(!hasSelected || busy.get());
    }

    private VBox buildTablesContainer() {
        UILabel outdatedLabel = UILabel.sectionTitle("Outdated Drivers");
        UILabel upToDateLabel = UILabel.sectionTitle("Up to Date Drivers");
        
        outdatedTable = buildTable(filteredOutdated);
        upToDateTable = buildUpToDateTable(filteredUpToDate);
        
        VBox.setVgrow(outdatedTable, Priority.ALWAYS);
        VBox.setVgrow(upToDateTable, Priority.ALWAYS);
        
        VBox container = new VBox(8, outdatedLabel, outdatedTable, upToDateLabel, upToDateTable);
        container.setPadding(new Insets(12, 16, 12, 16));
        return container;
    }

    private TableView<DriverRow> buildTable(ObservableList<DriverRow> items) {
        TableView<DriverRow> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriverRow, Boolean> selectCol = new TableColumn<>("Select");
        selectCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selectCol.setCellFactory(col -> new CheckBoxTableCell<DriverRow, Boolean>() {
            private final CheckBox cb = new CheckBox();
            {
                cb.selectedProperty().addListener((obs, old, val) -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        getTableView().getItems().get(idx).setSelected(val);
                    }
                    updateButtonStates();
                });
            }

            @Override
            public void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    cb.setSelected(item);
                    setGraphic(cb);
                }
            }
        });
        selectCol.setPrefWidth(50);
        selectCol.setEditable(true);
        selectCol.setSortable(false);

        TableColumn<DriverRow, String> deviceCol = new TableColumn<>("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());
        deviceCol.setPrefWidth(220);

        TableColumn<DriverRow, String> currentCol = new TableColumn<>("Current");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(100);

        TableColumn<DriverRow, String> availableCol = new TableColumn<>("Available");
        availableCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availableCol.setPrefWidth(100);

        TableColumn<DriverRow, UpdateSeverity> severityCol = new TableColumn<>("Severity");
        severityCol.setCellValueFactory(c -> c.getValue().severityProperty());
        severityCol.setPrefWidth(100);
        severityCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(UpdateSeverity item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                    return;
                }
                DriverRow row = getTableRow() != null ? getTableRow().getItem() : null;
                if (row != null && row.isProblematic()) {
                    Label badge = new Label("ISSUE");
                    badge.setStyle("-fx-background-color: #ff5555; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-weight: bold;");
                    badge.setTooltip(new Tooltip("Device status: " + row.installed().status()));
                    setGraphic(badge);
                } else if (item != null) {
                    Label badge = new Label(item.name());
                    badge.setStyle(switch (item) {
                        case CRITICAL -> "-fx-background-color: #ff5555; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-weight: bold;";
                        case IMPORTANT -> "-fx-background-color: #ffb86c; -fx-text-fill: #282a36; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-weight: bold;";
                        case RECOMMENDED -> "-fx-background-color: #f1fa8c; -fx-text-fill: #282a36; -fx-padding: 2 6; -fx-background-radius: 4;";
                        case OPTIONAL -> "-fx-background-color: #6272a4; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4;";
                        case UNKNOWN -> "-fx-background-color: #44475a; -fx-text-fill: #ccc; -fx-padding: 2 6; -fx-background-radius: 4;";
                    });
                    setGraphic(badge);
                } else {
                    setGraphic(null);
                }
            }
        });

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
        actionCol.setPrefWidth(280);
        actionCol.setCellFactory(col -> new DriverActionCell());

        table.setPlaceholder(new Label("No outdated drivers \u2014 run a scan to check for updates."));
        table.getColumns().addAll(selectCol, deviceCol, currentCol, availableCol, severityCol, sourceCol, healthCol, actionCol);
        table.setEditable(true);
        selectedListener = (javafx.collections.ListChangeListener<DriverRow>) c -> updateButtonStates();
        items.addListener(selectedListener);
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
            if (getTableView() == null) return null;
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
            if (row == null) {
                setGraphic(null);
                return;
            }
            updateBtn.setDisable(!row.hasUpdate() || busy.get());
            ignoreBtn.setDisable(busy.get());
            if (row.candidate() != null) {
                String tooltipText = row.installed().friendlyName();
                if (row.candidate().availableVersion() != null) {
                    tooltipText += " \u2192 " + row.candidate().availableVersion();
                }
                updateBtn.setTooltip(new Tooltip(tooltipText));
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
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        DriverRow row = getTableView().getItems().get(idx);
                        if (row != null) {
                            excludeDriver(row);
                            getTableView().getItems().remove(row);
                        }
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

        table.setPlaceholder(new Label("No up-to-date drivers detected yet \u2014 run a scan to populate this list."));
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
        updateButtonStates();
        setStatus("Scan stopped.");
    }

    private void startScan() {
        startScanInternal(false);
    }

    private void startForceScan() {
        startScanInternal(true);
    }

    private void startScanInternal(boolean forceRefresh) {
        if (busy.get()) {
            return;
        }
        if (forceRefresh) {
            catalog.clearCache();
        }
        final CancellationToken token = new CancellationToken();
        scanToken = token;
        busy.set(true);
        setStatus("Enumerating installed drivers…");
        scanButton.setDisable(true);
        forceScanButton.setDisable(true);
        stopScanButton.setDisable(false);
        Set<String> previouslySelected = new HashSet<>();
        for (DriverRow row : outdatedRows) {
            if (row.isSelected()) {
                previouslySelected.add(row.installed().deviceId());
            }
        }
        for (DriverRow row : upToDateRows) {
            if (row.isSelected()) {
                previouslySelected.add(row.installed().deviceId());
            }
        }
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
                            if (previouslySelected.contains(row.installed().deviceId())) {
                                row.setSelected(true);
                            }
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
                    forceScanButton.setDisable(false);
                    stopScanButton.setDisable(true);
                    updateButtonStates();
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
        for (Map.Entry<String, DriverUpdateCandidate> entry : candidateMap.entrySet()) {
            DriverRow row = rowByDevice.get(entry.getKey());
            if (row == null) continue;
            DriverUpdateCandidate newCandidate = entry.getValue();
            DriverUpdateCandidate oldCandidate = row.candidate();
            if (oldCandidate == null || !newCandidate.availableVersion().equals(oldCandidate.availableVersion())) {
                row.setCandidate(newCandidate);
            }
        }
        for (Map.Entry<String, DriverRow> rowEntry : rowByDevice.entrySet()) {
            if (!candidateMap.containsKey(rowEntry.getKey())) {
                DriverRow row = rowEntry.getValue();
                if (row.candidate() != null) {
                    row.setCandidate(null);
                }
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
            if (t < 0) t = e.lastIndexOf('\u001F');
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
        java.util.Set<DriverRow> outdatedSet = new java.util.HashSet<>(outdatedRows);
        java.util.Set<DriverRow> upToDateSet = new java.util.HashSet<>(upToDateRows);
        java.util.List<DriverRow> toAddOutdated = new java.util.ArrayList<>();
        java.util.List<DriverRow> toRemoveOutdated = new java.util.ArrayList<>();
        java.util.List<DriverRow> toAddUpToDate = new java.util.ArrayList<>();
        java.util.List<DriverRow> toRemoveUpToDate = new java.util.ArrayList<>();

        for (DriverRow row : rowByDevice.values()) {
            String deviceId = row.installed().deviceId();
            boolean excluded = excludedIds.contains(deviceId);
            if (excluded) {
                if (outdatedSet.contains(row)) toRemoveOutdated.add(row);
                if (upToDateSet.contains(row)) toRemoveUpToDate.add(row);
                continue;
            }
            if (row.hasUpdate()) {
                if (upToDateSet.contains(row)) toRemoveUpToDate.add(row);
                if (!outdatedSet.contains(row)) toAddOutdated.add(row);
            } else {
                if (outdatedSet.contains(row)) toRemoveOutdated.add(row);
                if (!upToDateSet.contains(row)) toAddUpToDate.add(row);
            }
        }

        outdatedRows.removeAll(toRemoveOutdated);
        outdatedRows.addAll(toAddOutdated);
        upToDateRows.removeAll(toRemoveUpToDate);
        upToDateRows.addAll(toAddUpToDate);
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
                    if (t < 0) t = item.lastIndexOf('\u001F');
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
        if (cell != null) {
            cell.setDownloading("Downloading...", 0.0);
        }

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
        String deviceId = row.installed().deviceId();
        boolean alreadyExcluded = excluded.stream().anyMatch(s -> {
            int t = s.lastIndexOf('\t');
            if (t < 0) t = s.lastIndexOf('\u001F');
            return t >= 0 && s.substring(t + 1).equals(deviceId);
        });
        if (!alreadyExcluded) {
            String stored = row.installed().friendlyName() + "\u001F" + deviceId;
            excluded.add(stored);
        }
        try {
            settingsStore.save(current.withExcludedDriverIds(excluded));
        } catch (IOException ex) {
            AppLogger.warning("Failed to save excluded driver: " + ex.getMessage());
        }
    }

    private final FilteredList<DriverRow> filteredOutdated = new FilteredList<>(outdatedRows);
    private final FilteredList<DriverRow> filteredUpToDate = new FilteredList<>(upToDateRows);

    private void filterTables() {
        String filter = searchField.getText().toLowerCase().trim();
        if (filter.isEmpty()) {
            filteredOutdated.setPredicate(null);
            filteredUpToDate.setPredicate(null);
        } else {
            filteredOutdated.setPredicate(row -> matchesFilter(row, filter));
            filteredUpToDate.setPredicate(row -> matchesFilter(row, filter));
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

    private void showDriverDetails(DriverRow row) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Driver Details");
        dialog.setHeaderText(row.installed().friendlyName());

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        int r = 0;
        addDetailRow(grid, r++, "Device ID:", row.installed().deviceId());
        addDetailRow(grid, r++, "Hardware IDs:", row.installed().hardwareIds());
        addDetailRow(grid, r++, "Provider:", row.installed().provider());
        addDetailRow(grid, r++, "INF Name:", row.installed().infName());
        addDetailRow(grid, r++, "Driver Key:", row.installed().driverKey());
        addDetailRow(grid, r++, "Status:", row.installed().status());
        if (row.installed().releaseDate() != null) {
            addDetailRow(grid, r++, "Release Date:", row.installed().releaseDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        addDetailRow(grid, r++, "Current Version:", row.installed().driverVersion());

        if (row.hasUpdate()) {
            DriverUpdateCandidate c = row.candidate();
            addDetailRow(grid, r++, "Available Version:", c.availableVersion());
            addDetailRow(grid, r++, "Source:", c.source());
            addDetailRow(grid, r++, "Severity:", c.severity() != null ? c.severity().name() : "Unknown");

            if (c.title() != null && !c.title().isBlank()) {
                addDetailRow(grid, r++, "Title:", c.title());
            }
            if (c.description() != null && !c.description().isBlank()) {
                Label descLabel = new Label(c.description());
                descLabel.setWrapText(true);
                descLabel.setMaxWidth(400);
                grid.add(new Label("Description:"), 0, r);
                grid.add(descLabel, 1, r);
                r++;
            }
            if (c.downloadUrl() != null && !c.downloadUrl().isBlank()) {
                grid.add(new Label("Download:"), 0, r);
                Hyperlink dlLink = new Hyperlink(c.downloadUrl());
                dlLink.setOnAction(e -> {
                    try { java.awt.Desktop.getDesktop().browse(new java.net.URI(c.downloadUrl())); }
                    catch (Exception ex) { AppLogger.warning("Failed to open browser: " + ex.getMessage()); }
                });
                grid.add(dlLink, 1, r);
                r++;
            }
            if (c.vendorPageUrl() != null && !c.vendorPageUrl().isBlank()) {
                grid.add(new Label("Vendor Page:"), 0, r);
                Hyperlink vpLink = new Hyperlink(c.vendorPageUrl());
                vpLink.setOnAction(e -> {
                    try { java.awt.Desktop.getDesktop().browse(new java.net.URI(c.vendorPageUrl())); }
                    catch (Exception ex) { AppLogger.warning("Failed to open browser: " + ex.getMessage()); }
                });
                grid.add(vpLink, 1, r);
                r++;
            }
        }

        DriverHealthService.DriverHealthScore hs = row.getHealthScore();
        if (hs != null) {
            Label scoreLabel = new Label(hs.score() + "/100 (" + hs.getLabel() + ")");
            scoreLabel.setStyle(hs.getColorStyle());
            grid.add(new Label("Health Score:"), 0, r);
            grid.add(scoreLabel, 1, r);
            r++;
            if (hs.details() != null && !hs.details().isBlank()) {
                Label detailsLabel = new Label(hs.details());
                detailsLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
                grid.add(new Label("Breakdown:"), 0, r);
                grid.add(detailsLabel, 1, r);
                r++;
            }
        }

        try {
            List<UpdateHistoryStore.UpdateEntry> history = historyStore.listAll();
            List<UpdateHistoryStore.UpdateEntry> deviceHistory = history.stream()
                    .filter(h -> h.deviceId().equals(row.installed().deviceId()))
                    .toList();
            if (!deviceHistory.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (UpdateHistoryStore.UpdateEntry h : deviceHistory) {
                    String icon = h.success() ? "\u2713" : "\u2717";
                    sb.append(icon).append(" ").append(h.oldVersion()).append(" \u2192 ").append(h.newVersion())
                            .append(" (").append(h.source()).append(")\n");
                }
                Label histLabel = new Label(sb.toString().trim());
                histLabel.setStyle("-fx-font-family: monospace; -fx-font-size: 11;");
                grid.add(new Label("Update History:"), 0, r);
                grid.add(histLabel, 1, r);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to load update history: " + e.getMessage());
        }

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private static void addDetailRow(GridPane grid, int row, String label, String value) {
        grid.add(new Label(label), 0, row);
        Label val = new Label(value != null ? value : "\u2014");
        val.setWrapText(true);
        val.setMaxWidth(400);
        grid.add(val, 1, row);
    }

    private void startBatchUpdate() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing drivers requires administrator rights.").showAndWait();
            return;
        }
        List<DriverRow> snapshot = new ArrayList<>(outdatedRows);
        int count = snapshot.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update all " + count + " outdated driver(s)? This may take several minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Batch Update");
        confirm.setHeaderText("Update All Drivers");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        installBatchUpdates(snapshot);
    }

    private void startBatchUpdateSelected() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing drivers requires administrator rights.").showAndWait();
            return;
        }
        List<DriverRow> selected = outdatedRows.stream().filter(DriverRow::isSelected).toList();
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No drivers selected.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update " + selected.size() + " selected driver(s)? This may take several minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Batch Update");
        confirm.setHeaderText("Update Selected Drivers");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        installBatchUpdates(selected);
    }

    private void installBatchUpdates(List<DriverRow> rows) {
        busy.set(true);
        scanButton.setDisable(true);
        updateAllButton.setDisable(true);
        updateSelectedButton.setDisable(true);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);
        progressLabel.setText("0%");

        installExecutor.submit(() -> {
            int succeeded = 0;
            int failed = 0;
            int skipped = 0;
            int total = rows.size();
            AppSettings settings = settingsStore.load();
            for (int i = 0; i < total; i++) {
                DriverRow row = rows.get(i);
                if (row.candidate() == null) {
                    skipped++;
                    continue;
                }
                DriverUpdateCandidate c = row.candidate();

                boolean isWuInstall = "WindowsUpdate".equals(c.source())
                        && c.packageId() != null && !c.packageId().isBlank();
                if (!isWuInstall && (c.downloadUrl() == null || c.downloadUrl().isBlank())) {
                    skipped++;
                    continue;
                }

                final int idx = i;
                Platform.runLater(() -> {
                    statusLabel.setText("Installing " + (idx + 1) + "/" + total + ": "
                            + row.installed().friendlyName() + "\u2026");
                    double p = (double) idx / total;
                    progressBar.setProgress(p);
                    progressLabel.setText((int)(p * 100) + "%");
                });

                try {
                    installService.resetCancellation();
                    installService.setProgressCallback((bytesReceived, totalBytes, fraction) -> {
                        String sizeText = totalBytes > 0
                                ? formatBytes(bytesReceived) + " / " + formatBytes(totalBytes)
                                : formatBytes(bytesReceived);
                        Platform.runLater(() -> statusLabel.setText("Installing " + (idx + 1) + "/" + total
                                + ": " + row.installed().friendlyName() + " \u2014 " + sizeText));
                    });
                    installService.setStatusCallback(status -> Platform.runLater(() ->
                            statusLabel.setText("Installing " + (idx + 1) + "/" + total + ": "
                                    + row.installed().friendlyName() + " \u2014 " + status)));
                    DriverInstallService.InstallResult result = installService.install(c, settings);
                    if (result.installed()) {
                        succeeded++;
                        Platform.runLater(() -> {
                            row.setCandidate(null);
                            row.setSelected(false);
                            outdatedRows.remove(row);
                            if (!upToDateRows.contains(row)) {
                                upToDateRows.add(row);
                            }
                        });
                        recordHistory(row, c, true);
                    } else {
                        failed++;
                        recordHistory(row, c, false);
                    }
                } catch (Exception ex) {
                    failed++;
                    AppLogger.warning("Batch install failed for " + row.installed().friendlyName() + ": " + ex.getMessage());
                }
            }
            final int s = succeeded;
            final int f = failed;
            final int k = skipped;
            Platform.runLater(() -> {
                busy.set(false);
                scanButton.setDisable(false);
                updateAllButton.setDisable(outdatedRows.isEmpty());
                updateSelectedButton.setDisable(true);
                progressBar.setVisible(false);
                progressLabel.setVisible(false);
                String summary = "Batch update complete: " + s + " succeeded, " + f + " failed";
                if (k > 0) {
                    summary += ", " + k + " skipped (manual download required)";
                }
                statusLabel.setText(summary + ".");
                StringBuilder msg = new StringBuilder();
                msg.append("Batch update complete.\n\n");
                msg.append(s).append(" driver(s) updated successfully.\n");
                msg.append(f).append(" driver(s) failed.\n");
                if (k > 0) {
                    msg.append(k).append(" driver(s) skipped (no automatic download available).");
                }
                new Alert(Alert.AlertType.INFORMATION, msg.toString()).showAndWait();
                filterTables();
            });
        });
    }

    private void startBackupAll() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Driver backup requires administrator rights.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Back up all currently installed drivers? This may take a few minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Driver Backup");
        confirm.setHeaderText("Backup All Drivers");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        final CancellationToken token = new CancellationToken();
        backupToken = token;
        busy.set(true);
        scanButton.setDisable(true);
        backupButton.setDisable(true);
        stopBackupButton.setVisible(true);
        stopBackupButton.setManaged(true);
        stopBackupButton.setDisable(false);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        setStatus("Scanning installed drivers for backup\u2026");

        installExecutor.submit(() -> {
            int succeeded = 0;
            int failed = 0;
            int skipped = 0;
            try {
                List<InstalledDriver> installed = scanService.scanInstalled();
                int total = installed.size();
                AppSettings settings = settingsStore.load();
                for (int i = 0; i < total; i++) {
                    if (token.isCancelled()) {
                        skipped = total - i;
                        break;
                    }
                    InstalledDriver driver = installed.get(i);
                    final int idx = i;
                    Platform.runLater(() -> {
                        if (!token.isCancelled()) {
                            statusLabel.setText("Backing up " + (idx + 1) + "/" + total + ": "
                                    + driver.friendlyName() + "\u2026");
                            double p = (double) idx / total;
                            progressBar.setProgress(p);
                            progressLabel.setText((int)(p * 100) + "%");
                        }
                    });
                    try {
                        backupService.backupBeforeUpdate(driver, settings);
                        succeeded++;
                    } catch (Exception ex) {
                        failed++;
                        AppLogger.warning("Backup failed for " + driver.friendlyName() + ": " + ex.getMessage());
                    }
                }
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    busy.set(false);
                    scanButton.setDisable(false);
                    backupButton.setDisable(false);
                    stopBackupButton.setVisible(false);
                    stopBackupButton.setManaged(false);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    setStatus("Backup failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Backup failed:\n" + ex.getMessage()).showAndWait();
                });
                return;
            }
            final int s = succeeded;
            final int f = failed;
            final int k = skipped;
            Platform.runLater(() -> {
                busy.set(false);
                scanButton.setDisable(false);
                backupButton.setDisable(false);
                stopBackupButton.setVisible(false);
                stopBackupButton.setManaged(false);
                progressBar.setVisible(false);
                progressLabel.setVisible(false);
                String summary = "Backup complete: " + s + " backed up, " + f + " failed";
                if (k > 0) {
                    summary += ", " + k + " skipped";
                }
                statusLabel.setText(summary + ".");
                StringBuilder msg = new StringBuilder();
                msg.append("Driver backup complete.\n\n");
                msg.append(s).append(" driver(s) backed up successfully.\n");
                msg.append(f).append(" driver(s) failed.\n");
                if (k > 0) {
                    msg.append(k).append(" driver(s) skipped (operation was cancelled).");
                }
                new Alert(Alert.AlertType.INFORMATION, msg.toString()).showAndWait();
            });
        });
    }

    private void stopBackup() {
        CancellationToken token = backupToken;
        if (token != null) {
            token.cancel();
        }
        stopBackupButton.setDisable(true);
        setStatus("Cancelling backup\u2026");
    }

    /**
     * Shuts down background executor services. Call when the tab is removed
     * or the application is shutting down to avoid leaked threads.
     */
    public void dispose() {
        CancellationToken scan = scanToken;
        if (scan != null) scan.cancel();
        CancellationToken backup = backupToken;
        if (backup != null) backup.cancel();
        installService.cancel();
        shutdownExecutor(scanExecutor);
        shutdownExecutor(installExecutor);
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
