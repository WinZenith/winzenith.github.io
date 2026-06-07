package com.sbtools.ui;

import com.sbtools.duplicates.DuplicateFileRow;
import com.sbtools.duplicates.DuplicateFinderService;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
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

import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class DuplicateFilesTabView extends BorderPane {

    private final DuplicateFinderService service = new DuplicateFinderService();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;

    private final ObservableList<DuplicateFileRow> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Click Scan to find duplicate files.");
    private final Label progressLabel = new Label("");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button scanButton = new Button("Scan");
    private final Button stopButton = new Button("Stop");
    private final Button selectAllButton = new Button("Select All");
    private final Button cleanButton = new Button("Clean Selected");
    private final TableView<DuplicateFileRow> table = new TableView<>(rows);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public DuplicateFilesTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopButton.setDisable(true);
        cleanButton.setDisable(true);
        stopButton.getStyleClass().add("danger");
        cleanButton.getStyleClass().add("danger");

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> cancelled.set(true));
        selectAllButton.setOnAction(e -> toggleSelectAll());
        cleanButton.setOnAction(e -> startClean());

        HBox top = new HBox(12, scanButton, stopButton, selectAllButton, cleanButton,
                progressBar, progressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();

        VBox center = new VBox(8, table);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(top);
        setCenter(center);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            stopButton.setDisable(!newVal);
            selectAllButton.setDisable(newVal);
            cleanButton.setDisable(newVal || getSelectedCount() == 0);
        });

        rows.addListener((ListChangeListener<DuplicateFileRow>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (DuplicateFileRow row : c.getAddedSubList()) {
                        row.selectedProperty().addListener((obs, ov, nv) -> updateCleanButtonState());
                    }
                }
            }
            updateCleanButtonState();
        });
    }

    private int getSelectedCount() {
        return (int) rows.stream().filter(DuplicateFileRow::isSelected).count();
    }

    private void updateCleanButtonState() {
        cleanButton.setDisable(busy.get() || getSelectedCount() == 0);
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DuplicateFileRow, DuplicateFileRow> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private DuplicateFileRow previousItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }
            @Override
            protected void updateItem(DuplicateFileRow item, boolean empty) {
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

        TableColumn<DuplicateFileRow, String> nameCol = new TableColumn<>("File Name");
        nameCol.setCellValueFactory(c -> c.getValue().fileNameProperty());
        nameCol.setPrefWidth(200);

        TableColumn<DuplicateFileRow, String> pathCol = new TableColumn<>("Path");
        pathCol.setCellValueFactory(c -> c.getValue().fullPathProperty());
        pathCol.setPrefWidth(300);

        TableColumn<DuplicateFileRow, Number> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().fileSizeProperty());
        sizeCol.setPrefWidth(100);
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(com.sbtools.cleaner.CleanupService.formatBytes(item.longValue()));
                }
            }
        });

        TableColumn<DuplicateFileRow, Number> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(c -> c.getValue().totalDuplicatesProperty());
        totalCol.setPrefWidth(80);

        table.getColumns().addAll(checkCol, nameCol, pathCol, sizeCol, totalCol);
    }

    private void startScan() {
        if (busy.get()) return;
        cancelled.set(false);
        busy.set(true);
        statusLabel.setText("Scanning for duplicates...");
        scanButton.setDisable(true);
        stopButton.setDisable(false);
        cleanButton.setDisable(true);
        rows.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressLabel.setText("Enumerating files...");

        new Thread(() -> {
            try {
                String userHome = System.getProperty("user.home");
                long[] lastProgressUpdate = {0};
                List<DuplicateFileRow> results = service.scan(
                        Paths.get(userHome),
                        (processed, total) -> {
                            long now = System.currentTimeMillis();
                            boolean isFinal = total > 0 && processed >= total;
                            if (!isFinal && now - lastProgressUpdate[0] < 40) return;
                            lastProgressUpdate[0] = now;
                            Platform.runLater(() -> {
                                if (total == 0) {
                                    statusLabel.setText("Enumerating files... " + processed);
                                    progressBar.setProgress(-1);
                                } else {
                                    double pct = total > 0 ? (double) processed / total : 0;
                                    progressBar.setProgress(pct);
                                    progressLabel.setText(processed + " / " + total + " hashed");
                                }
                            });
                        },
                        cancelled
                );
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        statusLabel.setText("Scan cancelled.");
                    } else {
                        rows.setAll(results);
                        long totalBytes = results.stream().mapToLong(DuplicateFileRow::getFileSize).sum();
                        statusLabel.setText("Found " + results.size() + " duplicate group(s) — "
                                + com.sbtools.cleaner.CleanupService.formatBytes(totalBytes));
                        cleanButton.setDisable(results.isEmpty());
                    }
                    progressBar.setVisible(false);
                    progressLabel.setVisible(false);
                });
            } catch (Exception e) {
                AppLogger.error("Duplicate scan failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Duplicate scan failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    stopButton.setDisable(true);
                });
            }
        }, "duplicate-scan").start();
    }

    private void toggleSelectAll() {
        boolean allSelected = rows.stream().allMatch(DuplicateFileRow::isSelected);
        for (DuplicateFileRow row : rows) {
            row.setSelected(!allSelected);
        }
    }

    private void startClean() {
        if (busy.get()) return;
        List<DuplicateFileRow> selected = rows.stream().filter(DuplicateFileRow::isSelected).toList();
        if (selected.isEmpty()) return;

        long totalFilesToDelete = selected.stream()
                .mapToLong(r -> r.getDeletablePaths() != null ? r.getDeletablePaths().size() : 0)
                .sum();
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Deletion");
        confirm.setHeaderText("Delete " + totalFilesToDelete + " older copy(ies) across " + selected.size() + " group(s)?");
        confirm.setContentText("The newest copy of each group will be kept.");
        ButtonType recycleBtn = new ButtonType("Move items to Recycle Bin");
        ButtonType deleteBtn = new ButtonType("Delete Permanently");
        confirm.getButtonTypes().setAll(recycleBtn, deleteBtn, ButtonType.CANCEL);

        var choice = confirm.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;
        boolean useRecycleBin = choice == recycleBtn;

        busy.set(true);
        statusLabel.setText(useRecycleBin ? "Moving to Recycle Bin..." : "Deleting duplicates...");
        cleanButton.setDisable(true);

        String actionLabel = useRecycleBin ? "Moved" : "Deleted";
        new Thread(() -> {
            try {
                int deleted = service.clean(selected, useRecycleBin);
                String msg = actionLabel + " " + deleted + " older copy(ies).";
                Platform.runLater(() -> {
                    statusLabel.setText(msg);
                    new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
                    rows.removeAll(selected);
                    cleanButton.setDisable(true);
                });
            } catch (Exception e) {
                AppLogger.error("Duplicate clean failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Cleanup failed.");
                    new Alert(Alert.AlertType.ERROR, "Cleanup failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "duplicate-clean").start();
    }
}
