package com.sbtools.ui;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanupService;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;

import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;

public class CleanerTabView extends BorderPane {

    private final CleanupService service = new CleanupService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    public CleanerTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        setCenter(buildSystemCleanupContent());
    }

    private VBox buildSystemCleanupContent() {
        ObservableList<CleanupRow> rows = FXCollections.observableArrayList();
        Label statusLabel = new Label("Click Scan to analyze cleanup opportunities.");
        ProgressBar progressBar = new ProgressBar(0);
        Button scanButton = new Button("Scan");
        Button selectAllButton = new Button("Select All");
        Button cleanButton = new Button("Clean Selected");
        TableView<CleanupRow> table = new TableView<>(rows);

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        scanButton.setOnAction(e -> startScan(rows, statusLabel, progressBar, scanButton, selectAllButton, cleanButton, table));
        selectAllButton.setOnAction(e -> toggleSelectAll(rows));
        cleanButton.setOnAction(e -> startClean(rows, statusLabel, progressBar, scanButton, selectAllButton, cleanButton));
        cleanButton.setDisable(true);
        cleanButton.getStyleClass().add("danger");

        HBox top = new HBox(12, scanButton, selectAllButton, cleanButton, progressBar, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable(table, rows);

        VBox center = new VBox(8, table);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            selectAllButton.setDisable(newVal);
            cleanButton.setDisable(newVal || getSelectedCount(rows) == 0);
        });

        VBox content = new VBox(top, center);
        VBox.setVgrow(center, Priority.ALWAYS);
        return content;
    }

    private void buildTable(TableView<CleanupRow> table, ObservableList<CleanupRow> rows) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<CleanupRow, CleanupRow> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private CleanupRow previousItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }
            @Override
            protected void updateItem(CleanupRow item, boolean empty) {
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

        TableColumn<CleanupRow, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(c -> c.getValue().categoryNameProperty());
        categoryCol.setPrefWidth(200);

        TableColumn<CleanupRow, String> sizeCol = new TableColumn<>("Size / Count");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeOrCountTextProperty());
        sizeCol.setPrefWidth(150);

        table.getColumns().addAll(checkCol, categoryCol, sizeCol);
    }

    private void startScan(ObservableList<CleanupRow> rows, Label statusLabel, ProgressBar progressBar,
                           Button scanButton, Button selectAllButton, Button cleanButton,
                           TableView<CleanupRow> table) {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Scanning system...");
        scanButton.setDisable(true);
        cleanButton.setDisable(true);
        rows.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(true);

        new Thread(() -> {
            try {
                List<CleanupRow> results = service.scan(() -> {});
                Platform.runLater(() -> {
                    rows.setAll(results);
                    long totalBytes = results.stream().mapToLong(CleanupRow::getTotalBytes).sum();
                    statusLabel.setText("Scan complete — " + CleanupService.formatBytes(totalBytes) + " identified.");
                    cleanButton.setDisable(false);
                    progressBar.setVisible(false);
                });
            } catch (Exception e) {
                AppLogger.error("Cleanup scan failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    scanButton.setDisable(false);
                    progressBar.setVisible(false);
                });
            }
        }, "cleaner-scan").start();
    }

    private void toggleSelectAll(ObservableList<CleanupRow> rows) {
        boolean allSelected = rows.stream().allMatch(CleanupRow::isSelected);
        for (CleanupRow row : rows) {
            row.setSelected(!allSelected);
        }
    }

    private int getSelectedCount(ObservableList<CleanupRow> rows) {
        return (int) rows.stream().filter(CleanupRow::isSelected).count();
    }

    private void startClean(ObservableList<CleanupRow> rows, Label statusLabel, ProgressBar progressBar,
                            Button scanButton, Button selectAllButton, Button cleanButton) {
        if (busy.get()) return;
        List<CleanupRow> selected = rows.stream().filter(CleanupRow::isSelected).toList();
        if (selected.isEmpty()) return;

        if (!adminCheck.getAsBoolean()) {
            Alert a = new Alert(Alert.AlertType.WARNING,
                    "Some cleanup operations (registry, system files) require administrator rights.");
            a.showAndWait();
            return;
        }

        boolean registrySelected = selected.stream()
                .anyMatch(r -> r.getCategory() == CleanupCategory.REGISTRY);

        boolean registryBackup = false;
        if (registrySelected) {
            Alert backupPrompt = new Alert(Alert.AlertType.CONFIRMATION);
            backupPrompt.setTitle("Registry Backup");
            backupPrompt.setHeaderText("Backup registry entries before cleanup?");
            backupPrompt.setContentText("Invalid registry entries will be exported to a .reg file before deletion.\n\n"
                    + "Choose Yes to create a backup, or No to delete entries directly.");
            ButtonType yesBtn = new ButtonType("Yes, create backup");
            ButtonType noBtn = new ButtonType("No, delete directly");
            backupPrompt.getButtonTypes().setAll(yesBtn, noBtn, ButtonType.CANCEL);
            var result = backupPrompt.showAndWait().orElse(ButtonType.CANCEL);
            if (result == ButtonType.CANCEL) return;
            registryBackup = result == yesBtn;
        }

        busy.set(true);
        statusLabel.setText("Cleaning...");
        cleanButton.setDisable(true);
        progressBar.setProgress(0);
        progressBar.setVisible(true);

        final boolean finalRegistryBackup = registryBackup;
        new Thread(() -> {
            try {
                service.clean(selected, finalRegistryBackup, () -> {});
                Platform.runLater(() -> {
                    statusLabel.setText("Cleanup completed");
                    new Alert(Alert.AlertType.INFORMATION, "Cleanup completed").showAndWait();
                });
            } catch (Exception e) {
                AppLogger.error("Cleanup failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Cleanup failed.");
                    new Alert(Alert.AlertType.ERROR, "Cleanup failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    scanButton.setDisable(false);
                    progressBar.setVisible(false);
                });
            }
        }, "cleaner-clean").start();
    }
}
