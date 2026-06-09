package com.sbtools.ui;

import com.sbtools.backup.DriverBackupService;
import com.sbtools.backup.RestoreRow;
import com.sbtools.backup.SystemRestoreRow;
import com.sbtools.backup.SystemRestoreService;
import com.sbtools.util.AdminCheck;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
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
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.File;
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

    // ── Rollback drivers tab (from RestoreTabView) ───────────────────────────

    private Tab buildRollbackTab() {
        DriverBackupService backupService = new DriverBackupService();
        ObservableList<RestoreRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("Driver backups appear here (up to 3 versions per device).");
        Button refreshButton = new Button("Refresh");

        refreshButton.setOnAction(e -> refreshRollback(backupService, rows, statusLabel));

        HBox top = new HBox(12, refreshButton, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        TableView<RestoreRow> table = buildRollbackTable(rows);
        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(table);

        if (AppPaths.isWindows()) {
            refreshRollback(backupService, rows, statusLabel);
        } else {
            refreshButton.setDisable(true);
        }

        Tab tab = new Tab("Rollback drivers");
        tab.setContent(pane);
        return tab;
    }

    private TableView<RestoreRow> buildRollbackTable(ObservableList<RestoreRow> rows) {
        TableView<RestoreRow> table = new TableView<>(rows);
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

        TableColumn<RestoreRow, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(90);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton revertBtn = UIButton.small("Revert");

            {
                revertBtn.setOnAction(e -> {
                    RestoreRow row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        revertRollback(row);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    revertBtn.setDisable(busy.get());
                    setGraphic(revertBtn);
                }
            }
        });

        table.getColumns().addAll(deviceCol, versionCol, dateCol, actionCol);
        return table;
    }

    private void refreshRollback(DriverBackupService backupService,
                                  ObservableList<RestoreRow> rows, Label statusLabel) {
        new Thread(() -> {
            try {
                var entries = backupService.listAll();
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList();
                for (var e : entries) {
                    newRows.add(new RestoreRow(e));
                }
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    statusLabel.setText(entries.isEmpty()
                            ? "No backups yet. Backups are created automatically before driver updates."
                            : entries.size() + " backup(s) available.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> statusLabel.setText("Failed to load backups: " + ex.getMessage()));
            }
        }, "restore-refresh").start();
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
        if (confirm.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        DriverBackupService backupService = new DriverBackupService();
        busy.set(true);
        new Thread(() -> {
            try {
                backupService.revert(row.entry());
                Platform.runLater(() -> {
                    Label label = new Label("Reverted " + row.entry().friendlyName());
                    new Alert(Alert.AlertType.INFORMATION, "Driver reverted. Restart if devices do not work correctly.").showAndWait();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Revert failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "driver-revert").start();
    }

    // ── System restore tab (from SystemRestoreTabView) ───────────────────────

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

        buildSystemRestoreTable(table);
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

    private void buildSystemRestoreTable(TableView<SystemRestoreRow> table) {
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

        new Thread(() -> {
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
        }, "restore-scan").start();
    }

    private void createSystemRestorePoint(SystemRestoreService service, BooleanProperty localBusy,
                                           ObservableList<SystemRestoreRow> rows, Label statusLabel,
                                           ProgressIndicator spinner, Button scanButton, Button createButton,
                                           Button launchButton) {
        if (localBusy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Creating system restore points requires administrator rights.").showAndWait();
            try {
                AdminCheck.requestElevation();
            } catch (java.io.IOException ex) {
                AppLogger.warning("Failed to request elevation: " + ex.getMessage());
            }
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

        new Thread(() -> {
            try {
                boolean ok = service.createRestorePoint(desc);
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
        }, "restore-create").start();
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

    // ── Registry backup tab (new) ────────────────────────────────────────────

    private Tab buildRegistryBackupTab() {
        ObservableList<RegistryBackupRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("No registry backups found.");
        TableView<RegistryBackupRow> table = buildRegistryBackupTable(rows);

        UIButton backupNowBtn = UIButton.primary("Backup Now");
        UIButton restoreBtn = UIButton.secondary("Restore Selected");
        UIButton deleteBtn = UIButton.danger("Delete Backup");

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

        HBox top = new HBox(12, backupNowBtn, restoreBtn, deleteBtn, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

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
        new Thread(() -> {
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
                try (var stream = Files.list(backupsDir)) {
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
                                    String filename = p.getFileName().toString();
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
        }, "registry-refresh").start();
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

        new Thread(() -> {
            try {
                Path backupsDir = AppPaths.backupsRoot().resolve("cleanup-backups");
                Files.createDirectories(backupsDir);

                String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
                Path outputFile = backupsDir.resolve("registry_backup_" + timestamp + ".reg");

                List<String> exportArgs = new ArrayList<>();
                exportArgs.add("reg");
                exportArgs.add("export");

                for (String area : selected) {
                    exportArgs.add(area);
                }
                exportArgs.add(outputFile.toString());
                exportArgs.add("/y");

                ProcessBuilder pb = new ProcessBuilder(exportArgs);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();

                if (exitCode == 0) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Registry backup created.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Registry exported to:\n" + outputFile.toString()).showAndWait();
                        refreshRegistryBackups(rows, statusLabel);
                    });
                } else {
                    Platform.runLater(() -> {
                        statusLabel.setText("Backup failed.");
                        new Alert(Alert.AlertType.ERROR,
                                "reg export exited with code " + exitCode).showAndWait();
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
        }, "registry-backup").start();
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

        new Thread(() -> {
            try {
                Path filePath = AppPaths.backupsRoot().resolve("cleanup-backups").resolve(selected.getFilename());
                ProcessBuilder pb = new ProcessBuilder("reg", "import", filePath.toString());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();

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
        }, "registry-restore").start();
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
            Path filePath = AppPaths.backupsRoot().resolve("cleanup-backups").resolve(selected.getFilename());
            Files.deleteIfExists(filePath);
            rows.remove(selected);
            statusLabel.setText("Backup deleted.");
        } catch (Exception e) {
            AppLogger.error("Failed to delete registry backup", e);
            new Alert(Alert.AlertType.ERROR,
                    "Failed to delete backup:\n" + e.getMessage()).showAndWait();
        }
    }

    private static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ── Registry backup row model ────────────────────────────────────────────

    public static class RegistryBackupRow {
        private final StringProperty filename;
        private final StringProperty date;
        private final StringProperty size;

        public RegistryBackupRow(String filename, String date, String size) {
            this.filename = new SimpleStringProperty(filename);
            this.date = new SimpleStringProperty(date);
            this.size = new SimpleStringProperty(size);
        }

        public StringProperty filenameProperty() { return filename; }
        public StringProperty dateProperty() { return date; }
        public StringProperty sizeProperty() { return size; }

        public String getFilename() { return filename.get(); }
        public String getDate() { return date.get(); }
        public String getSize() { return size.get(); }
    }
}
