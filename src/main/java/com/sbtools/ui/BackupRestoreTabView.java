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
import java.nio.file.attribute.BasicFileAttributes;
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

    private DriverBackupService rollbackBackupService;
    private ObservableList<RestoreRow> rollbackRows;
    private Label rollbackStatusLabel;

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
        Button refreshButton = new Button("Refresh");
        Button deleteAllButton = UIButton.danger("Delete All");
        TextField searchField = new TextField();
        searchField.setPromptText("Search backups...");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);

        Tooltip.install(refreshButton, new Tooltip("Reload the backup list from disk"));
        Tooltip.install(deleteAllButton, new Tooltip("Remove all driver backups permanently"));

        refreshButton.setOnAction(e -> refreshRollback());
        deleteAllButton.setOnAction(e -> deleteAllBackups());

        HBox top = new HBox(12, refreshButton, deleteAllButton, spinner, searchField, rollbackStatusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        FilteredList<RestoreRow> filteredList = new FilteredList<>(rollbackRows);
        SortedList<RestoreRow> sortedList = new SortedList<>(filteredList);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            filteredList.setPredicate(row -> newVal == null || newVal.isBlank() || matches(row, newVal));
        });

        TableView<RestoreRow> table = buildRollbackTable(sortedList);
        sortedList.comparatorProperty().bind(table.comparatorProperty());

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(table);

        if (AppPaths.isWindows()) {
            refreshRollback();
        } else {
            refreshButton.setDisable(true);
            deleteAllButton.setDisable(true);
        }

        Tab tab = new Tab("Rollback drivers");
        tab.setContent(pane);
        return tab;
    }

    private boolean matches(RestoreRow row, String query) {
        String q = query.toLowerCase();
        return row.deviceNameProperty().get().toLowerCase().contains(q)
                || row.versionProperty().get().toLowerCase().contains(q)
                || row.backedUpAtProperty().get().toLowerCase().contains(q);
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
                    revertBtn.setDisable(isBusy);
                    deleteBtn.setDisable(isBusy);
                    setGraphic(box);
                }
            }
        });

        table.getColumns().addAll(deviceCol, versionCol, dateCol, sizeCol, actionCol);
        return table;
    }

    private void refreshRollback() {
        AppExecutors.ioPool().execute(() -> {
            try {
                var entries = rollbackBackupService.listAll();
                long totalBytes = rollbackBackupService.getTotalSize();
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList();
                for (var e : entries) {
                    RestoreRow row = new RestoreRow(e);
                    newRows.add(row);
                }
                RestoreRow.computeAllSizesAsync(newRows);
                String sizeStr = RestoreRow.formatFileSize(totalBytes);
                Platform.runLater(() -> {
                    rollbackRows.setAll(newRows);
                    rollbackStatusLabel.setText(entries.isEmpty()
                            ? "No backups yet. Backups are created automatically before driver updates."
                            : entries.size() + " backup(s) available \u2014 " + sizeStr + " total");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> rollbackStatusLabel.setText("Failed to load backups: " + ex.getMessage()));
            }
        });
    }

    private void revertRollback(RestoreRow row) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Reverting drivers requires administrator rights.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Revert driver for:\n" + row.entry().friendlyName()
                        + "\n\nTo version: " + row.entry().version()
                        + "\n\nBacked up: " + row.backedUpAtProperty().get());
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
            return;
        }
        busy.set(true);
        AppExecutors.ioPool().execute(() -> {
            try {
                rollbackBackupService.revert(row.entry());
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.INFORMATION,
                            "Driver reverted. Restart if devices do not work correctly.").showAndWait();
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

    private void deleteAllBackups() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete all driver backups?\n\nThis will permanently remove all backup data.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
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
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Deleting backups requires administrator rights.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete backup for: " + row.entry().friendlyName()
                        + "\nVersion: " + row.entry().version()
                        + "\nBacked up: " + row.backedUpAtProperty().get());
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

        scanSystemRestore(service, localBusy, rows, statusLabel, spinner, scanButton, createButton, launchButton);

        Tab tab = new Tab("System restore");
        tab.setContent(pane);
        return tab;
    }

    private void buildSystemRestoreTable(TableView<SystemRestoreRow> table, SortedList<SystemRestoreRow> sortedRows) {
        table.setItems(sortedRows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SystemRestoreRow, SystemRestoreRow> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private SystemRestoreRow previousItem;

            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }

            @Override
            protected void updateItem(SystemRestoreRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (previousItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                        previousItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (previousItem != null && previousItem != item) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                    }
                    if (checkBox.selectedProperty().isBound()) {
                        checkBox.selectedProperty().unbind();
                    }
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<SystemRestoreRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(300);

        TableColumn<SystemRestoreRow, String> dateCol = new TableColumn<>("Creation Date/Time");
        dateCol.setCellValueFactory(c -> c.getValue().creationTimeProperty());
        dateCol.setPrefWidth(160);

        TableColumn<SystemRestoreRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> c.getValue().eventTypeProperty());
        typeCol.setPrefWidth(140);

        table.getColumns().addAll(checkCol, descCol, dateCol, typeCol);
    }

    private void scanSystemRestore(SystemRestoreService service, BooleanProperty localBusy,
                                    ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                    ProgressIndicator spinner, Button scanButton, Button createButton,
                                    Button launchButton) {
        if (localBusy.get()) return;
        localBusy.set(true);
        statusLabel.setText("Scanning restore points...");
        rows.clear();

        AppExecutors.ioPool().execute(() -> {
            try {
                List<SystemRestoreRow> results = service.listRestorePoints();
                Platform.runLater(() -> {
                    rows.setAll(results);
                    statusLabel.setText(results.size() + " restore point(s) found.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to scan restore points", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan restore points:\n" + e.getMessage()).showAndWait();
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
                boolean ok = service.createRestorePoint(desc).success();
                Platform.runLater(() -> {
                    if (ok) {
                        statusLabel.setText("Restore point created.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Restore point '" + desc + "' created successfully.").showAndWait();
                    } else {
                        statusLabel.setText("Failed to create restore point.");
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to create restore point. Ensure System Protection is enabled for your system drive.")
                                .showAndWait();
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

    private Tab buildRegistryBackupTab() {
        ObservableList<RegistryBackupRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("No registry backups found.");
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);
        TableView<RegistryBackupRow> table = buildRegistryBackupTable(rows);

        UIButton backupNowBtn = UIButton.primary("Backup Now");
        UIButton restoreBtn = UIButton.secondary("Restore Selected");
        UIButton deleteBtn = UIButton.danger("Delete Backup");

        Tooltip.install(backupNowBtn, new Tooltip("Export selected registry areas to a backup file"));
        Tooltip.install(restoreBtn, new Tooltip("Import the selected registry backup file"));
        Tooltip.install(deleteBtn, new Tooltip("Remove the selected registry backup file"));

        restoreBtn.setDisable(true);
        deleteBtn.setDisable(true);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            boolean hasSel = newSel != null;
            restoreBtn.setDisable(!hasSel);
            deleteBtn.setDisable(!hasSel);
        });

        backupNowBtn.setOnAction(e -> backupRegistry(rows, statusLabel));
        restoreBtn.setOnAction(e -> restoreRegistryBackup(table, rows, statusLabel));
        deleteBtn.setOnAction(e -> deleteRegistryBackup(table, rows, statusLabel));

        HBox top = new HBox(12, backupNowBtn, restoreBtn, deleteBtn, spinner, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        FilteredList<RegistryBackupRow> filteredList = new FilteredList<>(rows);
        SortedList<RegistryBackupRow> sortedList = new SortedList<>(filteredList);
        sortedList.comparatorProperty().bind(table.comparatorProperty());

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
                Path backupsDir = AppPaths.backupsRoot().resolve("cleanup-backups");
                if (!Files.isDirectory(backupsDir)) {
                    Platform.runLater(() -> {
                        rows.clear();
                        statusLabel.setText("No registry backups found.");
                    });
                    return;
                }
                List<RegistryBackupRow> results = new ArrayList<>();
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                try (var stream = Files.walk(backupsDir)) {
                    stream.filter(p -> p.toString().toLowerCase().endsWith(".reg"))
                            .sorted((a, b) -> {
                                try {
                                    return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
                                } catch (IOException e) {
                                    return 0;
                                }
                            })
                            .forEach(p -> {
                                try {
                                    BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class);
                                    String filename = backupsDir.relativize(p).toString().replace('\\', '/');
                                    String date = sdf.format(new Date(attrs.lastModifiedTime().to(TimeUnit.MILLISECONDS)));
                                    String size = formatFileSize(attrs.size());
                                    results.add(new RegistryBackupRow(filename, date, size));
                                } catch (IOException ignored) {
                                }
                            });
                }
                Platform.runLater(() -> {
                    rows.setAll(results);
                    statusLabel.setText(results.size() + " registry backup(s) found.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to list registry backups", e);
                Platform.runLater(() -> statusLabel.setText("Failed to load backups."));
            }
        });
    }

    private void backupRegistry(ObservableList<RegistryBackupRow> rows, Label statusLabel) {
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

        VBox checks = new VBox(6, hkcuRun, hklmRun, hklmRunOnce, hkcuRunOnce, hklmWowRun, hkcuRunServices);
        checks.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(checks);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(btn -> {
            if (btn == ButtonType.OK) {
                List<String> selected = new ArrayList<>();
                if (hkcuRun.isSelected()) selected.add(hkcuRun.getText());
                if (hklmRun.isSelected()) selected.add(hklmRun.getText());
                if (hklmRunOnce.isSelected()) selected.add(hklmRunOnce.getText());
                if (hkcuRunOnce.isSelected()) selected.add(hkcuRunOnce.getText());
                if (hklmWowRun.isSelected()) selected.add(hklmWowRun.getText());
                if (hkcuRunServices.isSelected()) selected.add(hkcuRunServices.getText());
                return selected;
            }
            return null;
        });

        List<String> selected = dialog.showAndWait().orElse(null);
        if (selected == null || selected.isEmpty()) return;

        busy.set(true);
        statusLabel.setText("Creating registry backup...");

        AppExecutors.ioPool().execute(() -> {
            try {
                Path backupsDir = AppPaths.backupsRoot().resolve("cleanup-backups");
                Files.createDirectories(backupsDir);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                Path backupDir = backupsDir.resolve("registry_backup_" + timestamp);
                Files.createDirectories(backupDir);

                int failedCount = 0;
                List<String> exportedFiles = new ArrayList<>();
                for (String area : selected) {
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

                if (failedCount == selected.size()) {
                    Files.deleteIfExists(backupDir);
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup failed.");
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to export any registry areas.").showAndWait();
                    });
                } else {
                    String msg = failedCount == 0
                            ? "Registry exported to:\n" + backupDir
                            : "Partial backup: " + (selected.size() - failedCount) + "/" + selected.size()
                                    + " areas exported to:\n" + backupDir;
                    Platform.runLater(() -> {
                        statusLabel.setText("Registry backup created.");
                        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
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
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void restoreRegistryBackup(TableView<RegistryBackupRow> table,
                                        ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        RegistryBackupRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Restoring registry backups requires administrator rights.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Restore Registry Backup");
        confirm.setHeaderText("Import registry file: " + selected.getFilename());
        confirm.setContentText("This will merge the selected .reg file into the Windows registry.\n\n"
                + "Ensure all work is saved before proceeding.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        busy.set(true);
        statusLabel.setText("Restoring registry backup...");

        AppExecutors.ioPool().execute(() -> {
            try {
                Path filePath = resolveRegistryBackupPath(selected.getFilename());
                ProcessBuilder pb = new ProcessBuilder("reg", "import", filePath.toString());
                pb.redirectErrorStream(true);
                Process process = ProcessManager.start(pb);
                boolean finished = process.waitFor(120, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    throw new IOException("Registry import timed out after 120 seconds");
                }
                int exitCode = process.exitValue();

                Platform.runLater(() -> {
                    if (exitCode == 0) {
                        statusLabel.setText("Registry backup restored.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Registry backup imported successfully.").showAndWait();
                    } else {
                        statusLabel.setText("Restore failed.");
                        new Alert(Alert.AlertType.ERROR,
                                "reg import exited with code " + exitCode).showAndWait();
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
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void deleteRegistryBackup(TableView<RegistryBackupRow> table,
                                       ObservableList<RegistryBackupRow> rows, Label statusLabel) {
        RegistryBackupRow selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Delete Registry Backup");
        confirm.setHeaderText("Delete backup file: " + selected.getFilename());
        confirm.setContentText("This will permanently delete the selected registry backup file.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        try {
            Path filePath = resolveRegistryBackupPath(selected.getFilename());
            Files.deleteIfExists(filePath);
            rows.remove(selected);
            statusLabel.setText("Backup deleted.");
        } catch (Exception e) {
            AppLogger.error("Failed to delete registry backup", e);
            new Alert(Alert.AlertType.ERROR,
                    "Failed to delete backup:\n" + e.getMessage()).showAndWait();
        }
    }

    private static Path resolveRegistryBackupPath(String filename) throws IOException {
        Path base = AppPaths.backupsRoot().resolve("cleanup-backups");
        Path filePath = base.resolve(filename).normalize();
        if (!filePath.startsWith(base)) {
            throw new IOException("Invalid backup path: " + filename);
        }
        return filePath;
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
