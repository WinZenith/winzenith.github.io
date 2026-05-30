package com.basicsdriverupdate.ui;

import com.basicsdriverupdate.backup.SystemRestoreService;
import com.basicsdriverupdate.software.SoftwareUpdateEntry;
import com.basicsdriverupdate.software.SoftwareUpdateService;
import com.basicsdriverupdate.util.AppInfo;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.function.BooleanSupplier;
import java.nio.file.Path;

public class SoftwareUpdatesTabView extends BorderPane {

    private final SoftwareUpdateService service = new SoftwareUpdateService();
    private final SystemRestoreService restoreService = new SystemRestoreService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<SoftwareUpdateEntry> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Scan for available app updates via winget.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button scanButton = new Button("Scan");
    private final Button updateSelectedButton = new Button("Update Selected");

    public SoftwareUpdatesTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        scanButton.setOnAction(e -> scan());
        updateSelectedButton.setOnAction(e -> updateSelected());
        updateSelectedButton.setDisable(true);

        HBox top = new HBox(12, scanButton, updateSelectedButton, progress, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        TableView<SoftwareUpdateEntry> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(top);
        setCenter(table);

        rows.addListener((ListChangeListener<SoftwareUpdateEntry>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (SoftwareUpdateEntry entry : c.getAddedSubList()) {
                        entry.selectedProperty().addListener((obs, oldVal, newVal) -> updateInstallButtonState());
                    }
                }
            }
            updateInstallButtonState();
        });

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            updateSelectedButton.setDisable(true);
            statusLabel.setText("This application requires Windows.");
        }
    }

    private TableView<SoftwareUpdateEntry> buildTable() {
        TableView<SoftwareUpdateEntry> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SoftwareUpdateEntry, Boolean> selCol = new TableColumn<>("Install");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setPrefWidth(60);

        TableColumn<SoftwareUpdateEntry, String> nameCol = new TableColumn<>("Program");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());

        TableColumn<SoftwareUpdateEntry, String> currentCol = new TableColumn<>("Current Version");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(120);

        TableColumn<SoftwareUpdateEntry, String> availCol = new TableColumn<>("Available Version");
        availCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availCol.setPrefWidth(120);

        TableColumn<SoftwareUpdateEntry, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(90);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button updateBtn = new Button("Update");

            {
                updateBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        updateSingle(entry);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    updateBtn.setDisable(entry == null || busy.get());
                    setGraphic(updateBtn);
                }
            }
        });

        table.getColumns().addAll(selCol, nameCol, currentCol, availCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<SoftwareUpdateEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty()) {
                    SoftwareUpdateEntry entry = row.getItem();
                    entry.selectedProperty().set(!entry.selectedProperty().get());
                }
            });
            return row;
        });

        return table;
    }

    private void updateInstallButtonState() {
        boolean any = rows.stream().anyMatch(r -> r.selectedProperty().get());
        updateSelectedButton.setDisable(!any || busy.get());
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        progress.setVisible(true);
        scanButton.setDisable(true);
        statusLabel.setText("Checking winget for updates…");
        rows.clear();
        new Thread(() -> {
            try {
                if (!service.isWingetAvailable()) {
                    String diag = service.getWingetDiagnostics();
                    final String diagnostics = diag;
                    Platform.runLater(() -> {
                        statusLabel.setText("winget not found on PATH.");
                        TextArea ta = new TextArea(diagnostics);
                        ta.setEditable(false);
                        ta.setWrapText(true);
                        ta.setPrefRowCount(12);
                        ta.setPrefColumnCount(80);

                        Button storeBtn = new Button("Open App Installer in Microsoft Store");
                        storeBtn.setOnAction(evt -> {
                            try {
                                java.awt.Desktop.getDesktop().browse(new java.net.URI("ms-windows-store://pdp/?productid=9NBLGGH4NNS1"));
                            } catch (Exception ex) {
                                try {
                                    new ProcessBuilder("cmd.exe", "/c", "start", "", "https://www.microsoft.com/store/apps/9NBLGGH4NNS1").start();
                                } catch (Exception ex2) {
                                    new Alert(Alert.AlertType.ERROR, "Could not open Store: " + ex2.getMessage()).showAndWait();
                                }
                            }
                        });

                        Button aliasBtn = new Button("Open App execution aliases settings");
                        aliasBtn.setOnAction(evt -> {
                            try {
                                java.awt.Desktop.getDesktop().browse(new java.net.URI("ms-settings:appsfeatures"));
                            } catch (Exception ex) {
                                try {
                                    new ProcessBuilder("cmd.exe", "/c", "start", "", "ms-settings:appsfeatures").start();
                                } catch (Exception ex2) {
                                    new Alert(Alert.AlertType.ERROR, "Could not open Settings: " + ex2.getMessage()).showAndWait();
                                }
                            }
                        });

                        HBox btnBox = new HBox(8, storeBtn, aliasBtn);
                        VBox content = new VBox(new Label("winget is not available on this system. Diagnostic output:"), ta, btnBox);
                        content.setSpacing(8);
                        Alert a = new Alert(Alert.AlertType.ERROR);
                        a.setTitle(AppInfo.DISPLAY_NAME);
                        a.getDialogPane().setContent(content);
                        a.showAndWait();
                    });
                    return;
                }
                List<SoftwareUpdateEntry> updates = service.scanForUpdates();
                Platform.runLater(() -> {
                    rows.setAll(updates);
                    statusLabel.setText(updates.size() + " outdated program(s) found.");
                    updateInstallButtonState();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    scanButton.setDisable(false);
                });
            }
        }, "software-scan").start();
    }

    private void updateSelected() {
        if (!adminCheck.getAsBoolean()) {
            Platform.runLater(() -> new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait());
            return;
        }
        List<SoftwareUpdateEntry> selected = rows.stream().filter(r -> r.selectedProperty().get()).collect(Collectors.toList());
        if (selected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Select at least one program to update.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the updates?");
        confirm.setHeaderText(AppInfo.DISPLAY_NAME);
        if (confirm.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
            boolean created = restoreService.createRestorePoint("SBasic software update");
            if (!created) {
                // proceed but log
                AppLogger.warning("Restore point creation failed or skipped.");
            }
        }
        busy.set(true);
        statusLabel.setText("Installing " + selected.size() + " update(s) …");
        new Thread(() -> {
            for (SoftwareUpdateEntry e : selected) {
                try {
                    Instant start = Instant.now();
                    ProcessResult res = service.updatePackage(e.id(), true, 1200);
                    if (res.success()) {
                        // Find candidate installer files (do not delete yet)
                        List<Path> candidates = service.findCandidateInstallersForPackage(e, start);
                        if (candidates == null || candidates.isEmpty()) {
                            AppLogger.info("No installer candidates found for " + e.id());
                        } else {
                            AtomicBoolean userConfirmed = new AtomicBoolean(false);
                            CountDownLatch latch = new CountDownLatch(1);
                            Platform.runLater(() -> {
                                StringBuilder sb = new StringBuilder();
                                for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
                                Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                                        "The following installer files were detected in your Downloads folder:\n\n" + sb.toString() + "\nDelete these files?");
                                del.setHeaderText("Delete installer files for " + (e.getName() != null ? e.getName() : e.id()));
                                if (del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                                    userConfirmed.set(true);
                                }
                                latch.countDown();
                            });
                            try {
                                latch.await();
                                if (userConfirmed.get()) {
                                    List<Path> deleted = service.deleteInstallerFiles(candidates);
                                    AppLogger.info("Deleted " + deleted.size() + " installer(s) for " + e.id());
                                } else {
                                    AppLogger.info("User declined to delete installers for " + e.id());
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        AppLogger.warning("Update failed for " + e.id() + ": " + res.combinedOutput());
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Exception during update: " + ex.getMessage());
                }
            }
            Platform.runLater(() -> {
                busy.set(false);
                statusLabel.setText("Selected updates finished. Re-scan to refresh list.");
                scan();
            });
        }, "software-install").start();
    }

    private void updateSingle(SoftwareUpdateEntry entry) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait();
            return;
        }
        Alert confirmRestore = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the update?");
        confirmRestore.setHeaderText(AppInfo.DISPLAY_NAME);
        if (confirmRestore.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
            restoreService.createRestorePoint("SBasic software update");
        }
        busy.set(true);
        statusLabel.setText("Installing update for " + entry.getName() + " …");
        new Thread(() -> {
            try {
                Instant start = Instant.now();
                ProcessResult res = service.updatePackage(entry.id(), true, 1200);
                if (res.success()) {
                    // Find candidates and ask user before deletion
                    List<Path> candidates = service.findCandidateInstallersForPackage(entry, start);
                    if (candidates == null || candidates.isEmpty()) {
                        AppLogger.info("No installer candidates found for " + entry.id());
                    } else {
                        AtomicBoolean userConfirmed = new AtomicBoolean(false);
                        CountDownLatch latch = new CountDownLatch(1);
                        Platform.runLater(() -> {
                            StringBuilder sb = new StringBuilder();
                            for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
                            Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                                    "The following installer files were detected in your Downloads folder:\n\n" + sb.toString() + "\nDelete these files?");
                            del.setHeaderText("Delete installer files for " + (entry.getName() != null ? entry.getName() : entry.id()));
                            if (del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                                userConfirmed.set(true);
                            }
                            latch.countDown();
                        });
                        try {
                            latch.await();
                            if (userConfirmed.get()) {
                                List<Path> deleted = service.deleteInstallerFiles(candidates);
                                AppLogger.info("Deleted " + deleted.size() + " installer(s) for " + entry.id());
                            } else {
                                AppLogger.info("User declined to delete installers for " + entry.id());
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    Platform.runLater(() -> {
                        statusLabel.setText("Update installed for " + entry.getName());
                        rows.remove(entry);
                    });
                } else {
                    Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + res.combinedOutput()).showAndWait());
                }
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "software-install-single").start();
    }
}
