package com.sbtools.ui;

import com.sbtools.duplicates.DuplicateFileRow;
import com.sbtools.duplicates.DuplicateFinderService;
import com.sbtools.duplicates.DuplicateFinderService.CleanResult;
import com.sbtools.duplicates.DuplicateSafety;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppLogger;
import com.sbtools.util.DataSizeFormatter;
import com.sbtools.util.WindowsServicingSafety;
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
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class DuplicateFilesTabView extends BorderPane {

    private final DuplicateFinderService service = new DuplicateFinderService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final SettingsStore settingsStore = new SettingsStore();

    private final ObservableList<DuplicateFileRow> rows = FXCollections.observableArrayList();
    private final ObservableList<Path> scanRoots = FXCollections.observableArrayList();
    private final Map<String, Integer> groupColorMap = new HashMap<>();
    private final Map<DuplicateFileRow, javafx.beans.value.ChangeListener<Boolean>> rowListenerMap = new HashMap<>();
    // Per-file selection: for each group, map deletable path -> selected property (true = will be deleted)
    private final Map<DuplicateFileRow, Map<String, BooleanProperty>> perFileSelection = new HashMap<>();
    private final Label statusLabel = new Label("Add folder(s) to scan. System and app folders are blocked for safety.");
    private final Label progressLabel = new Label("");
    private final Label safetyInfoLabel = new Label("System and app folders (Windows, Program Files, ProgramData, AppData, WindowsApps, System Volume Information, $Recycle.Bin, Recovery) are automatically excluded on any drive.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button scanButton = new Button("Scan");
    private final Button stopButton = new Button("Stop");
    private final Button selectAllButton = new Button("Select All");
    private final Button deselectAllButton = new Button("Deselect All");
    private final Button cleanButton = new Button("Clean Selected");
    private final Button addDirButton = new Button("Add...");
    private final Button removeDirButton = new Button("Remove");
    private final ListView<Path> dirListView = new ListView<>(scanRoots);
    private final TableView<DuplicateFileRow> table = new TableView<>(rows);
    private final ListView<HBox> deletableListView = new ListView<>();
    private final Label detailTitle = new Label("Select a group to see copies to delete");

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean cleanCancelled = new AtomicBoolean(false);
    private volatile Thread scanThread;
    private volatile Thread cleanThread;

    public DuplicateFilesTabView(BooleanSupplier adminCheck) {
        this(new SimpleBooleanProperty(false), adminCheck);
    }

    public DuplicateFilesTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopButton.setDisable(true);
        cleanButton.setDisable(true);
        stopButton.getStyleClass().add("danger");
        cleanButton.getStyleClass().add("danger");
        addDirButton.getStyleClass().add("button-outlined");
        removeDirButton.getStyleClass().add("button-outlined");
        safetyInfoLabel.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        safetyInfoLabel.setWrapText(true);
        detailTitle.setStyle("-fx-text-fill: #8be9fd; -fx-font-size: 12px; -fx-font-weight: bold;");
        detailTitle.setWrapText(true);

        dirListView.setPrefHeight(80);
        dirListView.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Path item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    String display = item.toString();
                    if (display.length() > 60) {
                        display = "..." + display.substring(display.length() - 57);
                    }
                    setText(display);
                    setTooltip(new Tooltip(item.toString()));
                }
            }
        });
        // Placeholder when empty
        dirListView.setPlaceholder(new Label("No folder selected\nClick Add..."));

        deletableListView.setPrefHeight(140);
        deletableListView.setPlaceholder(new Label("No group selected"));

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> {
            // Stop cancels whichever operation is running (scan and/or clean).
            cancelled.set(true);
            cleanCancelled.set(true);
            Thread st = scanThread;
            if (st != null && st.isAlive()) st.interrupt();
            Thread ct = cleanThread;
            if (ct != null && ct.isAlive()) ct.interrupt();
            statusLabel.setText("Cancelling...");
        });
        selectAllButton.setOnAction(e -> toggleSelectAll());
        deselectAllButton.setOnAction(e -> deselectAll());
        cleanButton.setOnAction(e -> startClean());
        addDirButton.setOnAction(e -> addDirectory());
        removeDirButton.setOnAction(e -> removeSelectedDirectory());

        HBox dirButtons = new HBox(4, addDirButton, removeDirButton);
        dirButtons.setAlignment(Pos.CENTER_LEFT);

        VBox dirBox = new VBox(4, dirListView, dirButtons, safetyInfoLabel);
        dirBox.setPrefWidth(260);

        HBox top = new HBox(12, dirBox, scanButton, stopButton, selectAllButton, deselectAllButton, cleanButton,
                progressBar, progressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();

        VBox detailBox = new VBox(4, detailTitle, deletableListView);
        detailBox.setPadding(new Insets(0, 16, 12, 16));
        VBox.setVgrow(deletableListView, Priority.ALWAYS);

        VBox center = new VBox(8, table, detailBox);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(top);
        setCenter(center);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            stopButton.setDisable(!newVal);
            selectAllButton.setDisable(newVal);
            deselectAllButton.setDisable(newVal);
            cleanButton.setDisable(newVal || getSelectedDeletableCount() == 0);
            addDirButton.setDisable(newVal);
            removeDirButton.setDisable(newVal);
            dirListView.setDisable(newVal);
        });

        rows.addListener((ListChangeListener<DuplicateFileRow>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (DuplicateFileRow row : c.getAddedSubList()) {
                        javafx.beans.value.ChangeListener<Boolean> listener =
                                (obs, ov, nv) -> {
                                    updateCleanButtonState();
                                    // Refresh detail if this row is currently selected in table
                                    DuplicateFileRow selected = table.getSelectionModel().getSelectedItem();
                                    if (selected == row) updateDeletableDetail(row);
                                };
                        row.selectedProperty().addListener(listener);
                        rowListenerMap.put(row, listener);
                        // Initialize per-file selection map for this group.
                        // Safe default: NOT selected — user must explicitly tick
                        // groups/copies to delete (prevents one-misclick mass delete).
                        Map<String, BooleanProperty> fileMap = new HashMap<>();
                        if (row.getDeletablePaths() != null) {
                            for (String p : row.getDeletablePaths()) {
                                BooleanProperty prop = new SimpleBooleanProperty(false);
                                prop.addListener((o, ov, nv) -> updateCleanButtonState());
                                fileMap.put(p, prop);
                            }
                        }
                        perFileSelection.put(row, fileMap);
                    }
                }
                if (c.wasRemoved()) {
                    for (DuplicateFileRow row : c.getRemoved()) {
                        javafx.beans.value.ChangeListener<Boolean> listener = rowListenerMap.remove(row);
                        if (listener != null) {
                            row.selectedProperty().removeListener(listener);
                        }
                        perFileSelection.remove(row);
                    }
                }
            }
            updateCleanButtonState();
        });

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) updateDeletableDetail(sel);
            else {
                detailTitle.setText("Select a group to see copies to delete");
                deletableListView.getItems().clear();
            }
        });
    }

    private void addDirectory() {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Select folder to scan for duplicates");
        if (!scanRoots.isEmpty()) {
            File init = scanRoots.get(0).toFile();
            if (init.exists() && init.isDirectory()) dc.setInitialDirectory(init);
        } else {
            String userHome = System.getProperty("user.home");
            File home = new File(userHome);
            if (home.exists()) dc.setInitialDirectory(home);
        }
        File chosen = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
        if (chosen != null) {
            Path chosenPath = chosen.toPath().toAbsolutePath().normalize();
            if (scanRoots.stream().anyMatch(p -> p.equals(chosenPath))) {
                new Alert(Alert.AlertType.INFORMATION, "Folder already in scan list.").showAndWait();
                return;
            }
            String reason = DuplicateSafety.validateScanRoot(chosenPath);
            if (reason != null) {
                Alert a = new Alert(Alert.AlertType.WARNING, reason);
                a.setHeaderText("Protected Folder");
                a.setTitle("Cannot Add Folder");
                a.showAndWait();
                return;
            }
            scanRoots.add(chosenPath);
            statusLabel.setText("Added: " + chosenPath);
        }
    }

    private void removeSelectedDirectory() {
        Path selected = dirListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            scanRoots.remove(selected);
            if (scanRoots.isEmpty()) {
                statusLabel.setText("Add folder(s) to scan. System and app folders are blocked.");
            }
        }
    }

    private long getSelectedDeletableCount() {
        long count = 0;
        for (DuplicateFileRow row : rows) {
            if (!row.isSelected()) continue;
            Map<String, BooleanProperty> fileMap = perFileSelection.get(row);
            if (fileMap == null || fileMap.isEmpty()) {
                // Fallback: count all deletables if map not initialized yet
                if (row.getDeletablePaths() != null) count += row.getDeletablePaths().size();
            } else {
                for (BooleanProperty p : fileMap.values()) if (p.get()) count++;
            }
        }
        return count;
    }

    private void updateCleanButtonState() {
        cleanButton.setDisable(busy.get() || getSelectedDeletableCount() == 0);
    }

    private void updateDeletableDetail(DuplicateFileRow row) {
        deletableListView.getItems().clear();
        if (row == null || row.getDeletablePaths() == null || row.getDeletablePaths().isEmpty()) {
            detailTitle.setText("No deletable copies");
            return;
        }
        detailTitle.setText("Keeper (kept): " + row.getFullPath() + "  —  " + row.getDeletablePaths().size() + " copy(ies) (tick to select for deletion):");
        Map<String, BooleanProperty> fileMap = perFileSelection.computeIfAbsent(row, k -> {
            Map<String, BooleanProperty> m = new HashMap<>();
            for (String p : k.getDeletablePaths()) {
                BooleanProperty prop = new SimpleBooleanProperty(false);
                prop.addListener((o, ov, nv) -> updateCleanButtonState());
                m.put(p, prop);
            }
            return m;
        });
        // Ensure missing entries are added (if row updated elsewhere)
        for (String p : row.getDeletablePaths()) {
            if (!fileMap.containsKey(p)) {
                BooleanProperty prop = new SimpleBooleanProperty(false);
                prop.addListener((o, ov, nv) -> updateCleanButtonState());
                fileMap.put(p, prop);
            }
        }
        for (String path : row.getDeletablePaths()) {
            BooleanProperty prop = fileMap.get(path);
            CheckBox cb = new CheckBox(path);
            cb.setTooltip(new Tooltip(path));
            cb.setSelected(prop.get());
            cb.selectedProperty().bindBidirectional(prop);
            // Disable individual delete checkbox if row not selected? Keep enabled but hint
            cb.disableProperty().bind(row.selectedProperty().not());
            HBox box = new HBox(6, cb);
            box.setAlignment(Pos.CENTER_LEFT);
            deletableListView.getItems().add(box);
        }
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);

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
                        try { checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty()); } catch (Exception ignored) {}
                        if (checkBox.selectedProperty().isBound()) try { checkBox.selectedProperty().unbind(); } catch (Exception ignored) {}
                        previousItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (previousItem != null) {
                        try { checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty()); } catch (Exception ignored) {}
                        if (checkBox.selectedProperty().isBound()) try { checkBox.selectedProperty().unbind(); } catch (Exception ignored) {}
                    }
                    try { checkBox.selectedProperty().bindBidirectional(item.selectedProperty()); } catch (Exception e) {
                        try { checkBox.selectedProperty().unbindBidirectional(item.selectedProperty()); } catch (Exception ignored) {}
                        if (checkBox.selectedProperty().isBound()) try { checkBox.selectedProperty().unbind(); } catch (Exception ignored) {}
                        checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    }
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<DuplicateFileRow, String> nameCol = new TableColumn<>("File Name");
        nameCol.setCellValueFactory(c -> c.getValue().fileNameProperty());
        nameCol.setPrefWidth(200);

        TableColumn<DuplicateFileRow, String> pathCol = new TableColumn<>("Keeper Path");
        pathCol.setCellValueFactory(c -> c.getValue().fullPathProperty());
        pathCol.setPrefWidth(360);

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
        totalCol.setPrefWidth(70);
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

        table.setRowFactory(tv -> {
            TableRow<DuplicateFileRow> row = new TableRow<>() {
                @Override
                protected void updateItem(DuplicateFileRow item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("group-even", "group-odd");
                    if (item != null && !empty) {
                        String hash = item.getChecksumSha256();
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

            MenuItem copyPathItem = new MenuItem("Copy Keeper Path");
            copyPathItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null) copyToClipboard(r.getFullPath());
            });

            MenuItem copyDeletableItem = new MenuItem("Copy Deletable Paths (selected)");
            copyDeletableItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null) {
                    List<String> sel = getSelectedDeletablesForRow(r);
                    if (!sel.isEmpty()) copyToClipboard(String.join("\n", sel));
                }
            });

            MenuItem copyAllDeletableItem = new MenuItem("Copy All Deletable Paths");
            copyAllDeletableItem.setOnAction(e -> {
                DuplicateFileRow r = row.getItem();
                if (r != null && r.getDeletablePaths() != null) {
                    copyToClipboard(String.join("\n", r.getDeletablePaths()));
                }
            });

            ctxMenu.getItems().addAll(openFileItem, openFolderItem,
                    new SeparatorMenuItem(),
                    copyPathItem, copyDeletableItem, copyAllDeletableItem);

            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(ctxMenu));

            return row;
        });
    }

    private List<String> getSelectedDeletablesForRow(DuplicateFileRow row) {
        Map<String, BooleanProperty> fileMap = perFileSelection.get(row);
        List<String> result = new ArrayList<>();
        if (row.getDeletablePaths() == null) return result;
        if (fileMap == null) {
            if (row.isSelected()) result.addAll(row.getDeletablePaths());
            return result;
        }
        for (String p : row.getDeletablePaths()) {
            BooleanProperty prop = fileMap.get(p);
            if (prop != null && prop.get() && row.isSelected()) result.add(p);
        }
        return result;
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
                Process proc = Runtime.getRuntime().exec(new String[]{"explorer", "/select,", path});
                proc.getInputStream().close();
                proc.getErrorStream().close();
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
        if (scanRoots.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please add at least one directory to scan.\nSystem and app folders like C:\\Windows and C:\\Program Files are blocked.").showAndWait();
            return;
        }
        // Re-validate roots at scan time (in case env changed)
        List<Path> invalidRoots = new ArrayList<>();
        for (Path r : scanRoots) {
            String reason = DuplicateSafety.validateScanRoot(r);
            if (reason != null) invalidRoots.add(r);
        }
        if (!invalidRoots.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Some scan folders are now protected and will be skipped:\n" + invalidRoots).showAndWait();
            scanRoots.removeAll(invalidRoots);
            if (scanRoots.isEmpty()) return;
        }

        cancelled.set(false);
        busy.set(true);
        statusLabel.setText("Scanning for duplicates...");
        scanButton.setDisable(true);
        stopButton.setDisable(false);
        cleanButton.setDisable(true);
        rows.clear();
        groupColorMap.clear();
        perFileSelection.clear();
        deletableListView.getItems().clear();
        detailTitle.setText("Select a group to see copies to delete");
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressLabel.setText("Preparing...");

        List<Path> rootsToScan = List.copyOf(scanRoots);

        scanThread = new Thread(() -> {
            try {
                long[] lastProgressUpdate = {0};
                List<DuplicateFileRow> results = service.scan(
                        rootsToScan,
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
                        if (results.isEmpty()) {
                            statusLabel.setText("No duplicates found in selected folders. System and app folders were excluded.");
                        } else {
                            statusLabel.setText("Found " + results.size() + " duplicate group(s) — "
                                    + DataSizeFormatter.formatBytes(totalBytes)
                                    + " total, " + DataSizeFormatter.formatBytes(reclaimable) + " reclaimable (system and app folders excluded). Tick groups to select for deletion.");
                        }
                        // Safe default: nothing selected after scan — Clean stays
                        // disabled until the user explicitly ticks groups/copies.
                        updateCleanButtonState();
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
        }, "duplicate-scan");
        scanThread.setDaemon(true);
        scanThread.start();
    }

    private void toggleSelectAll() {
        boolean allSelected = rows.stream().allMatch(r -> r.isSelected() && getSelectedDeletablesForRow(r).size() == r.getDeletablePaths().size());
        for (DuplicateFileRow row : rows) {
            row.setSelected(!allSelected);
            Map<String, BooleanProperty> fileMap = perFileSelection.get(row);
            if (fileMap != null) {
                for (BooleanProperty p : fileMap.values()) p.set(!allSelected);
            }
        }
        // Refresh detail for current selection
        DuplicateFileRow sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) updateDeletableDetail(sel);
    }

    private void deselectAll() {
        for (DuplicateFileRow row : rows) {
            row.setSelected(false);
            Map<String, BooleanProperty> fileMap = perFileSelection.get(row);
            if (fileMap != null) {
                for (BooleanProperty p : fileMap.values()) p.set(false);
            }
        }
        DuplicateFileRow sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) updateDeletableDetail(sel);
    }

    public void dispose() {
        cancelled.set(true);
        cleanCancelled.set(true);
        Thread st = scanThread;
        if (st != null && st.isAlive()) st.interrupt();
        Thread ct = cleanThread;
        if (ct != null && ct.isAlive()) ct.interrupt();
    }

    private void startClean() {
        if (busy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Administrator privileges are required to delete files.").showAndWait();
            return;
        }
        // Build filtered selection respecting per-file checkboxes
        List<DuplicateFileRow> filteredSelected = new ArrayList<>();
        Map<DuplicateFileRow, DuplicateFileRow> filteredToOriginal = new HashMap<>();
        long totalFilesToDelete = 0;
        long totalBytesToReclaim = 0;
        List<String> previewPaths = new ArrayList<>();
        for (DuplicateFileRow row : rows) {
            if (!row.isSelected()) continue;
            List<String> sel = getSelectedDeletablesForRow(row);
            if (sel.isEmpty()) continue;
            filteredSelected.add(new DuplicateFileRow(
                    row.getFileName(), row.getFullPath(), row.getFileSize(), row.getChecksumSha256(),
                    row.getTotalDuplicates(), new ArrayList<>(sel)));
            // Keep original reference mapping via checksum+keeper path
            filteredToOriginal.put(filteredSelected.get(filteredSelected.size() - 1), row);
            totalFilesToDelete += sel.size();
            totalBytesToReclaim += (long) sel.size() * row.getFileSize();
            // Collect preview up to 30
            if (previewPaths.size() < 30) {
                for (String p : sel) {
                    if (previewPaths.size() >= 30) break;
                    previewPaths.add(p);
                }
            }
        }
        if (filteredSelected.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No files selected for deletion.\nSelect groups and individual copies in the detail pane.").showAndWait();
            return;
        }

        // Servicing pending warning (informational for duplicates — deletion still allowed but warn)
        if (WindowsServicingSafety.isServicingPending()) {
            List<String> reasons = WindowsServicingSafety.getPendingReasons();
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "Windows servicing is pending:\n  - " + String.join("\n  - ", reasons)
                            + "\n\nDeleting files now is still safe for user folders (system folders are already blocked), but a reboot is pending. Continue?");
            warn.setHeaderText("Windows Update Pending");
            if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        // Build detailed confirmation dialog
        StringBuilder header = new StringBuilder();
        header.append("Delete ").append(totalFilesToDelete).append(" file(s) (")
                .append(DataSizeFormatter.formatBytes(totalBytesToReclaim)).append(") across ")
                .append(filteredSelected.size()).append(" group(s)?\n")
                .append("The keeper (newest / safest location) will be kept for each group.");

        Label msgLabel = new Label(header.toString() + "\n\nFiles to be removed (first " + previewPaths.size() + (totalFilesToDelete > previewPaths.size() ? " of " + totalFilesToDelete : "") + "):\n" + String.join("\n", previewPaths)
                + (totalFilesToDelete > previewPaths.size() ? "\n... and " + (totalFilesToDelete - previewPaths.size()) + " more" : "")
                + "\n\nRecycle Bin is recommended (recoverable). Permanent delete requires typing DELETE.");
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(520);
        ScrollPane scroll = new ScrollPane(msgLabel);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(Math.min(380, 200 + previewPaths.size() * 18));
        scroll.setMinHeight(180);

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Deletion");
        confirm.setHeaderText(header.toString());
        confirm.getDialogPane().setContent(scroll);
        ButtonType recycleBtn = new ButtonType("Move to Recycle Bin (recommended)");
        ButtonType deleteBtn = new ButtonType("Delete Permanently");
        confirm.getButtonTypes().setAll(recycleBtn, deleteBtn, ButtonType.CANCEL);
        confirm.getDialogPane().setMinWidth(580);

        var choice = confirm.showAndWait().orElse(ButtonType.CANCEL);
        if (choice == ButtonType.CANCEL) return;
        boolean useRecycleBin = choice == recycleBtn;

        if (!useRecycleBin) {
            TextInputDialog diag = new TextInputDialog("");
            diag.setTitle("Confirm Permanent Delete");
            diag.setHeaderText("Type DELETE to confirm permanent deletion of " + totalFilesToDelete + " file(s)");
            diag.setContentText("This cannot be undone:");
            Optional<String> typed = diag.showAndWait();
            if (typed.isEmpty() || !"DELETE".equals(typed.get().trim())) {
                new Alert(Alert.AlertType.INFORMATION, "Permanent delete cancelled.").showAndWait();
                return;
            }
        }

        // Optional restore point (mirrors CleanerTabView)
        boolean createRestorePoint = false;
        try {
            createRestorePoint = settingsStore.load().autoCreateRestoreBeforeCleanup();
        } catch (Exception ignored) {}

        Runnable doClean = () -> {
            cleanCancelled.set(false);
            busy.set(true);
            Platform.runLater(() -> {
                statusLabel.setText(useRecycleBin ? "Moving to Recycle Bin... (Stop to cancel)" : "Deleting duplicates... (Stop to cancel)");
                cleanButton.setDisable(true);
                stopButton.setDisable(false);
                progressBar.setProgress(0);
                progressBar.setVisible(true);
                progressLabel.setVisible(true);
                progressLabel.setText("Starting...");
            });
            String actionLabel = useRecycleBin ? "Moved" : "Deleted";
            cleanThread = new Thread(() -> {
                try {
                    // filteredSelected already contains only selected deletables; ensure each is marked selected for service
                    for (DuplicateFileRow fr : filteredSelected) fr.setSelected(true);
                    long[] lastProgressUpdate = {0};
                    CleanResult result = service.clean(filteredSelected, useRecycleBin,
                            (processed, total) -> {
                                long now = System.currentTimeMillis();
                                boolean isFinal = total > 0 && processed >= total;
                                if (!isFinal && now - lastProgressUpdate[0] < 100) return;
                                lastProgressUpdate[0] = now;
                                Platform.runLater(() -> {
                                    if (total > 0) {
                                        progressBar.setProgress((double) processed / total);
                                        progressLabel.setText(processed + " / " + total + " files");
                                    } else {
                                        progressBar.setProgress(-1);
                                        progressLabel.setText(processed + " files");
                                    }
                                    statusLabel.setText((useRecycleBin ? "Moving to Recycle Bin... " : "Deleting duplicates... ")
                                            + processed + (total > 0 ? "/" + total : "") + " (Stop to cancel)");
                                });
                            },
                            cleanCancelled);
                    int deleted = result.getDeleted();
                    int failed = result.getFailed();
                    boolean wasCancelled = result.isCancelled() || cleanCancelled.get();
                    String msg;
                    if (wasCancelled) {
                        msg = "Cleanup cancelled — " + actionLabel.toLowerCase() + " " + deleted + " file(s) before cancel."
                                + (failed > 0 ? " " + failed + " file(s) skipped/failed." : "")
                                + "\n\nRemaining files were left untouched.";
                    } else if (failed == 0) {
                        msg = actionLabel + " " + deleted + " file(s).";
                    } else {
                        msg = actionLabel + " " + deleted + " file(s). "
                                + failed + " file(s) could not be deleted or were blocked (protected/changed/missing keeper).";
                    }
                    if (!wasCancelled && result.getDeleted() > 0 && failed > 0) {
                        msg += "\n\nProtected, app, or changed files were automatically skipped.";
                    }
                    final String finalMsg = msg;
                    final boolean finalCancelled = wasCancelled;
                    Platform.runLater(() -> {
                        statusLabel.setText((finalCancelled ? "Cleanup cancelled. " : "") + finalMsg.split("\n")[0]);
                        new Alert(finalCancelled ? Alert.AlertType.WARNING : (failed > 0 ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION), finalMsg).showAndWait();
                        // Update UI ONLY from verified filesystem state (Files.exists).
                        // Never drop rows on service claim alone — a claimed success
                        // with files still present means the delete did not happen.
                        List<DuplicateFileRow> toRemove = new ArrayList<>();
                        for (DuplicateFileRow filtered : filteredSelected) {
                            DuplicateFileRow orig = filteredToOriginal.get(filtered);
                            if (orig == null) {
                                // fallback by checksum+keeper
                                for (DuplicateFileRow o : rows) {
                                    if (o.getChecksumSha256().equals(filtered.getChecksumSha256())
                                            && o.getFullPath().equals(filtered.getFullPath())) {
                                        orig = o; break;
                                    }
                                }
                                if (orig == null) continue;
                            }
                            List<String> deletedForThisGroup = new ArrayList<>();
                            for (String pStr : filtered.getDeletablePaths()) {
                                try {
                                    Path p = Paths.get(pStr);
                                    boolean exists = Files.exists(DuplicateFinderService.toLongPath(p));
                                    if (!exists) deletedForThisGroup.add(pStr);
                                } catch (Exception ignored) {}
                            }
                            if (deletedForThisGroup.isEmpty()) continue;
                            List<String> remaining = new ArrayList<>(orig.getDeletablePaths());
                            remaining.removeAll(deletedForThisGroup);
                            Map<String, BooleanProperty> fileMap = perFileSelection.get(orig);
                            if (fileMap != null) {
                                for (String del : deletedForThisGroup) fileMap.remove(del);
                            }
                            if (remaining.isEmpty()) {
                                toRemove.add(orig);
                            } else {
                                orig.setDeletablePaths(remaining);
                                orig.setTotalDuplicates(remaining.size() + 1);
                                // If all remaining files are now unselected, deselect row
                                if (fileMap != null) {
                                    boolean anySelected = false;
                                    for (String r : remaining) {
                                        BooleanProperty bp = fileMap.get(r);
                                        if (bp != null && bp.get()) { anySelected = true; break; }
                                        if (bp == null) { anySelected = true; break; }
                                    }
                                    if (!anySelected) orig.setSelected(false);
                                }
                            }
                        }
                        if (!toRemove.isEmpty()) rows.removeAll(toRemove);
                        if (!toRemove.isEmpty()) {
                            groupColorMap.clear();
                            table.refresh();
                        } else if (!filteredSelected.isEmpty()) {
                            table.refresh();
                        }
                        // Refresh detail pane for currently selected row if it still exists
                        DuplicateFileRow sel = table.getSelectionModel().getSelectedItem();
                        if (sel != null && rows.contains(sel)) updateDeletableDetail(sel);
                        else {
                            deletableListView.getItems().clear();
                            detailTitle.setText("Select a group to see copies to delete");
                        }
                        updateCleanButtonState();
                        // If table selection cleared, ensure detail pane cleared
                        if (table.getSelectionModel().getSelectedItem() == null) {
                            deletableListView.getItems().clear();
                            detailTitle.setText("Select a group to see copies to delete");
                        }
                        progressBar.setVisible(false);
                        progressLabel.setVisible(false);
                    });
                } catch (Exception e) {
                    AppLogger.error("Duplicate clean failed", e);
                    Platform.runLater(() -> {
                        statusLabel.setText("Cleanup failed.");
                        progressBar.setVisible(false);
                        progressLabel.setVisible(false);
                        new Alert(Alert.AlertType.ERROR, "Cleanup failed:\n" + e.getMessage()).showAndWait();
                    });
                } finally {
                    Platform.runLater(() -> {
                        busy.set(false);
                        stopButton.setDisable(true);
                    });
                }
            }, "duplicate-clean");
            cleanThread.setDaemon(true);
            cleanThread.start();
        };

        if (createRestorePoint) {
            cleanCancelled.set(false);
            statusLabel.setText("Creating System Restore point... (Stop to skip)");
            busy.set(true);
            stopButton.setDisable(false);
            CompletableFuture.runAsync(() -> {
                Process p = null;
                try {
                    ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                            "Checkpoint-Computer -Description 'WinZenith Duplicate Cleanup' -RestorePointType MODIFY_SETTINGS");
                    pb.redirectErrorStream(true);
                    p = pb.start();
                    // Cancellable wait: 120s total, 200ms slices so Stop skips the wait.
                    long deadline = System.currentTimeMillis() + 120_000;
                    boolean finished = false;
                    while (System.currentTimeMillis() < deadline) {
                        if (cleanCancelled.get()) break;
                        try {
                            if (p.waitFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                                finished = true;
                                break;
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    if (cleanCancelled.get() || Thread.currentThread().isInterrupted()) {
                        if (p != null && p.isAlive()) p.destroyForcibly();
                        AppLogger.info("Restore point creation cancelled by user — proceeding without it");
                        return;
                    }
                    if (!finished) {
                        p.destroyForcibly();
                        AppLogger.warning("Restore point creation timed out");
                    } else if (p.exitValue() != 0) {
                        String err = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        AppLogger.warning("Restore point creation failed: " + err.trim());
                        Platform.runLater(() -> {
                            Alert a = new Alert(Alert.AlertType.INFORMATION,
                                    "Could not create a System Restore point.\nSystem Protection may be disabled.\nCleanup will continue without a restore point.");
                            a.setHeaderText("Restore Point Unavailable");
                            a.showAndWait();
                        });
                    }
                } catch (Exception e) {
                    AppLogger.warning("Failed to create restore point: " + e.getMessage());
                } finally {
                    if (p != null && p.isAlive()) p.destroyForcibly();
                }
            }).whenComplete((v, ex) -> Platform.runLater(() -> {
                busy.set(false);
                if (cleanCancelled.get()) {
                    statusLabel.setText("Cleanup cancelled before starting.");
                    stopButton.setDisable(true);
                    updateCleanButtonState();
                    return;
                }
                doClean.run();
            }));
        } else {
            doClean.run();
        }
    }
}
