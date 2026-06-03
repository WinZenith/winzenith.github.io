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
import javafx.collections.FXCollections;

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
    private TableCell<SoftwareUpdateEntry, Void> currentInstallCell;

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
        top.getStyleClass().add("toolbar");

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

        busy.addListener((obs, oldVal, newVal) -> table.refresh());

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
        actionCol.setPrefWidth(280);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final Button updateBtn = new Button("Update");
            private final ProgressBar downloadProgress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            private final Label sizeLabel = new Label("Installing…");
            private final Label installingLabel = new Label("Installing update. Please wait…");
            private final ProgressIndicator spinner = new ProgressIndicator();

            {
                spinner.setPrefSize(48, 48);
                spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                spinner.setVisible(false);
                installingLabel.setVisible(false);
                downloadProgress.setPrefWidth(80);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);

                updateBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) {
                        updateSingle(entry, this);
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
                    HBox container = new HBox(6, updateBtn, sizeLabel, downloadProgress, installingLabel, spinner);
                    container.setAlignment(Pos.CENTER_LEFT);
                    setGraphic(container);
                }
            }

            private void showInstallingState() {
                updateBtn.setVisible(false);
                downloadProgress.setVisible(true);
                downloadProgress.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                sizeLabel.setVisible(true);
                sizeLabel.setText("Installing…");
                installingLabel.setVisible(true);
                spinner.setVisible(true);
            }

            private void hideInstallingState() {
                updateBtn.setVisible(true);
                updateBtn.setDisable(false);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);
                installingLabel.setVisible(false);
                spinner.setVisible(false);
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

    @SuppressWarnings("unchecked")
    private void showCellInstallingState(TableCell<SoftwareUpdateEntry, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox container) {
                for (var child : container.getChildren()) {
                    if (child instanceof Button btn) {
                        btn.setVisible(false);
                    } else if (child instanceof ProgressBar pb) {
                        pb.setVisible(true);
                        pb.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    } else if (child instanceof Label label) {
                        if (label.getText() != null && label.getText().contains("Installing")) {
                            label.setVisible(true);
                        } else {
                            label.setVisible(true);
                            label.setText("Installing…");
                        }
                    } else if (child instanceof ProgressIndicator pi) {
                        pi.setVisible(true);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
    }

    @SuppressWarnings("unchecked")
    private void hideCellInstallingState(TableCell<SoftwareUpdateEntry, Void> cell) {
        try {
            if (cell.getGraphic() instanceof HBox container) {
                for (var child : container.getChildren()) {
                    if (child instanceof Button btn) {
                        btn.setVisible(true);
                        btn.setDisable(false);
                    } else if (child instanceof ProgressBar pb) {
                        pb.setVisible(false);
                    } else if (child instanceof Label label) {
                        label.setVisible(false);
                        label.setText("");
                    } else if (child instanceof ProgressIndicator pi) {
                        pi.setVisible(false);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore
        }
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
            boolean created = restoreService.createRestorePoint("SB Tools software update");
            if (!created) {
                AppLogger.warning("Restore point creation failed or skipped.");
            }
        }
        busy.set(true);
        statusLabel.setText("Installing " + selected.size() + " update(s) …");

        new Thread(() -> {
            for (SoftwareUpdateEntry e : selected) {
                try {
                    Instant start = Instant.now();
                    Platform.runLater(() -> statusLabel.setText("Installing " + e.getName() + "…"));
                    ProcessResult res = service.updatePackage(e.id(), true, 1200);
                    if (res.success()) {
                        List<Path> candidates = service.findCandidateInstallersForPackage(e, start);
                        if (candidates != null && !candidates.isEmpty()) {
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
                                    service.deleteInstallerFiles(candidates);
                                }
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        Platform.runLater(() -> {
                            statusLabel.setText("Update installed for " + e.getName());
                            rows.remove(e);
                        });
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

    private void updateSingle(SoftwareUpdateEntry entry, TableCell<SoftwareUpdateEntry, Void> cell) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing updates may require administrator rights.").showAndWait();
            return;
        }
        Alert confirmRestore = new Alert(Alert.AlertType.CONFIRMATION, "Would you like to create a System Restore Point before proceeding with the update?");
        confirmRestore.setHeaderText(AppInfo.DISPLAY_NAME);
        if (confirmRestore.showAndWait().orElse(null) == javafx.scene.control.ButtonType.OK) {
            restoreService.createRestorePoint("SB Tools software update");
        }
        busy.set(true);
        currentInstallCell = cell;
        statusLabel.setText("Installing update for " + entry.getName() + " …");
        Platform.runLater(() -> showCellInstallingState(cell));

        new Thread(() -> {
            try {
                Instant start = Instant.now();
                ProcessResult res = service.updatePackage(entry.id(), true, 1200);
                if (res.success()) {
                    List<Path> candidates = service.findCandidateInstallersForPackage(entry, start);
                    if (candidates != null && !candidates.isEmpty()) {
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
                                service.deleteInstallerFiles(candidates);
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
                Platform.runLater(() -> {
                    if (currentInstallCell != null) {
                        hideCellInstallingState(currentInstallCell);
                    }
                    currentInstallCell = null;
                    busy.set(false);
                });
            }
        }, "software-install-single").start();
    }
}
