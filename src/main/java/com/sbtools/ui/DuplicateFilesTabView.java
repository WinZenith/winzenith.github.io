package com.sbtools.ui;

import com.sbtools.duplicates.DuplicateFileRow;
import com.sbtools.duplicates.DuplicateFinderService;
import com.sbtools.duplicates.DuplicateFinderService.CleanResult;
import com.sbtools.util.AppLogger;
import com.sbtools.util.DataSizeFormatter;
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
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;

import java.awt.Desktop;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final Button browseButton = new Button("Browse...");
    private final Label scanPathLabel = new Label();
    private final TableView<DuplicateFileRow> table = new TableView<>(rows);

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private Path scanRoot;

    public DuplicateFilesTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;
        this.scanRoot = Paths.get(System.getProperty("user.home"));
        scanPathLabel.setText(truncatePath(scanRoot.toString()));
        scanPathLabel.getStyleClass().add("label.text-muted");
        scanPathLabel.setTooltip(new Tooltip(scanRoot.toString()));

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopButton.setDisable(true);
        cleanButton.setDisable(true);
        stopButton.getStyleClass().add("danger");
        cleanButton.getStyleClass().add("danger");
        browseButton.getStyleClass().add("button-outlined");

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> cancelled.set(true));
        selectAllButton.setOnAction(e -> toggleSelectAll());
        cleanButton.setOnAction(e -> startClean());
        browseButton.setOnAction(e -> chooseDirectory());

        HBox pathBox = new HBox(6, browseButton, scanPathLabel);
        pathBox.setAlignment(Pos.CENTER_LEFT);

        HBox top = new HBox(12, pathBox, scanButton, stopButton, selectAllButton, cleanButton,
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
            browseButton.setDisable(newVal);
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

    private static String truncatePath(String path) {
        if (path.length() <= 50) return path;
        return "..." + path.substring(path.length() - 47);
    }

    private void chooseDirectory() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select folder to scan for duplicates");
        dc.setInitialDirectory(scanRoot.toFile());
        File chosen = dc.showDialog(getScene().getWindow());
        if (chosen != null) {
            scanRoot = chosen.toPath();
            scanPathLabel.setText(truncatePath(scanRoot.toString()));
            scanPathLabel.setTooltip(new Tooltip(scanRoot.toString()));
        }
    }

    private int getSelectedCount() {
        return (int) rows.stream().filter(DuplicateFileRow::isSelected).count();
    }

    private void updateCleanButtonState() {
        cleanButton.setDisable(busy.get() || getSelectedCount() == 0);
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

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
        sizeCol.setComparator(Comparator.comparingLong(Number::longValue));
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(DataSizeFormatter.formatBytes(item.longValue()));
                }
            }
        });

        TableColumn<DuplicateFileRow, Number> totalCol = new TableColumn<>("Total");
        totalCol.setCellValueFactory(c -> c.getValue().totalDuplicatesProperty());
        totalCol.setPrefWidth(80);
        totalCol.setComparator(Comparator.comparingInt(Number::intValue));

        TableColumn<DuplicateFileRow, Number> wasteCol = new TableColumn<>("Reclaimable");
        wasteCol.setCellValueFactory(c -> {
            DuplicateFileRow row = c.getValue();
            long reclaimable = (row.getTotalDuplicates() - 1) * row.getFileSize();
            return new javafx.beans.property.SimpleLongProperty(reclaimable);
        });
        wasteCol.setPrefWidth(110);
        wasteCol.setComparator(Comparator.comparingLong(Number::longValue));
        wasteCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(DataSizeFormatter.formatBytes(item.longValue()));
                }
            }
        });

        table.getColumns().addAll(checkCol, nameCol, pathCol, sizeCol, totalCol, wasteCol);

        // Row factory for context menu + group coloring
        Map<String, Integer> groupColorMap = new HashMap<>();
        table.setRowFactory(tv -> {
            TableRow<DuplicateFileRow> row = new TableRow<>() {
                @Override
                protected void updateItem(DuplicateFileRow item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("group-even", "group-odd");
                    if (item != null && !empty) {
                        String hash = item.getChecksumMd5();
                        int groupNum = groupColorMap.computeIfAbsent(hash,
                                k -> groupColorMap.size() + 1);
                        if (groupNum % 2 == 0) {
                            getStyleClass().add("group-even");
                        } else {
                            getStyleClass().add("group-odd");
                        }
                    }
                }
            };

            ContextMenu ctxMenu = new ContextMenu();

            MenuItem openFileItem = new MenuItem("Open File");
            openFileItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null) openFile(r.getFullPath());
            });

            MenuItem openFolderItem = new MenuItem("Open Folder");
            openFolderItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null) openContainingFolder(r.getFullPath());
            });

            MenuItem copyPathItem = new MenuItem("Copy Path");
            copyPathItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null) copyToClipboard(r.getFullPath());
            });

            MenuItem copyDeletableItem = new MenuItem("Copy Deletable Paths");
            copyDeletableItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null && r.getDeletablePaths() != null) {
                    copyToClipboard(String.join("\n", r.getDeletablePaths()));
                }
            });

            ctxMenu.getItems().addAll(openFileItem, openFolderItem,
                    new SeparatorMenuItem(),
                    copyPathItem, copyDeletableItem);

            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(ctxMenu));

            return row;
        });
    }

    private void openFile(String path) {
        try {
            Desktop.getDesktop().open(new File(path));
        } catch (Exception e) {
            AppLogger.warning("Failed to open file: " + path + " — " + e.getMessage());
        }
    }

    private void openContainingFolder(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                Desktop.getDesktop().browseFileDirectory(file);
            }
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"explorer", "/select,", path});
            } catch (Exception ex) {
                AppLogger.warning("Failed to open folder for: " + path + " — " + ex.getMessage());
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
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
        progressLabel.setText("Preparing...");

        Path rootToScan = scanRoot;

        new Thread(() -> {
            try {
                long[] lastProgressUpdate = {0};
                List<DuplicateFileRow> results = service.scan(
                        rootToScan,
                        (processed, total) -> {
                            long now = System.currentTimeMillis();
                            boolean isFinal = total > 0 && processed >= total;
                            if (!isFinal && now - lastProgressUpdate[0] < 40) return;
                            lastProgressUpdate[0] = now;
                            Platform.runLater(() -> {
                                if (total < 0) {
                                    progressBar.setProgress(-1);
                                    progressLabel.setText("Enumerating files... " + processed);
                                } else {
                                    double pct = total > 0 ? (double) processed / total : 0;
                                    progressBar.setProgress(pct);
                                    progressLabel.setText(processed + " / " + total + " hashed");
                                }
                            });
                        },
                        phase -> Platform.runLater(() -> statusLabel.setText(phase)),
                        cancelled
                );
                Platform.runLater(() -> {
                    if (cancelled.get()) {
                        statusLabel.setText("Scan cancelled.");
                    } else {
                        rows.setAll(results);
                        long totalBytes = results.stream().mapToLong(DuplicateFileRow::getFileSize).sum();
                        long reclaimable = results.stream()
                                .mapToLong(r -> (r.getTotalDuplicates() - 1) * r.getFileSize())
                                .sum();
                        statusLabel.setText("Found " + results.size() + " duplicate group(s) — "
                                + DataSizeFormatter.formatBytes(totalBytes)
                                + " total, " + DataSizeFormatter.formatBytes(reclaimable) + " reclaimable");
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
                CleanResult result = service.clean(selected, useRecycleBin);
                int deleted = result.getDeleted();
                int failed = result.getFailed();
                String msg;
                if (failed == 0) {
                    msg = actionLabel + " " + deleted + " older copy(ies).";
                } else {
                    msg = actionLabel + " " + deleted + " older copy(ies). "
                            + failed + " file(s) could not be deleted.";
                }
                Platform.runLater(() -> {
                    statusLabel.setText(msg);
                    new Alert(failed > 0 ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION, msg).showAndWait();
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
