package com.sbtools.ui;

import com.sbtools.backup.DriverBackupService;
import com.sbtools.backup.RegistryBackupRow;
import com.sbtools.backup.RestoreRow;
import com.sbtools.backup.SystemRestoreRow;
import com.sbtools.backup.SystemRestoreService;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.ProcessManager;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class BackupRestoreTabView extends BorderPane {

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final TabPane tabPane = new TabPane();
    private final BooleanProperty registryBusy = new SimpleBooleanProperty(false);
    private final BooleanProperty rollbackRefreshBusy = new SimpleBooleanProperty(false);
    private final AtomicInteger rollbackRefreshGen = new AtomicInteger(0);

    private DriverBackupService rollbackBackupService;
    private ObservableList<RestoreRow> rollbackRows;
    private Label rollbackStatusLabel;
    private Label rollbackWarningLabel;
    private ProgressIndicator rollbackSpinner;
    private TableView<RestoreRow> rollbackTable;
    private Button rollbackDetailsButton;
    private Button rollbackOpenFolderButton;
    private Button rollbackVerifyButton;
    private Button rollbackRepairButton;

    public BackupRestoreTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        Tab rollbackTab = buildRollbackTab();
        Tab systemTab = buildSystemRestoreTab();
        Tab registryTab = buildRegistryBackupTab();

        tabPane.getTabs().addAll(rollbackTab, systemTab, registryTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        setCenter(tabPane);
    }

    // ── Rollback drivers tab ───────────────────────────────────────────────

    private Tab buildRollbackTab() {
        rollbackBackupService = new DriverBackupService();
        rollbackRows = FXCollections.observableArrayList();
        rollbackStatusLabel = new Label("Driver backups appear here. Backups are created automatically before driver updates.");
        rollbackWarningLabel = new Label();
        rollbackWarningLabel.setStyle("-fx-text-fill: #f0ad4e;");
        rollbackWarningLabel.setVisible(false);
        rollbackWarningLabel.setWrapText(true);
        Button refreshButton = new Button("Refresh");
        Button deleteAllButton = UIButton.danger("Delete All");
        rollbackDetailsButton = new Button("Details");
        rollbackOpenFolderButton = new Button("Open Folder");
        rollbackVerifyButton = new Button("Verify");
        rollbackRepairButton = UIButton.secondary("Repair");
        TextField searchField = new TextField();
        searchField.setPromptText("Search backups...");
        rollbackSpinner = new ProgressIndicator();
        rollbackSpinner.setVisible(false);
        rollbackSpinner.setMaxSize(20, 20);

        Tooltip.install(refreshButton, new Tooltip("Reload the backup list from disk"));
        Tooltip.install(deleteAllButton, new Tooltip("Remove all driver backups permanently (two-step confirmation)"));
        Tooltip.install(rollbackDetailsButton, new Tooltip("Show details for the selected backup"));
        Tooltip.install(rollbackOpenFolderButton, new Tooltip("Open the backup folder in Explorer"));
        Tooltip.install(rollbackVerifyButton, new Tooltip("Re-check health of all backups on disk"));
        Tooltip.install(rollbackRepairButton, new Tooltip("List backups missing on disk and remove their stale index entries"));

        refreshButton.setOnAction(e -> refreshRollback());
        deleteAllButton.setOnAction(e -> deleteAllBackups());
        rollbackDetailsButton.setOnAction(e -> showRollbackDetails());
        rollbackOpenFolderButton.setOnAction(e -> openRollbackFolder());
        rollbackVerifyButton.setOnAction(e -> verifyAllRollbacks());
        rollbackRepairButton.setOnAction(e -> repairStaleBackups());

        // Disable buttons while busy (revert/delete) and keep table cells in sync
        refreshButton.disableProperty().bind(busy.or(rollbackRefreshBusy));
        deleteAllButton.disableProperty().bind(busy);
        rollbackVerifyButton.disableProperty().bind(busy);
        rollbackRepairButton.disableProperty().bind(busy);
        rollbackDetailsButton.setDisable(true);
        rollbackOpenFolderButton.setDisable(true);
        busy.addListener((obs, oldVal, newVal) -> {
            if (rollbackTable != null) rollbackTable.refresh();
            updateRollbackSelectionButtons();
        });

        HBox top = new HBox(12, refreshButton, rollbackVerifyButton, rollbackDetailsButton,
                rollbackOpenFolderButton, rollbackRepairButton, deleteAllButton,
                rollbackSpinner, searchField);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 4, 16));
        top.getStyleClass().add("toolbar");

        VBox statusBox = new VBox(2, rollbackStatusLabel, rollbackWarningLabel);
        statusBox.setPadding(new Insets(0, 16, 8, 16));
        VBox topBox = new VBox(top, statusBox);

        FilteredList<RestoreRow> filteredList = new FilteredList<>(rollbackRows);
        SortedList<RestoreRow> sortedList = new SortedList<>(filteredList);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredList.setPredicate(row -> newVal == null || newVal.isBlank() || matches(row, newVal));
        });

        rollbackTable = buildRollbackTable(sortedList);
        sortedList.comparatorProperty().bind(rollbackTable.comparatorProperty());
        rollbackTable.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> updateRollbackSelectionButtons());
        rollbackTable.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                showRollbackDetails();
            }
        });

        BorderPane pane = new BorderPane();
        pane.setTop(topBox);
        pane.setCenter(rollbackTable);

        if (AppPaths.isWindows()) {
            refreshRollback();
        } else {
            // On non-Windows keep disabled regardless of busy
            refreshButton.disableProperty().unbind();
            deleteAllButton.disableProperty().unbind();
            rollbackVerifyButton.disableProperty().unbind();
            rollbackRepairButton.disableProperty().unbind();
            refreshButton.setDisable(true);
            deleteAllButton.setDisable(true);
            rollbackVerifyButton.setDisable(true);
            rollbackRepairButton.setDisable(true);
            rollbackDetailsButton.setDisable(true);
            rollbackOpenFolderButton.setDisable(true);
            rollbackStatusLabel.setText("Driver backup is available on Windows only.");
        }

        Tab tab = UiTab.tab("Rollback drivers");
        tab.setContent(pane);
        return tab;
    }

    private void updateRollbackSelectionButtons() {
        boolean hasSel = rollbackTable != null && rollbackTable.getSelectionModel().getSelectedItem() != null;
        boolean isBusy = busy != null && busy.get();
        boolean win = AppPaths.isWindows();
        if (rollbackDetailsButton != null) {
            rollbackDetailsButton.setDisable(!hasSel);
        }
        if (rollbackOpenFolderButton != null) {
            rollbackOpenFolderButton.setDisable(!hasSel || isBusy || !win);
        }
    }

    private boolean matches(RestoreRow row, String query) {
        String q = query.toLowerCase();
        return row.deviceNameProperty().get().toLowerCase().contains(q)
                || row.versionProperty().get().toLowerCase().contains(q)
                || row.backedUpAtProperty().get().toLowerCase().contains(q)
                || row.statusProperty().get().toLowerCase().contains(q);
    }

    private TableView<RestoreRow> buildRollbackTable(SortedList<RestoreRow> sortedRows) {
        TableView<RestoreRow> table = new TableView<>(sortedRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<RestoreRow, String> deviceCol = UiColumn.of("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());

        TableColumn<RestoreRow, String> versionCol = UiColumn.of("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(100);

        TableColumn<RestoreRow, String> dateCol = UiColumn.of("Backed up");
        dateCol.setCellValueFactory(c -> c.getValue().backedUpAtProperty());
        dateCol.setPrefWidth(140);

        TableColumn<RestoreRow, String> sizeCol = UiColumn.of("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeProperty());
        sizeCol.setPrefWidth(80);

        TableColumn<RestoreRow, String> statusCol = UiColumn.of("Health");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(90);

        TableColumn<RestoreRow, Void> actionCol = UiColumn.of("Action");
        actionCol.setPrefWidth(150);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton revertBtn = UIButton.small("Revert");
            private final UIButton deleteBtn = UIButton.danger("Delete");
            private final HBox box = new HBox(4, revertBtn, deleteBtn);

            {
                Tooltip.install(revertBtn, new Tooltip("Restore the backed-up version of this driver. A restart may be required."));
                Tooltip.install(deleteBtn, new Tooltip("Permanently remove this backup"));
                revertBtn.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        revertRollback(getTableView().getItems().get(idx));
                    }
                });
                deleteBtn.setOnAction(e -> {
                    int idx = getIndex();
                    if (idx >= 0 && idx < getTableView().getItems().size()) {
                        deleteSingleBackup(getTableView().getItems().get(idx));
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    boolean isBusy = busy.get();
                    boolean win = AppPaths.isWindows();
                    RestoreRow row = getIndex() >= 0 && getIndex() < getTableView().getItems().size()
                            ? getTableView().getItems().get(getIndex()) : null;
                    boolean revertOk = row != null && row.isRevertAllowed();
                    revertBtn.setDisable(isBusy || !win || !revertOk);
                    if (row != null && row.healthComputedProperty().get() && !row.isHealthy()) {
                        Tooltip.install(revertBtn, new Tooltip("Cannot revert: backup is "
                                + row.statusProperty().get() + " (" + row.entry().backupFolder() + ")"));
                    }
                    deleteBtn.setDisable(isBusy || !win);
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(deviceCol, versionCol, dateCol, sizeCol, statusCol, actionCol);
        return table;
    }

    private void refreshRollback() {
        final int generation = rollbackRefreshGen.incrementAndGet();
        busy.set(true);
        Platform.runLater(() -> {
            rollbackRefreshBusy.set(true);
            if (rollbackSpinner != null) rollbackSpinner.setVisible(true);
            rollbackStatusLabel.setText("Loading backups...");
            if (rollbackWarningLabel != null) {
                rollbackWarningLabel.setVisible(false);
            }
        });
        AppExecutors.ioPool().execute(() -> {
            try {
                // Single settings load per refresh (was re-loaded per entry inside service).
                com.sbtools.settings.AppSettings cachedSettings = null;
                try {
                    cachedSettings = new com.sbtools.settings.SettingsStore().load();
                } catch (Exception ignored) {
                }
                var entries = rollbackBackupService.listAll();
                long totalBytes = rollbackBackupService.getTotalSize(entries);
                long freeBytes = rollbackBackupService.usableSpaceForBackups();
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList();
                for (var e : entries) {
                    // Filter null/invalid entries already done in service, but double-check
                    if (e == null || e.id() == null) continue;
                    RestoreRow row = new RestoreRow(e);
                    newRows.add(row);
                }
                String sizeStr = RestoreRow.formatFileSize(totalBytes);
                String dirTmp = "";
                try {
                    dirTmp = cachedSettings != null ? cachedSettings.backupDirectory() : "";
                } catch (Exception ignored) {
                }
                if (dirTmp == null || dirTmp.isBlank()) dirTmp = AppPaths.backupsRoot().toString();
                final String dirInfo = dirTmp;
                // Manual-only retention warnings (never auto-delete).
                StringBuilder warn = new StringBuilder();
                if (!entries.isEmpty()) {
                    if (totalBytes > com.sbtools.backup.BackupHealth.WARNING_SIZE_BYTES) {
                        warn.append("Backups use ").append(sizeStr).append(" (>5 GB). Consider deleting old backups manually. ");
                    }
                    if (entries.size() > com.sbtools.backup.BackupHealth.WARNING_COUNT) {
                        warn.append(entries.size()).append(" backups stored (>50). Review old entries. ");
                    }
                    try {
                        long oldCount = entries.stream()
                                .filter(e -> e.createdAt() != null
                                        && com.sbtools.backup.BackupHealth.isOld(e.createdAt(),
                                                com.sbtools.backup.BackupHealth.WARNING_AGE_DAYS))
                                .count();
                        if (oldCount > 0) {
                            warn.append(oldCount).append(" backup(s) older than 90 days. ");
                        }
                    } catch (Exception ignored) {
                    }
                    if (freeBytes >= 0 && freeBytes < com.sbtools.backup.BackupHealth.MIN_FREE_BYTES) {
                        warn.append("Low disk space on backup volume (")
                                .append(RestoreRow.formatFileSize(freeBytes)).append(" free). ");
                    }
                }
                final String warnStr = warn.toString().trim();
                final String freeStr = freeBytes >= 0 ? RestoreRow.formatFileSize(freeBytes) : null;
                Platform.runLater(() -> {
                    if (generation != rollbackRefreshGen.get()) {
                        return;
                    }
                    rollbackRows.setAll(newRows);
                    updateRollbackSelectionButtons();
                    if (entries.isEmpty()) {
                        rollbackStatusLabel.setText("No backups yet. Backups are created automatically before driver updates. (" + dirInfo + ")");
                    } else {
                        String base = entries.size() + " backup(s) available \u2014 " + sizeStr + " total";
                        if (freeStr != null) {
                            base += " (" + freeStr + " free)";
                        }
                        rollbackStatusLabel.setText(base + "  [" + dirInfo + "]");
                    }
                    if (rollbackWarningLabel != null) {
                        if (!warnStr.isBlank()) {
                            rollbackWarningLabel.setText("\u26A0 " + warnStr + " Nothing is deleted automatically.");
                            rollbackWarningLabel.setVisible(true);
                        } else {
                            rollbackWarningLabel.setVisible(false);
                        }
                    }
                });
                RestoreRow.computeAllSizesAsync(newRows).whenComplete((v, ex) -> Platform.runLater(() -> {
                    // Stale generation must not clear busy for a newer refresh.
                    if (generation != rollbackRefreshGen.get()) {
                        return;
                    }
                    try {
                        if (rollbackTable != null) {
                            rollbackTable.refresh();
                        }
                        if (rollbackSpinner != null) {
                            rollbackSpinner.setVisible(false);
                        }
                        rollbackRefreshBusy.set(false);
                    } finally {
                        busy.set(false);
                    }
                }));
            } catch (Exception ex) {
                AppLogger.error("Failed to load backups", ex);
                Platform.runLater(() -> {
                    if (generation != rollbackRefreshGen.get()) {
                        return;
                    }
                    try {
                        rollbackStatusLabel.setText("Failed to load backups: " + ex.getMessage());
                        if (rollbackSpinner != null) rollbackSpinner.setVisible(false);
                        rollbackRefreshBusy.set(false);
                    } finally {
                        busy.set(false);
                    }
                });
            }
        });
    }

    private void revertRollback(RestoreRow row) {
        if (row == null) {
            return;
        }
        // Cached check on FX thread (fast). Fresh elevation is re-verified
        // in the background task before touching pnputil.
        if (adminCheck != null && !adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Reverting drivers requires administrator rights. Please restart as administrator.").showAndWait();
            return;
        }
        // Pre-flight: refuse to revert a backup that is not healthy on disk.
        // The service would throw anyway, but this avoids locking the UI in busy state.
        try {
            com.sbtools.backup.BackupHealth.Stats pre = com.sbtools.backup.BackupHealth.inspect(row.entry().backupFolder());
            if (!com.sbtools.backup.BackupHealth.isHealthy(pre.status())) {
                new Alert(Alert.AlertType.WARNING,
                        "Cannot revert: backup is " + com.sbtools.backup.BackupHealth.statusLabel(pre.status())
                                + ".\n\nFolder: " + row.entry().backupFolder()
                                + "\n\nUse Repair to clean stale entries, or pick a healthy backup.").showAndWait();
                return;
            }
        } catch (Exception ex) {
            new Alert(Alert.AlertType.WARNING,
                    "Cannot verify backup health:\n" + ex.getMessage()).showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Revert driver for:\n" + row.entry().friendlyName()
                        + "\n\nTo version: " + row.entry().version()
                        + "\n\nBacked up: " + row.backedUpAtProperty().get()
                        + "\nHealth: " + row.statusProperty().get() + " (" + row.getInfCount() + " INF file(s))"
                        + "\n\nThis stages the backed-up INF and attempts a non-destructive device restart."
                        + " Windows may still keep the newer driver active until reboot/manual Have-Disk install.");
        confirm.setHeaderText("Revert driver?");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        busy.set(true);
        AppExecutors.ioPool().execute(() -> {
            boolean handoffToRefresh = false;
            try {
                if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                            "Reverting drivers requires administrator rights. Please restart as administrator.").showAndWait());
                    return;
                }
                rollbackBackupService.revert(row.entry());
                // Verify the active driver actually matches the backup
                // version: pnputil stages the old INF but Windows may keep
                // the newer driver bound until reboot/manual rollback.
                String verifyMsg = verifyRevertedVersion(row);
                handoffToRefresh = true;
                Platform.runLater(() -> {
                    if (verifyMsg == null) {
                        new Alert(Alert.AlertType.INFORMATION,
                                "Driver reverted to " + row.entry().version() + " and verified active."
                                        + " Restart if devices do not work correctly.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.WARNING,
                                "Backup staged, but the active driver does not yet match "
                                + row.entry().version() + ".\n\n" + verifyMsg
                                + "\n\nRestart, then use Device Manager → Update driver → Browse → Let me pick → Have Disk"
                                + "\nand point at:\n" + row.entry().backupFolder()).showAndWait();
                    }
                    // refresh owns busy from here — do not clear in mutate finally
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Revert failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                if (!handoffToRefresh) {
                    Platform.runLater(() -> busy.set(false));
                }
            }
        });
    }

    /**
     * Re-scans the device after pnputil restore and reports whether the
     * active version matches the backup. Returns null when verified,
     * otherwise a human-readable explanation (caller shows WARNING).
     */
    private String verifyRevertedVersion(RestoreRow row) {
        try {
            String deviceId = row.entry().deviceId();
            if (deviceId == null || deviceId.isBlank()) {
                return "Device ID not recorded for this backup — staged only, verify manually in Device Manager.";
            }
            com.sbtools.drivers.DriverScanService scanner = new com.sbtools.drivers.DriverScanService();
            com.sbtools.drivers.model.InstalledDriver fresh =
                    scanner.scanSingleDriver(deviceId);
            if (fresh == null) return "Device no longer found after restore.";
            String active = fresh.driverVersion() == null ? "" : fresh.driverVersion().trim();
            String expected = row.entry().version() == null ? "" : row.entry().version().trim();
            if (!expected.isBlank() && com.sbtools.util.VersionCompare.compare(active, expected) == 0) {
                return null;
            }
            return "Active version is " + (active.isBlank() ? "unknown" : active)
                    + ", expected " + (expected.isBlank() ? "backup version" : expected) + ".";
        } catch (Exception ex) {
            AppLogger.warning("Post-revert verification failed: " + ex.getMessage());
            return "Could not verify active version (" + ex.getMessage() + ").";
        }
    }

    private void deleteAllBackups() {
        if (!canDeleteAllDriverBackups()) {
            showDriverDeleteRequiresElevation();
            return;
        }
        int count = rollbackRows != null ? rollbackRows.size() : 0;
        String dirInfo = "";
        try {
            dirInfo = new com.sbtools.settings.SettingsStore().load().backupDirectory();
        } catch (Exception ignored) {
        }
        if (dirInfo == null || dirInfo.isBlank()) {
            dirInfo = AppPaths.backupsRoot().toString();
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete all backups?");
        confirm.setHeaderText("Delete all " + count + " driver backup(s)?");
        confirm.setContentText("This will permanently remove all backup data.\n\nLocation:\n" + dirInfo
                + "\n\nAfter this, driver rollback will NOT be possible.\nNothing will be deleted automatically — this is your explicit choice.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        // Two-step hardening for a destructive, irreversible action.
        Alert finalConfirm = new Alert(Alert.AlertType.CONFIRMATION);
        finalConfirm.setTitle("Final confirmation");
        finalConfirm.setHeaderText("Final confirmation — delete everything?");
        finalConfirm.setContentText("Click OK to permanently delete all " + count + " backup(s). This cannot be undone.");
        if (finalConfirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        busy.set(true);
        AppExecutors.ioPool().execute(() -> {
            boolean handoffToRefresh = false;
            try {
                rollbackBackupService.removeAll();
                handoffToRefresh = true;
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "All driver backups deleted.").showAndWait();
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backups:\n" + ex.getMessage()).showAndWait());
            } finally {
                if (!handoffToRefresh) {
                    Platform.runLater(() -> busy.set(false));
                }
            }
        });
    }

    private void deleteSingleBackup(RestoreRow row) {
        if (row == null) {
            return;
        }
        if (!canDeleteDriverBackup(row)) {
            showDriverDeleteRequiresElevation();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete backup for: " + row.entry().friendlyName()
                        + "\nVersion: " + row.entry().version()
                        + "\nBacked up: " + row.backedUpAtProperty().get()
                        + "\nHealth: " + row.statusProperty().get());
        confirm.setHeaderText("Delete backup?");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        busy.set(true);
        AppExecutors.ioPool().execute(() -> {
            boolean handoffToRefresh = false;
            try {
                rollbackBackupService.removeBackupEntry(row.entry());
                handoffToRefresh = true;
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "Backup deleted.").showAndWait();
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backup:\n" + ex.getMessage()).showAndWait());
            } finally {
                if (!handoffToRefresh) {
                    Platform.runLater(() -> busy.set(false));
                }
            }
        });
    }

    private RestoreRow selectedRollbackRow() {
        return rollbackTable != null ? rollbackTable.getSelectionModel().getSelectedItem() : null;
    }

    private void showRollbackDetails() {
        RestoreRow row = selectedRollbackRow();
        if (row == null) {
            new Alert(Alert.AlertType.INFORMATION, "Select a backup first.").showAndWait();
            return;
        }
        var e = row.entry();
        String details = "Device: " + row.deviceNameProperty().get()
                + "\nVersion: " + row.versionProperty().get()
                + "\nBacked up: " + row.backedUpAtProperty().get()
                + "\nDevice ID: " + (e.deviceId() != null ? e.deviceId() : "—")
                + "\nINF: " + (e.infName() != null ? e.infName() : "—")
                + "\nFolder: " + (e.backupFolder() != null ? e.backupFolder() : "—")
                + "\nSize: " + row.sizeProperty().get()
                + "\nHealth: " + row.statusProperty().get()
                + "\nFiles: " + row.getFileCount() + " (" + row.getInfCount() + " INF)";
        Alert info = new Alert(Alert.AlertType.INFORMATION, details);
        info.setTitle("Backup details");
        info.setHeaderText(row.deviceNameProperty().get());
        info.showAndWait();
    }

    private void openRollbackFolder() {
        RestoreRow row = selectedRollbackRow();
        if (row == null) {
            new Alert(Alert.AlertType.INFORMATION, "Select a backup first.").showAndWait();
            return;
        }
        String folder = row.entry().backupFolder();
        if (folder == null || folder.isBlank()) {
            new Alert(Alert.AlertType.WARNING, "No folder recorded for this backup.").showAndWait();
            return;
        }
        try {
            Path p = Path.of(folder);
            if (!com.sbtools.backup.BackupHealth.isPathShapeSafe(p)) {
                new Alert(Alert.AlertType.WARNING,
                        "Refusing to open unsafe backup path:\n" + folder).showAndWait();
                return;
            }
            if (!Files.isDirectory(p)) {
                new Alert(Alert.AlertType.WARNING,
                        "Folder no longer exists:\n" + folder + "\n\nUse Repair to clean stale entries.").showAndWait();
                return;
            }
            if (AppPaths.isWindows()) {
                new ProcessBuilder("explorer.exe", p.toString()).start();
            } else {
                new Alert(Alert.AlertType.INFORMATION, "Backup folder:\n" + folder).showAndWait();
            }
        } catch (Exception ex) {
            AppLogger.warning("Failed to open backup folder: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Could not open folder:\n" + folder + "\n" + ex.getMessage()).showAndWait();
        }
    }

    private void verifyAllRollbacks() {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        if (rollbackStatusLabel != null) {
            rollbackStatusLabel.setText("Verifying backups on disk...");
        }
        AppExecutors.ioPool().execute(() -> {
            try {
                var entries = rollbackBackupService.listAll();
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList();
                for (var e : entries) {
                    if (e == null || e.id() == null) {
                        continue;
                    }
                    newRows.add(new RestoreRow(e));
                }
                // Single disk pass: count from the same inspection that feeds
                // the display, so "x/y healthy" can never disagree with the
                // Health column (join on worker thread only — never FX).
                // Inline inspect on this io worker — never .join() a second ioPool task
                // (fixed 4-thread pool deadlock) and keep health fields in sync before count.
                RestoreRow.inspectAllOnWorker(newRows);
                final int okFinal = (int) newRows.stream().filter(RestoreRow::isHealthy).count();
                final int totalFinal = newRows.size();
                Platform.runLater(() -> {
                    rollbackRows.setAll(newRows);
                    updateRollbackSelectionButtons();
                    rollbackStatusLabel.setText("Verified " + okFinal + "/" + totalFinal + " backup(s) healthy.");
                    if (okFinal < totalFinal) {
                        new Alert(Alert.AlertType.WARNING,
                                (totalFinal - okFinal) + " backup(s) are missing, empty or unreadable.\n\n"
                                        + "Revert is disabled for those rows.\nUse Repair to remove stale index entries.").showAndWait();
                    } else if (totalFinal > 0) {
                        new Alert(Alert.AlertType.INFORMATION,
                                "All " + totalFinal + " backup(s) verified healthy.").showAndWait();
                    }
                    // No refreshRollback() here: verify already rebuilt rows from disk.
                    // A refresh would triple disk I/O and instantly overwrite the
                    // "Verified x/y" status with "Loading backups...".
                });
            } catch (Exception ex) {
                AppLogger.error("Verify backups failed", ex);
                Platform.runLater(() -> {
                    rollbackStatusLabel.setText("Verify failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Verify failed:\n" + ex.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void repairStaleBackups() {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        if (rollbackStatusLabel != null) {
            rollbackStatusLabel.setText("Checking for stale backups...");
        }
        // Ownership flag: once the confirm dialog is queued, the inner purge task
        // owns busy + refresh. The outer finally must not clear/refresh early,
        // otherwise purge runs with busy=false and refresh races the purge.
        java.util.concurrent.atomic.AtomicBoolean handoff = new java.util.concurrent.atomic.AtomicBoolean(false);
        AppExecutors.ioPool().execute(() -> {
            try {
                List<com.sbtools.backup.DriverBackupEntry> stale = rollbackBackupService.findStaleEntries();
                if (stale.isEmpty()) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION,
                            "No stale backups found. All index entries point to healthy folders.").showAndWait());
                    return;
                }
                StringBuilder sb = new StringBuilder();
                for (var e : stale) {
                    sb.append("• ").append(e.friendlyName() != null ? e.friendlyName() : e.deviceId())
                            .append(" [").append(e.version() != null ? e.version() : "?").append("]")
                            .append("\n  ").append(e.backupFolder()).append("\n");
                    if (sb.length() > 2000) {
                        sb.append("... and ").append(stale.size()).append(" total");
                        break;
                    }
                }
                final String list = sb.toString();
                final int count = stale.size();
                handoff.set(true);
                Platform.runLater(() -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("Repair stale backups");
                    confirm.setHeaderText(count + " stale index entr" + (count == 1 ? "y" : "ies") + " found");
                    confirm.setContentText("These backups are missing, empty or unreadable on disk:\n\n" + list
                            + "\nRemove their index entries? Folders (if any) are left untouched.\nNothing else will be deleted.");
                    if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
                        // Cancelled: refresh owns busy from here.
                        refreshRollback();
                        return;
                    }
                    AppExecutors.ioPool().execute(() -> {
                        boolean purgeHandoff = false;
                        try {
                            rollbackBackupService.purgeStaleIndexEntries(stale);
                            purgeHandoff = true;
                            Platform.runLater(() -> {
                                new Alert(Alert.AlertType.INFORMATION,
                                        "Removed " + count + " stale index entr" + (count == 1 ? "y" : "ies") + ".").showAndWait();
                                refreshRollback();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "Repair failed:\n" + ex.getMessage()).showAndWait());
                        } finally {
                            if (!purgeHandoff) {
                                Platform.runLater(() -> busy.set(false));
                            }
                        }
                    });
                });
            } catch (Exception ex) {
                AppLogger.error("Repair check failed", ex);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Repair check failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                // Only the non-handoff paths (empty / error) clean up here.
                // Handoff path is owned by the confirm/purge chain above.
                if (!handoff.get()) {
                    Platform.runLater(this::refreshRollback);
                }
            }
        });
    }

    // ── System restore tab ─────────────────────────────────────────────────

    private Tab buildSystemRestoreTab() {
        SystemRestoreService service = new SystemRestoreService();
        BooleanProperty localBusy = new SimpleBooleanProperty(false);
        ObservableList<SystemRestoreRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("Click Scan to list system restore points.");
        ProgressIndicator spinner = new ProgressIndicator();
        Button scanButton = new Button("Scan");
        Button createButton = new Button("Create new restore point");
        Button launchButton = new Button("Launch restore point");
        TableView<SystemRestoreRow> table = new TableView<>(rows);

        Tooltip.install(scanButton, new Tooltip("Query Windows for available system restore points"));
        Tooltip.install(createButton, new Tooltip("Create a manual system restore point"));
        Tooltip.install(launchButton, new Tooltip("Open the Windows System Restore wizard"));

        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);

        scanButton.setOnAction(e -> scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton));
        createButton.setOnAction(e -> createSystemRestorePoint(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton));
        launchButton.setOnAction(e -> launchSystemRestore(service, statusLabel));

        createButton.getStyleClass().add("success");

        HBox top = new HBox(12, scanButton, createButton, launchButton, spinner, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        FilteredList<SystemRestoreRow> filteredList = new FilteredList<>(rows);
        SortedList<SystemRestoreRow> sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(table.comparatorProperty());

        buildSystemRestoreTable(table, sortedList);
        VBox center = new VBox(8, table);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(center);

        localBusy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            createButton.setDisable(newVal);
            launchButton.setDisable(newVal);
            spinner.setVisible(newVal);
        });

        if (AppPaths.isWindows()) {
            // Silent initial scan: status label only, never a modal at startup
            // (standard users / protection-disabled would otherwise get a blocking error on launch).
            scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton, true);
        } else {
            statusLabel.setText("System Restore is available on Windows only.");
            scanButton.setDisable(true);
            createButton.setDisable(true);
            launchButton.setDisable(true);
        }

        Tab tab = UiTab.tab("System restore");
        tab.setContent(pane);
        return tab;
    }

    private void buildSystemRestoreTable(TableView<SystemRestoreRow> table, SortedList<SystemRestoreRow> sortedRows) {
        table.setItems(sortedRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Note: no selection checkbox column — system restore points are
        // read-only here (scan/create/launch only, never delete).

        TableColumn<SystemRestoreRow, String> descCol = UiColumn.of("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(300);

        TableColumn<SystemRestoreRow, String> dateCol = UiColumn.of("Creation Date/Time");
        dateCol.setCellValueFactory(c -> c.getValue().creationTimeProperty());
        dateCol.setPrefWidth(160);

        TableColumn<SystemRestoreRow, String> typeCol = UiColumn.of("Type");
        typeCol.setCellValueFactory(c -> c.getValue().eventTypeProperty());
        typeCol.setPrefWidth(140);

        table.getColumns().addAll(descCol, dateCol, typeCol);
    }

    private void scanSystemRestore(SystemRestoreService service, BooleanProperty localBusy,
                                    ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                    ProgressIndicator spinner, Button scanButton, Button createButton,
                                    Button launchButton) {
        scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton, false);
    }

    private void scanSystemRestore(SystemRestoreService service, BooleanProperty localBusy,
                                    ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                    ProgressIndicator spinner, Button scanButton, Button createButton,
                                    Button launchButton, boolean silent) {
        if (localBusy.get()) return;
        localBusy.set(true);
        busy.set(true);
        statusLabel.setText("Scanning restore points...");
        // Keep snapshot to restore on failure so UI doesn't lose previous data
        List<SystemRestoreRow> snapshot = new ArrayList<>(rows);
        rows.clear();

        AppExecutors.ioPool().execute(() -> {
            try {
                List<SystemRestoreRow> results = service.listRestorePoints();
                Platform.runLater(() -> {
                    rows.setAll(results);
                    if (results.isEmpty()) {
                        statusLabel.setText("No restore points found. Create one or ensure System Protection is enabled.");
                    } else {
                        statusLabel.setText(results.size() + " restore point(s) found.");
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to scan restore points", e);
                Platform.runLater(() -> {
                    // Restore previous data on failure
                    rows.setAll(snapshot);
                    String msg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    if (msg.toLowerCase().contains("access denied") || msg.toLowerCase().contains("administrator")) {
                        statusLabel.setText("Scan failed: Access denied (run as Administrator). Click Scan to retry as admin.");
                    } else if (msg.toLowerCase().contains("protection")) {
                        statusLabel.setText("Scan failed: System Protection disabled.");
                    } else {
                        statusLabel.setText("Scan failed: " + msg);
                    }
                    // Silent (initial) scans never pop a modal — status label only.
                    if (!silent) {
                        new Alert(Alert.AlertType.ERROR, "Failed to scan restore points:\n" + msg).showAndWait();
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    localBusy.set(false);
                    busy.set(false);
                });
            }
        });
    }

    private void createSystemRestorePoint(SystemRestoreService service, BooleanProperty localBusy,
                                           ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                           ProgressIndicator spinner, Button scanButton, Button createButton,
                                           Button launchButton) {
        if (localBusy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Creating system restore points requires administrator rights.").showAndWait();
            return;
        }

        TextInputDialog dialog = new TextInputDialog("Manual Restore Point");
        dialog.setTitle(com.sbtools.util.UiText.label("Create restore point"));
        dialog.setHeaderText("Enter a description for the new restore point:");
        dialog.setContentText("Description:");
        String description = dialog.showAndWait().orElse(null);
        if (description == null || description.isBlank()) return;

        localBusy.set(true);
        busy.set(true);
        statusLabel.setText("Creating restore point (this can take several minutes)...");
        final String desc = description;

        AppExecutors.ioPool().execute(() -> {
            boolean succeeded = false;
            try {
                if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                            "Creating system restore points requires administrator rights. Please restart as administrator.")
                            .showAndWait());
                    return;
                }
                var result = service.createRestorePoint(desc);
                boolean ok = result.success();
                succeeded = ok;
                String err = result.error();
                Platform.runLater(() -> {
                    if (ok) {
                        statusLabel.setText("Restore point created.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Restore point '" + desc + "' created successfully.").showAndWait();
                    } else {
                        statusLabel.setText("Failed to create restore point.");
                        String msg = "Failed to create restore point.";
                        if (err != null && !err.isBlank()) {
                            if (err.contains("FREQUENCY_LIMIT")) {
                                msg = "A restore point was already created within the last 24 hours.\nWindows limits creation to once per 24 hours by default.\n\n" + err;
                            } else if (err.contains("PROTECTION_DISABLED")) {
                                msg = "System Protection is disabled for your system drive.\nEnable it in System Properties -> System Protection.\n\n" + err;
                            } else {
                                msg += "\n\n" + err;
                            }
                        } else {
                            msg += "\nEnsure System Protection is enabled for your system drive.";
                        }
                        new Alert(Alert.AlertType.ERROR, msg).showAndWait();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to create restore point", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Creation failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to create restore point:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                final boolean rescan = succeeded;
                Platform.runLater(() -> {
                    localBusy.set(false);
                    busy.set(false);
                    // Only rescan on success: on failure a second 60s scan would
                    // hide the error status and double the wait after a timeout.
                    if (rescan) {
                        scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton);
                    }
                });
            }
        });
    }

    private void launchSystemRestore(SystemRestoreService service, Label statusLabel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(com.sbtools.util.UiText.label("Launch system restore"));
        confirm.setHeaderText(com.sbtools.util.UiText.label("Start Windows system restore?"));
        confirm.setContentText("This will launch the System Restore wizard and may reboot your computer.\n\n"
                + "Ensure all work is saved before proceeding.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        try {
            service.launchSystemRestore();
            statusLabel.setText("System Restore launched.");
        } catch (Exception e) {
            AppLogger.error("Failed to launch System Restore", e);
            new Alert(Alert.AlertType.ERROR, "Failed to launch System Restore:\n" + e.getMessage()).showAndWait();
        }
    }

    // ── Registry backup tab ────────────────────────────────────────────────

    /** Full-hive sources for the optional advanced `reg save` export. */
    private static final String[] REGISTRY_FULL_HIVES = {
            "HKLM\\SYSTEM",
            "HKLM\\SOFTWARE",
            "HKCU"
    };

    private Tab buildRegistryBackupTab() {
        ObservableList<RegistryBackupRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("No registry backups found.");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);
        // Bind spinner visibility to registryBusy
        registryBusy.addListener((obs, oldV, newV) -> spinner.setVisible(Boolean.TRUE.equals(newV)));
        TableView<RegistryBackupRow> table = buildRegistryBackupTable(rows);

        UIButton backupNowBtn = UIButton.primary("Backup Now");
        UIButton restoreBtn = UIButton.secondary("Restore selected");
        UIButton deleteBtn = UIButton.danger("Delete backup");
        TextField searchField = new TextField();
        searchField.setPromptText("Search sessions...");

        Tooltip.install(backupNowBtn, new Tooltip("Export selected registry areas to a backup session"));
        Tooltip.install(restoreBtn, new Tooltip("Import the selected registry backup session (.reg files only)"));
        Tooltip.install(deleteBtn, new Tooltip("Remove the selected registry backup session"));
        Tooltip.install(searchField, new Tooltip("Filter sessions by name"));

        restoreBtn.setDisable(true);
        deleteBtn.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            boolean hasSel = newSel != null;
            // Keep disabled when busy
            boolean busyNow = registryBusy.get();
            restoreBtn.setDisable(!hasSel || busyNow);
            deleteBtn.setDisable(!hasSel || busyNow);
        });
        registryBusy.addListener((obs, oldV, newV) -> {
            boolean busyNow = Boolean.TRUE.equals(newV);
            boolean hasSel = table.getSelectionModel().getSelectedItem() != null;
            backupNowBtn.setDisable(busyNow);
            restoreBtn.setDisable(!hasSel || busyNow);
            deleteBtn.setDisable(!hasSel || busyNow);
        });

        backupNowBtn.setOnAction(e -> backupRegistry(rows, statusLabel));
        restoreBtn.setOnAction(e -> restoreRegistryBackup(table, rows, statusLabel));
        deleteBtn.setOnAction(e -> deleteRegistryBackup(table, rows, statusLabel));

        HBox top = new HBox(12, backupNowBtn, restoreBtn, deleteBtn, spinner, searchField, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        FilteredList<RegistryBackupRow> filteredList = new FilteredList<>(rows);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredList.setPredicate(row -> newVal == null || newVal.isBlank()
                    || row.getFilename().toLowerCase().contains(newVal.toLowerCase()));
        });
        SortedList<RegistryBackupRow> sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sortedList);

        VBox center = new VBox(8, table);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(center);

        refreshRegistryBackups(rows, statusLabel);

        Tab tab = UiTab.tab("Registry backup");
        tab.setContent(pane);
        return tab;
    }

    private TableView<RegistryBackupRow> buildRegistryBackupTable(ObservableList<RegistryBackupRow> rows) {
        TableView<RegistryBackupRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<RegistryBackupRow, String> fileCol = UiColumn.of("Filename");
        fileCol.setCellValueFactory(c -> c.getValue().filenameProperty());
        fileCol.setPrefWidth(300);

        TableColumn<RegistryBackupRow, String> dateCol = UiColumn.of("Date");
        dateCol.setCellValueFactory(c -> c.getValue().dateProperty());
        dateCol.setPrefWidth(160);

        TableColumn<RegistryBackupRow, String> sizeCol = UiColumn.of("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeProperty());
        sizeCol.setPrefWidth(100);

        table.getColumns().addAll(fileCol, dateCol, sizeCol);
        return table;
    }

    private void refreshRegistryBackups(ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        AppExecutors.ioPool().execute(() -> {
            try {
                List<Path> bases = registryBackupsRoots();
                List<RegistryBackupRow> results = new ArrayList<>();
                java.util.Set<String> seen = new java.util.HashSet<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (Path backupsDir : bases) {
                if (!Files.isDirectory(backupsDir)) {
                    continue;
                }
                try (var stream = Files.list(backupsDir)) {
                    // List ANY session dir containing .reg/.hiv files (manual
                    // "registry_backup_*" AND cleaner "yyyyMMdd-HHmmss*" share
                    // this root). Prefix-only filtering hid cleaner backups.
                    stream.filter(Files::isDirectory)
                            .sorted((a, b) -> b.getFileName().toString().compareTo(a.getFileName().toString()))
                            .forEach(dir -> {
                                try {
                                    long dirSize = 0;
                                    long latestModified = 0;
                                    int regCount = 0;
                                    int hivCount = 0;
                                    try (var files = Files.list(dir)) {
                                        for (Path f : (Iterable<Path>) files::iterator) {
                                            String lower = f.toString().toLowerCase();
                                            if (lower.endsWith(".reg") || lower.endsWith(".hiv")) {
                                                if (lower.endsWith(".reg")) {
                                                    regCount++;
                                                } else {
                                                    hivCount++;
                                                }
                                                try {
                                                    dirSize += Files.size(f);
                                                } catch (IOException ignored) {
                                                }
                                                try {
                                                    long modTime = Files.getLastModifiedTime(f).toMillis();
                                                    if (modTime > latestModified) latestModified = modTime;
                                                } catch (IOException ignored) {
                                                }
                                            } else if (lower.endsWith("manifest.json")) {
                                                try {
                                                    long modTime = Files.getLastModifiedTime(f).toMillis();
                                                    if (modTime > latestModified) latestModified = modTime;
                                                } catch (IOException ignored) {
                                                }
                                            }
                                        }
                                    }
                                    if (regCount > 0 || hivCount > 0) {
                                        if (!com.sbtools.backup.RegistryBackupSafety.isRestorableSession(dir)) {
                                            return; // unrestorable (out-of-scope / deletion lines) — hide
                                        }
                                        String dirName = dir.getFileName().toString();
                                        if (seen.add(dirName)) {
                                            String date = latestModified > 0
                                                    ? sdf.format(new Date(latestModified)) : "—";
                                            String size = RestoreRow.formatFileSize(dirSize);
                                            if (hivCount > 0) {
                                                size += " (+hive)";
                                            }
                                            results.add(new RegistryBackupRow(dirName + "/", date, size));
                                        }
                                    }
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException listEx) {
                    AppLogger.warning("Could not list registry backups in " + backupsDir + ": " + listEx.getMessage());
                }
                }
                results.sort((a, b) -> b.getFilename().compareTo(a.getFilename()));
                Platform.runLater(() -> {
                    rows.setAll(results);
                    if (results.isEmpty()) {
                        statusLabel.setText("No registry backups found.");
                    } else {
                        statusLabel.setText(results.size() + " registry backup session(s) found.");
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to list registry backups", e);
                Platform.runLater(() -> statusLabel.setText("Failed to load backups."));
            }
        });
    }

    private void backupRegistry(ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        if (registryBusy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Registry backup requires administrator rights.").showAndWait();
            return;
        }

        Dialog<List<String>> dialog = new Dialog<>();
        dialog.setTitle(com.sbtools.util.UiText.label("Registry backup"));
        dialog.setHeaderText("Select registry areas to back up:");
        dialog.initModality(Modality.APPLICATION_MODAL);
        try {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        } catch (Exception ignored) {}

        CheckBox hkcuRun = new CheckBox("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        hkcuRun.setSelected(true);
        CheckBox hklmRun = new CheckBox("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run");
        hklmRun.setSelected(true);
        CheckBox hklmRunOnce = new CheckBox("HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        CheckBox hkcuRunOnce = new CheckBox("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce");
        CheckBox hklmWowRun = new CheckBox("HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Run");
        CheckBox hkcuRunServices = new CheckBox("HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunServices");
        hkcuRunServices.setTooltip(new Tooltip("Legacy autostart area — often absent on Windows 10/11 (skipped if missing)"));

        Label coreLabel = new Label("Core autostart areas (recommended):");
        coreLabel.setStyle("-fx-font-weight: bold;");
        Label extLabel = new Label("Extended areas (optional, larger):");
        extLabel.setStyle("-fx-font-weight: bold;");
        List<CheckBox> extBoxes = new ArrayList<>();
        for (String key : com.sbtools.backup.RegistryBackupSafety.EXTENDED_REGISTRY_KEYS) {
            CheckBox cb = new CheckBox(key);
            cb.setSelected(false);
            cb.setTooltip(new Tooltip("Optional extended area — export is larger/slower"));
            extBoxes.add(cb);
        }
        CheckBox fullHiveBox = new CheckBox("Full hive export via 'reg save' (advanced, large .hiv files)");
        fullHiveBox.setSelected(false);
        fullHiveBox.setTooltip(new Tooltip("Saves HKLM\\SYSTEM, HKLM\\SOFTWARE and HKCU as binary .hiv files. "
                + "Restore of .hiv files is manual (reg restore) — .reg import does not cover them."));
        Label hint = new Label("Windows merges .reg on restore. Extended + hive options increase coverage but not removal.");
        hint.setWrapText(true);

        VBox checks = new VBox(6);
        checks.getChildren().add(coreLabel);
        checks.getChildren().addAll(hkcuRun, hklmRun, hklmRunOnce, hkcuRunOnce, hklmWowRun, hkcuRunServices);
        checks.getChildren().add(extLabel);
        checks.getChildren().addAll(extBoxes);
        checks.getChildren().addAll(fullHiveBox, hint);
        checks.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(checks);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        final boolean[] fullHiveSelected = {false};
        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                List<String> selected = new ArrayList<>();
                if (hkcuRun.isSelected()) selected.add(hkcuRun.getText());
                if (hklmRun.isSelected()) selected.add(hklmRun.getText());
                if (hklmRunOnce.isSelected()) selected.add(hklmRunOnce.getText());
                if (hkcuRunOnce.isSelected()) selected.add(hkcuRunOnce.getText());
                if (hklmWowRun.isSelected()) selected.add(hklmWowRun.getText());
                if (hkcuRunServices.isSelected()) selected.add(hkcuRunServices.getText());
                for (CheckBox cb : extBoxes) {
                    if (cb.isSelected()) selected.add(cb.getText());
                }
                fullHiveSelected[0] = fullHiveBox.isSelected();
                return selected;
            }
            return null;
        });

        List<String> selected = dialog.showAndWait().orElse(null);
        if ((selected == null || selected.isEmpty()) && !fullHiveSelected[0]) return;
        if (selected == null) selected = new ArrayList<>();
        final boolean doFullHive = fullHiveSelected[0];
        final List<String> selectedFinal = new ArrayList<>(selected);

        beginRegistryMutation();
        statusLabel.setText("Creating registry backup...");

        AppExecutors.ioPool().execute(() -> {
            Path backupDir = null;
            try {
                Path backupsDir = registryBackupsBaseForWrite();
                Files.createDirectories(backupsDir);

                // Millis + random suffix: two backups in the same second must
                // never share (and overwrite) one directory.
                backupDir = newUniqueRegistryBackupDir(backupsDir, "registry_backup_");
                Files.createDirectories(backupDir);

                int failedCount = 0;
                List<String> exportedFiles = new ArrayList<>();
                List<String> skippedMissing = new ArrayList<>();
                List<String> failedAreas = new ArrayList<>();
                for (String area : selectedFinal) {
                    String safeName = area.replace('\\', '_').replace(':', '_');
                    Path outputFile = backupDir.resolve(safeName + ".reg");
                    com.sbtools.backup.RegistryBackupSafety.RegExportOutcome outcome =
                            com.sbtools.backup.RegistryBackupSafety.exportRegKeyIfPresent(area, outputFile);
                    if (outcome == com.sbtools.backup.RegistryBackupSafety.RegExportOutcome.OK) {
                        exportedFiles.add(outputFile.getFileName().toString());
                    } else if (outcome == com.sbtools.backup.RegistryBackupSafety.RegExportOutcome.MISSING) {
                        skippedMissing.add(area);
                        AppLogger.info("Registry backup skipped missing key: " + area);
                    } else {
                        failedCount++;
                        failedAreas.add(area);
                        AppLogger.warning("reg export failed for " + area);
                    }
                }

                int hivOk = 0;
                int hivFailed = 0;
                List<String> hivFiles = new ArrayList<>();
                if (doFullHive) {
                    for (String hive : REGISTRY_FULL_HIVES) {
                        String safeName = hive.replace('\\', '_').replace(':', '_');
                        Path out = backupDir.resolve(safeName + ".hiv");
                        try {
                            ProcessBuilder pb = new ProcessBuilder("reg", "save", hive, out.toString(), "/y");
                            pb.redirectErrorStream(true);
                            Process p = ProcessManager.start(pb);
                            boolean finished = p.waitFor(120, TimeUnit.SECONDS);
                            if (!finished) {
                                p.destroyForcibly();
                                hivFailed++;
                                AppLogger.warning("reg save timed out for " + hive);
                                try { Files.deleteIfExists(out); } catch (Exception ignored) {}
                            } else if (p.exitValue() == 0) {
                                hivOk++;
                                hivFiles.add(out.getFileName().toString());
                            } else {
                                hivFailed++;
                                AppLogger.warning("reg save failed for " + hive + " (exit=" + p.exitValue() + ")");
                                try { Files.deleteIfExists(out); } catch (Exception ignored) {}
                            }
                        } catch (Exception hiveEx) {
                            hivFailed++;
                            AppLogger.warning("reg save error for " + hive + ": " + hiveEx.getMessage());
                        }
                    }
                }

                // Manifest for future-proof listing (legacy sessions without it still list via .reg scan).
                try {
                    writeRegistryManifest(backupDir, selectedFinal, exportedFiles, doFullHive, hivFiles);
                } catch (Exception manifestEx) {
                    AppLogger.warning("Failed to write registry manifest: " + manifestEx.getMessage());
                }

                int totalRequested = selectedFinal.size() + (doFullHive ? REGISTRY_FULL_HIVES.length : 0);
                int totalOk = exportedFiles.size() + hivOk;
                final List<String> skippedFinal = List.copyOf(skippedMissing);
                final List<String> failedAreasFinal = List.copyOf(failedAreas);
                if (totalOk == 0 && skippedMissing.size() == selectedFinal.size() && hivOk == 0) {
                    // Non-empty dirs throw on deleteIfExists: remove recursively
                    // so failed sessions do not pollute the backup list/disk.
                    try { deleteDirectoryRecursive(backupDir); } catch (Exception ignored) {}
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup failed.");
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to export any registry areas.").showAndWait();
                    });
                } else {
                    boolean realPartial = failedCount > 0 || hivFailed > 0;
                    String msg = realPartial
                            ? "Partial backup: " + totalOk + "/" + totalRequested
                                    + " areas exported to:\n" + backupDir
                            : "Registry backup completed (" + totalOk + " file(s)):\n" + backupDir;
                    if (!skippedFinal.isEmpty()) {
                        msg += "\n\nSkipped (registry key not present on this PC):\n"
                                + String.join("\n", skippedFinal);
                    }
                    if (!failedAreasFinal.isEmpty()) {
                        msg += "\n\nFailed to export:\n" + String.join("\n", failedAreasFinal);
                    }
                    if (doFullHive && hivOk > 0) {
                        msg += "\n\nNote: .hiv files require manual 'reg restore'. Only .reg files are auto-imported.";
                    }
                    final String finalMsg = msg;
                    final Path finalDir = backupDir;
                    Platform.runLater(() -> {
                        statusLabel.setText("Registry backup created (" + totalOk + " file(s)).");
                        new Alert(Alert.AlertType.INFORMATION, finalMsg + "\n\nLocation:\n" + finalDir).showAndWait();
                        refreshRegistryBackups(rows, statusLabel);
                    });
                }
            } catch (Exception e) {
                AppLogger.error("Failed to create registry backup", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Backup failed.");
                    new Alert(Alert.AlertType.ERROR,
                            "Failed to create registry backup:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(this::endRegistryMutation);
            }
        });
    }

    private void restoreRegistryBackup(TableView<RegistryBackupRow> table,
                                        ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        if (registryBusy.get()) return;
        RegistryBackupRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Restoring registry backups requires administrator rights.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(com.sbtools.util.UiText.label("Restore registry backup"));
        confirm.setHeaderText("Import registry session: " + selected.getFilename());
        confirm.setContentText("Windows MERGES .reg files: values added after the backup will NOT be removed.\n\n"
                + "Existing registry keys targeted by this session will be exported first into a safety session. "
                + "Keys that did not exist are recorded in the manifest only.\n\n"
                + "Ensure all work is saved before proceeding.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        beginRegistryMutation();
        statusLabel.setText("Restoring registry backup...");

        AppExecutors.ioPool().execute(() -> {
            Path safetyDir = null;
            try {
                if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
                    Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                            "Restoring registry backups requires administrator rights. Please restart as administrator.")
                            .showAndWait());
                    return;
                }
                // Validate the selected session BEFORE creating the safety net:
                // a missing/.hiv-only session must not leave an orphan pre-restore dir.
                Path dirPath = resolveRegistryBackupPath(selected.getFilename());
                if (com.sbtools.backup.BackupHealth.isReparseOrSymlink(dirPath)) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup session is a junction/symlink.");
                        new Alert(Alert.AlertType.WARNING,
                                "Refusing to restore a registry session that is a junction or symlink:\n"
                                        + dirPath).showAndWait();
                    });
                    return;
                }
                if (!Files.isDirectory(dirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup session not found.");
                        new Alert(Alert.AlertType.WARNING,
                                "Registry backup session folder no longer exists:\n" + dirPath).showAndWait();
                    });
                    return;
                }
                List<Path> regFiles;
                long hivCount = 0;
                try (var stream = Files.list(dirPath)) {
                    List<Path> all = stream.sorted().toList();
                    regFiles = all.stream()
                            .filter(p -> p.toString().toLowerCase().endsWith(".reg"))
                            .filter(p -> !com.sbtools.backup.BackupHealth.isReparseOrSymlink(p))
                            .toList();
                    hivCount = all.stream()
                            .filter(p -> p.toString().toLowerCase().endsWith(".hiv"))
                            .count();
                }
                final long hivFinal = hivCount;
                if (regFiles.isEmpty()) {
                    Platform.runLater(() -> {
                        statusLabel.setText(hivFinal > 0
                                ? "Session contains only .hiv files (manual restore required)."
                                : "No .reg files found in session.");
                        String msg = hivFinal > 0
                                ? "This session contains " + hivFinal + " .hiv file(s) and no .reg files.\n\n"
                                        + ".hiv files are full-hive images from 'reg save' and cannot be auto-imported.\n"
                                        + "Restore manually with 'reg restore <hive> <file>' from an elevated prompt."
                                : "No .reg files found in the backup session.";
                        new Alert(Alert.AlertType.WARNING, msg).showAndWait();
                    });
                    return;
                }
                Set<String> targetKeys = com.sbtools.backup.RegistryBackupSafety.collectTargetKeys(regFiles);
                Path base = registryBackupsBaseForWrite();
                Files.createDirectories(base);
                safetyDir = newUniqueRegistryBackupDir(base, "registry_backup_pre-restore_");
                com.sbtools.backup.RegistryBackupSafety.SnapshotResult safetySnap =
                        com.sbtools.backup.RegistryBackupSafety.createPreRestoreSnapshot(safetyDir, targetKeys);
                if (safetySnap.exportedKeys().isEmpty() && !targetKeys.isEmpty()) {
                    final String warn = "None of the registry keys in this session currently exist on this PC.\n"
                            + "If restore fails partway through, automatic rollback may not be possible.\n\n"
                            + "Continue anyway?";
                    CompletableFuture<Boolean> proceed = new CompletableFuture<>();
                    Platform.runLater(() -> {
                        Alert extra = new Alert(Alert.AlertType.WARNING);
                        extra.setTitle(com.sbtools.util.UiText.label("Limited rollback coverage"));
                        extra.setHeaderText("No pre-restore keys could be exported");
                        extra.setContentText(warn);
                        extra.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                        proceed.complete(extra.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK);
                    });
                    if (!proceed.get(10, TimeUnit.MINUTES)) {
                        Platform.runLater(() -> statusLabel.setText("Restore cancelled."));
                        return;
                    }
                }
                com.sbtools.backup.RegistryBackupSafety.importRegSessionAtomically(regFiles, safetyDir);
                final int totalFiles = regFiles.size();
                final String safetyInfo = "\n\nSafety backup of targeted pre-restore keys:\n" + safetyDir
                        + "\n(Import those .reg files to undo this restore.)";
                final String mergeNote = "\n\nNote: Windows merges .reg files — entries added after the backup were NOT removed.";
                final String hivNote = hivFinal > 0
                        ? "\n\nSession also contains " + hivFinal + " .hiv file(s) which were NOT auto-imported (manual 'reg restore' required)."
                        : "";
                Platform.runLater(() -> {
                    statusLabel.setText("Registry backup restored.");
                    new Alert(Alert.AlertType.INFORMATION,
                            "All " + totalFiles + " registry file(s) merged successfully."
                                    + mergeNote + hivNote + safetyInfo).showAndWait();
                    refreshRegistryBackups(rows, statusLabel);
                });
            } catch (Exception e) {
                AppLogger.error("Failed to restore registry backup", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Restore failed.");
                    new Alert(Alert.AlertType.ERROR,
                            "Failed to restore registry backup:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(this::endRegistryMutation);
            }
        });
    }

    private void deleteRegistryBackup(TableView<RegistryBackupRow> table,
                                       ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        if (registryBusy.get()) return;
        RegistryBackupRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!canDeleteRegistrySession(selected.getFilename())) {
            new Alert(Alert.AlertType.WARNING,
                    "Cannot delete this registry backup session.\n\n"
                            + "Restart as administrator, or ensure the session folder is under your backup directory "
                            + "and is writable by your user account.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(com.sbtools.util.UiText.label("Delete registry backup"));
        confirm.setHeaderText("Delete backup session: " + selected.getFilename());
        confirm.setContentText("This will permanently delete all registry backup files in this session.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        beginRegistryMutation();
        statusLabel.setText("Deleting backup...");
        AppExecutors.ioPool().execute(() -> {
            try {
                Path dirPath = resolveRegistryBackupPath(selected.getFilename());
                if (Files.exists(dirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    deleteDirectoryRecursive(dirPath);
                    if (Files.exists(dirPath, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        throw new IOException("Backup session folder still exists: " + dirPath);
                    }
                }
                Platform.runLater(() -> {
                    refreshRegistryBackups(rows, statusLabel);
                    statusLabel.setText("Backup session deleted.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to delete registry backup", e);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backup:\n" + e.getMessage()).showAndWait());
            } finally {
                Platform.runLater(this::endRegistryMutation);
            }
        });
    }

    private boolean canDeleteAllDriverBackups() {
        if (adminCheck != null && adminCheck.getAsBoolean()) {
            return true;
        }
        try {
            return rollbackBackupService.canDeleteAllAsCurrentUser();
        } catch (Exception ex) {
            AppLogger.warning("canDeleteAllDriverBackups: " + ex.getMessage());
            return false;
        }
    }

    private boolean canDeleteDriverBackup(RestoreRow row) {
        if (row == null) {
            return false;
        }
        if (adminCheck != null && adminCheck.getAsBoolean()) {
            return true;
        }
        return rollbackBackupService.canDeleteAsCurrentUser(row.entry());
    }

    private void showDriverDeleteRequiresElevation() {
        new Alert(Alert.AlertType.WARNING,
                "Cannot delete these backups with your current permissions.\n\n"
                        + "Restart as administrator, or ensure backup folders and index files are writable "
                        + "under your configured backup directory.").showAndWait();
    }

    private boolean canDeleteRegistrySession(String filename) {
        if (adminCheck != null && adminCheck.getAsBoolean()) {
            return true;
        }
        try {
            Path dir = resolveRegistryBackupPath(filename);
            boolean underKnownRoot = false;
            for (Path root : registryBackupsRoots()) {
                if (dir.toAbsolutePath().normalize().startsWith(root)) {
                    underKnownRoot = true;
                    break;
                }
            }
            if (!underKnownRoot) {
                return false;
            }
            return com.sbtools.backup.BackupDeleteAccess.isPathDeletableByCurrentUser(dir);
        } catch (Exception ex) {
            return false;
        }
    }

    private void beginRegistryMutation() {
        registryBusy.set(true);
        busy.set(true);
    }

    private void endRegistryMutation() {
        registryBusy.set(false);
        busy.set(false);
    }

    private static void deleteDirectoryRecursive(Path directory) throws IOException {
        com.sbtools.backup.BackupHealth.deleteTree(directory);
    }

    private static Path resolveRegistryBackupPath(String filename) throws IOException {
        String cleanName = filename.endsWith("/") ? filename.substring(0, filename.length() - 1) : filename;
        // Reject traversal segments up front before resolving against any root.
        if (cleanName.contains("..") || cleanName.contains("/") || cleanName.contains("\\")) {
            throw new IOException("Invalid backup path: " + filename);
        }
        List<Path> roots = registryBackupsRoots();
        if (roots.isEmpty()) {
            throw new IOException("No backup locations available: " + filename);
        }
        for (Path base : roots) {
            Path filePath = base.resolve(cleanName).normalize();
            if (!filePath.startsWith(base)) {
                continue;
            }
            if (Files.isDirectory(filePath)) {
                return filePath;
            }
        }
        // Fall back to primary for a clear missing-dir error downstream.
        Path primary = roots.get(0);
        Path filePath = primary.resolve(cleanName).normalize();
        if (!filePath.startsWith(primary)) {
            throw new IOException("Invalid backup path: " + filename);
        }
        return filePath;
    }

    /**
     * All locations that may hold registry sessions: settings-aware custom
     * dir first, then portable and legacy fallbacks. Includes both
     * {@code cleanup-backups} and legacy {@code uninstaller-registry}.
     */
    private static List<Path> registryBackupsRoots() {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        List<Path> bases = new ArrayList<>();
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            bases.add(AppPaths.backupsRoot(s).toAbsolutePath().normalize());
        } catch (Exception ignored) {}
        try {
            bases.add(AppPaths.backupsRoot().toAbsolutePath().normalize());
        } catch (Exception ignored) {}
        try {
            bases.add(AppPaths.legacyBackupsRoot().toAbsolutePath().normalize());
        } catch (Exception ignored) {}
        for (Path base : bases) {
            if (base == null) continue;
            roots.add(base.resolve("cleanup-backups"));
            roots.add(base.resolve("uninstaller-registry"));
        }
        return new ArrayList<>(roots);
    }

    private static Path registryBackupsBaseForWrite() {
        Path preferred = null;
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            preferred = AppPaths.backupsRoot(s).resolve("cleanup-backups");
        } catch (Exception ignored) {
            preferred = AppPaths.backupsRoot().resolve("cleanup-backups");
        }
        try {
            Files.createDirectories(preferred);
            Path probe = preferred.resolve(".wz-write-probe");
            Files.writeString(probe, "ok");
            Files.deleteIfExists(probe);
            return preferred;
        } catch (Exception writeEx) {
            Path fallback = AppPaths.legacyBackupsRoot().resolve("cleanup-backups");
            try {
                Files.createDirectories(fallback);
                AppLogger.warning("Registry backups root not writable (" + preferred
                        + "), using fallback " + fallback);
                return fallback;
            } catch (Exception fallbackEx) {
                AppLogger.warning("Registry backup write fallback also failed: " + fallbackEx.getMessage());
                return preferred != null ? preferred : fallback;
            }
        }
    }

    /**
     * Collision-proof session dir: millis + random suffix so two backups in
     * the same second never share (and overwrite) one directory.
     */
    private static Path newUniqueRegistryBackupDir(Path base, String prefix) throws IOException {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        for (int i = 0; i < 10; i++) {
            String rand = String.format("%04x", java.util.concurrent.ThreadLocalRandom.current().nextInt(0x10000));
            Path candidate = base.resolve(prefix + stamp + "-" + rand);
            if (!Files.exists(candidate)) {
                return candidate;
            }
            stamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS").format(new Date());
        }
        return base.resolve(prefix + stamp + "-" + java.util.UUID.randomUUID().toString().substring(0, 8));
    }

    /**
     * Writes a small manifest alongside the .reg/.hiv files so future
     * listings can show coverage without re-scanning. Legacy sessions
     * without a manifest keep working via the .reg/.hiv scan above.
     */
    private static void writeRegistryManifest(Path dir, List<String> requestedKeys,
                                              List<String> exportedRegFiles,
                                              boolean fullHive, List<String> hivFiles) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"createdAt\": \"").append(java.time.Instant.now().toString().replace("\"", "")).append("\",\n");
        sb.append("  \"fullHive\": ").append(fullHive).append(",\n");
        sb.append("  \"requestedKeys\": [");
        for (int i = 0; i < requestedKeys.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(requestedKeys.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append("\"");
        }
        sb.append("],\n  \"regFiles\": [");
        for (int i = 0; i < exportedRegFiles.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(exportedRegFiles.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("],\n  \"hivFiles\": [");
        for (int i = 0; i < hivFiles.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append("\"").append(hivFiles.get(i).replace("\"", "\\\"")).append("\"");
        }
        sb.append("]\n}\n");
        Files.writeString(dir.resolve("manifest.json"), sb.toString());
    }

}
