package com.sbtools.ui;

import com.sbtools.duplicates.DuplicateFileRow;
import com.sbtools.duplicates.DuplicateFinderService;
import com.sbtools.duplicates.DuplicateFinderService.CleanResult;
import com.sbtools.duplicates.DuplicateFinderService.ScanResult;
import com.sbtools.duplicates.DuplicateKeeperStrategy;
import com.sbtools.duplicates.DuplicateScanOptions;
import com.sbtools.duplicates.DuplicateSafety;
import com.sbtools.settings.AppSettings;
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
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
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
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

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
    private final FilteredList<DuplicateFileRow> filteredRows = new FilteredList<>(rows, r -> true);
    private final SortedList<DuplicateFileRow> sortedRows = new SortedList<>(filteredRows);
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
    private final TableView<DuplicateFileRow> table = new TableView<>();
    private final ListView<HBox> deletableListView = new ListView<>();
    private final Label detailTitle = new Label("Select a group to see copies to delete");
    private final Label summaryLabel = new Label("");
    private final ComboBox<MinSizeOption> minSizeCombo = new ComboBox<>();
    private final TextField extFilterField = new TextField();
    private final ComboBox<DuplicateKeeperStrategy> keeperCombo = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final Button autoSelectButton = new Button("Auto-select");
    private final Button exportButton = new Button("Export CSV...");

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean cleanCancelled = new AtomicBoolean(false);
    private volatile Thread scanThread;
    private volatile Thread cleanThread;

    /** Minimum-size choices for the scan filter (default = All files). */
    public record MinSizeOption(String label, long bytes) {
        @Override public String toString() { return label; }
        static MinSizeOption forBytes(long bytes) {
            if (bytes >= 10L * 1024 * 1024) return new MinSizeOption("> 10 MB", 10L * 1024 * 1024);
            if (bytes >= 1024 * 1024) return new MinSizeOption("> 1 MB", 1024 * 1024);
            if (bytes >= 100L * 1024) return new MinSizeOption("> 100 KB", 100L * 1024);
            return new MinSizeOption("All files", 1L);
        }
    }

    public DuplicateFilesTabView(BooleanSupplier adminCheck) {
        this(new SimpleBooleanProperty(false), adminCheck);
    }

    public DuplicateFilesTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        table.setItems(sortedRows);
        sortedRows.comparatorProperty().bind(table.comparatorProperty());

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        progressLabel.setVisible(false);
        stopButton.setDisable(true);
        cleanButton.setDisable(true);
        stopButton.getStyleClass().add("danger");
        cleanButton.getStyleClass().add("danger");
        addDirButton.getStyleClass().add("button-outlined");
        removeDirButton.getStyleClass().add("button-outlined");
        autoSelectButton.getStyleClass().add("button-outlined");
        exportButton.getStyleClass().add("button-outlined");
        safetyInfoLabel.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        safetyInfoLabel.setWrapText(true);
        detailTitle.setStyle("-fx-text-fill: #8be9fd; -fx-font-size: 12px; -fx-font-weight: bold;");
        detailTitle.setWrapText(true);
        summaryLabel.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        summaryLabel.setWrapText(true);

        minSizeCombo.getItems().setAll(
                new MinSizeOption("All files", 1L),
                new MinSizeOption("> 100 KB", 100L * 1024),
                new MinSizeOption("> 1 MB", 1024 * 1024),
                new MinSizeOption("> 10 MB", 10L * 1024 * 1024));
        minSizeCombo.getSelectionModel().select(0);
        minSizeCombo.setPrefWidth(110);
        minSizeCombo.setTooltip(new Tooltip("Skip files below this size (faster scans)"));
        extFilterField.setPromptText("Types: *.jpg, *.png...");
        extFilterField.setPrefWidth(170);
        extFilterField.setTooltip(new Tooltip("Only include these file types (empty = all). Example: *.jpg, *.png, *.mp4"));
        keeperCombo.getItems().setAll(DuplicateKeeperStrategy.values());
        keeperCombo.getSelectionModel().select(DuplicateKeeperStrategy.NEWEST);
        keeperCombo.setPrefWidth(150);
        keeperCombo.setTooltip(new Tooltip("Which copy to keep per group (safest location always wins first)"));
        searchField.setPromptText("Search results...");
        searchField.setPrefWidth(170);
        searchField.setTooltip(new Tooltip("Filter groups by file name or keeper path"));
        autoSelectButton.setTooltip(new Tooltip("Select every group and every copy"));
        exportButton.setTooltip(new Tooltip("Export current results to CSV"));

        restoreDuplicatePrefs();

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
        autoSelectButton.setOnAction(e -> selectAllCopies());
        exportButton.setOnAction(e -> exportCsv());
        keeperCombo.setOnAction(e -> onKeeperStrategyChanged());
        searchField.textProperty().addListener((obs, ov, nv) -> applySearchFilter());
        minSizeCombo.setOnAction(e -> { persistDuplicatePrefs(); noteFiltersNeedRescan(); });
        extFilterField.focusedProperty().addListener((obs, ov, nv) -> { if (!nv) { persistDuplicatePrefs(); noteFiltersNeedRescan(); } });
        extFilterField.setOnAction(e -> { persistDuplicatePrefs(); noteFiltersNeedRescan(); });

        // Drag-and-drop folders onto the scan list.
        dirListView.setOnDragOver(e -> {
            Dragboard db = e.getDragboard();
            if (db.hasFiles() && !busy.get()) e.acceptTransferModes(TransferMode.COPY);
            e.consume();
        });
        dirListView.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean done = false;
            if (db.hasFiles() && !busy.get()) {
                for (java.io.File f : db.getFiles()) {
                    try {
                        if (f != null && f.isDirectory()) tryAddScanRoot(f.toPath().toAbsolutePath().normalize());
                    } catch (Exception ignored) {}
                }
                done = true;
            }
            e.setDropCompleted(done);
            e.consume();
        });

        HBox dirButtons = new HBox(4, addDirButton, removeDirButton);
        dirButtons.setAlignment(Pos.CENTER_LEFT);

        VBox dirBox = new VBox(4, dirListView, dirButtons, safetyInfoLabel);
        dirBox.setPrefWidth(260);

        // Wrapping button bars + never-shrink buttons: crowded HBoxes used to
        // squeeze every button label into "...". FlowPane wraps instead and
        // each button keeps its full preferred width.
        for (Button b : new Button[]{scanButton, stopButton, selectAllButton, deselectAllButton,
                autoSelectButton, exportButton, cleanButton, addDirButton, removeDirButton}) {
            b.setMinWidth(Button.USE_PREF_SIZE);
        }
        statusLabel.setWrapText(true);

        FlowPane actionBar = new FlowPane(8, 6, scanButton, stopButton, selectAllButton,
                deselectAllButton, autoSelectButton, exportButton, cleanButton);
        actionBar.setAlignment(Pos.CENTER_LEFT);

        HBox progressRow = new HBox(8, progressBar, progressLabel);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        VBox actionBox = new VBox(6, actionBar, progressRow, statusLabel);
        actionBox.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(actionBox, Priority.ALWAYS);

        HBox top = new HBox(12, dirBox, actionBox);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        FlowPane filterBar = new FlowPane(8, 6,
                new Label("Min size:"), minSizeCombo,
                new Label("Types:"), extFilterField,
                new Label("Keep:"), keeperCombo,
                new Label("Search:"), searchField);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(0, 16, 8, 16));

        VBox topBox = new VBox(top, filterBar, summaryLabel);
        topBox.setPadding(new Insets(0, 0, 0, 0));
        VBox.setMargin(summaryLabel, new Insets(0, 16, 8, 16));

        buildTable();

        VBox detailBox = new VBox(4, detailTitle, deletableListView);
        detailBox.setPadding(new Insets(0, 16, 12, 16));
        VBox.setVgrow(deletableListView, Priority.ALWAYS);

        VBox center = new VBox(8, table, detailBox);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(topBox);
        setCenter(center);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            stopButton.setDisable(!newVal);
            selectAllButton.setDisable(newVal);
            deselectAllButton.setDisable(newVal);
            autoSelectButton.setDisable(newVal);
            cleanButton.setDisable(newVal || getSelectedDeletableCount() == 0);
            addDirButton.setDisable(newVal);
            removeDirButton.setDisable(newVal);
            dirListView.setDisable(newVal);
            minSizeCombo.setDisable(newVal);
            extFilterField.setDisable(newVal);
            keeperCombo.setDisable(newVal || rows.isEmpty());
        });

        rows.addListener((ListChangeListener<DuplicateFileRow>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (DuplicateFileRow row : c.getAddedSubList()) {
                        javafx.beans.value.ChangeListener<Boolean> listener =
                                (obs, ov, nv) -> {
                                    // Ticking a group ticks all its copies (unticking clears
                                    // them), so Clean Selected enables immediately and the
                                    // detail pane mirrors the group state. Individual copies
                                    // can still be unticked afterwards for fine control.
                                    Map<String, BooleanProperty> fm = perFileSelection.get(row);
                                    if (fm != null && !fm.isEmpty() && nv != null) {
                                        for (BooleanProperty p : fm.values()) p.set(nv);
                                    }
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
            tryAddScanRoot(chosen.toPath().toAbsolutePath().normalize());
        }
    }

    private void tryAddScanRoot(Path chosenPath) {
        if (chosenPath == null) return;
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
        persistDuplicatePrefs();
    }

    private void removeSelectedDirectory() {
        Path selected = dirListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            scanRoots.remove(selected);
            if (scanRoots.isEmpty()) {
                statusLabel.setText("Add folder(s) to scan. System and app folders are blocked.");
            }
            persistDuplicatePrefs();
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
        exportButton.setDisable(rows.isEmpty());
        keeperCombo.setDisable(busy.get() || rows.isEmpty());
        updateSummary();
    }

    private void updateSummary() {
        if (rows.isEmpty()) {
            summaryLabel.setText("");
            return;
        }
        long groups = rows.size();
        long wasted = rows.stream().mapToLong(r -> (long) (r.getTotalDuplicates() - 1) * r.getFileSize()).sum();
        long selFiles = getSelectedDeletableCount();
        long selBytes = 0;
        for (DuplicateFileRow row : rows) {
            if (!row.isSelected()) continue;
            for (String p : getSelectedDeletablesForRow(row)) selBytes += row.getFileSize();
        }
        long shown = filteredRows.size();
        StringBuilder sb = new StringBuilder();
        sb.append(groups).append(" group(s), ")
                .append(DataSizeFormatter.formatBytes(wasted)).append(" reclaimable");
        if (shown != groups) sb.append(" (").append(shown).append(" shown)");
        if (selFiles > 0) {
            sb.append("  —  selected: ").append(selFiles).append(" file(s), ")
                    .append(DataSizeFormatter.formatBytes(selBytes));
        } else {
            sb.append("  —  nothing selected");
        }
        summaryLabel.setText(sb.toString());
    }

    private void applySearchFilter() {
        String q = searchField.getText();
        final String needle = q == null ? "" : q.trim().toLowerCase(java.util.Locale.ROOT);
        if (needle.isEmpty()) {
            filteredRows.setPredicate(r -> true);
        } else {
            filteredRows.setPredicate(r -> r != null
                    && ((r.getFileName() != null && r.getFileName().toLowerCase(java.util.Locale.ROOT).contains(needle))
                    || (r.getFullPath() != null && r.getFullPath().toLowerCase(java.util.Locale.ROOT).contains(needle))));
        }
        updateSummary();
        table.refresh();
    }

    private DuplicateScanOptions buildScanOptions() {
        MinSizeOption min = minSizeCombo.getSelectionModel().getSelectedItem();
        long minBytes = min == null ? 1L : Math.max(1L, min.bytes());
        java.util.Set<String> exts = DuplicateScanOptions.parseExtensionFilter(extFilterField.getText());
        DuplicateKeeperStrategy keeper = keeperCombo.getSelectionModel().getSelectedItem();
        if (keeper == null) keeper = DuplicateKeeperStrategy.NEWEST;
        return DuplicateScanOptions.of(minBytes, exts, keeper);
    }

    private DuplicateKeeperStrategy currentKeeperStrategy() {
        DuplicateKeeperStrategy k = keeperCombo.getSelectionModel().getSelectedItem();
        return k == null ? DuplicateKeeperStrategy.NEWEST : k;
    }

    private void onKeeperStrategyChanged() {
        if (busy.get() || rows.isEmpty()) {
            persistDuplicatePrefs();
            return;
        }
        DuplicateKeeperStrategy strategy = currentKeeperStrategy();
        int changed = 0;
        for (DuplicateFileRow row : rows) {
            try {
                if (DuplicateFinderService.recomputeKeeper(row, strategy)) {
                    changed++;
                    // Reset per-file selection for regrouped rows (safe default: unchecked).
                    Map<String, BooleanProperty> fileMap = new HashMap<>();
                    if (row.getDeletablePaths() != null) {
                        for (String p : row.getDeletablePaths()) {
                            BooleanProperty prop = new SimpleBooleanProperty(false);
                            prop.addListener((o, ov, nv) -> updateCleanButtonState());
                            fileMap.put(p, prop);
                        }
                    }
                    perFileSelection.put(row, fileMap);
                    row.setSelected(false);
                }
            } catch (Exception e) {
                AppLogger.warning("Keeper recompute failed for " + row.getFullPath() + ": " + e.getMessage());
            }
        }
        persistDuplicatePrefs();
        groupColorMap.clear();
        table.refresh();
        DuplicateFileRow sel = table.getSelectionModel().getSelectedItem();
        if (sel != null && rows.contains(sel)) updateDeletableDetail(sel);
        else {
            deletableListView.getItems().clear();
            detailTitle.setText("Select a group to see copies to delete");
        }
        updateCleanButtonState();
        if (changed > 0) statusLabel.setText("Keeper strategy: " + strategy.getDisplayName() + " — updated " + changed + " group(s). Review before cleaning.");
        else statusLabel.setText("Keeper strategy: " + strategy.getDisplayName() + " — no changes.");
    }

    private void restoreDuplicatePrefs() {
        try {
            AppSettings s = settingsStore.load();
            if (s.duplicateScanRoots() != null) {
                for (String rootStr : s.duplicateScanRoots()) {
                    try {
                        if (rootStr == null || rootStr.isBlank()) continue;
                        Path p = Paths.get(rootStr.trim()).toAbsolutePath().normalize();
                        if (Files.exists(p) && Files.isDirectory(p)
                                && DuplicateSafety.validateScanRoot(p) == null
                                && scanRoots.stream().noneMatch(e -> e.equals(p))) {
                            scanRoots.add(p);
                        }
                    } catch (Exception ignored) {}
                }
            }
            long minBytes = s.duplicateMinSizeBytes() < 1 ? 1L : s.duplicateMinSizeBytes();
            minSizeCombo.getSelectionModel().select(MinSizeOption.forBytes(minBytes));
            if (s.duplicateIncludeFilter() != null) extFilterField.setText(s.duplicateIncludeFilter());
            keeperCombo.getSelectionModel().select(DuplicateKeeperStrategy.fromString(s.duplicateKeeperStrategy()));
        } catch (Exception e) {
            AppLogger.warning("Failed to restore duplicate prefs: " + e.getMessage());
        }
    }

    /**
     * Scan filters apply at scan time. If results are already shown, tell the
     * user a rescan is needed instead of silently showing stale groups.
     */
    private void noteFiltersNeedRescan() {
        if (busy.get() || rows.isEmpty()) return;
        statusLabel.setText("Scan filters changed — click Scan to apply them to a fresh scan.");
    }

    private void persistDuplicatePrefs() {        try {
            MinSizeOption min = minSizeCombo.getSelectionModel().getSelectedItem();
            long minBytes = min == null ? 1L : Math.max(1L, min.bytes());
            String filter = extFilterField.getText() == null ? "" : extFilterField.getText().trim();
            DuplicateKeeperStrategy keeper = currentKeeperStrategy();
            List<String> roots = new ArrayList<>();
            for (Path p : scanRoots) {
                try { roots.add(p.toAbsolutePath().toString()); } catch (Exception ignored) {}
            }
            settingsStore.update(s -> s.toBuilder()
                    .duplicateScanRoots(roots)
                    .duplicateMinSizeBytes(minBytes)
                    .duplicateIncludeFilter(filter)
                    .duplicateKeeperStrategy(keeper.name())
                    .build());
        } catch (Exception e) {
            AppLogger.warning("Failed to persist duplicate prefs: " + e.getMessage());
        }
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
                BooleanProperty prop = new SimpleBooleanProperty(row.isSelected());
                prop.addListener((o, ov, nv) -> updateCleanButtonState());
                fileMap.put(p, prop);
            }
        }
        for (String path : row.getDeletablePaths()) {
            BooleanProperty prop = fileMap.get(path);
            CheckBox cb = new CheckBox(path);
            cb.setTooltip(new Tooltip(path + fileDetailHint(path, row.getFileSize())));
            cb.setSelected(prop.get());
            cb.selectedProperty().bindBidirectional(prop);
            // Individual copies can only be deleted when their group is ticked.
            cb.disableProperty().bind(row.selectedProperty().not());
            HBox.setHgrow(cb, Priority.ALWAYS);
            cb.setMaxWidth(Double.MAX_VALUE);
            Button keepBtn = new Button("Keep instead");
            keepBtn.setTooltip(new Tooltip("Make this copy the keeper (previous keeper becomes deletable)"));
            keepBtn.getStyleClass().add("button-outlined");
            // Non-destructive (only swaps which copy is listed as keeper),
            // so it stays enabled whenever no scan/clean is running.
            keepBtn.disableProperty().bind(busy);
            keepBtn.setOnAction(e -> reassignRowKeeper(row, path));
            Button openBtn = new Button("Open");
            openBtn.setTooltip(new Tooltip("Open this copy"));
            openBtn.getStyleClass().add("button-outlined");
            openBtn.setOnAction(e -> openFile(path));
            Label meta = new Label(fileDetailShort(path, row.getFileSize()));
            meta.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
            HBox box = new HBox(6, cb, meta, keepBtn, openBtn);
            box.setAlignment(Pos.CENTER_LEFT);
            deletableListView.getItems().add(box);
        }
    }

    private void reassignRowKeeper(DuplicateFileRow row, String newKeeperPath) {
        if (row == null || newKeeperPath == null || busy.get()) return;
        try {
            if (!DuplicateFinderService.reassignKeeper(row, newKeeperPath)) return;
            Map<String, BooleanProperty> fileMap = new HashMap<>();
            for (String p : row.getDeletablePaths()) {
                BooleanProperty prop = new SimpleBooleanProperty(false);
                prop.addListener((o, ov, nv) -> updateCleanButtonState());
                fileMap.put(p, prop);
            }
            perFileSelection.put(row, fileMap);
            row.setSelected(false);
            groupColorMap.clear();
            table.refresh();
            updateDeletableDetail(row);
            updateCleanButtonState();
            statusLabel.setText("Keeper changed to: " + newKeeperPath);
        } catch (Exception e) {
            AppLogger.warning("Failed to reassign keeper: " + e.getMessage());
        }
    }

    private static String fileDetailHint(String path, long fallbackSize) {
        try {
            Path p = Paths.get(path);
            Path eff = DuplicateFinderService.toLongPath(p);
            long size = fallbackSize;
            try { size = Files.size(eff); } catch (Exception ignored) {}
            String modified = "";
            try { modified = Files.getLastModifiedTime(eff).toInstant().toString(); } catch (Exception ignored) {}
            return "\n" + DataSizeFormatter.formatBytes(size) + (modified.isEmpty() ? "" : "  •  modified " + modified);
        } catch (Exception e) {
            return "";
        }
    }

    private static String fileDetailShort(String path, long fallbackSize) {
        try {
            Path eff = DuplicateFinderService.toLongPath(Paths.get(path));
            long size = fallbackSize;
            try { size = Files.size(eff); } catch (Exception ignored) {}
            return DataSizeFormatter.formatBytes(size);
        } catch (Exception e) {
            return DataSizeFormatter.formatBytes(fallbackSize);
        }
    }

    private void selectAllCopies() {
        if (busy.get() || rows.isEmpty()) return;
        for (DuplicateFileRow row : rows) {
            row.setSelected(true);
            Map<String, BooleanProperty> fileMap = perFileSelection.get(row);
            if (fileMap != null) {
                for (BooleanProperty p : fileMap.values()) p.set(true);
            }
        }
        DuplicateFileRow sel = table.getSelectionModel().getSelectedItem();
        if (sel != null) updateDeletableDetail(sel);
        updateCleanButtonState();
    }

    private void exportCsv() {
        if (rows.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No results to export. Run a scan first.").showAndWait();
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export duplicate results (CSV)");
        fc.setInitialFileName("duplicates.csv");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
        File target = fc.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (target == null) return;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("File Name,Keeper Path,Size Bytes,Size,SHA-256,Total Copies,Deletable Paths\n");
            for (DuplicateFileRow r : rows) {
                sb.append(csvCell(r.getFileName())).append(',')
                        .append(csvCell(r.getFullPath())).append(',')
                        .append(r.getFileSize()).append(',')
                        .append(csvCell(DataSizeFormatter.formatBytes(r.getFileSize()))).append(',')
                        .append(csvCell(r.getChecksumSha256())).append(',')
                        .append(r.getTotalDuplicates()).append(',')
                        .append(csvCell(r.getDeletablePaths() == null ? "" : String.join(" | ", r.getDeletablePaths())))
                        .append('\n');
            }
            Files.writeString(target.toPath(), sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
            new Alert(Alert.AlertType.INFORMATION, "Exported " + rows.size() + " group(s) to:\n" + target).showAndWait();
        } catch (Exception e) {
            AppLogger.warning("Duplicate CSV export failed: " + e.getMessage());
            new Alert(Alert.AlertType.ERROR, "Export failed:\n" + e.getMessage()).showAndWait();
        }
    }

    private static String csvCell(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
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
        searchField.clear();
        filteredRows.setPredicate(r -> true);
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        progressLabel.setVisible(true);
        progressLabel.setText("Preparing...");
        persistDuplicatePrefs();

        List<Path> rootsToScan = List.copyOf(scanRoots);
        DuplicateScanOptions options = buildScanOptions();

        scanThread = new Thread(() -> {
            try {
                long[] lastProgressUpdate = {0};
                ScanResult scan = service.scanWithStats(
                        rootsToScan,
                        options,
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
                List<DuplicateFileRow> results = scan.getRows();
                Platform.runLater(() -> {
                    if (cancelled.get() || scan.isCancelled()) {
                        statusLabel.setText("Scan cancelled.");
                    } else {
                        rows.setAll(results);
                        long totalBytes = results.stream().mapToLong(DuplicateFileRow::getFileSize).sum();
                        long reclaimable = scan.getReclaimableBytes();
                        StringBuilder extra = new StringBuilder();
                        if (scan.getSkippedProtected() > 0) {
                            extra.append(" Skipped ").append(scan.getSkippedProtected()).append(" protected file(s).");
                        }
                        if (scan.getSkippedFiltered() > 0) {
                            extra.append(" Filtered out ").append(scan.getSkippedFiltered()).append(" file(s).");
                        }
                        if (results.isEmpty()) {
                            statusLabel.setText("No duplicates found in selected folders. System and app folders were excluded." + extra);
                        } else {
                            statusLabel.setText("Found " + results.size() + " duplicate group(s) — "
                                    + DataSizeFormatter.formatBytes(totalBytes)
                                    + " total, " + DataSizeFormatter.formatBytes(reclaimable) + " reclaimable (system and app folders excluded)."
                                    + extra + " Tick groups to select for deletion.");
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
        boolean allSelected = !rows.isEmpty() && rows.stream().allMatch(r -> r.isSelected()
                && r.getDeletablePaths() != null
                && getSelectedDeletablesForRow(r).size() == r.getDeletablePaths().size());
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
