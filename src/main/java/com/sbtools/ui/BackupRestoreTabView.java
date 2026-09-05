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
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

public class BackupRestoreTabView extends BorderPane {

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final TabPane tabPane = new TabPane();
    private final BooleanProperty registryBusy = new SimpleBooleanProperty(false);

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
        refreshButton.disableProperty().bind(busy);
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

        Tab tab = new Tab("Rollback drivers");
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

        TableColumn<RestoreRow, String> deviceCol = new TableColumn<>("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());

        TableColumn<RestoreRow, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(100);

        TableColumn<RestoreRow, String> dateCol = new TableColumn<>("Backed up");
        dateCol.setCellValueFactory(c -> c.getValue().backedUpAtProperty());
        dateCol.setPrefWidth(140);

        TableColumn<RestoreRow, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeProperty());
        sizeCol.setPrefWidth(80);

        TableColumn<RestoreRow, String> statusCol = new TableColumn<>("Health");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(90);

        TableColumn<RestoreRow, Void> actionCol = new TableColumn<>("Action");
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
                    boolean healthy = row == null || row.getHealth() == com.sbtools.backup.BackupHealth.Status.MISSING
                            ? true // health not computed yet — allow, revert() re-checks on disk
                            : row.isHealthy();
                    // Missing/Empty/Unreadable backups cannot be reverted; delete stays available.
                    revertBtn.setDisable(isBusy || !win || !healthy);
                    if (row != null && !healthy && row.getHealth() != com.sbtools.backup.BackupHealth.Status.MISSING) {
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
        Platform.runLater(() -> {
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
                RestoreRow.computeAllSizesAsync(newRows);
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
                    if (rollbackSpinner != null) rollbackSpinner.setVisible(false);
                });
            } catch (Exception ex) {
                AppLogger.error("Failed to load backups", ex);
                Platform.runLater(() -> {
                    rollbackStatusLabel.setText("Failed to load backups: " + ex.getMessage());
                    if (rollbackSpinner != null) rollbackSpinner.setVisible(false);
                });
            }
        });
    }

    private void revertRollback(RestoreRow row) {
        if (row == null) {
            return;
        }
        if (!com.sbtools.util.AdminCheck.isRunningAsAdminFresh()) {
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
        } catch (Exception ignored) {
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Revert driver for:\n" + row.entry().friendlyName()
                        + "\n\nTo version: " + row.entry().version()
                        + "\n\nBacked up: " + row.backedUpAtProperty().get()
                        + "\nHealth: " + row.statusProperty().get() + " (" + row.getInfCount() + " INF file(s))");
        confirm.setHeaderText("Revert driver?");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        busy.set(true);
        AppExecutors.ioPool().execute(() -> {
            try {
                rollbackBackupService.revert(row.entry());
                // Verify the active driver actually matches the backup
                // version: pnputil stages the old INF but Windows may keep
                // the newer driver bound until reboot/manual rollback.
                String verifyMsg = verifyRevertedVersion(row);
                Platform.runLater(() -> {
                    if (verifyMsg == null) {
                        new Alert(Alert.AlertType.INFORMATION,
                                "Driver reverted to " + row.entry().version() + ". Restart if devices do not work correctly.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.WARNING,
                                "Backup staged, but the active driver does not yet match "
                                + row.entry().version() + ".\n\n" + verifyMsg
                                + "\n\nRestart, then use Device Manager → Rollback if needed.").showAndWait();
                    }
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Revert failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
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
            com.sbtools.drivers.DriverScanService scanner = new com.sbtools.drivers.DriverScanService();
            com.sbtools.drivers.model.InstalledDriver fresh =
                    scanner.scanSingleDriver(row.entry().deviceId());
            if (fresh == null) return "Device no longer found after restore.";
            String active = fresh.driverVersion() == null ? "" : fresh.driverVersion().trim();
            String expected = row.entry().version() == null ? "" : row.entry().version().trim();
            if (!expected.isBlank() && active.equals(expected)) return null;
            return "Active version is " + (active.isBlank() ? "unknown" : active)
                    + ", expected " + (expected.isBlank() ? "backup version" : expected) + ".";
        } catch (Exception ex) {
            AppLogger.warning("Post-revert verification failed: " + ex.getMessage());
            return "Could not verify active version (" + ex.getMessage() + ").";
        }
    }

    private void deleteAllBackups() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Deleting backups requires administrator rights.").showAndWait();
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
            try {
                rollbackBackupService.removeAll();
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "All driver backups deleted.").showAndWait();
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backups:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void deleteSingleBackup(RestoreRow row) {
        if (row == null) {
            return;
        }
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Deleting backups requires administrator rights.").showAndWait();
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
            try {
                rollbackBackupService.removeBackupEntry(row.entry());
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION, "Backup deleted.").showAndWait();
                    refreshRollback();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backup:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
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
                List<RestoreRow> checked = new ArrayList<>();
                int ok = 0;
                for (var e : entries) {
                    if (e == null || e.id() == null) {
                        continue;
                    }
                    com.sbtools.backup.BackupHealth.Stats stats =
                            com.sbtools.backup.BackupHealth.inspect(e.backupFolder());
                    if (com.sbtools.backup.BackupHealth.isHealthy(stats.status())) {
                        ok++;
                    }
                    checked.add(new RestoreRow(e));
                }
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList(checked);
                RestoreRow.computeAllSizesAsync(newRows);
                final int okFinal = ok;
                final int totalFinal = checked.size();
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
                    refreshRollback();
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
                Platform.runLater(() -> {
                    Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                    confirm.setTitle("Repair stale backups");
                    confirm.setHeaderText(count + " stale index entr" + (count == 1 ? "y" : "ies") + " found");
                    confirm.setContentText("These backups are missing, empty or unreadable on disk:\n\n" + list
                            + "\nRemove their index entries? Folders (if any) are left untouched.\nNothing else will be deleted.");
                    if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
                        return;
                    }
                    busy.set(true);
                    AppExecutors.ioPool().execute(() -> {
                        try {
                            rollbackBackupService.purgeStaleIndexEntries(stale);
                            Platform.runLater(() -> {
                                new Alert(Alert.AlertType.INFORMATION,
                                        "Removed " + count + " stale index entr" + (count == 1 ? "y" : "ies") + ".").showAndWait();
                                refreshRollback();
                            });
                        } catch (Exception ex) {
                            Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                                    "Repair failed:\n" + ex.getMessage()).showAndWait());
                        } finally {
                            Platform.runLater(() -> busy.set(false));
                        }
                    });
                });
            } catch (Exception ex) {
                AppLogger.error("Repair check failed", ex);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Repair check failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> {
                    if (busy.get()) {
                        // Outer task done; inner purge task re-sets busy if confirmed.
                        busy.set(false);
                    }
                    refreshRollback();
                });
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
            scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton);
        } else {
            statusLabel.setText("System Restore is available on Windows only.");
            scanButton.setDisable(true);
            createButton.setDisable(true);
            launchButton.setDisable(true);
        }

        Tab tab = new Tab("System restore");
        tab.setContent(pane);
        return tab;
    }

    private void buildSystemRestoreTable(TableView<SystemRestoreRow> table, SortedList<SystemRestoreRow> sortedRows) {
        table.setItems(sortedRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        // Note: no selection checkbox column — system restore points are
        // read-only here (scan/create/launch only, never delete).

        TableColumn<SystemRestoreRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(300);

        TableColumn<SystemRestoreRow, String> dateCol = new TableColumn<>("Creation Date/Time");
        dateCol.setCellValueFactory(c -> c.getValue().creationTimeProperty());
        dateCol.setPrefWidth(160);

        TableColumn<SystemRestoreRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> c.getValue().eventTypeProperty());
        typeCol.setPrefWidth(140);

        table.getColumns().addAll(descCol, dateCol, typeCol);
    }

    private void scanSystemRestore(SystemRestoreService service, BooleanProperty localBusy,
                                    ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                    ProgressIndicator spinner, Button scanButton, Button createButton,
                                    Button launchButton) {
        if (localBusy.get()) return;
        localBusy.set(true);
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
                        statusLabel.setText("Scan failed: Access denied (run as Administrator).");
                    } else if (msg.toLowerCase().contains("protection")) {
                        statusLabel.setText("Scan failed: System Protection disabled.");
                    } else {
                        statusLabel.setText("Scan failed: " + msg);
                    }
                    new Alert(Alert.AlertType.ERROR, "Failed to scan restore points:\n" + msg).showAndWait();
                });
            } finally {
                Platform.runLater(() -> localBusy.set(false));
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
        dialog.setTitle("Create Restore Point");
        dialog.setHeaderText("Enter a description for the new restore point:");
        dialog.setContentText("Description:");
        String description = dialog.showAndWait().orElse(null);
        if (description == null || description.isBlank()) return;

        localBusy.set(true);
        statusLabel.setText("Creating restore point...");
        final String desc = description;

        AppExecutors.ioPool().execute(() -> {
            try {
                var result = service.createRestorePoint(desc);
                boolean ok = result.success();
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
                Platform.runLater(() -> {
                    localBusy.set(false);
                    scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton);
                });
            }
        });
    }

    private void launchSystemRestore(SystemRestoreService service, Label statusLabel) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Launch System Restore");
        confirm.setHeaderText("Start Windows System Restore?");
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

    /** Extended registry areas (unchecked by default; larger/slower). Added per user request. */
    private static final String[] REGISTRY_EXTENDED_KEYS = {
            "HKLM\\SYSTEM\\CurrentControlSet\\Services",
            "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall",
            "HKLM\\SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion\\Drivers32"
    };

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
        UIButton restoreBtn = UIButton.secondary("Restore Selected");
        UIButton deleteBtn = UIButton.danger("Delete Backup");
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

        Tab tab = new Tab("Registry backup");
        tab.setContent(pane);
        return tab;
    }

    private TableView<RegistryBackupRow> buildRegistryBackupTable(ObservableList<RegistryBackupRow> rows) {
        TableView<RegistryBackupRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<RegistryBackupRow, String> fileCol = new TableColumn<>("Filename");
        fileCol.setCellValueFactory(c -> c.getValue().filenameProperty());
        fileCol.setPrefWidth(300);

        TableColumn<RegistryBackupRow, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(c -> c.getValue().dateProperty());
        dateCol.setPrefWidth(160);

        TableColumn<RegistryBackupRow, String> sizeCol = new TableColumn<>("Size");
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
        dialog.setTitle("Registry Backup");
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

        Label coreLabel = new Label("Core autostart areas (recommended):");
        coreLabel.setStyle("-fx-font-weight: bold;");
        Label extLabel = new Label("Extended areas (optional, larger):");
        extLabel.setStyle("-fx-font-weight: bold;");
        List<CheckBox> extBoxes = new ArrayList<>();
        for (String key : REGISTRY_EXTENDED_KEYS) {
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

        registryBusy.set(true);
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
                for (String area : selectedFinal) {
                    String safeName = area.replace('\\', '_').replace(':', '_');
                    Path outputFile = backupDir.resolve(safeName + ".reg");
                    List<String> exportArgs = new ArrayList<>(
                            List.of("reg", "export", area, outputFile.toString(), "/y"));
                    ProcessBuilder pb = new ProcessBuilder(exportArgs);
                    pb.redirectErrorStream(true);
                    Process process = ProcessManager.start(pb);
                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        failedCount++;
                        AppLogger.warning("reg export timed out for " + area);
                    } else if (process.exitValue() == 0) {
                        exportedFiles.add(outputFile.getFileName().toString());
                    } else {
                        failedCount++;
                        AppLogger.warning("reg export failed for " + area + " (exit=" + process.exitValue() + ")");
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
                if (totalOk == 0) {
                    // Non-empty dirs throw on deleteIfExists: remove recursively
                    // so failed sessions do not pollute the backup list/disk.
                    try { deleteDirectoryRecursive(backupDir); } catch (Exception ignored) {}
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup failed.");
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to export any registry areas.").showAndWait();
                    });
                } else {
                    String msg = (failedCount == 0 && hivFailed == 0)
                            ? "Registry exported to:\n" + backupDir
                            : "Partial backup: " + totalOk + "/" + totalRequested
                                    + " areas exported to:\n" + backupDir;
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
                Platform.runLater(() -> registryBusy.set(false));
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
        confirm.setTitle("Restore Registry Backup");
        confirm.setHeaderText("Import registry session: " + selected.getFilename());
        confirm.setContentText("Windows MERGES .reg files: values added after the backup will NOT be removed.\n\n"
                + "A safety backup of the current state will be created first so this restore can be undone.\n\n"
                + "Ensure all work is saved before proceeding.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        registryBusy.set(true);
        statusLabel.setText("Restoring registry backup...");

        AppExecutors.ioPool().execute(() -> {
            Path safetyDir = null;
            try {
                // Pre-restore safety net: export current state before merging,
                // so a bad restore can be undone. Best-effort, never blocks.
                try {
                    Path base = registryBackupsBaseForWrite();
                    Files.createDirectories(base);
                    safetyDir = newUniqueRegistryBackupDir(base, "registry_backup_pre-restore_");
                    Files.createDirectories(safetyDir);
                    exportCurrentRegistryForSafety(safetyDir);
                } catch (Exception safetyEx) {
                    AppLogger.warning("Pre-restore safety backup failed: " + safetyEx.getMessage());
                    safetyDir = null;
                }
                Path dirPath = resolveRegistryBackupPath(selected.getFilename());
                List<Path> regFiles;
                long hivCount = 0;
                try (var stream = Files.list(dirPath)) {
                    List<Path> all = stream.sorted().toList();
                    regFiles = all.stream()
                            .filter(p -> p.toString().toLowerCase().endsWith(".reg"))
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
                int regFailed = 0;
                for (Path regFile : regFiles) {
                    ProcessBuilder pb = new ProcessBuilder("reg", "import", regFile.toString());
                    pb.redirectErrorStream(true);
                    Process process = ProcessManager.start(pb);
                    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                        regFailed++;
                        AppLogger.warning("reg import timed out for " + regFile.getFileName());
                    } else if (process.exitValue() != 0) {
                        regFailed++;
                        AppLogger.warning("reg import failed for " + regFile.getFileName() + " (exit=" + process.exitValue() + ")");
                    }
                }
                final int imported = regFiles.size() - regFailed;
                final int regFailedFinal = regFailed;
                final int totalFiles = regFiles.size();
                final String safetyInfo = safetyDir != null
                        ? "\n\nSafety backup of pre-restore state:\n" + safetyDir
                          + "\n(Import it to undo this restore.)"
                        : "\n\nNote: pre-restore safety backup could not be created.";
                final String mergeNote = "\n\nNote: Windows merges .reg files — entries added after the backup were NOT removed.";
                final String hivNote = hivFinal > 0
                        ? "\n\nSession also contains " + hivFinal + " .hiv file(s) which were NOT auto-imported (manual 'reg restore' required)."
                        : "";
                Platform.runLater(() -> {
                    if (regFailedFinal == 0) {
                        statusLabel.setText("Registry backup restored.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "All " + imported + " registry file(s) merged successfully."
                                        + mergeNote + hivNote + safetyInfo).showAndWait();
                        refreshRegistryBackups(rows, statusLabel);
                    } else {
                        statusLabel.setText(imported + " restored, " + regFailedFinal + " failed.");
                        new Alert(Alert.AlertType.WARNING,
                                imported + " of " + totalFiles + " registry file(s) merged.\n"
                                        + regFailedFinal + " file(s) failed." + mergeNote + hivNote + safetyInfo).showAndWait();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to restore registry backup", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Restore failed.");
                    new Alert(Alert.AlertType.ERROR,
                            "Failed to restore registry backup:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> registryBusy.set(false));
            }
        });
    }

    private void deleteRegistryBackup(TableView<RegistryBackupRow> table,
                                       ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        if (registryBusy.get()) return;
        RegistryBackupRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Deleting registry backups requires administrator rights.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Registry Backup");
        confirm.setHeaderText("Delete backup session: " + selected.getFilename());
        confirm.setContentText("This will permanently delete all registry backup files in this session.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        registryBusy.set(true);
        statusLabel.setText("Deleting backup...");
        AppExecutors.ioPool().execute(() -> {
            try {
                Path dirPath = resolveRegistryBackupPath(selected.getFilename());
                if (Files.isDirectory(dirPath)) {
                    deleteDirectoryRecursive(dirPath);
                }
                Platform.runLater(() -> {
                    // Re-scan from disk: surfaces partial-delete leftovers
                    // instead of claiming success on a ghost row.
                    refreshRegistryBackups(rows, statusLabel);
                    statusLabel.setText("Backup session deleted.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to delete registry backup", e);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR,
                        "Failed to delete backup:\n" + e.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> registryBusy.set(false));
            }
        });
    }

    private static void deleteDirectoryRecursive(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (var stream = Files.walk(directory)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                AppLogger.warning("Could not delete: " + path, e);
                            }
                        });
            }
        }
    }

    private static Path resolveRegistryBackupPath(String filename) throws IOException {
        String cleanName = filename.endsWith("/") ? filename.substring(0, filename.length() - 1) : filename;
        // Reject traversal segments up front before resolving against any root.
        if (cleanName.contains("..") || cleanName.contains("/") || cleanName.contains("\\")) {
            throw new IOException("Invalid backup path: " + filename);
        }
        for (Path base : registryBackupsRoots()) {
            Path filePath = base.resolve(cleanName).normalize();
            if (!filePath.startsWith(base)) {
                continue;
            }
            if (Files.isDirectory(filePath)) {
                return filePath;
            }
        }
        // Fall back to primary for a clear missing-dir error downstream.
        Path primary = registryBackupsRoots().get(0);
        Path filePath = primary.resolve(cleanName).normalize();
        if (!filePath.startsWith(primary)) {
            throw new IOException("Invalid backup path: " + filename);
        }
        return filePath;
    }

    /**
     * All locations that may hold registry sessions: settings-aware custom
     * dir first, then portable and legacy fallbacks. Listing all three fixes
     * the split-brain where driver backups honored the custom directory but
     * registry backups did not.
     */
    private static List<Path> registryBackupsRoots() {
        java.util.LinkedHashSet<Path> roots = new java.util.LinkedHashSet<>();
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            Path custom = AppPaths.backupsRoot(s).resolve("cleanup-backups").toAbsolutePath().normalize();
            roots.add(custom);
        } catch (Exception ignored) {}
        try {
            roots.add(AppPaths.backupsRoot().resolve("cleanup-backups").toAbsolutePath().normalize());
        } catch (Exception ignored) {}
        try {
            roots.add(AppPaths.legacyBackupsRoot().resolve("cleanup-backups").toAbsolutePath().normalize());
        } catch (Exception ignored) {}
        return new ArrayList<>(roots);
    }

    private static Path registryBackupsBaseForWrite() {
        try {
            com.sbtools.settings.AppSettings s = new com.sbtools.settings.SettingsStore().load();
            return AppPaths.backupsRoot(s).resolve("cleanup-backups");
        } catch (Exception ignored) {}
        return AppPaths.backupsRoot().resolve("cleanup-backups");
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

    /**
     * Best-effort export of the current autostart/SharedDLLs state before a
     * registry merge, so the restore can be undone. Never throws.
     */
    private static void exportCurrentRegistryForSafety(Path dir) {
        List<String> keys = new ArrayList<>(List.of(
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\Run",
                "HKLM\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce",
                "HKLM\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Run",
                "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\RunServices",
                "HKLM\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\SharedDLLs"
        ));
        // Cover extended areas on restore-undo as well (best-effort).
        keys.addAll(List.of(REGISTRY_EXTENDED_KEYS));
        for (String key : keys) {
            try {
                String safeName = key.replace('\\', '_').replace(':', '_');
                Path out = dir.resolve("pre-restore_" + safeName + ".reg");
                ProcessBuilder pb = new ProcessBuilder("reg", "export", key, out.toString(), "/y");
                pb.redirectErrorStream(true);
                Process p = ProcessManager.start(pb);
                boolean finished = p.waitFor(60, TimeUnit.SECONDS);
                if (!finished) {
                    p.destroyForcibly();
                    try { Files.deleteIfExists(out); } catch (Exception ignored) {}
                } else if (p.exitValue() != 0) {
                    try { Files.deleteIfExists(out); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
    }

}
