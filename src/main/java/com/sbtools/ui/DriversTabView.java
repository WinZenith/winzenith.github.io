package com.sbtools.ui;

import com.sbtools.backup.DriverBackupService;
import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.catalog.DriverCatalogDatabase;
import com.sbtools.drivers.DriverHealthService;
import com.sbtools.drivers.DriverInstallFailure;
import com.sbtools.drivers.DriverInstallService;
import com.sbtools.drivers.DriverPreflightService;
import com.sbtools.drivers.DriverPostInstallVerifier;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.DriverVersionVerifier;
import com.sbtools.drivers.RebootPendingStore;
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
    private final RebootPendingStore rebootStore = new RebootPendingStore();
    // Scans are I/O-bound; one virtual thread per task scales nicely with provider fan-out.
    private final ExecutorService scanExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "driver-scan"); t.setDaemon(true); return t; });
    // Installs are admin-bound and effectively serial; a dedicated single-thread pool is enough.
    private final ExecutorService installExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "driver-install"); t.setDaemon(true); return t; });
    // Backups run pnputil exports that can take minutes: isolate from installs
    // so a backup never queues/block installs (and vice versa).
    private final ExecutorService backupExecutor = Executors.newSingleThreadExecutor(
            r -> { Thread t = new Thread(r, "driver-backup"); t.setDaemon(true); return t; });
    private final java.util.concurrent.atomic.AtomicBoolean installCancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean backupCancelFlag = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final DriversOperationGate operationGate = new DriversOperationGate();
    private volatile DriversOperationGate.Lease scanLease;
    private volatile DriversOperationGate.Lease installLease;
    private volatile DriversOperationGate.Lease backupLease;
    private volatile DriverRow lastSelectedRow;
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<DriverRow> outdatedRows = FXCollections.observableArrayList(
            row -> new javafx.beans.Observable[]{row.selectedProperty()});
    private final ObservableList<DriverRow> upToDateRows = FXCollections.observableArrayList(
            row -> new javafx.beans.Observable[]{row.selectedProperty()});
    // Per-row install cell tracking — supports concurrent visual state per row
    // (current execution is still serialized by installExecutor, but the UI is decoupled).
    // Uses ConcurrentHashMap for thread-safe access from FX thread and installExecutor callbacks.
    // Identity semantics preserved via IdentityHashMap wrapper is unnecessary; ConcurrentHashMap
    // keyed by deviceId via row identity is safe because DriverRow instances are unique per scan.
    private final Map<DriverRow, DriverActionCell> installCells = new java.util.concurrent.ConcurrentHashMap<>();
    // Retain identity semantics fallback via wrapper if needed – use synchronized view instead:
    // (ConcurrentHashMap does not support null keys, which we never store)
    private final Label statusLabel = new Label("Click Scan to check for outdated drivers.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label progressLabel = new Label("0%");
    private final Button scanButton = new Button("Scan");
    private final Button stopScanButton = new Button("Stop");
    private final Button updateAllButton = new Button("Update All");
    private final Button updateSelectedButton = new Button("Update Selected");
    private final Button backupButton = new Button("Backup");
    private final Button stopBackupButton = new Button("Stop Backup");
    private final Button stopInstallButton = new Button("Stop Install");
    private final TextField searchField = new TextField();
    private TableView<DriverRow> outdatedTable;
    private TableView<DriverRow> upToDateTable;
    private javafx.collections.ListChangeListener<DriverRow> selectedListener;
    private volatile CancellationToken scanToken;
    private volatile Future<?> scanFuture;
    private volatile Future<?> installFuture;
    private volatile Future<?> backupFuture;
    private volatile CancellationToken backupToken;
    // Post-install verifier daemons (see single-install success path): a set,
    // because a second install may start while an earlier 90s rescan is still
    // running — a single field would orphan the first thread past dispose().
    private volatile boolean disposed;
    // Lazily-loaded catalog DB for the Details dialog (+ stamp): avoids a
    // full disk+parse load() on the FX thread for every dialog open.
    private volatile DriverCatalogDatabase detailsDb;
    private volatile long detailsDbStamp = Long.MIN_VALUE;

    /**
     * Tab-level cached catalog database, reloaded only when a refreshed
     * catalog file changed (mtime stamp). Bundled content is immutable.
     */
    private DriverCatalogDatabase detailsCatalogDatabase() {
        long stamp = 0;
        try {
            for (java.nio.file.Path p : new java.nio.file.Path[]{
                    detailsRefreshedPath(true), detailsRefreshedPath(false)}) {
                if (p != null) {
                    try {
                        if (java.nio.file.Files.exists(p)) {
                            stamp = Math.max(stamp, java.nio.file.Files.getLastModifiedTime(p).toMillis());
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        DriverCatalogDatabase cached = detailsDb;
        if (cached == null || detailsDbStamp != stamp) {
            cached = DriverCatalogDatabase.load();
            detailsDb = cached;
            detailsDbStamp = stamp;
        }
        return cached;
    }

    private static java.nio.file.Path detailsRefreshedPath(boolean portable) {
        try {
            if (portable) {
                java.nio.file.Path base = com.sbtools.util.AppPaths.portableBaseDir();
                if (base != null) return base.resolve("catalog").resolve("driver-catalog.json");
            } else {
                return com.sbtools.util.AppPaths.localAppData().resolve("catalog").resolve("driver-catalog.json");
            }
        } catch (Exception ignored) {
        }
        return null;
    }
    // Pre-scan snapshots for Stop-restore: rescan clears the tables up front,
    // so Stop (before seed AND after seed) must bring back the previous
    // results instead of leaving empty / half-seeded tables.
    private volatile List<DriverRow> preScanOutdated = List.of();
    private volatile List<DriverRow> preScanUpToDate = List.of();

    private DriversOperationGate.Lease tryAcquireOperation(DriversOperationGate.Owner owner) {
        DriversOperationGate.Lease lease = operationGate.tryAcquire(owner);
        if (lease == null) {
            return null;
        }
        switch (owner) {
            case SCAN -> scanLease = lease;
            case INSTALL -> installLease = lease;
            case BACKUP -> backupLease = lease;
            default -> { }
        }
        if (Platform.isFxApplicationThread()) {
            busy.set(true);
        } else {
            Platform.runLater(() -> busy.set(true));
        }
        return lease;
    }

    private void releaseOperation(DriversOperationGate.Lease lease) {
        if (lease == null || !operationGate.release(lease)) {
            return;
        }
        if (Platform.isFxApplicationThread()) {
            busy.set(false);
        } else {
            Platform.runLater(() -> busy.set(false));
        }
    }

    private boolean isLeaseCurrent(DriversOperationGate.Lease lease) {
        return operationGate.isCurrent(lease);
    }

    private boolean requireAdminFresh() {
        boolean admin;
        try {
            admin = com.sbtools.util.AdminCheck.isRunningAsAdminFresh();
        } catch (Exception ex) {
            admin = adminCheck.getAsBoolean();
        }
        if (!admin) {
            new Alert(Alert.AlertType.WARNING,
                    "This operation requires administrator rights. Please restart the app as administrator.").showAndWait();
            return false;
        }
        return true;
    }

    public DriversTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopBackupButton.setVisible(false);
        stopBackupButton.setManaged(false);
        stopBackupButton.setDisable(true);
        stopInstallButton.setVisible(false);
        stopInstallButton.setManaged(false);
        stopInstallButton.setDisable(true);

        searchField.setPromptText("Search...");
        searchField.setPrefWidth(160);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> filterTables());

        scanButton.setOnAction(e -> startScan());
        stopScanButton.setOnAction(e -> stopScan());
        stopScanButton.setDisable(true);

        updateAllButton.setDisable(true);
        updateAllButton.setOnAction(e -> startBatchUpdate());
        updateSelectedButton.setDisable(true);
        updateSelectedButton.setOnAction(e -> startBatchUpdateSelected());
        backupButton.setOnAction(e -> startBackupAll());
        stopBackupButton.setOnAction(e -> stopBackup());
        stopInstallButton.setOnAction(e -> stopInstall());

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
        stopInstallButton.setTooltip(new Tooltip("Cancel the running install / batch update"));
        ignoredListButton.setTooltip(new Tooltip("Manage ignored/excluded drivers"));
        historyButton.setTooltip(new Tooltip("View past driver update history"));
        detailsButton.setTooltip(new Tooltip("View details of the selected driver"));

        HBox row1 = new HBox(8, scanButton, stopScanButton, updateAllButton, updateSelectedButton,
                stopInstallButton, backupButton, stopBackupButton, ignoredListButton, historyButton, detailsButton);
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
            updateControlStates();
            if (outdatedTable != null) {
                outdatedTable.refresh();
            }
            if (upToDateTable != null) {
                upToDateTable.refresh();
            }
        });
        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    private DriverRow getSelectedRow() {
        return lastSelectedRow;
    }

    private void updateControlStates() {
        boolean isBusy = busy.get();
        boolean hasOutdated = !outdatedRows.isEmpty();
        boolean hasSelected = outdatedRows.stream().anyMatch(DriverRow::isSelected);
        boolean windows = AppPaths.isWindows();
        scanButton.setDisable(isBusy || !windows);
        stopScanButton.setDisable(!isBusy || scanLease == null || !isLeaseCurrent(scanLease));
        updateAllButton.setDisable(!hasOutdated || isBusy || !windows);
        updateSelectedButton.setDisable(!hasSelected || isBusy || !windows);
        backupButton.setDisable(isBusy || !windows);
        stopBackupButton.setDisable(backupLease == null || !isLeaseCurrent(backupLease));
        stopInstallButton.setDisable(installLease == null || !isLeaseCurrent(installLease));
    }

    private void updateButtonStates() {
        updateControlStates();
    }

    private VBox buildTablesContainer() {
        UILabel outdatedLabel = UILabel.sectionTitle("Outdated Drivers");
        UILabel upToDateLabel = UILabel.sectionTitle("Up to Date Drivers");
        
        outdatedTable = buildTable(filteredOutdated);
        upToDateTable = buildUpToDateTable(filteredUpToDate);
        outdatedTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (newRow != null) {
                lastSelectedRow = newRow;
                if (upToDateTable != null) {
                    upToDateTable.getSelectionModel().clearSelection();
                }
            }
        });
        upToDateTable.getSelectionModel().selectedItemProperty().addListener((obs, oldRow, newRow) -> {
            if (newRow != null) {
                lastSelectedRow = newRow;
                if (outdatedTable != null) {
                    outdatedTable.getSelectionModel().clearSelection();
                }
            }
        });
        
        VBox.setVgrow(outdatedTable, Priority.ALWAYS);
        VBox.setVgrow(upToDateTable, Priority.ALWAYS);
        
        VBox container = new VBox(8, outdatedLabel, outdatedTable, upToDateLabel, upToDateTable);
        container.setPadding(new Insets(12, 16, 12, 16));
        return container;
    }

    private TableView<DriverRow> buildTable(ObservableList<DriverRow> items) {
        TableView<DriverRow> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriverRow, Boolean> selectCol = UiColumn.of("Select");
        selectCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selectCol.setCellFactory(col -> new TableCell<DriverRow, Boolean>() {
            private final CheckBox cb = new CheckBox();
            {
                cb.setOnAction(e -> {
                    DriverRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null) {
                        row.setSelected(cb.isSelected());
                    }
                    updateButtonStates();
                });
            }

            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    DriverRow row = getTableRow().getItem();
                    // Avoid firing action while programmatically updating
                    cb.setOnAction(null);
                    cb.setSelected(row.isSelected());
                    cb.setOnAction(e2 -> {
                        DriverRow r2 = getTableRow() != null ? getTableRow().getItem() : null;
                        if (r2 != null) {
                            r2.setSelected(cb.isSelected());
                        }
                        updateButtonStates();
                    });
                    setGraphic(cb);
                }
            }
        });
        selectCol.setPrefWidth(50);
        selectCol.setEditable(false);
        selectCol.setSortable(false);

        TableColumn<DriverRow, String> deviceCol = UiColumn.of("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());
        deviceCol.setPrefWidth(220);

        TableColumn<DriverRow, String> currentCol = UiColumn.of("Current");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(100);

        TableColumn<DriverRow, String> availableCol = UiColumn.of("Available");
        availableCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availableCol.setPrefWidth(100);

        TableColumn<DriverRow, UpdateSeverity> severityCol = UiColumn.of("Severity");
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
                if (row != null && row.isRebootPending()) {
                    Label badge = new Label("REBOOT");
                    badge.setStyle("-fx-background-color: #bd93f9; -fx-text-fill: white; -fx-padding: 2 6; -fx-background-radius: 4; -fx-font-weight: bold;");
                    badge.setTooltip(new Tooltip("Driver installed — restart required to complete"));
                    setGraphic(badge);
                    return;
                }
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

        TableColumn<DriverRow, String> sourceCol = UiColumn.of("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(90);

        TableColumn<DriverRow, DriverHealthService.DriverHealthScore> healthCol = UiColumn.of("Health");
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

        TableColumn<DriverRow, Void> actionCol = UiColumn.of("Action");
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
        private final UIButton compareBtn = UIButton.small("Compare");
        private final UIButton stopBtn = UIButton.small("Stop");
        private final ProgressBar downloadProgress = new ProgressBar(0);
        private final UILabel sizeLabel = new UILabel("");
        private final Label installingLabel = new Label("Installing driver. Please wait…");
        private final ProgressIndicator spinner = new ProgressIndicator();
        private final HBox container;
        private State state = State.IDLE;
        private DriverRow trackedRow;

        DriverActionCell() {
            spinner.setPrefSize(24, 24);
            spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            downloadProgress.setPrefWidth(80);
            container = new HBox(6, updateBtn, ignoreBtn, compareBtn, sizeLabel, downloadProgress, stopBtn, installingLabel, spinner);
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
                    outdatedRows.remove(row);
                }
            });
            compareBtn.setOnAction(e -> {
                DriverRow row = currentRow();
                if (row != null && row.hasUpdate()) {
                    showComparisonDialog(row);
                }
            });
            stopBtn.setOnAction(e -> {
                installService.cancel();
                stopBtn.setDisable(true);
            });

            applyVisibility();
        }

        private DriverRow currentRow() {
            // TableRow item, not items.get(index): getIndex() is a view index
            // that diverges from backing-list order once the user sorts a
            // column, so index lookup acted on the wrong driver.
            if (getTableRow() != null && getTableRow().getItem() != null) {
                return getTableRow().getItem();
            }
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
                // Cell recycled to empty: drop stale row->cell mapping so
                // late progress callbacks don't paint a detached cell.
                if (trackedRow != null) {
                    installCells.remove(trackedRow, this);
                    trackedRow = null;
                }
                setGraphic(null);
                return;
            }
            DriverRow row = currentRow();
            if (row == null) {
                if (trackedRow != null) {
                    installCells.remove(trackedRow, this);
                    trackedRow = null;
                }
                setGraphic(null);
                return;
            }
            if (row != trackedRow) {
                // Cell recycled for a different row: unhook the old mapping
                // first, otherwise install progress for the old row would
                // update this cell while it displays the new row.
                if (trackedRow != null) {
                    installCells.remove(trackedRow, this);
                }
                state = State.IDLE;
                sizeLabel.setText("");
                downloadProgress.setProgress(0);
                stopBtn.setDisable(true);
                trackedRow = row;
                installCells.put(row, this);
            }
            updateBtn.setDisable(!row.hasUpdate() || busy.get());
            ignoreBtn.setDisable(busy.get());
            compareBtn.setDisable(!row.hasUpdate() || busy.get());
            compareBtn.setVisible(row.hasUpdate());
            compareBtn.setManaged(row.hasUpdate());
            if (row.candidate() != null) {
                String tooltipText = row.installed().friendlyName();
                if (row.candidate().availableVersion() != null) {
                    tooltipText += " \u2192 " + row.candidate().availableVersion();
                }
                updateBtn.setTooltip(new Tooltip(tooltipText));
            }
            applyVisibility();
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
            boolean active = downloading || installing;
            updateBtn.setVisible(idle);
            updateBtn.setManaged(idle);
            ignoreBtn.setVisible(idle);
            ignoreBtn.setManaged(idle);
            downloadProgress.setVisible(downloading);
            downloadProgress.setManaged(downloading);
            sizeLabel.setVisible(downloading);
            sizeLabel.setManaged(downloading);
            // Stop must stay visible during INSTALLING: the 900s pnputil /
            // installer phase is cancellable via ProcessRunner kill.
            stopBtn.setVisible(active);
            stopBtn.setManaged(active);
            stopBtn.setDisable(false);
            installingLabel.setVisible(installing);
            installingLabel.setManaged(installing);
            spinner.setVisible(installing);
            spinner.setManaged(installing);
        }
    }

    private TableView<DriverRow> buildUpToDateTable(ObservableList<DriverRow> items) {
        TableView<DriverRow> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriverRow, String> deviceCol = UiColumn.of("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());
        deviceCol.setPrefWidth(220);

        TableColumn<DriverRow, String> currentCol = UiColumn.of("Current");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(100);

        TableColumn<DriverRow, DriverHealthService.DriverHealthScore> healthCol = UiColumn.of("Health");
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

        TableColumn<DriverRow, Void> actionCol = UiColumn.of("Action");
        actionCol.setPrefWidth(120);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton ignoreBtn = UIButton.small("Ignore");

            {
                ignoreBtn.setOnAction(e -> {
                    if (busy.get()) return;
                    // TableRow item, not items.get(index): view index diverges
                    // from backing order under column sorting (same fix as
                    // DriverActionCell.currentRow).
                    DriverRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row == null && getTableView() != null) {
                        int idx = getIndex();
                        if (idx >= 0 && idx < getTableView().getItems().size()) {
                            row = getTableView().getItems().get(idx);
                        }
                    }
                    if (row != null) {
                        excludeDriver(row);
                        upToDateRows.remove(row);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    // Disabled while busy (like the Outdated table): ignoring
                    // mid-scan is resurrected by the next provider callback
                    // reconciling new rows. Busy listener refreshes tables.
                    ignoreBtn.setDisable(busy.get());
                    setGraphic(ignoreBtn);
                }
            }
        });

        table.setPlaceholder(new Label("No up-to-date drivers detected yet \u2014 run a scan to populate this list."));
        table.getColumns().addAll(deviceCol, currentCol, healthCol, actionCol);
        return table;
    }

    private void stopScan() {
        // Only touch scan state: never clear another operation's busy flag
        // (stopScan previously set busy=false even mid-install).
        if (scanLease == null && scanToken == null && scanFuture == null) return;
        CancellationToken token = scanToken;
        if (token != null) {
            token.cancel();
        }
        if (scanFuture != null) {
            scanFuture.cancel(true);
            scanFuture = null;
        }
        DriversOperationGate.Lease lease = scanLease;
        if (lease != null && isLeaseCurrent(lease)) {
            try {
                DriversScanSnapshot.restore(outdatedRows, upToDateRows,
                        preScanOutdated, preScanUpToDate, installCells);
                filterTables();
            } catch (Exception restoreEx) {
                AppLogger.warning("Stop-scan restore failed: " + restoreEx.getMessage());
            }
            releaseOperation(lease);
            progressBar.setVisible(false);
            progressLabel.setVisible(false);
            updateControlStates();
            setStatus("Scan stopped.");
        }
    }

    private void stopInstall() {
        installCancelFlag.set(true);
        installService.cancel();
        if (installFuture != null) installFuture.cancel(true);
        stopInstallButton.setDisable(true);
        setStatus("Cancelling install…");
    }

    private void startScan() {
        startScanInternal();
    }

    private void startScanInternal() {
        if (busy.get()) {
            setStatus("Busy: finish the running operation before scanning.");
            return;
        }
        final DriversOperationGate.Lease capturedScanLease = tryAcquireOperation(DriversOperationGate.Owner.SCAN);
        if (capturedScanLease == null) return;
        CancellationToken previousToken = scanToken;
        if (previousToken != null) {
            previousToken.cancel();
        }
        final CancellationToken token = new CancellationToken();
        scanToken = token;
        setStatus("Enumerating installed drivers…");
        updateControlStates();
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
        // Snapshot pre-scan rows BEFORE clearing: Stop before the seed block
        // must restore them instead of leaving freshly-cleared empty tables.
        // Stored in fields too so stopScan() can restore after the seed.
        final List<DriverRow> preScanOutdated = new ArrayList<>(outdatedRows);
        final List<DriverRow> preScanUpToDate = new ArrayList<>(upToDateRows);
        this.preScanOutdated = List.copyOf(preScanOutdated);
        this.preScanUpToDate = List.copyOf(preScanUpToDate);
        outdatedRows.clear();
        upToDateRows.clear();
        // Rows are recreated per scan, so stale row->cell entries would leak
        // and route progress to detached cells. Scans never overlap installs
        // (busy gate above), so clearing here is safe.
        installCells.clear();
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        // Self-holder for the guarded finally-clear below: without it a
        // stale scan task wipes a newer rescan's token/future references.
        final Future<?>[] selfFuture = new Future<?>[1];
        Future<?> submitted = scanExecutor.submit(() -> {
            try {
                if (token.isCancelled()) return;
                List<InstalledDriver> installed = scanService.scanInstalled();
                if (token.isCancelled()) return;
                // Clear reboot-pending entries completed by a reboot since they were
                // recorded, plus entries for devices no longer present. Prevents
                // drivers staying in Outdated/REBOOT forever after rebooting.
                try {
                    rebootStore.purgeAfterReboot();
                    if (installed != null) {
                        Set<String> installedIds = new HashSet<>();
                        for (InstalledDriver d : installed) {
                            if (d != null && d.deviceId() != null && !d.deviceId().isBlank()) {
                                installedIds.add(d.deviceId());
                            }
                        }
                        rebootStore.purgeMissingDevices(installedIds);
                    }
                } catch (Exception purgeEx) {
                    AppLogger.warning("Failed to purge reboot-pending: " + purgeEx.getMessage());
                }
                Map<String, DriverRow> rowByDevice = new HashMap<>();
                if (installed != null) {
                    for (InstalledDriver d : installed) {
                        if (d == null || d.deviceId() == null || d.deviceId().isBlank()) continue;
                        rowByDevice.put(d.deviceId(), new DriverRow(d));
                    }
                }
                    Platform.runLater(() -> {
                        if (token.isCancelled()) {
                        DriversScanSnapshot.restore(outdatedRows, upToDateRows,
                                preScanOutdated, preScanUpToDate, installCells);
                        filterTables();
                        return;
                    }
                    progressBar.setProgress(0.2);
                    progressLabel.setText("20%");
                    setStatus("Listed " + installed.size() + " device(s). Checking update sources…");
                    // Reload exclusions live: the set may have changed during
                    // enumeration (Ignored dialog), and provider callbacks
                    // already reload per callback (see below).
                    Set<String> seedExcluded = loadExcludedIdSet();
                    // Seed the up-to-date list immediately so users get feedback before any provider replies.
                    for (DriverRow row : rowByDevice.values()) {
                        if (!seedExcluded.contains(com.sbtools.drivers.DriverScanService
                                .normalizeDeviceKey(row.installed().deviceId()))) {
                            if (previouslySelected.contains(row.installed().deviceId())) {
                                row.setSelected(true);
                            }
                            upToDateRows.add(row);
                        }
                    }
                });

                if (token.isCancelled()) return;
                AtomicInteger providersDone = new AtomicInteger();
                // Single vendor-detection pass shared by the count and the run.
                java.util.List<com.sbtools.drivers.catalog.DriverCatalogProvider> activeProviders =
                        catalog.relevantProviders(installed);
                int providerCount = activeProviders.size();
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
                                // Clamp: a provider must never push progress past
                                // 100% ("Checked 6/5 sources") even if a stray
                                // duplicate callback slips through dedup upstream.
                                int shown = Math.min(done, Math.max(1, providerCount));
                                double progress = 0.2 + (0.8 * shown / Math.max(1, providerCount));
                                progressBar.setProgress(progress);
                                progressLabel.setText((int)(progress * 100) + "%");
                                // Reload exclusions per callback: Ignore clicked
                                // mid-scan must not be resurrected by the next
                                // provider reconciling against a stale snapshot.
                                boolean rowsChanged = reconcileRows(rowByDevice, loadExcludedIdSet());
                                // B3: reconcile reboot-pending state so Dashboard/Drivers stay in sync
                                boolean rebootChanged = false;
                                try {
                                    Set<String> pendingIds = rebootStore.loadPendingIds();
                                    for (DriverRow r : rowByDevice.values()) {
                                        String did = r.installed().deviceId();
                                        if (pendingIds.contains(did)) {
                                            if (!r.isRebootPending()) {
                                                r.setRebootPending(true);
                                                rebootChanged = true;
                                            }
                                            // Keep reboot-pending drivers visibly in Outdated until reboot completes
                                            if (outdatedRows.contains(r)) {
                                                // already there with REBOOT badge
                                            } else if (r.hasUpdate()) {
                                                // has update but was in upToDate due to prior clear — move to outdated
                                                upToDateRows.remove(r);
                                                if (!outdatedRows.contains(r)) outdatedRows.add(r);
                                                rebootChanged = true;
                                            } else {
                                                // No candidate anymore but still pending — keep as reboot badge in outdated
                                                // (provider may still report candidate; if not, treat as pending completion)
                                                upToDateRows.remove(r);
                                                if (!outdatedRows.contains(r)) outdatedRows.add(r);
                                                rebootChanged = true;
                                            }
                                        } else if (r.isRebootPending()) {
                                            // Was pending but store cleared externally or version now matches — clear badge
                                            if (!r.hasUpdate()) {
                                                r.setRebootPending(false);
                                                rebootChanged = true;
                                            }
                                        }
                                    }
                                    // Perf: only refresh tables when rows or reboot badges actually changed.
                                    // ObservableLists already fire change events; explicit refresh is only
                                    // needed for badge/state cell repaint.
                                    if ((rowsChanged || rebootChanged) && !pendingIds.isEmpty()) {
                                        outdatedTable.refresh();
                                        upToDateTable.refresh();
                                    }
                                } catch (Exception ex) {
                                    AppLogger.warning("Failed to reconcile reboot pending: " + ex.getMessage());
                                }
                                int outdated = outdatedRows.size();
                                long rebootPendingCount = outdatedRows.stream().filter(DriverRow::isRebootPending).count();
                                if (shown < providerCount) {
                                    setStatus("Checked " + shown + "/" + providerCount + " sources — "
                                            + outdated + " update(s) found so far…"
                                            + (rebootPendingCount > 0 ? " (" + rebootPendingCount + " reboot pending)" : ""));
                                } else {
                                    String suffix = rebootPendingCount > 0 ? " (" + rebootPendingCount + " reboot pending)" : "";
                                    setStatus("Found " + outdated + " outdated driver(s) out of "
                                            + installed.size() + " device(s)." + suffix);
                                }
                            });
                        },
                        activeProviders
                );
            } catch (Exception ex) {
                if (!token.isCancelled()) {
                    Platform.runLater(() -> {
                        DriversScanSnapshot.restore(outdatedRows, upToDateRows,
                                preScanOutdated, preScanUpToDate, installCells);
                        filterTables();
                        setStatus("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                // Guarded clears: a rescan started after Stop would otherwise
                // have its token/future wiped by this stale task's finally,
                // making the new scan unstoppable.
                if (scanToken == token) scanToken = null;
                if (scanFuture == selfFuture[0]) scanFuture = null;
                Platform.runLater(() -> {
                    if (!isLeaseCurrent(capturedScanLease)) {
                        return;
                    }
                    releaseOperation(capturedScanLease);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    updateControlStates();
                });
            }
        });
        selfFuture[0] = submitted;
        scanFuture = submitted;
    }

    private void setStatus(String text) {
        statusLabel.setText(text);
    }

    private static final Map<String, String> PROVIDER_STATUS_NAMES = Map.ofEntries(
            Map.entry("WindowsUpdate", "Windows Update"),
            Map.entry("Nvidia", "NVIDIA"),
            Map.entry("AMD", "AMD"),
            Map.entry("Intel", "Intel"),
            Map.entry("Realtek", "Realtek"),
            Map.entry("Broadcom", "Broadcom"),
            Map.entry("Qualcomm", "Qualcomm"),
            Map.entry("Synaptics", "Synaptics"),
            Map.entry("Lenovo", "Lenovo"),
            Map.entry("Dell", "Dell"),
            Map.entry("HP", "HP"),
            Map.entry("ASUS", "ASUS")
    );

    private static String providerStatus(String providerId, int deviceCount) {
        String displayName = PROVIDER_STATUS_NAMES.getOrDefault(providerId, providerId);
        if ("WindowsUpdate".equals(providerId)) {
            return "Checking " + displayName + " (" + deviceCount + " devices)\u2026";
        }
        return "Checking " + displayName + " catalog\u2026";
    }

    private static void applyCandidates(Map<String, DriverRow> rowByDevice, List<DriverUpdateCandidate> candidates) {
        if (rowByDevice == null || candidates == null) return;
        Map<String, DriverUpdateCandidate> candidateMap = new HashMap<>();
        for (DriverUpdateCandidate c : candidates) {
            if (c == null || c.installed() == null) continue;
            String did = c.installed().deviceId();
            if (did == null || did.isBlank()) continue;
            if (c.availableVersion() == null || c.availableVersion().isBlank()) continue;
            candidateMap.put(did, c);
        }
        for (Map.Entry<String, DriverUpdateCandidate> entry : candidateMap.entrySet()) {
            DriverRow row = rowByDevice.get(entry.getKey());
            if (row == null) continue;
            DriverUpdateCandidate newCandidate = entry.getValue();
            DriverUpdateCandidate oldCandidate = row.candidate();
            // Record equality covers version + downloadUrl + source + packageId:
            // the aggregator streams cumulative best-pick snapshots, so a later
            // snapshot with the same version but a better download (e.g. manual
            // OEM URL replaced by a direct URL / WU packageId) must still swap.
            if (oldCandidate == null || !newCandidate.equals(oldCandidate)) {
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
        if (settings.excludedDriverIds() == null) return ids;
        for (String e : settings.excludedDriverIds()) {
            if (e == null || e.isBlank()) continue;
            int t = e.lastIndexOf('\t');
            if (t < 0) t = e.lastIndexOf('\u001F');
            String id = t >= 0 ? e.substring(t + 1).trim() : e.trim();
            if (!id.isBlank()) {
                ids.add(com.sbtools.drivers.DriverScanService.normalizeDeviceKey(id));
            }
        }
        return ids;
    }

    static String extractExcludedId(String stored) {
        if (stored == null) return "";
        int t = stored.lastIndexOf('\t');
        if (t < 0) t = stored.lastIndexOf('\u001F');
        return t >= 0 ? stored.substring(t + 1).trim() : stored.trim();
    }

    /**
     * Incrementally moves rows between {@link #outdatedRows} and {@link #upToDateRows}
     * based on each row's current candidate. Unlike the old {@code splitRows} this does not
     * clear-and-refill, so selection and scroll positions are preserved across provider
     * callbacks during a scan.
     *
     * @return true when any row actually moved (callers skip redundant table.refresh() otherwise).
     */
    private boolean reconcileRows(Map<String, DriverRow> rowByDevice, Set<String> excludedIds) {
        java.util.Set<DriverRow> outdatedSet = new java.util.HashSet<>(outdatedRows);
        java.util.Set<DriverRow> upToDateSet = new java.util.HashSet<>(upToDateRows);
        java.util.List<DriverRow> toAddOutdated = new java.util.ArrayList<>();
        java.util.List<DriverRow> toRemoveOutdated = new java.util.ArrayList<>();
        java.util.List<DriverRow> toAddUpToDate = new java.util.ArrayList<>();
        java.util.List<DriverRow> toRemoveUpToDate = new java.util.ArrayList<>();

        for (DriverRow row : rowByDevice.values()) {
            String deviceId = row.installed().deviceId();
            boolean excluded = excludedIds.contains(
                    com.sbtools.drivers.DriverScanService.normalizeDeviceKey(deviceId));
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

        boolean changed = !toAddOutdated.isEmpty() || !toRemoveOutdated.isEmpty()
                || !toAddUpToDate.isEmpty() || !toRemoveUpToDate.isEmpty();
        if (!changed) return false;
        outdatedRows.removeAll(toRemoveOutdated);
        outdatedRows.addAll(toAddOutdated);
        upToDateRows.removeAll(toRemoveUpToDate);
        upToDateRows.addAll(toAddUpToDate);
        return true;
    }

    private void showIgnoredListDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(com.sbtools.util.UiText.label("Ignored drivers"));
        dialog.setHeaderText(com.sbtools.util.UiText.label("Ignored drivers"));

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
                final String selId = extractExcludedId(selected);
                excludedIds.remove(selected);
                try {
                    settingsStore.update(cur -> cur.withExcludedDriverIds(new ArrayList<>(excludedIds)));
                    AppSettings refreshed = settingsStore.load();
                    if (refreshed.excludedDriverIds() != null && refreshed.excludedDriverIds().stream().anyMatch(s -> extractExcludedId(s).equals(selId))) {
                        AppLogger.warning("Ignored entry still present after remove (legacy store); retrying purge");
                        settingsStore.update(cur2 -> cur2.withExcludedDriverIds(cur2.excludedDriverIds() == null ? new ArrayList<>() : cur2.excludedDriverIds().stream().filter(s -> !extractExcludedId(s).equals(selId)).toList()));
                    }
                    // The tables hold per-scan row instances: an un-ignored
                    // driver cannot reappear without re-enumeration, so tell
                    // the user instead of looking broken.
                    setStatus("Removed from ignored list. Run Scan to pick it up again.");
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
        if (row == null) return;
        if (!requireAdminFresh()) return;
        if (busy.get()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Another operation is running. Please wait for it to finish.").showAndWait();
            return;
        }
        if (row.isRebootPending()) {
            new Alert(Alert.AlertType.INFORMATION,
                    "This driver is already installed and awaiting restart (REBOOT badge). "
                    + "Restart Windows before reinstalling.").showAndWait();
            return;
        }
        DriverUpdateCandidate c = row.candidate();
        if (c == null) {
            new Alert(Alert.AlertType.INFORMATION,
                    "No update available for " + row.installed().friendlyName()
                    + " — the available update was cleared by a concurrent scan. Please scan again.").showAndWait();
            return;
        }

        // Shared classification (single + batch stay identical).
        if (DriverPreflightService.needsManualDownload(c)) {
            showManualDownloadDialog(c);
            return;
        }

        // Pre-flight safety: disk space, backup volume, device health. Blocks on
        // errors, shows warnings non-modally via status + confirmation.
        AppSettings preSettings = settingsStore.load();
        DriverPreflightService.PreflightResult pre = DriverPreflightService.check(c, preSettings, rebootStore);
        if (!pre.ok()) {
            new Alert(Alert.AlertType.WARNING, pre.blockReason()).showAndWait();
            return;
        }
        if (pre.hasWarnings() && !confirmPreflightWarnings(row.installed().friendlyName(), pre.warnings())) {
            return;
        }

        // Acquire FIRST: the callbacks + cancel flags below are shared with
        // any running batch install. Touching them before owning the gate
        // would overwrite the live install's callbacks and clear its cancel
        // request, then fail to acquire and leave stale state behind.
        final DriversOperationGate.Lease capturedInstallLease = tryAcquireOperation(DriversOperationGate.Owner.INSTALL);
        if (capturedInstallLease == null) {
            new Alert(Alert.AlertType.INFORMATION,
                    "Another operation is running. Please wait for it to finish.").showAndWait();
            return;
        }
        boolean allowRestoreOnly = com.sbtools.backup.DriverBackupService.backupSupportIssue(c.installed()) != null;
        if (cell != null) installCells.put(row, cell);
        installCancelFlag.set(false);
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
        scanButton.setDisable(true);
        updateAllButton.setDisable(true);
        updateSelectedButton.setDisable(true);
        stopInstallButton.setVisible(true);
        stopInstallButton.setManaged(true);
        stopInstallButton.setDisable(false);
        statusLabel.setText("Installing update for " + row.installed().friendlyName() + "...");
        if (cell != null) {
            cell.setDownloading("Downloading...", 0.0);
        }

        AppSettings settings = settingsStore.load();
        final String oldCurrentAtInstall = row.currentVersionProperty().get();
        final String deviceIdAtInstall = row.installed().deviceId();
        final String friendlyAtInstall = row.installed().friendlyName();
        installFuture = installExecutor.submit(() -> {
            try {
                DriverInstallService.InstallResult result = installService.install(c, settings, allowRestoreOnly);
                final boolean wasCancelled = installCancelFlag.get() || installService.isCancelled();
                if (wasCancelled && result.installed()) {
                    AppLogger.warning("Install completed after cancel request for " + friendlyAtInstall);
                }
                try {
                    catalog.clearWindowsUpdateCache();
                    if (c.source() != null && !c.source().isBlank()) {
                        catalog.clearCacheForProvider(c.source());
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Failed to clear provider cache: " + ex.getMessage());
                }
                if (wasCancelled) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Install cancelled for " + friendlyAtInstall
                                + ". No changes assumed — scan again to verify.");
                        recordHistory(row, c, false, "cancelled by user");
                        DriverActionCell live = installCells.remove(row);
                        if (live != null) {
                            live.setIdle();
                        }
                        updateControlStates();
                    });
                    return;
                }
                if (!result.installed()) {
                    Platform.runLater(() -> {
                        String failDetail = DriverInstallFailure.sanitizeHistoryDetail(
                                result.message(), friendlyAtInstall);
                        recordHistory(row, c, false, failDetail);
                        showErrorWithFallback(failDetail, c.vendorPageUrl(), friendlyAtInstall);
                        DriverActionCell live = installCells.remove(row);
                        if (live != null) {
                            live.setIdle();
                        }
                        updateControlStates();
                    });
                    return;
                }
                if (result.rebootRequired()) {
                    Platform.runLater(() -> {
                        recordHistory(row, c, true, "reboot-required; rollback kept");
                        rebootStore.addPending(deviceIdAtInstall, friendlyAtInstall);
                        row.setRebootPending(true);
                        statusLabel.setText("Update installed for " + friendlyAtInstall
                                + " — restart pending. Driver remains in Outdated until reboot.");
                        if (!outdatedRows.contains(row)) {
                            outdatedRows.add(row);
                        }
                        upToDateRows.remove(row);
                        outdatedTable.refresh();
                        upToDateTable.refresh();
                        DriverActionCell live = installCells.remove(row);
                        if (live != null) {
                            live.setIdle();
                        }
                        updateControlStates();
                        Alert rebootAlert = new Alert(Alert.AlertType.INFORMATION,
                                "Driver installed but a restart is required to complete the installation.\n\n"
                                + "The driver will stay in Outdated Drivers with a REBOOT badge until you restart.\n"
                                + "Dashboard will also show it as outdated until reboot.");
                        rebootAlert.setTitle(com.sbtools.util.UiText.label("Restart required"));
                        rebootAlert.setHeaderText("Restart required");
                        rebootAlert.showAndWait();
                    });
                    return;
                }

                final String deviceKey = DriverScanService.normalizeDeviceKey(deviceIdAtInstall);
                DriverPostInstallVerifier.Outcome verifyOutcome;
                try {
                    Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                            List.of(new DriverPostInstallVerifier.VerifyRequest(
                                    deviceIdAtInstall,
                                    c.availableVersion(),
                                    oldCurrentAtInstall,
                                    rebootStore.isPending(deviceIdAtInstall))),
                            () -> {
                                try {
                                    return scanInstalledByDeviceKey();
                                } catch (Exception scanEx) {
                                    AppLogger.warning("Post-install scan failed: " + scanEx.getMessage());
                                    return Map.of();
                                }
                            },
                            installCancelFlag,
                            ms -> {
                                if (installCancelFlag.get()) {
                                    throw new InterruptedException("cancelled");
                                }
                                Thread.sleep(ms);
                            },
                            attempt -> Platform.runLater(() -> {
                                if (attempt <= 1) {
                                    statusLabel.setText("Verifying installed version for "
                                            + friendlyAtInstall + "\u2026");
                                } else {
                                    statusLabel.setText("Waiting for Windows to apply "
                                            + friendlyAtInstall + " (check " + attempt + ")\u2026");
                                }
                            }));
                    verifyOutcome = outcomes.get(deviceKey);
                    if (verifyOutcome == null) {
                        verifyOutcome = new DriverPostInstallVerifier.Outcome(
                                null,
                                DriverVersionVerifier.Verdict.INCONCLUSIVE,
                                0,
                                "no scan result");
                    }
                } catch (InterruptedException verifyInterrupt) {
                    Thread.currentThread().interrupt();
                    Platform.runLater(() -> {
                        statusLabel.setText("Install verification cancelled for " + friendlyAtInstall + ".");
                        DriverActionCell live = installCells.remove(row);
                        if (live != null) {
                            live.setIdle();
                        }
                        updateControlStates();
                    });
                    return;
                } catch (Exception verifyEx) {
                    AppLogger.warning("Post-install verify failed for " + friendlyAtInstall + ": "
                            + verifyEx.getMessage());
                    verifyOutcome = new DriverPostInstallVerifier.Outcome(
                            null,
                            DriverVersionVerifier.Verdict.INCONCLUSIVE,
                            0,
                            verifyEx.getMessage());
                }
                final DriverPostInstallVerifier.Outcome outcomeFinal = verifyOutcome;
                Platform.runLater(() -> applySingleInstallVerdict(
                        row, c, deviceIdAtInstall, friendlyAtInstall, outcomeFinal));
            } catch (Exception ex) {
                AppLogger.warning("Unexpected error during driver install for " + friendlyAtInstall, ex);
                Platform.runLater(() -> {
                    String msg = DriverInstallFailure.userMessage("install", ex, installCancelFlag.get());
                    String safe = DriverInstallFailure.sanitizeUserMessage(msg, friendlyAtInstall);
                    recordHistory(row, c, false, safe);
                    showErrorWithFallback(safe, c.vendorPageUrl(), friendlyAtInstall);
                    DriverActionCell live = installCells.remove(row);
                    if (live != null) {
                        live.setIdle();
                    }
                    updateControlStates();
                });
            } finally {
                installService.setProgressCallback(null);
                installService.setStatusCallback(null);
                installFuture = null;
                Platform.runLater(() -> {
                    if (!isLeaseCurrent(capturedInstallLease)) {
                        return;
                    }
                    releaseOperation(capturedInstallLease);
                    stopInstallButton.setVisible(false);
                    stopInstallButton.setManaged(false);
                    updateControlStates();
                });
            }
        });
    }

    private Map<String, InstalledDriver> scanInstalledByDeviceKey() throws IOException, InterruptedException {
        Map<String, InstalledDriver> byKey = new java.util.HashMap<>();
        for (InstalledDriver d : scanService.scanInstalled()) {
            byKey.put(DriverScanService.normalizeDeviceKey(d.deviceId()), d);
        }
        return byKey;
    }

    private void applySingleInstallVerdict(DriverRow row,
                                           DriverUpdateCandidate c,
                                           String deviceId,
                                           String friendlyName,
                                           DriverPostInstallVerifier.Outcome outcome) {
        if (disposed) {
            return;
        }
        InstalledDriver fresh = outcome.fresh();
        DriverVersionVerifier.Verdict verdict = outcome.verdict();
        String freshVersion = DriverInstallReconciliation.freshVersion(fresh);
        if (fresh != null) {
            row.refreshFrom(fresh);
        }
        DriverActionCell live = installCells.remove(row);
        if (live != null) {
            live.setIdle();
        }
        String detail = DriverInstallReconciliation.historyDetail(
                verdict, freshVersion, outcome.readAttempts(), outcome.diagnostic());
        recordHistory(row, c, DriverInstallReconciliation.historySuccess(verdict), detail);
        statusLabel.setText(DriverInstallReconciliation.userStatusLine(
                friendlyName, verdict, c.availableVersion(), freshVersion,
                outcome.readAttempts(), outcome.diagnostic()));
        if (verdict == DriverVersionVerifier.Verdict.VERIFIED) {
            rebootStore.clearPending(deviceId);
            row.setRebootPending(false);
            row.setCandidate(null);
            outdatedRows.remove(row);
            if (!upToDateRows.contains(row)) {
                upToDateRows.add(row);
            }
        } else if (verdict == DriverVersionVerifier.Verdict.NEEDS_REBOOT) {
            rebootStore.addPending(deviceId, friendlyName);
            row.setRebootPending(true);
            if (!outdatedRows.contains(row)) {
                outdatedRows.add(row);
            }
            upToDateRows.remove(row);
        } else {
            row.setCandidate(c);
            row.setRebootPending(false);
            if (!outdatedRows.contains(row)) {
                outdatedRows.add(row);
            }
            upToDateRows.remove(row);
            new Alert(Alert.AlertType.WARNING,
                    DriverInstallReconciliation.userStatusLine(
                            friendlyName, verdict, c.availableVersion(), freshVersion,
                            outcome.readAttempts(), outcome.diagnostic()))
                    .showAndWait();
        }
        outdatedTable.refresh();
        upToDateTable.refresh();
        updateControlStates();
    }

    private void recordHistory(DriverRow row, DriverUpdateCandidate c, boolean success) {
        recordHistory(row, c, success, "");
    }

    private void recordHistory(DriverRow row, DriverUpdateCandidate c, boolean success, String detail) {
        if (!success) {
            detail = DriverInstallFailure.sanitizeHistoryDetail(
                    detail, row.installed().friendlyName());
        }
        try {
            historyStore.recordUpdate(
                    row.installed().deviceId(),
                    row.installed().friendlyName(),
                    row.installed().driverVersion(),
                    c.availableVersion(),
                    c.source(),
                    success,
                    detail == null ? "" : detail);
        } catch (Exception ex) {
            AppLogger.warning("Failed to record update history: " + ex.getMessage());
        }
    }

    /**
     * Shows pre-flight warnings and asks for confirmation. Returns true to proceed.
     * Non-blocking for single-warning cases is intentionally modal here: installing
     * a driver is destructive and the user must acknowledge health/space risks.
     */
    private boolean confirmPreflightWarnings(String deviceName, List<String> warnings) {
        String body = "Pre-install checks for " + deviceName + ":\n\n• " + String.join("\n• ", warnings)
                + "\n\nProceed anyway?";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body, ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Pre-install Warnings");
        confirm.setHeaderText("Proceed with driver update?");
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private List<String> collectBatchPreflightWarnings(List<DriverRow> rows, AppSettings settings) {
        List<String> lines = new ArrayList<>();
        for (DriverRow row : rows) {
            if (row == null || row.candidate() == null || row.isRebootPending()) {
                continue;
            }
            DriverUpdateCandidate c = row.candidate();
            if (DriverPreflightService.needsManualDownload(c)) {
                continue;
            }
            try {
                DriverPreflightService.PreflightResult pre =
                        DriverPreflightService.check(c, settings, rebootStore);
                if (pre.ok() && pre.hasWarnings()) {
                    String name = row.installed().friendlyName();
                    lines.add(name + ": " + String.join("; ", pre.warnings()));
                }
            } catch (Exception ex) {
                AppLogger.warning("Batch pre-flight collect failed for "
                        + row.installed().friendlyName() + ": " + ex.getMessage());
            }
        }
        return lines;
    }

    private boolean confirmBatchPreflightWarnings(List<String> perDeviceWarnings) {
        int cap = 15;
        List<String> shown = perDeviceWarnings.size() <= cap
                ? perDeviceWarnings
                : perDeviceWarnings.subList(0, cap);
        StringBuilder body = new StringBuilder();
        body.append("Pre-install checks reported warnings for ")
                .append(perDeviceWarnings.size())
                .append(" driver(s):\n\n");
        for (String line : shown) {
            body.append("• ").append(line).append('\n');
        }
        if (perDeviceWarnings.size() > cap) {
            body.append("\n… and ").append(perDeviceWarnings.size() - cap)
                    .append(" more (see app.log).\n");
        }
        body.append("\nProceed with batch update anyway?");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, body.toString(),
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Pre-install Warnings");
        confirm.setHeaderText("Proceed with batch driver updates?");
        confirm.getDialogPane().setPrefWidth(560);
        return confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void showErrorWithFallback(String message, String vendorPageUrl, String friendlyDeviceName) {
        // Cap: raw installer/pnputil/WU output can be megabytes and would
        // freeze the dialog (batch caps at the same 1500).
        String safe = DriverInstallFailure.sanitizeUserMessage(message, friendlyDeviceName);
        if (safe.length() > 1500) {
            AppLogger.warning("Driver install failure detail (truncated in dialog): " + safe);
            safe = safe.substring(0, 1500) + "… [truncated — see app.log]";
        }
        if (vendorPageUrl != null && !vendorPageUrl.isBlank()) {
            Alert alert = new Alert(Alert.AlertType.ERROR, safe + "\n\nYou can try downloading manually from the vendor website.",
                    ButtonType.OK, ButtonType.CANCEL);
            alert.setTitle(com.sbtools.util.UiText.label("Driver install failed"));
            alert.setHeaderText("Install failed — manual download available");

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
            new Alert(Alert.AlertType.ERROR, safe).showAndWait();
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
        alert.setTitle(com.sbtools.util.UiText.label("Manual download required"));
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

    /**
     * Looks up the {@link DriverActionCell} for a given row.
     * Primary source is the {@link #installCells} map (populated on FX thread when a row enters installing state).
     * Falls back to walking the table's live cell map only if the row is currently visible.
     * Returns {@code null} if the row is not currently tracked or not visible in the viewport.
     */
    private DriverActionCell lookupActionCell(DriverRow row) {
        // Fast path: map tracks the live cell registered during installUpdate/installBatchUpdates
        DriverActionCell tracked = installCells.get(row);
        if (tracked != null) {
            return tracked;
        }
        if (outdatedTable == null) return null;
        // Fallback: scan installed cells via map entries – avoid lookupAll(".table-cell") which
        // does not reliably return custom cell instances post-virtualization.
        for (Map.Entry<DriverRow, DriverActionCell> e : installCells.entrySet()) {
            if (e.getKey() == row) {
                return e.getValue();
            }
        }
        return null;
    }

    
    private void excludeDriver(DriverRow row) {
        if (row == null || row.installed() == null) return;
        String deviceId = row.installed().deviceId();
        if (deviceId == null || deviceId.isBlank()) return;
        String friendly = row.installed().friendlyName() == null ? deviceId : row.installed().friendlyName();
        try {
            settingsStore.update(current -> {
                java.util.List<String> excluded = current.excludedDriverIds() == null
                        ? new java.util.ArrayList<>() : new java.util.ArrayList<>(current.excludedDriverIds());
                boolean alreadyExcluded = excluded.stream().anyMatch(s -> extractExcludedId(s).equals(deviceId));
                if (!alreadyExcluded) {
                    excluded.add(friendly + "\u001F" + deviceId);
                }
                return current.withExcludedDriverIds(excluded);
            });
        } catch (IOException ex) {
            AppLogger.warning("Failed to save excluded driver: " + ex.getMessage());
        }
    }
    private final FilteredList<DriverRow> filteredOutdated = new FilteredList<>(outdatedRows);
    private final FilteredList<DriverRow> filteredUpToDate = new FilteredList<>(upToDateRows);

    private void filterTables() {
        // ROOT locale: tr-TR turns "Intel" into "ıntel" and breaks contains().
        String filter = searchField.getText().toLowerCase(java.util.Locale.ROOT).trim();
        if (filter.isEmpty()) {
            filteredOutdated.setPredicate(null);
            filteredUpToDate.setPredicate(null);
        } else {
            filteredOutdated.setPredicate(row -> matchesFilter(row, filter));
            filteredUpToDate.setPredicate(row -> matchesFilter(row, filter));
        }
    }

    private static boolean matchesFilter(DriverRow row, String filter) {
        String name = row.installed().friendlyName() == null ? "" : row.installed().friendlyName().toLowerCase(java.util.Locale.ROOT);
        // Use live properties (not the immutable installed record) so the
        // filter reflects post-install refreshFrom() version changes.
        String current = row.currentVersionProperty().get() == null ? "" : row.currentVersionProperty().get().toLowerCase(java.util.Locale.ROOT);
        String available = row.availableVersionProperty().get() == null ? "" : row.availableVersionProperty().get().toLowerCase(java.util.Locale.ROOT);
        String src = row.sourceProperty().get() == null ? "" : row.sourceProperty().get().toLowerCase(java.util.Locale.ROOT);
        return name.contains(filter) || current.contains(filter) || available.contains(filter) || src.contains(filter);
    }

    private void showUpdateHistory() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(com.sbtools.util.UiText.label("Update history"));
        dialog.setHeaderText(com.sbtools.util.UiText.label("Driver update history"));

        ListView<UpdateHistoryStore.UpdateEntry> listView = new ListView<>();
        listView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(UpdateHistoryStore.UpdateEntry item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    String status = item.success() ? "✓" : "✗";
                    String text = status + " " + item.deviceName() + " " + item.oldVersion()
                            + " → " + item.newVersion() + " (" + item.source() + ")";
                    if (item.detail() != null && !item.detail().isBlank()) {
                        text += " — " + item.detail();
                    }
                    setText(text);
                    if (item.timestamp() != null) {
                        setTooltip(new Tooltip(item.timestamp().toString()));
                    } else {
                        setTooltip(null);
                    }
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
        dialog.setTitle(com.sbtools.util.UiText.label("Driver details"));
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
        // Problem-device highlight: non-OK status (e.g. Code 28) shown in red with guidance.
        if (row.isProblematic()) {
            Label statusVal = new Label((row.installed().status() == null ? "Unknown" : row.installed().status())
                    + " — device reports a problem (check Device Manager). An update may help, but hardware issues can persist.");
            statusVal.setWrapText(true);
            statusVal.setMaxWidth(400);
            statusVal.setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
            grid.add(new Label("Status:"), 0, r);
            grid.add(statusVal, 1, r);
            r++;
        } else {
            addDetailRow(grid, r++, "Status:", row.installed().status());
        }
        if (row.isRebootPending()) {
            Label rebootVal = new Label("Yes — restart Windows to complete the installed update.");
            rebootVal.setStyle("-fx-text-fill: #bd93f9; -fx-font-weight: bold;");
            rebootVal.setWrapText(true);
            rebootVal.setMaxWidth(400);
            grid.add(new Label("Reboot Pending:"), 0, r);
            grid.add(rebootVal, 1, r);
            r++;
        }
        if (row.installed().releaseDate() != null) {
            addDetailRow(grid, r++, "Release Date:", row.installed().releaseDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
        addDetailRow(grid, r++, "Current Version:", row.currentVersionProperty().get());

        if (row.hasUpdate()) {
            DriverUpdateCandidate c = row.candidate();
            addDetailRow(grid, r++, "Available Version:", c.availableVersion());
            addDetailRow(grid, r++, "Source:", c.source());
            addDetailRow(grid, r++, "Severity:", c.severity() != null ? c.severity().name() : "Unknown");
            // Match explanation: why this catalog entry was chosen (HWID specificity, confidence).
            // Tab-level cached DB (mtime-checked): a full load() per dialog
            // open froze the FX thread on disk+parse.
            try {
                DriverCatalogDatabase db = detailsCatalogDatabase();
                String why = db.describeMatch(row.installed());
                if (why != null && !why.isBlank()) {
                    addDetailRow(grid, r++, "Why this match:", why + " · catalog: " + db.sourceLabel());
                }
            } catch (Exception ignored) {
            }

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
                            .append(" (").append(h.source()).append(")");
                    if (h.detail() != null && !h.detail().isBlank()) {
                        sb.append(" — ").append(h.detail());
                    }
                    sb.append("\n");
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

    private void showComparisonDialog(DriverRow row) {
        DriverUpdateCandidate c = row.candidate();
        if (c == null) return;

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(com.sbtools.util.UiText.label("Driver comparison"));
        dialog.setHeaderText(row.installed().friendlyName());

        GridPane grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(8);
        grid.setPadding(new Insets(16));

        Label currentHeader = new Label("Current");
        currentHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14;");
        Label availableHeader = new Label("Available");
        availableHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14; -fx-text-fill: #50fa7b;");
        grid.add(currentHeader, 0, 0);
        grid.add(availableHeader, 1, 0);

        // Live property, not the immutable installed record: post-install
        // refreshFrom() updates Current without a full rescan.
        addComparisonRow(grid, 1, "Version:", row.currentVersionProperty().get(), c.availableVersion());
        addComparisonRow(grid, 2, "Provider:", row.installed().provider(), c.source());
        addComparisonRow(grid, 3, "Release Date:",
                row.installed().releaseDate() != null
                        ? row.installed().releaseDate().format(DateTimeFormatter.ISO_LOCAL_DATE) : "\u2014",
                "\u2014");

        int r = 4;
        if (c.severity() != null) {
            Label sevLabel = new Label(c.severity().name());
            sevLabel.setStyle(switch (c.severity()) {
                case CRITICAL -> "-fx-text-fill: #ff5555; -fx-font-weight: bold;";
                case IMPORTANT -> "-fx-text-fill: #ffb86c; -fx-font-weight: bold;";
                case RECOMMENDED -> "-fx-text-fill: #f1fa8c;";
                case OPTIONAL -> "-fx-text-fill: #6272a4;";
                default -> "";
            });
            grid.add(new Label("Severity:"), 0, r);
            grid.add(sevLabel, 1, r);
            r++;
        }

        if (c.description() != null && !c.description().isBlank()) {
            Label descLabel = new Label(c.description());
            descLabel.setWrapText(true);
            descLabel.setMaxWidth(400);
            grid.add(new Label("Description:"), 0, r);
            grid.add(descLabel, 1, r);
            r++;
        }

        DriverHealthService.DriverHealthScore hs = row.getHealthScore();
        if (hs != null) {
            Label scoreLabel = new Label(hs.score() + "/100 \u2014 " + hs.getLabel());
            scoreLabel.setStyle(hs.getColorStyle());
            grid.add(new Label("Health Score:"), 0, r);
            grid.add(scoreLabel, 1, r);
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
        }

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.setResizable(true);
        dialog.showAndWait();
    }

    private static void addComparisonRow(GridPane grid, int row, String label, String current, String available) {
        Label curLabel = new Label(current != null ? current : "\u2014");
        curLabel.setWrapText(true);
        curLabel.setMaxWidth(200);
        Label arrowLabel = new Label("\u2192");
        Label availLabel = new Label(available != null ? available : "\u2014");
        availLabel.setWrapText(true);
        availLabel.setMaxWidth(200);
        availLabel.setStyle("-fx-text-fill: #50fa7b;");
        HBox rowBox = new HBox(12, curLabel, arrowLabel, availLabel);
        rowBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(new Label(label), 0, row);
        grid.add(rowBox, 1, row);
    }

    private void startBatchUpdate() {
        if (!requireAdminFresh()) return;
        if (busy.get()) {
            new Alert(Alert.AlertType.INFORMATION, "Another operation is running. Please wait.").showAndWait();
            return;
        }
        List<DriverRow> snapshot = new ArrayList<>(outdatedRows.stream().filter(r -> !r.isRebootPending()).toList());
        if (snapshot.isEmpty() && !outdatedRows.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "All outdated drivers are awaiting restart (REBOOT). Restart Windows first.").showAndWait();
            return;
        }
        int count = snapshot.size();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update all " + count + " outdated driver(s)? This may take several minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle(com.sbtools.util.UiText.label("Batch update"));
        confirm.setHeaderText(com.sbtools.util.UiText.label("Update all drivers"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        installBatchUpdates(snapshot);
    }

    private void startBatchUpdateSelected() {
        if (!requireAdminFresh()) return;
        if (busy.get()) {
            new Alert(Alert.AlertType.INFORMATION, "Another operation is running. Please wait.").showAndWait();
            return;
        }
        List<DriverRow> selected = outdatedRows.stream().filter(r -> r.isSelected() && !r.isRebootPending()).toList();
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No drivers selected.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Update " + selected.size() + " selected driver(s)? This may take several minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle(com.sbtools.util.UiText.label("Batch update"));
        confirm.setHeaderText(com.sbtools.util.UiText.label("Update selected drivers"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        installBatchUpdates(selected);
    }

    private void installBatchUpdates(List<DriverRow> rows) {
        final DriversOperationGate.Lease capturedBatchLease = tryAcquireOperation(DriversOperationGate.Owner.INSTALL);
        if (capturedBatchLease == null) return;
        AppSettings batchSettings = settingsStore.load();
        List<String> batchPreflightWarnings = collectBatchPreflightWarnings(rows, batchSettings);
        if (!batchPreflightWarnings.isEmpty()
                && !confirmBatchPreflightWarnings(batchPreflightWarnings)) {
            releaseOperation(capturedBatchLease);
            updateControlStates();
            return;
        }
        installCancelFlag.set(false);
        installService.resetCancellation();
        scanButton.setDisable(true);
        updateAllButton.setDisable(true);
        updateSelectedButton.setDisable(true);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressBar.setProgress(0);
        progressLabel.setText("0%");
        stopInstallButton.setVisible(true);
        stopInstallButton.setManaged(true);
        stopInstallButton.setDisable(false);

        // Snapshot visible action cells NOW on the FX thread: the background
        // loop registers cells via runLater *after* starting each install, so
        // early download progress found an empty map and the row stayed IDLE.
        // (The per-row runLater inside the loop stays for rows scrolled into
        // view mid-batch.)
        try {
            for (DriverRow r : rows) {
                DriverActionCell cell = lookupActionCell(r);
                if (cell != null) {
                    installCells.put(r, cell);
                }
            }
        } catch (Exception lookupEx) {
            AppLogger.warning("Batch cell snapshot failed: " + lookupEx.getMessage());
        }

        installFuture = installExecutor.submit(() -> {
            int verifiedCount = 0;
            int unverifiedCount = 0;
            int failed = 0;
            int skipped = 0;
            int rebootNeeded = 0;
            List<String> failureDetails = new ArrayList<>();
            List<PendingBatchVerify> pendingVerify = new ArrayList<>();
            try {
                int total = rows.size();
                AppSettings settings = settingsStore.load();
                for (int i = 0; i < total; i++) {
                    if (installCancelFlag.get() || installService.isCancelled()) {
                        skipped += (total - i);
                        failureDetails.add("Cancelled by user.");
                        break;
                    }
                    DriverRow row = rows.get(i);
                    if (row.candidate() == null) {
                        skipped++;
                        continue;
                    }
                    DriverUpdateCandidate c = row.candidate();

                    if (DriverPreflightService.needsManualDownload(c)) {
                        skipped++;
                        failureDetails.add(row.installed().friendlyName() + ": manual download required (" + c.source() + ")");
                        continue;
                    }

                    // Batch pre-flight: block on errors (disk/admin), log warnings without
                    // modal dialogs (would spam for N drivers). Warnings are appended to history detail.
                    String preflightWarnings = "";
                    try {
                        DriverPreflightService.PreflightResult pre =
                                DriverPreflightService.check(c, settings, rebootStore);
                        if (!pre.ok()) {
                            skipped++;
                            failureDetails.add(row.installed().friendlyName() + ": pre-flight blocked — " + pre.blockReason());
                            recordHistory(row, c, false, "pre-flight blocked: " + pre.blockReason());
                            continue;
                        }
                        if (pre.hasWarnings()) {
                            preflightWarnings = String.join("; ", pre.warnings());
                            AppLogger.warning("Pre-flight warnings for " + row.installed().friendlyName() + ": " + preflightWarnings);
                        }
                    } catch (Exception preEx) {
                        AppLogger.warning("Pre-flight check failed for " + row.installed().friendlyName() + ": " + preEx.getMessage());
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
                        installService.setProgressCallback((bytesReceived, totalBytes, fraction) -> {
                            String sizeText = totalBytes > 0
                                    ? formatBytes(bytesReceived) + " / " + formatBytes(totalBytes)
                                    : formatBytes(bytesReceived);
                            Platform.runLater(() -> {
                                statusLabel.setText("Installing " + (idx + 1) + "/" + total
                                        + ": " + row.installed().friendlyName() + " \u2014 " + sizeText);
                                DriverActionCell cell = installCells.get(row);
                                if (cell != null) {
                                    // Pass through untouched (single-install parity):
                                    // -1 renders indeterminate instead of a frozen 0%.
                                    cell.setDownloading(sizeText, fraction);
                                }
                            });
                        });
                        installService.setStatusCallback(status -> Platform.runLater(() -> {
                            statusLabel.setText("Installing " + (idx + 1) + "/" + total + ": "
                                    + row.installed().friendlyName() + " \u2014 " + status);
                            DriverActionCell cell = installCells.get(row);
                            if (cell != null) {
                                cell.setInstalling();
                            }
                        }));
                        Platform.runLater(() -> {
                            DriverActionCell cell = lookupActionCell(row);
                            if (cell != null) {
                                installCells.put(row, cell);
                            }
                        });
                        boolean allowRestoreOnly = com.sbtools.backup.DriverBackupService
                                .backupSupportIssue(c.installed()) != null;
                        DriverInstallService.InstallResult result = installService.install(
                                c, settings, allowRestoreOnly);
                        // Invalidate caches per-install so next Dashboard scan is fresh
                        try {
                            catalog.clearWindowsUpdateCache();
                            if (c.source() != null && !c.source().isBlank()) catalog.clearCacheForProvider(c.source());
                        } catch (Exception ex) { AppLogger.warning("Cache clear failed: " + ex.getMessage()); }
                        // Stop Install during the 900s pnputil/installer phase:
                        // a post-cancel success must not count as succeeded nor
                        // move the row to Up-to-Date (single-install parity).
                        boolean cancelledAfterInstall = installCancelFlag.get() || installService.isCancelled();
                        if (cancelledAfterInstall) {
                            skipped++;
                            int remaining = total - idx - 1;
                            if (remaining > 0) skipped += remaining;
                            failureDetails.add(row.installed().friendlyName() + ": cancelled by user");
                            recordHistory(row, c, false, "cancelled by user");
                            AppLogger.warning("Batch install completed after cancel request for "
                                    + row.installed().friendlyName() + " — treated as cancelled");
                            Platform.runLater(() -> {
                                DriverActionCell cell = installCells.remove(row);
                                if (cell != null) {
                                    cell.setIdle();
                                }
                            });
                            break;
                        }
                        if (result.installed()) {
                            boolean needsReboot = result.rebootRequired();
                            if (needsReboot) {
                                rebootNeeded++;
                                verifiedCount++;
                                rebootStore.addPending(row.installed().deviceId(), row.installed().friendlyName());
                                Platform.runLater(() -> {
                                    row.setSelected(false);
                                    row.setRebootPending(true);
                                    if (!outdatedRows.contains(row)) {
                                        outdatedRows.add(row);
                                    }
                                    upToDateRows.remove(row);
                                    outdatedTable.refresh();
                                    upToDateTable.refresh();
                                    DriverActionCell cell = installCells.remove(row);
                                    if (cell != null) {
                                        cell.setIdle();
                                    }
                                });
                                recordHistory(row, c, true,
                                        "reboot-required; rollback kept"
                                                + (preflightWarnings.isBlank() ? "" : "; warnings: " + preflightWarnings));
                            } else {
                                String previousVersion = c.installed().driverVersion() != null
                                        ? c.installed().driverVersion() : "";
                                pendingVerify.add(new PendingBatchVerify(row, c, previousVersion, preflightWarnings));
                                Platform.runLater(() -> {
                                    row.setSelected(false);
                                    DriverActionCell cell = installCells.remove(row);
                                    if (cell != null) {
                                        cell.setIdle();
                                    }
                                });
                            }
                        } else {
                            failed++;
                            failureDetails.add(row.installed().friendlyName() + ": " + result.message());
                            recordHistory(row, c, false,
                                    result.message() + (preflightWarnings.isBlank() ? "" : "; warnings: " + preflightWarnings));
                            Platform.runLater(() -> {
                                DriverActionCell cell = installCells.remove(row);
                                if (cell != null) {
                                    cell.setIdle();
                                }
                            });
                        }
                    } catch (java.util.concurrent.CancellationException cex) {
                        installCancelFlag.set(true);
                        skipped++;
                        failureDetails.add(row.installed().friendlyName() + ": cancelled by user");
                        AppLogger.warning("Batch install cancelled at " + row.installed().friendlyName());
                        Platform.runLater(() -> {
                            DriverActionCell cell = installCells.remove(row);
                            if (cell != null) {
                                cell.setIdle();
                            }
                        });
                        break;
                    } catch (Exception ex) {
                        if (installCancelFlag.get() || installService.isCancelled()) {
                            skipped++;
                            failureDetails.add(row.installed().friendlyName() + ": cancelled by user");
                            break;
                        }
                        failed++;
                        failureDetails.add(row.installed().friendlyName() + ": exception " + ex.getMessage());
                        AppLogger.warning("Batch install failed for " + row.installed().friendlyName() + ": " + ex.getMessage());
                        Platform.runLater(() -> {
                            DriverActionCell cell = installCells.remove(row);
                            if (cell != null) {
                                cell.setIdle();
                            }
                        });
                    } catch (Throwable t) {
                        // Isolate per-driver Errors (e.g. StackOverflow from a bad
                        // package) so one driver cannot wedge the whole batch busy.
                        failed++;
                        failureDetails.add(row.installed().friendlyName() + ": error " + t);
                        AppLogger.warning("Batch install error for " + row.installed().friendlyName() + ": " + t);
                        Platform.runLater(() -> {
                            DriverActionCell cell = installCells.remove(row);
                            if (cell != null) {
                                cell.setIdle();
                            }
                        });
                    }
                }
            } catch (Exception ex) {
                AppLogger.warning("Batch install initialization failed: " + ex.getMessage());
                failed += rows.size() - verifiedCount - unverifiedCount - skipped;
            } catch (Throwable t) {
                AppLogger.warning("Batch install failed with error: " + t);
                failureDetails.add("Batch aborted: " + t);
                failed += rows.size() - verifiedCount - unverifiedCount - skipped;
            } finally {
                installService.setProgressCallback(null);
                installService.setStatusCallback(null);
            }
            List<BatchVerifyResult> verifyResults = new ArrayList<>();
            if (!pendingVerify.isEmpty()) {
                Platform.runLater(() -> statusLabel.setText("Verifying installed versions\u2026"));
                try {
                    List<DriverPostInstallVerifier.VerifyRequest> verifyRequests = new ArrayList<>();
                    for (PendingBatchVerify pv : pendingVerify) {
                        verifyRequests.add(new DriverPostInstallVerifier.VerifyRequest(
                                pv.row().installed().deviceId(),
                                pv.candidate().availableVersion(),
                                pv.previousVersion(),
                                rebootStore.isPending(pv.row().installed().deviceId())));
                    }
                    Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                            verifyRequests,
                            () -> {
                                try {
                                    return scanInstalledByDeviceKey();
                                } catch (Exception scanEx) {
                                    AppLogger.warning("Post-batch scan failed: " + scanEx.getMessage());
                                    return Map.of();
                                }
                            },
                            installCancelFlag,
                            ms -> {
                                if (installCancelFlag.get()) {
                                    throw new InterruptedException("cancelled");
                                }
                                Thread.sleep(ms);
                            },
                            attempt -> Platform.runLater(() -> statusLabel.setText(
                                    attempt <= 1
                                            ? "Verifying installed versions\u2026"
                                            : "Waiting for Windows to apply drivers (check " + attempt + ")\u2026")));
                    for (PendingBatchVerify pv : pendingVerify) {
                        String key = DriverScanService.normalizeDeviceKey(pv.row().installed().deviceId());
                        DriverPostInstallVerifier.Outcome outcome = outcomes.get(key);
                        if (outcome == null) {
                            outcome = new DriverPostInstallVerifier.Outcome(
                                    null,
                                    DriverVersionVerifier.Verdict.INCONCLUSIVE,
                                    0,
                                    "no scan result");
                        }
                        verifyResults.add(new BatchVerifyResult(pv, outcome));
                        DriverVersionVerifier.Verdict verdict = outcome.verdict();
                        String freshVer = DriverInstallReconciliation.freshVersion(outcome.fresh());
                        if (DriverInstallReconciliation.historySuccess(verdict)) {
                            verifiedCount++;
                        } else {
                            unverifiedCount++;
                            failureDetails.add(pv.row().installed().friendlyName() + ": "
                                    + DriverInstallReconciliation.userStatusLine(
                                    pv.row().installed().friendlyName(),
                                    verdict,
                                    pv.candidate().availableVersion(),
                                    freshVer,
                                    outcome.readAttempts(),
                                    outcome.diagnostic()));
                        }
                        String detail = DriverInstallReconciliation.historyDetail(
                                verdict, freshVer, outcome.readAttempts(), outcome.diagnostic());
                        if (!pv.preflightWarnings().isBlank()) {
                            detail += "; warnings: " + pv.preflightWarnings();
                        }
                        recordHistory(pv.row(), pv.candidate(),
                                DriverInstallReconciliation.historySuccess(verdict), detail);
                    }
                    final List<BatchVerifyResult> fxResults = new ArrayList<>(verifyResults);
                    Platform.runLater(() -> {
                        if (disposed) {
                            return;
                        }
                        for (BatchVerifyResult br : fxResults) {
                            applyBatchInstallVerdict(
                                    br.pending().row(),
                                    br.pending().candidate(),
                                    br.outcome());
                        }
                        outdatedTable.refresh();
                        upToDateTable.refresh();
                    });
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    AppLogger.info("Post-batch verification cancelled");
                } catch (Throwable e) {
                    AppLogger.debug("Post-batch verification failed: " + e);
                    unverifiedCount += pendingVerify.size();
                    for (PendingBatchVerify pv : pendingVerify) {
                        failureDetails.add(pv.row().installed().friendlyName()
                                + ": verification scan failed");
                        recordHistory(pv.row(), pv.candidate(), false, "post-install verify: scan failed");
                    }
                }
            }
            final int s = verifiedCount;
            final int u = unverifiedCount;
            final int f = failed;
            final int k = skipped;
            final int r = rebootNeeded;
            final List<String> failures = new ArrayList<>(failureDetails);
            Platform.runLater(() -> {
                if (!isLeaseCurrent(capturedBatchLease)) {
                    return;
                }
                installFuture = null;
                releaseOperation(capturedBatchLease);
                stopInstallButton.setVisible(false);
                stopInstallButton.setManaged(false);
                updateControlStates();
                progressBar.setProgress(1.0);
                progressLabel.setText("100%");
                progressBar.setVisible(false);
                progressLabel.setVisible(false);
                String summary = "Batch update complete: " + s + " verified, " + u + " not verified, " + f + " failed";
                if (k > 0) summary += ", " + k + " skipped";
                if (r > 0) summary += ", " + r + " reboot pending";
                statusLabel.setText(summary + ".");
                if (r > 0) {
                    statusLabel.setText(summary + ". RESTART REQUIRED to finish " + r + " update(s) — Dashboard will show pending until reboot.");
                }
                // Ensure caches are cleared for next Dashboard scan even if all failed
                try { catalog.clearWindowsUpdateCache(); } catch (Exception ex) { AppLogger.warning("Cache clear failed: " + ex.getMessage()); }
                // Detailed dialog with per-driver failures
                Dialog<ButtonType> dlg = new Dialog<>();
                dlg.setTitle(com.sbtools.util.UiText.label("Batch update result"));
                dlg.setHeaderText("Batch update complete");
                VBox box = new VBox(8);
                box.setPadding(new Insets(12));
                Label summaryLabel = new Label(s + " verified, " + u + " not verified, " + f + " failed, " + k + " skipped"
                        + (r > 0 ? ", " + r + " reboot pending" : ""));
                summaryLabel.setWrapText(true);
                box.getChildren().add(summaryLabel);
                if (r > 0) {
                    Label rebootLabel = new Label("⚠ " + r + " driver(s) require a restart to complete. Dashboard will continue to show them as outdated until you reboot. Windows applet may already show them as done.");
                    rebootLabel.setWrapText(true);
                    rebootLabel.setStyle("-fx-text-fill: #ffb86c; -fx-font-weight: bold;");
                    box.getChildren().add(rebootLabel);
                }
                if (!failures.isEmpty()) {
                    Label failHeader = new Label("Failures / skipped:");
                    failHeader.setStyle("-fx-font-weight: bold;");
                    box.getChildren().add(failHeader);
                    // Cap per-row text: uncapped installer/pnputil/WU output can
                    // put megabytes into ListView rows and freeze the dialog.
                    java.util.List<String> shown = failures.stream()
                            .map(msg -> msg != null && msg.length() > 1500 ? msg.substring(0, 1500) + "… [truncated]" : msg)
                            .toList();
                    ListView<String> lv = new ListView<>(FXCollections.observableArrayList(shown));
                    lv.setPrefHeight(Math.min(200, failures.size() * 24 + 10));
                    box.getChildren().add(lv);
                }
                Label hint = new Label("Tip: Dashboard next scan will refresh from Windows Update (cache cleared).");
                hint.setWrapText(true);
                hint.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11;");
                box.getChildren().add(hint);
                dlg.getDialogPane().setContent(box);
                dlg.getDialogPane().getButtonTypes().add(ButtonType.OK);
                dlg.getDialogPane().setPrefWidth(560);
                dlg.showAndWait();
                filterTables();
            });
        });
    }

    private void startBackupAll() {
        if (!requireAdminFresh()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Back up all currently installed drivers? This may take a few minutes.",
                ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle(com.sbtools.util.UiText.label("Driver backup"));
        confirm.setHeaderText(com.sbtools.util.UiText.label("Backup all drivers"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }

        if (busy.get()) {
            setStatus("Busy: finish the running operation before backup.");
            return;
        }
        final DriversOperationGate.Lease capturedBackupLease = tryAcquireOperation(DriversOperationGate.Owner.BACKUP);
        if (capturedBackupLease == null) return;
        final CancellationToken token = new CancellationToken();
        backupToken = token;
        backupCancelFlag.set(false);
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

        backupFuture = backupExecutor.submit(() -> {
            int succeeded = 0;
            int failed = 0;
            int skipped = 0;
            try {
                List<InstalledDriver> installed = scanService.scanInstalled();
                int total = installed == null ? 0 : installed.size();
                AppSettings settings = settingsStore.load();
                for (int i = 0; i < total; i++) {
                    if (token.isCancelled() || backupCancelFlag.get()) {
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
                        backupService.backupBeforeUpdate(driver, settings, backupCancelFlag);
                        succeeded++;
                    } catch (java.util.concurrent.CancellationException cex) {
                        skipped = total - i;
                        break;
                    } catch (Exception ex) {
                        if (token.isCancelled() || backupCancelFlag.get()) {
                            skipped = total - i;
                            break;
                        }
                        failed++;
                        AppLogger.warning("Backup failed for " + driver.friendlyName() + ": " + ex.getMessage());
                    }
                }
            } catch (java.util.concurrent.CancellationException | InterruptedException cancelEx) {
                // Stop Backup during the pre-loop enumeration (scanInstalled
                // is a 90s PowerShell call): report cancelled, not failed.
                if (cancelEx instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                final int cs = succeeded;
                final int cf = failed;
                Platform.runLater(() -> {
                    if (isLeaseCurrent(capturedBackupLease)) {
                        releaseOperation(capturedBackupLease);
                    }
                    backupFuture = null;
                    updateControlStates();
                    stopBackupButton.setVisible(false);
                    stopBackupButton.setManaged(false);
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                    String summary = "Backup cancelled: " + cs + " backed up, " + cf + " failed";
                    statusLabel.setText(summary + ".");
                    new Alert(Alert.AlertType.INFORMATION, summary + ".").showAndWait();
                });
                return;
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    if (isLeaseCurrent(capturedBackupLease)) {
                        releaseOperation(capturedBackupLease);
                    }
                    backupFuture = null;
                    updateControlStates();
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
                if (isLeaseCurrent(capturedBackupLease)) {
                    releaseOperation(capturedBackupLease);
                }
                backupFuture = null;
                updateControlStates();
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
        backupCancelFlag.set(true);
        try { if (backupFuture != null) backupFuture.cancel(true); } catch (Exception ignored) {}
        stopBackupButton.setDisable(true);
        setStatus("Cancelling backup\u2026");
    }

    private void applyBatchInstallVerdict(DriverRow row,
                                          DriverUpdateCandidate c,
                                          DriverPostInstallVerifier.Outcome outcome) {
        if (disposed) {
            return;
        }
        InstalledDriver fresh = outcome.fresh();
        DriverVersionVerifier.Verdict verdict = outcome.verdict();
        if (fresh != null) {
            row.refreshFrom(fresh);
        }
        String deviceId = row.installed().deviceId();
        String friendlyName = row.installed().friendlyName();
        if (verdict == DriverVersionVerifier.Verdict.VERIFIED) {
            rebootStore.clearPending(deviceId);
            row.setRebootPending(false);
            row.setCandidate(null);
            outdatedRows.remove(row);
            if (!upToDateRows.contains(row)) {
                upToDateRows.add(row);
            }
        } else if (verdict == DriverVersionVerifier.Verdict.NEEDS_REBOOT) {
            rebootStore.addPending(deviceId, friendlyName);
            row.setRebootPending(true);
            if (!outdatedRows.contains(row)) {
                outdatedRows.add(row);
            }
            upToDateRows.remove(row);
        } else {
            row.setCandidate(c);
            row.setRebootPending(false);
            if (!outdatedRows.contains(row)) {
                outdatedRows.add(row);
            }
            upToDateRows.remove(row);
        }
    }

    private record PendingBatchVerify(DriverRow row,
                                      DriverUpdateCandidate candidate,
                                      String previousVersion,
                                      String preflightWarnings) {
    }

    private record BatchVerifyResult(PendingBatchVerify pending,
                                     DriverPostInstallVerifier.Outcome outcome) {
    }

    /**
     * Shuts down background executor services. Call when the tab is removed
     * or the application is shutting down to avoid leaked threads.
     */
    public void dispose() {
        disposed = true;
        CancellationToken scan = scanToken;
        if (scan != null) scan.cancel();
        CancellationToken backup = backupToken;
        if (backup != null) backup.cancel();
        installService.cancel();
        installCancelFlag.set(true);
        backupCancelFlag.set(true);
        try { if (scanFuture != null) scanFuture.cancel(true); } catch (Exception ignored) {}
        try { if (installFuture != null) installFuture.cancel(true); } catch (Exception ignored) {}
        try { if (backupFuture != null) backupFuture.cancel(true); } catch (Exception ignored) {}
        shutdownExecutor(scanExecutor);
        shutdownExecutor(installExecutor);
        shutdownExecutor(backupExecutor);
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
