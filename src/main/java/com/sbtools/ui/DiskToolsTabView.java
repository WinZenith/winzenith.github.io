package com.sbtools.ui;

import com.sbtools.defrag.BenchmarkResult;
import com.sbtools.defrag.BenchmarkService;
import com.sbtools.defrag.DefragService;
import com.sbtools.defrag.DriveInfo;
import com.sbtools.diskhealth.DiskHealthInfo;
import com.sbtools.diskhealth.DiskHealthService;
import com.sbtools.shredder.FolderDeleteResult;
import com.sbtools.shredder.RecycleBinEntry;
import com.sbtools.shredder.ShredderFileEntry;
import com.sbtools.shredder.ShredderResult;
import com.sbtools.shredder.ShredderSafety;
import com.sbtools.shredder.ShredderService;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DiskToolsTabView extends BorderPane {

    /**
     * Fire-and-forget workers must never keep the JVM alive after window close.
     * All raw threads in this view go through here so they are daemon by construction.
     */
    private static Thread newDaemonThread(Runnable task, String name) {
        Thread t = new java.lang.Thread(task, name);
        t.setDaemon(true);
        return t;
    }

    private final BooleanProperty defragBusy = new SimpleBooleanProperty(false);
    private final BooleanProperty wipeBusy = new SimpleBooleanProperty(false);
    private final BooleanProperty secureBusy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;
    private final DefragService defragService = new DefragService();
    private final ShredderService shredderService = new ShredderService();
    private final DiskHealthService diskHealthService = new DiskHealthService();
    private final BenchmarkService benchmarkService = new BenchmarkService();

    private final AtomicBoolean defragCancelled = new AtomicBoolean(false);
    private final AtomicBoolean wipeCancelled = new AtomicBoolean(false);

    /* ───── Defrag tab components ───── */
    private final TableView<DriveInfo> driveTable = new TableView<>();
    private final ObservableList<DriveInfo> allDrives = FXCollections.observableArrayList();
    private final FilteredList<DriveInfo> filteredDrives = new FilteredList<>(allDrives, d -> true);
    private final ComboBox<String> filterCombo = new ComboBox<>(
            FXCollections.observableArrayList("All", "HDD", "SSD"));
    private final CheckBox selectAllCheck = new CheckBox();
    private final Map<String, BooleanProperty> driveSelected = new HashMap<>();
    private final Button analyzeBtn = new Button("Analyze Selected");
    private final Button intelligentDefragBtn = new Button("Intelligent Defrag");
    private final ComboBox<String> defragModeCombo = new ComboBox<>(
            FXCollections.observableArrayList("Auto", "Quick", "Deep"));
    private final Button stopBtn = new Button("Stop");
    private final ProgressBar defragProgress = new ProgressBar(0);
    private final Label defragStatus = new Label("Select drives and click Analyze Selected.");
    private final Button refreshDrivesBtn = new Button("Refresh");
    private final Label emptyDrivesLabel = new Label("No drives detected. Click Refresh to retry.");
    private Thread currentDefragThread;
    private Thread currentAnalyzeThread;
    private final Canvas blockCanvas = new Canvas(400, 200);
    private final Label driveAnalysisLabel = new Label();
    private final Label fragCountLabel = new Label();
    private final Label fragPercentLabel = new Label();
    private final HBox legendBox = new HBox(8);
    private final VBox visualizationPanel = new VBox(8);
    private final Set<String> analyzedDrives = new CopyOnWriteArraySet<>();
    private final Map<String, Instant> lastAnalyzed = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastDefragged = new ConcurrentHashMap<>();

    /* ───── Secure Erase tab components ───── */
    private final TableView<ShredderFileEntry> shredderTable = new TableView<>();
    private final ObservableList<ShredderFileEntry> shredderEntries = FXCollections.observableArrayList();
    private final TextField filePathField = new TextField();
    private final Button browseBtn = new Button("Browse...");
    private final Button addFilesBtn = new Button("Add Files");
    private final Button secureDeleteBtn = new Button("Secure Delete");
    private final Button deleteAllBtn = new Button("Delete All");
    private final Button stopSecureBtn = new Button("Stop");
    private final AtomicBoolean secureCancelled = new AtomicBoolean(false);
    private final ProgressBar secureDeleteProgress = new ProgressBar(0);
    private final Label secureDeleteStatus = new Label();
    private final ComboBox<String> overwritePresetCombo = new ComboBox<>(
            FXCollections.observableArrayList("Quick (1 pass)", "Standard (3 passes)", "Deep (7 passes)"));

    private final TableView<DriveInfo> wipeDriveTable = new TableView<>();
    private final ObservableList<DriveInfo> wipeDrives = FXCollections.observableArrayList();
    private final Button startWipeBtn = new Button("Start");
    private final Button stopWipeBtn = new Button("Stop");
    private final ProgressBar wipeProgress = new ProgressBar(0);
    private final Label wipeStatus = new Label("Select drives and click Start.");
    private final CheckBox selectAllWipeCheck = new CheckBox("Select All");
    private final Map<String, BooleanProperty> wipeSelected = new HashMap<>();

    /* ───── Recycle Bin tab components ───── */
    private final TableView<RecycleBinEntry> recycleBinTable = new TableView<>();
    private final ObservableList<RecycleBinEntry> recycleBinEntries = FXCollections.observableArrayList();
    private final Button refreshRecycleBinBtn = new Button("Refresh");
    private final Button secureWipeRecycleBinBtn = new Button("Secure Wipe Recycle Bin");
    private final ProgressBar recycleBinProgress = new ProgressBar(0);
    private final Label recycleBinStatus = new Label("Click Refresh to list Recycle Bin contents.");
    private final Label recycleBinSummary = new Label();
    private final AtomicBoolean recycleBinBusy = new AtomicBoolean(false);
    private final AtomicBoolean recycleBinCancelled = new AtomicBoolean(false);

    /* ───── Shared thread pool ───── */
    private final ExecutorService sharedExecutor = Executors.newFixedThreadPool(3, r -> {
        Thread t = newDaemonThread(r, "disk-tools-worker");
        t.setDaemon(true);
        return t;
    });

    /* ───── Disk Health tab components ───── */
    private final ComboBox<String> healthDriveCombo = new ComboBox<>();
    private final ObservableList<DiskHealthInfo> healthDrives = FXCollections.observableArrayList();
    private final Map<String, DiskHealthInfo> healthDriveMap = new HashMap<>();
    private final Button refreshHealthBtn = new Button("Refresh");
    private final ProgressBar healthProgress = new ProgressBar(0);
    private final Label healthStatus = new Label("Click Refresh to load disk health data.");
    private final GridPane smartGrid = new GridPane();
    private final Label overallHealthLabel = new Label();
    private boolean smartctlAvailable = false;

    /* ───── Benchmark tab components ───── */
    private final ComboBox<String> benchDriveCombo = new ComboBox<>();
    private final Map<String, DriveInfo> benchDriveMap = new HashMap<>();
    private final ComboBox<String> benchSizeCombo = new ComboBox<>(
            FXCollections.observableArrayList("32 MB", "64 MB", "128 MB", "256 MB"));
    private final Button benchStartBtn = new Button("Start Benchmark");
    private final Button benchStopBtn = new Button("Stop");
    private final ProgressBar benchProgress = new ProgressBar(0);
    private final Label benchStatus = new Label("Select a drive and click Start Benchmark.");
    private final AtomicBoolean benchCancelled = new AtomicBoolean(false);
    private Thread currentBenchThread;
    private final Label benchSeqWriteLabel = new Label("-");
    private final Label benchSeqReadLabel = new Label("-");
    private final Label benchRandomReadLabel = new Label("-");
    private final Label benchRandom4KLabel = new Label("-");
    private final Label benchLatencyLabel = new Label("-");

    public DiskToolsTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        ShredderService.sweepOrphanedTempFiles();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab defragTab = new Tab("Defrag", buildDefragContent());
        Tab healthTab = new Tab("Disk Health", buildDiskHealthContent());
        Tab benchmarkTab = new Tab("Benchmark", buildBenchmarkContent());
        Tab secureEraseTab = new Tab("Secure Erase", buildSecureEraseContent());

        tabPane.getTabs().addAll(defragTab, healthTab, benchmarkTab, secureEraseTab);
        setCenter(tabPane);

        loadDrives();
    }

    /* ===================================================================
       DEFRAG TAB
       =================================================================== */

    private VBox buildDefragContent() {
        defragStatus.getStyleClass().add("text-muted");
        defragProgress.setVisible(false);
        defragProgress.setPrefWidth(200);

        filterCombo.getSelectionModel().select(0);
        filterCombo.setOnAction(e -> applyFilter());

        selectAllCheck.setTooltip(new Tooltip("Select/Deselect all visible drives"));
        selectAllCheck.setOnAction(e -> {
            boolean sel = selectAllCheck.isSelected();
            for (DriveInfo d : filteredDrives) {
                BooleanProperty prop = driveSelected.computeIfAbsent(d.getDriveLetter(), k -> createDriveSelectedProp(k));
                prop.set(sel);
            }
            driveTable.refresh();
            updateDefragButtons();
        });

        analyzeBtn.setDisable(true);
        intelligentDefragBtn.setDisable(true);
        intelligentDefragBtn.setTooltip(new Tooltip("Full defrag for HDD, ReTrim for SSD"));

        defragModeCombo.getSelectionModel().select(0);
        defragModeCombo.setTooltip(new Tooltip("Auto: best mode per drive type\nQuick: lighter/faster defrag\nDeep: full defrag + free space consolidation"));

        stopBtn.getStyleClass().add("danger");
        stopBtn.setVisible(false);
        stopBtn.setOnAction(e -> stopDefragOperation());

        refreshDrivesBtn.getStyleClass().add("accent");
        refreshDrivesBtn.setTooltip(new Tooltip("Refresh drive list"));
        refreshDrivesBtn.setOnAction(e -> loadDrives());

        analyzeBtn.setOnAction(e -> startAnalyze());
        intelligentDefragBtn.setOnAction(e -> startIntelligentDefrag());

        emptyDrivesLabel.getStyleClass().addAll("label", "text-muted");
        emptyDrivesLabel.setWrapText(true);
        emptyDrivesLabel.setVisible(false);
        emptyDrivesLabel.setManaged(false);
        emptyDrivesLabel.setPadding(new Insets(8, 0, 8, 0));
        emptyDrivesLabel.setStyle("-fx-font-size: 12px;");

        HBox defragToolbar = new HBox(8,
                refreshDrivesBtn, selectAllCheck, new Label("Filter:"), filterCombo,
                analyzeBtn, intelligentDefragBtn, defragModeCombo,
                stopBtn, defragProgress, defragStatus);
        defragToolbar.setAlignment(Pos.CENTER_LEFT);
        defragToolbar.setPadding(new Insets(12, 16, 12, 16));
        defragToolbar.getStyleClass().add("toolbar");

        buildDriveTable();

        VBox center = new VBox(4, driveTable, emptyDrivesLabel);
        center.setPadding(new Insets(8, 16, 4, 16));

        visualizationPanel.setPadding(new Insets(4, 16, 12, 16));
        visualizationPanel.setVisible(false);

        driveAnalysisLabel.getStyleClass().addAll("label", "accent");

        HBox blockBox = new HBox(blockCanvas);
        blockBox.setAlignment(Pos.CENTER);

        HBox statsBox = new HBox(24, fragCountLabel, fragPercentLabel);
        statsBox.setAlignment(Pos.CENTER);

        legendBox.setAlignment(Pos.CENTER);

        visualizationPanel.getChildren().addAll(driveAnalysisLabel, blockBox, legendBox, statsBox);

        defragBusy.addListener((obs, oldVal, newVal) -> {
            stopBtn.setVisible(newVal);
            refreshDrivesBtn.setDisable(newVal);
            if (newVal) {
                stopBtn.setDisable(false);
                analyzeBtn.setDisable(true);
                intelligentDefragBtn.setDisable(true);
            } else {
                defragProgress.setProgress(0);
                stopBtn.setDisable(true);
                updateDefragButtons();
            }
        });

        blockCanvas.widthProperty().addListener((obs, oldVal, newVal) -> refreshVisualization());
        blockCanvas.heightProperty().addListener((obs, oldVal, newVal) -> refreshVisualization());

        VBox content = new VBox(defragToolbar, center, visualizationPanel);
        VBox.setVgrow(visualizationPanel, Priority.ALWAYS);
        return content;
    }

    private void buildDriveTable() {
        driveTable.setItems(filteredDrives);
        driveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriveInfo, DriveInfo> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();
            private BooleanProperty prevProp;
            @Override
            protected void updateItem(DriveInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (prevProp != null) {
                    cb.selectedProperty().unbindBidirectional(prevProp);
                    prevProp = null;
                }
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    String key = item.getDriveLetter();
                    BooleanProperty prop = driveSelected.computeIfAbsent(key, k -> createDriveSelectedProp(k));
                    cb.selectedProperty().bindBidirectional(prop);
                    prevProp = prop;
                    setGraphic(cb);
                }
            }
        });

        TableColumn<DriveInfo, String> letterCol = new TableColumn<>("Drive");
        letterCol.setCellValueFactory(c -> c.getValue().driveLetterProperty());
        letterCol.setPrefWidth(60);

        TableColumn<DriveInfo, String> labelCol = new TableColumn<>("Label");
        labelCol.setCellValueFactory(c -> {
            String label = c.getValue().getVolumeLabel();
            return new SimpleObjectProperty<>(label.isBlank() ? "-" : label);
        });
        labelCol.setPrefWidth(120);

        TableColumn<DriveInfo, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> c.getValue().mediaTypeProperty());
        typeCol.setPrefWidth(70);

        TableColumn<DriveInfo, String> fsCol = new TableColumn<>("File System");
        fsCol.setCellValueFactory(c -> c.getValue().fileSystemProperty());
        fsCol.setPrefWidth(90);

        TableColumn<DriveInfo, String> sizeCol = new TableColumn<>("Total Size");
        sizeCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getSizeFormatted()));
        sizeCol.setPrefWidth(100);

        TableColumn<DriveInfo, String> freeCol = new TableColumn<>("Free Space");
        freeCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getFreeFormatted()));
        freeCol.setPrefWidth(100);

        TableColumn<DriveInfo, String> lastAnalyzedCol = new TableColumn<>("Last Analyzed");
        lastAnalyzedCol.setCellValueFactory(c -> {
            Instant last = lastAnalyzed.get(c.getValue().getDriveLetter());
            if (last == null) return new SimpleObjectProperty<>("-");
            Duration elapsed = Duration.between(last, Instant.now());
            if (elapsed.toMinutes() < 1) return new SimpleObjectProperty<>("Just now");
            if (elapsed.toHours() < 1) return new SimpleObjectProperty<>(elapsed.toMinutes() + " min ago");
            if (elapsed.toDays() < 1) return new SimpleObjectProperty<>(elapsed.toHours() + " hours ago");
            return new SimpleObjectProperty<>(elapsed.toDays() + " days ago");
        });
        lastAnalyzedCol.setPrefWidth(110);

        TableColumn<DriveInfo, String> lastDefraggedCol = new TableColumn<>("Last Defragged");
        lastDefraggedCol.setCellValueFactory(c -> {
            Instant last = lastDefragged.get(c.getValue().getDriveLetter());
            if (last == null) return new SimpleObjectProperty<>("-");
            Duration elapsed = Duration.between(last, Instant.now());
            if (elapsed.toMinutes() < 1) return new SimpleObjectProperty<>("Just now");
            if (elapsed.toHours() < 1) return new SimpleObjectProperty<>(elapsed.toMinutes() + " min ago");
            if (elapsed.toDays() < 1) return new SimpleObjectProperty<>(elapsed.toHours() + " hours ago");
            return new SimpleObjectProperty<>(elapsed.toDays() + " days ago");
        });
        lastDefraggedCol.setPrefWidth(110);

        driveTable.getColumns().addAll(checkCol, letterCol, labelCol, typeCol, fsCol, sizeCol, freeCol, lastAnalyzedCol, lastDefraggedCol);

        driveTable.setFixedCellSize(32);

        updateTableHeight();
        filteredDrives.addListener((javafx.collections.ListChangeListener<DriveInfo>) c -> updateTableHeight());

        driveTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null && analyzedDrives.contains(sel.getDriveLetter())) {
                updateRichBlockVisualization(sel);
            } else {
                visualizationPanel.setVisible(false);
            }
        });
    }

    private void updateTableHeight() {
        int rows = Math.max(filteredDrives.size(), 1);
        double header = 30;
        double rowH = driveTable.getFixedCellSize();
        double height = header + rows * rowH + rowH + 4;
        driveTable.setPrefHeight(height);
        driveTable.setMinHeight(height);
    }

    private void applyFilter() {
        String filter = filterCombo.getSelectionModel().getSelectedItem();
        if (filter == null || "All".equals(filter)) {
            filteredDrives.setPredicate(d -> true);
        } else {
            filteredDrives.setPredicate(d -> filter.equalsIgnoreCase(d.getMediaType()));
        }
        // If filter hides everything but drives exist, hint user
        if (!allDrives.isEmpty() && filteredDrives.isEmpty()) {
            emptyDrivesLabel.setText("No drives match filter '" + filter + "'. Switch filter to 'All' to see " + allDrives.size() + " drive(s).");
            emptyDrivesLabel.setVisible(true);
            emptyDrivesLabel.setManaged(true);
        } else if (allDrives.isEmpty()) {
            updateEmptyDrivesState();
        } else {
            // drives exist and filtered not empty -> hide filter-empty hint, but keep enumeration-empty logic
            if (emptyDrivesLabel.getText() != null && emptyDrivesLabel.getText().contains("No drives match filter")) {
                emptyDrivesLabel.setVisible(false);
                emptyDrivesLabel.setManaged(false);
            }
        }
        updateTableHeight();
    }

    private BooleanProperty createDriveSelectedProp(String driveLetter) {
        BooleanProperty prop = new SimpleBooleanProperty(false);
        prop.addListener((obs, oldVal, newVal) -> updateDefragButtons());
        return prop;
    }

    private void updateDefragButtons() {
        boolean anySelected = allDrives.stream()
                .anyMatch(d -> driveSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get());
        boolean isBusy = defragBusy.get();
        analyzeBtn.setDisable(isBusy || !anySelected);
        intelligentDefragBtn.setDisable(isBusy || !anySelected);
    }

    private void loadDrives() {
        analyzeBtn.setDisable(true);
        intelligentDefragBtn.setDisable(true);
        refreshDrivesBtn.setDisable(true);
        defragProgress.setVisible(true);
        defragProgress.setProgress(-1);
        defragStatus.setText("Loading drives...");
        emptyDrivesLabel.setVisible(false);
        emptyDrivesLabel.setManaged(false);

        newDaemonThread(() -> {
            try {
                List<DriveInfo> drives = defragService.getDrives();
                Platform.runLater(() -> {
                    analyzedDrives.clear();
                    lastAnalyzed.clear();
                    lastDefragged.clear();
                    // Prune selection maps of stale letters
                    driveSelected.keySet().retainAll(drives.stream().map(DriveInfo::getDriveLetter).toList());
                    wipeSelected.keySet().retainAll(drives.stream().map(DriveInfo::getDriveLetter).toList());
                    allDrives.setAll(drives);
                    wipeDrives.setAll(drives);
                    driveTable.refresh();
                    wipeDriveTable.refresh();
                    benchDriveCombo.getItems().clear();
                    benchDriveMap.clear();
                    for (DriveInfo d : drives) {
                        String display = d.getDriveLetter() + " - " + d.getVolumeLabel()
                                + " (" + d.getMediaType() + ", " + d.getSizeFormatted() + ")";
                        // Ensure unique keys if two volumes share same label/size (unlikely but safe)
                        String key = display;
                        int dup = 1;
                        while (benchDriveMap.containsKey(key)) key = display + " #" + (++dup);
                        benchDriveMap.put(key, d);
                        benchDriveCombo.getItems().add(key);
                    }
                    if (!benchDriveCombo.getItems().isEmpty() && benchDriveCombo.getSelectionModel().getSelectedItem() == null) {
                        benchDriveCombo.getSelectionModel().select(0);
                    }
                    updateBenchStartButton();
                    defragProgress.setVisible(false);
                    refreshDrivesBtn.setDisable(false);
                    if (drives.isEmpty()) {
                        defragStatus.setText("No drives detected. Check connections or click Refresh. See logs/drive-enum-last.json for details.");
                        emptyDrivesLabel.setText("No drives detected. This can happen if the Storage service is busy, drivers are missing, or all volumes are hidden. Click Refresh to retry. If the problem persists, check " + AppPaths.logsDir().resolve("drive-enum-last.json"));
                        emptyDrivesLabel.setVisible(true);
                        emptyDrivesLabel.setManaged(true);
                        AppLogger.warning("loadDrives: enumeration returned 0 drives");
                    } else {
                        defragStatus.setText("Found " + drives.size() + " drive(s). Select drives and click Analyze Selected.");
                        emptyDrivesLabel.setVisible(false);
                        emptyDrivesLabel.setManaged(false);
                    }
                    updateDefragButtons();
                    updateWipeStartButton();
                    updateTableHeight();
                    applyFilter();
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load drives", e);
                Platform.runLater(() -> {
                    defragProgress.setVisible(false);
                    refreshDrivesBtn.setDisable(false);
                    defragStatus.setText("Failed to load drives — click Refresh to retry.");
                    emptyDrivesLabel.setText("Failed to load drives: " + (e.getMessage() != null ? e.getMessage() : "Unknown error") + ". Click Refresh to retry. Details logged to app.log");
                    emptyDrivesLabel.setVisible(true);
                    emptyDrivesLabel.setManaged(true);
                    updateDefragButtons();
                    new Alert(Alert.AlertType.ERROR, "Failed to load drives:\n" + e.getMessage() + "\n\nClick Refresh to retry.").showAndWait();
                });
            }
        }, "load-drives").start();
    }

    private void updateEmptyDrivesState() {
        boolean isEmpty = filteredDrives.isEmpty() && allDrives.isEmpty();
        emptyDrivesLabel.setVisible(isEmpty);
        emptyDrivesLabel.setManaged(isEmpty);
    }

    private void stopDefragOperation() {
        defragCancelled.set(true);
        stopBtn.setDisable(true);
        defragStatus.setText("Stopping...");
        defragProgress.setProgress(-1);
        if (currentDefragThread != null && currentDefragThread.isAlive()) {
            currentDefragThread.interrupt();
        }
        if (currentAnalyzeThread != null && currentAnalyzeThread.isAlive()) {
            currentAnalyzeThread.interrupt();
        }
    }

    private void refreshVisualization() {
        DriveInfo sel = driveTable.getSelectionModel().getSelectedItem();
        if (sel != null && analyzedDrives.contains(sel.getDriveLetter())) {
            updateRichBlockVisualization(sel);
        }
    }

    private void startAnalyze() {
        List<DriveInfo> selected = allDrives.stream()
                .filter(d -> driveSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get())
                .toList();
        if (selected.isEmpty() || defragBusy.get()) return;

        if (currentAnalyzeThread != null && currentAnalyzeThread.isAlive()) {
            new Alert(Alert.AlertType.WARNING, "An analysis is already running. Please wait or stop it first.").showAndWait();
            return;
        }

        defragBusy.set(true);
        defragCancelled.set(false);
        defragProgress.setProgress(-1);
        defragProgress.setVisible(true);
        defragStatus.setText("Analyzing " + selected.size() + " drive(s)...");

        Instant startTime = Instant.now();
        int totalDrives = selected.size();

        ConcurrentLinkedQueue<String> statusMessages = new ConcurrentLinkedQueue<>();
        AtomicInteger completedCount = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = selected.stream()
                .map(driveCopy -> CompletableFuture.runAsync(() -> {
                    if (defragCancelled.get()) return;

                    String letter = driveCopy.getDriveLetter();
                    Platform.runLater(() -> defragStatus.setText(
                            "Analyzing " + letter + "... (" + (completedCount.get() + 1) + "/" + totalDrives + ")"));

                    try {
                        defragService.analyze(driveCopy, msg -> {
                            statusMessages.add(letter + ": " + msg);
                            Platform.runLater(() -> defragStatus.setText(msg));
                        }, defragCancelled);

                        analyzedDrives.add(letter);
                        lastAnalyzed.put(letter, Instant.now());

                        int done = completedCount.incrementAndGet();
                        Platform.runLater(() -> {
                            driveTable.refresh();
                            String elapsed = formatElapsed(Duration.between(startTime, Instant.now()));
                            defragStatus.setText("Analysis complete - "
                                    + driveCopy.getFragmentsFormatted() + " fragmented space, "
                                    + driveCopy.getFragmentationPercent() + "% fragmentation on " + letter
                                    + " (" + elapsed + ", " + done + "/" + totalDrives + ")");
                            updateRichBlockVisualization(driveCopy);
                        });
                    } catch (java.util.concurrent.CancellationException e) {
                        // ignored, handled at top level
                    } catch (Exception e) {
                        String msg = e.getMessage() != null ? e.getMessage() : "Analysis failed for " + letter;
                        Platform.runLater(() -> {
                            defragStatus.setText("Analysis failed for " + letter);
                            new Alert(Alert.AlertType.ERROR, msg).showAndWait();
                        });
                    }
                }, sharedExecutor))
                .toList();

        currentAnalyzeThread = newDaemonThread(() -> {
            try {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            } catch (java.util.concurrent.CancellationException e) {
                Platform.runLater(() -> defragStatus.setText("Analysis cancelled."));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String msg = e.getMessage() != null ? e.getMessage() : "Analysis failed.";
                    defragStatus.setText("Analysis failed.");
                    new Alert(Alert.AlertType.ERROR, msg).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    defragBusy.set(false);
                    defragProgress.setVisible(false);
                });
            }
        }, "analyze-orchestrator");
        currentAnalyzeThread.setDaemon(true);
        currentAnalyzeThread.start();
    }

    private void startIntelligentDefrag() {
        List<DriveInfo> selected = allDrives.stream()
                .filter(d -> driveSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get())
                .toList();
        if (selected.isEmpty() || defragBusy.get()) return;

        if (currentDefragThread != null && currentDefragThread.isAlive()) {
            new Alert(Alert.AlertType.WARNING, "A defrag operation is already running. Please wait or stop it first.").showAndWait();
            return;
        }

        boolean anyNotAnalyzed = selected.stream()
                .anyMatch(d -> !analyzedDrives.contains(d.getDriveLetter()));
        if (anyNotAnalyzed) {
            Alert warn = new Alert(Alert.AlertType.WARNING,
                    "Some selected drives have not been analyzed yet.\n"
                            + "It is recommended to analyze drives first for accurate results.\n\n"
                            + "Continue anyway?");
            warn.setHeaderText("Drives not analyzed");
            if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Defrag operations require administrator rights.").showAndWait();
            return;
        }

        // Reliability: pre-flight checks (FS, free space, Unknown media).
        // BLOCK entries stop the operation; warnings are appended to the confirm dialog.
        List<String> blockers = new ArrayList<>();
        List<String> preWarnings = new ArrayList<>();
        List<String> unknownLetters = new ArrayList<>();
        for (DriveInfo d : selected) {
            for (String w : DefragService.validateForDefrag(d)) {
                if (w.startsWith("BLOCK:")) blockers.add(d.getDriveLetter() + ": " + w.substring(6).trim());
                else preWarnings.add(d.getDriveLetter() + ": " + w);
            }
            if (!d.isSsd() && d.isUnknownMedia()) unknownLetters.add(d.getDriveLetter());
        }
        if (!blockers.isEmpty()) {
            new Alert(Alert.AlertType.ERROR,
                    "Cannot start defrag:\n\n" + String.join("\n", blockers)).showAndWait();
            return;
        }
        if (!unknownLetters.isEmpty()) {
            Alert uw = new Alert(Alert.AlertType.WARNING,
                    "Unknown media type on " + String.join(", ", unknownLetters) + ".\n\n"
                            + "These may be flash media (USB/SD/Storage Spaces/RAID) where "
                            + "defrag causes wear without benefit.\n\nContinue anyway?");
            uw.setHeaderText("Unknown drive type");
            if (uw.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        String mode = defragModeCombo.getSelectionModel().getSelectedItem();
        if (mode == null) mode = "Auto";
        final String defragMode = mode;

        StringBuilder drivesInfo = new StringBuilder();
        for (DriveInfo d : selected) {
            drivesInfo.append(d.getDriveLetter()).append(" (")
                    .append(d.getSizeFormatted()).append(", ")
                    .append(d.getMediaType());
            if (analyzedDrives.contains(d.getDriveLetter())) {
                drivesInfo.append(", ").append(d.getFragmentationPercent()).append("% frag");
            }
            drivesInfo.append(")\n");
        }

        String modeDescription = switch (defragMode) {
            case "Quick" -> "Quick defrag (faster, lighter pass)";
            case "Deep" -> "Deep defrag (full defrag + free space consolidation)";
            default -> "Auto (best mode per drive type: SSD=Trim, HDD=Full)";
        };

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Drives:\n" + drivesInfo.toString()
                        + "\nMode: " + modeDescription
                        + (preWarnings.isEmpty() ? "" : "\n\nWarnings:\n" + String.join("\n", preWarnings))
                        + "\n\nProceed?");
        confirm.setHeaderText("Intelligent Defrag (" + mode + ")");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        defragBusy.set(true);
        defragCancelled.set(false);
        defragProgress.setProgress(0);
        defragProgress.setVisible(true);

        Instant startTime = Instant.now();

        currentDefragThread = newDaemonThread(() -> {
            List<String> failedDrives = new ArrayList<>();
            try {
                for (int i = 0; i < selected.size(); i++) {
                    DriveInfo driveCopy = selected.get(i);
                    String letter = driveCopy.getDriveLetter();
                    int driveIndex = i;
                    if (defragCancelled.get()) break;

                    int current = driveIndex + 1;
                    int total = selected.size();

                    try {
                        if (driveCopy.isSsd()) {
                            String statusPrefix = "Trim on " + letter;
                            Platform.runLater(() -> defragStatus.setText(statusPrefix + "... (" + current + "/" + total + ")"));
                            defragService.trim(driveCopy,
                                    msg -> Platform.runLater(() -> defragStatus.setText(msg)),
                                    pct -> Platform.runLater(() -> {
                                        defragProgress.setProgress(pct);
                                        defragStatus.setText("Trim " + letter + " " + Math.round(pct * 100) + "%");
                                    }),
                                    defragCancelled);
                        } else {
                            DefragService.DefragOption option = resolveDefragOption(defragMode, driveCopy);
                            String hddModeLabel = switch (option) {
                                case FAST -> "Quick Defrag";
                                case FREE_SPACE -> "Free Space Consolidation";
                                case DEEP -> "Deep Defrag (Full + Free Space)";
                                default -> "Full Defrag";
                            };
                            String statusPrefix = hddModeLabel + " on " + letter;
                            Platform.runLater(() -> defragStatus.setText(statusPrefix + "... (" + current + "/" + total + ")"));
                            defragService.defrag(driveCopy, option,
                                    msg -> Platform.runLater(() -> defragStatus.setText(msg)),
                                    pct -> Platform.runLater(() -> {
                                        defragProgress.setProgress(pct);
                                        defragStatus.setText(hddModeLabel + " " + letter + " " + Math.round(pct * 100) + "%");
                                    }),
                                    defragCancelled);
                        }
                        lastDefragged.put(letter, Instant.now());
                        // Reliability: fragmentation numbers are stale after defrag.
                        // Drop the analyzed flag so the schematic viz hides until re-analyze.
                        analyzedDrives.remove(letter);
                        lastAnalyzed.remove(letter);
                    } catch (Exception driveEx) {
                        if (defragCancelled.get() || driveEx instanceof java.util.concurrent.CancellationException) throw driveEx;
                        AppLogger.error("Defrag failed for " + letter, driveEx);
                        failedDrives.add(letter + ": " + driveEx.getMessage());
                        Platform.runLater(() -> defragStatus.setText("Failed on " + letter + ": " + driveEx.getMessage()));
                        try { Thread.sleep(800); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new java.util.concurrent.CancellationException(); }
                        continue;
                    }

                    Platform.runLater(() -> driveTable.refresh());
                }
                Platform.runLater(() -> {
                    defragProgress.setProgress(1);
                    String elapsed = formatElapsed(Duration.between(startTime, Instant.now()));
                    if (failedDrives.isEmpty()) {
                        defragStatus.setText("Intelligent Defrag completed (" + elapsed + ").");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Intelligent Defrag completed successfully.\nElapsed: " + elapsed).showAndWait();
                    } else {
                        defragStatus.setText("Intelligent Defrag completed with errors (" + elapsed + ").");
                        new Alert(Alert.AlertType.WARNING,
                                "Intelligent Defrag completed with errors:\n" + String.join("\n", failedDrives) + "\n\nElapsed: " + elapsed).showAndWait();
                    }
                });
            } catch (java.util.concurrent.CancellationException e) {
                Platform.runLater(() -> defragStatus.setText("Intelligent Defrag cancelled."));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    defragStatus.setText("Intelligent Defrag failed.");
                    new Alert(Alert.AlertType.ERROR, "Intelligent Defrag failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    defragBusy.set(false);
                    defragProgress.setVisible(false);
                });
            }
        }, "intelligent-defrag");
        currentDefragThread.start();
    }

    private DefragService.DefragOption resolveDefragOption(String mode, DriveInfo drive) {
        return switch (mode) {
            case "Quick" -> DefragService.DefragOption.FAST;
            case "Deep" -> DefragService.DefragOption.DEEP;
            default -> {
                if (drive.isSsd()) {
                    yield DefragService.DefragOption.FAST;
                } else {
                    yield DefragService.DefragOption.FULL;
                }
            }
        };
    }

    private static String formatElapsed(Duration d) {
        long seconds = d.getSeconds();
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes < 60) return minutes + "m " + secs + "s";
        long hours = minutes / 60;
        long mins = minutes % 60;
        return hours + "h " + mins + "m";
    }

    private void updateRichBlockVisualization(DriveInfo drive) {
        visualizationPanel.setVisible(true);

        DefragVisualization.RenderResult result = DefragVisualization.render(blockCanvas, drive);

        driveAnalysisLabel.setText(result.analysisText());

        fragCountLabel.setText(result.fragCountText());
        fragCountLabel.getStyleClass().removeAll("label", "warning", "success", "danger");
        fragCountLabel.getStyleClass().addAll("label", "warning");
        fragPercentLabel.setText(result.fragPercentText());
        fragPercentLabel.getStyleClass().removeAll("label", "warning", "success", "danger");
        fragPercentLabel.getStyleClass().addAll("label", result.fragIsHigh() ? "danger" : "success");

        legendBox.getChildren().setAll(
                DefragVisualization.createLegendItem(Color.rgb(139, 233, 253), "System/MFT"),
                DefragVisualization.createLegendItem(Color.rgb(241, 250, 140), "Frequently Used (est.)"),
                DefragVisualization.createLegendItem(Color.rgb(80, 250, 123), "Normal"),
                DefragVisualization.createLegendItem(Color.rgb(255, 85, 85), "Fragmented"),
                DefragVisualization.createLegendItem(Color.rgb(255, 184, 108), "Page/Hibernation"),
                DefragVisualization.createLegendItem(Color.rgb(68, 71, 90), "Free")
        );
        // Directories legend is hidden: totalDirectories is not collected
        // (always 0) and painting it would imply false precision.
    }

    /* ===================================================================
       DISK HEALTH TAB
       =================================================================== */

    private VBox buildDiskHealthContent() {
        refreshHealthBtn.getStyleClass().add("accent");
        refreshHealthBtn.setOnAction(e -> loadDiskHealth());
        refreshHealthBtn.setTooltip(new Tooltip("Refresh disk health (SMART) data"));

        healthProgress.setVisible(false);
        healthProgress.setPrefWidth(200);
        healthStatus.getStyleClass().add("text-muted");

        healthDriveCombo.setPrefWidth(250);
        healthDriveCombo.setTooltip(new Tooltip("Select a drive to view detailed SMART data"));
        healthDriveCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            if (sel != null) {
                DiskHealthInfo info = findHealthInfo(sel);
                if (info != null) updateSmartDetailPanel(info);
            }
        });

        HBox toolbar = new HBox(8, refreshHealthBtn, healthDriveCombo, healthProgress, healthStatus);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.getStyleClass().add("toolbar");

        smartGrid.setHgap(16);
        smartGrid.setVgap(4);
        smartGrid.setPadding(new Insets(12, 16, 12, 16));

        overallHealthLabel.getStyleClass().addAll("label", "large");

        VBox detailCard = new VBox(8, overallHealthLabel, smartGrid);
        detailCard.setPadding(new Insets(12));
        detailCard.getStyleClass().add("sysinfo-card");

        VBox.setVgrow(detailCard, Priority.ALWAYS);

        VBox content = new VBox(4, toolbar, detailCard);
        return content;
    }

    private void loadDiskHealth() {
        refreshHealthBtn.setDisable(true);
        healthProgress.setProgress(-1);
        healthProgress.setVisible(true);
        healthStatus.setText("Loading disk health data...");

        newDaemonThread(() -> {
            try {
                DiskHealthService.HealthResult result = diskHealthService.getDiskHealth();
                Platform.runLater(() -> {
                    smartctlAvailable = result.smartctlAvailable();
                    healthDrives.setAll(result.drives());
                    healthDriveCombo.getItems().clear();
                    healthDriveMap.clear();
                    for (DiskHealthInfo d : result.drives()) {
                        String base = d.getDriveLetter().isEmpty()
                                ? d.getModel() + " (" + d.getMediaType() + ")"
                                : d.getDriveLetter() + " - " + d.getModel();
                        // Ensure unique display key even if models duplicate
                        String display = base;
                        int dup = 1;
                        while (healthDriveMap.containsKey(display)) {
                            display = base + " #" + (++dup);
                        }
                        healthDriveMap.put(display, d);
                        healthDriveCombo.getItems().add(display);
                    }
                    if (!healthDriveCombo.getItems().isEmpty()) {
                        healthDriveCombo.getSelectionModel().select(0);
                    }
                    healthStatus.setText("Found " + result.drives().size() + " disk(s).");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load disk health", e);
                Platform.runLater(() -> {
                    healthStatus.setText("Failed to load disk health data.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load disk health:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    refreshHealthBtn.setDisable(false);
                    healthProgress.setVisible(false);
                });
            }
        }, "disk-health-load").start();
    }

    private DiskHealthInfo findHealthInfo(String display) {
        DiskHealthInfo mapped = healthDriveMap.get(display);
        if (mapped != null) return mapped;
        // Fallback legacy logic for robustness
        for (DiskHealthInfo d : healthDrives) {
            String letter = d.getDriveLetter();
            if (!letter.isEmpty() && display.startsWith(letter + " - ") && display.contains(d.getModel())) {
                return d;
            }
        }
        for (DiskHealthInfo d : healthDrives) {
            if (d.getDriveLetter().isEmpty() && display.contains(d.getModel())) {
                return d;
            }
        }
        return healthDrives.isEmpty() ? null : healthDrives.get(0);
    }

    private void updateSmartDetailPanel(DiskHealthInfo info) {
        smartGrid.getChildren().clear();
        int row = 0;

        addSmartRow(row++, "Model", info.getModel().isEmpty() ? "Unknown" : info.getModel());
        addSmartRow(row++, "Serial", info.getSerialNumber().isEmpty() ? "-" : info.getSerialNumber());
        addSmartRow(row++, "Interface", info.getInterfaceType().isEmpty() ? "Unknown" : info.getInterfaceType());
        addSmartRow(row++, "Media Type", info.getMediaType());
        addSmartRow(row++, "Capacity", info.getSizeFormatted());

        addSmartSeparator(row++);

        String healthStatus = info.getHealthStatus();
        Label healthValueLabel = new Label(healthStatus);
        if (info.isHealthOk()) {
            healthValueLabel.getStyleClass().addAll("label", "success");
        } else if (info.isHealthCaution()) {
            healthValueLabel.getStyleClass().addAll("label", "warning");
        } else if (info.isHealthCritical()) {
            healthValueLabel.getStyleClass().addAll("label", "danger");
        } else {
            healthValueLabel.getStyleClass().addAll("label", "text-muted");
        }
        addSmartGridRow(row++, "Health Status", healthValueLabel);

        Label opLabel = new Label(info.getOperationalStatus());
        if ("OK".equalsIgnoreCase(info.getOperationalStatus())) {
            opLabel.getStyleClass().addAll("label", "success");
        } else {
            opLabel.getStyleClass().addAll("label", "warning");
        }
        addSmartGridRow(row++, "Operational Status", opLabel);

        boolean hasSmartSection = false;

        if (info.getTemperature() > 0) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            addSmartRow(row++, "Temperature", info.getTemperature() + " \u00B0C");
            hasSmartSection = true;
        }
        if (info.getPowerOnHours() >= 0) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            addSmartRow(row++, "Power-On Hours", DiskHealthInfo.formatDuration(info.getPowerOnHours()));
            hasSmartSection = true;
        }
        if (info.isSsd() && info.getWearLevel() >= 0) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            addSmartRow(row++, "Wear (used)", info.getWearLevel() + "% used");
            hasSmartSection = true;
        }

        boolean hasSectorData = info.getReallocatedSectors() >= 0
                || info.getCurrentPendingSectorCount() >= 0
                || info.getUncorrectableSectorCount() >= 0;
        if (hasSectorData) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            if (info.getReallocatedSectors() >= 0) {
                Label l = createColoredValueLabel(info.getReallocatedSectors());
                addSmartGridRow(row++, "Reallocated Sectors", l);
            }
            if (info.getCurrentPendingSectorCount() >= 0) {
                Label l = createColoredValueLabel(info.getCurrentPendingSectorCount());
                addSmartGridRow(row++, "Pending Sectors", l);
            }
            if (info.getUncorrectableSectorCount() >= 0) {
                Label l = createColoredValueLabel(info.getUncorrectableSectorCount());
                addSmartGridRow(row++, "Uncorrectable Sectors", l);
            }
            hasSmartSection = true;
        }

        if (info.getLoadCycleCount() >= 0) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            addSmartRow(row++, "Load Cycle Count", String.valueOf(info.getLoadCycleCount()));
            hasSmartSection = true;
        }
        if (info.getPowerCycleCount() >= 0) {
            if (hasSmartSection) { addSmartSeparator(row++); hasSmartSection = false; }
            addSmartRow(row++, "Power Cycle Count", String.valueOf(info.getPowerCycleCount()));
            hasSmartSection = true;
        }

        boolean hasTransferData = info.getTotalHostReads() >= 0 || info.getTotalHostWrites() >= 0;
        if (hasTransferData) {
            if (hasSmartSection) { addSmartSeparator(row++); }
            if (info.getTotalHostReads() >= 0) {
                addSmartRow(row++, "Total Host Reads", DiskHealthInfo.formatBytes(info.getTotalHostReads()));
            }
            if (info.getTotalHostWrites() >= 0) {
                addSmartRow(row++, "Total Host Writes", DiskHealthInfo.formatBytes(info.getTotalHostWrites()));
            }
        }

        String summary = "Drive: " + (info.getDriveLetter().isEmpty() ? info.getModel() : info.getDriveLetter())
                + "  |  Status: " + info.getHealthStatus()
                + "  |  " + info.getMediaType() + "  |  " + info.getInterfaceType();
        if ("smartctl".equals(info.getDataSource())) {
            summary += "  |  Data: smartctl";
        } else {
            summary += "  |  Data: WMI (limited — no smartmontools dependency)";
        }
        overallHealthLabel.setText(summary);
        if (info.isHealthOk()) {
            overallHealthLabel.getStyleClass().removeAll("warning", "danger");
            overallHealthLabel.getStyleClass().add("success");
        } else if (info.isHealthCaution()) {
            overallHealthLabel.getStyleClass().removeAll("success", "danger");
            overallHealthLabel.getStyleClass().add("warning");
        } else if (info.isHealthCritical()) {
            overallHealthLabel.getStyleClass().removeAll("success", "warning");
            overallHealthLabel.getStyleClass().add("danger");
        }
    }

    private void addSmartRow(int row, String label, String value) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().addAll("label", "sysinfo-label");
        labelNode.setMinWidth(160);

        Label valueNode = new Label(value);
        valueNode.getStyleClass().addAll("label", "sysinfo-value");

        smartGrid.add(labelNode, 0, row);
        smartGrid.add(valueNode, 1, row);
    }

    private void addSmartGridRow(int row, String label, Label valueLabel) {
        Label labelNode = new Label(label);
        labelNode.getStyleClass().addAll("label", "sysinfo-label");
        labelNode.setMinWidth(160);

        valueLabel.getStyleClass().addAll("label", "sysinfo-value");

        smartGrid.add(labelNode, 0, row);
        smartGrid.add(valueLabel, 1, row);
    }

    private void addSmartSeparator(int row) {
        Label sep = new Label("");
        sep.setMinHeight(8);
        smartGrid.add(sep, 0, row);
    }

    private Label createColoredValueLabel(long value) {
        Label l = new Label(String.valueOf(value));
        if (value == 0) {
            l.getStyleClass().addAll("label", "success");
        } else if (value < 10) {
            l.getStyleClass().addAll("label", "warning");
        } else {
            l.getStyleClass().addAll("label", "danger");
        }
        return l;
    }

    /* ===================================================================
       BENCHMARK TAB
       =================================================================== */

    private VBox buildBenchmarkContent() {
        Label desc = new Label("Test sequential read/write speed, 1MB random IOPS and 4K random read of a drive. "
                + "Larger sizes are more accurate but slower; results may be cache-influenced on repeated runs.");
        desc.getStyleClass().add("text-muted");
        desc.setWrapText(true);

        benchDriveCombo.setPrefWidth(250);
        benchDriveCombo.setTooltip(new Tooltip("Select a drive to benchmark"));
        benchDriveCombo.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> updateBenchStartButton());

        benchSizeCombo.getSelectionModel().select(1);
        benchSizeCombo.setTooltip(new Tooltip("Test file size (larger = more accurate but slower)"));

        benchStartBtn.getStyleClass().add("accent");
        benchStartBtn.setDisable(true);
        benchStartBtn.setOnAction(e -> startBenchmark());
        benchStartBtn.setTooltip(new Tooltip("Run sequential read/write and random read benchmark"));

        benchStopBtn.getStyleClass().add("danger");
        benchStopBtn.setVisible(false);
        benchStopBtn.setOnAction(e -> {
            benchCancelled.set(true);
            benchStopBtn.setDisable(true);
            benchStatus.setText("Stopping...");
        });
        benchStopBtn.setTooltip(new Tooltip("Stop the benchmark"));

        benchProgress.setVisible(false);
        benchProgress.setPrefWidth(200);
        benchStatus.getStyleClass().add("text-muted");

        HBox toolbar = new HBox(8, benchDriveCombo, new Label("Size:"), benchSizeCombo,
                benchStartBtn, benchStopBtn, benchProgress, benchStatus);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(12, 16, 12, 16));
        toolbar.getStyleClass().add("toolbar");

        benchSeqWriteLabel.getStyleClass().addAll("label", "large", "accent");
        benchSeqReadLabel.getStyleClass().addAll("label", "large", "accent");
        benchRandomReadLabel.getStyleClass().addAll("label", "large", "accent");
        benchRandom4KLabel.getStyleClass().addAll("label", "large", "accent");
        benchLatencyLabel.getStyleClass().addAll("label", "large", "accent");

        Label seqWriteTitle = new Label("Sequential Write:");
        seqWriteTitle.getStyleClass().addAll("label", "text-muted");
        Label seqReadTitle = new Label("Sequential Read:");
        seqReadTitle.getStyleClass().addAll("label", "text-muted");
        Label randomReadTitle = new Label("Random Read IOPS (1MB):");
        randomReadTitle.getStyleClass().addAll("label", "text-muted");
        Label random4KTitle = new Label("Random Read IOPS (4K):");
        random4KTitle.getStyleClass().addAll("label", "text-muted");
        Label latencyTitle = new Label("Avg 4K Latency:");
        latencyTitle.getStyleClass().addAll("label", "text-muted");

        GridPane resultsGrid = new GridPane();
        resultsGrid.setHgap(32);
        resultsGrid.setVgap(12);
        resultsGrid.setPadding(new Insets(16, 16, 16, 16));
        resultsGrid.add(seqWriteTitle, 0, 0);
        resultsGrid.add(benchSeqWriteLabel, 1, 0);
        resultsGrid.add(seqReadTitle, 0, 1);
        resultsGrid.add(benchSeqReadLabel, 1, 1);
        resultsGrid.add(randomReadTitle, 0, 2);
        resultsGrid.add(benchRandomReadLabel, 1, 2);
        resultsGrid.add(random4KTitle, 0, 3);
        resultsGrid.add(benchRandom4KLabel, 1, 3);
        resultsGrid.add(latencyTitle, 0, 4);
        resultsGrid.add(benchLatencyLabel, 1, 4);

        VBox resultsCard = new VBox(resultsGrid);
        resultsCard.setPadding(new Insets(12));
        resultsCard.getStyleClass().add("sysinfo-card");
        resultsCard.setPrefHeight(220);

        VBox content = new VBox(4, toolbar, desc, resultsCard);
        content.setPadding(new Insets(0));
        return content;
    }

    private void updateBenchStartButton() {
        benchStartBtn.setDisable(benchDriveCombo.getSelectionModel().getSelectedItem() == null);
    }

    private void startBenchmark() {
        String selected = benchDriveCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return;

        // Prefer map lookup (volumeLabel may contain " - "); fallback to legacy parsing for compatibility
        String driveLetter;
        DriveInfo mapped = benchDriveMap.get(selected);
        if (mapped != null) {
            driveLetter = mapped.getDriveLetter();
        } else {
            String rawSelection = selected.trim();
            String extracted;
            if (rawSelection.contains(" - ")) {
                extracted = rawSelection.substring(0, rawSelection.indexOf(" - ")).trim();
            } else {
                extracted = rawSelection;
            }
            if (!extracted.endsWith(":")) {
                extracted = extracted.split("\\s+")[0].trim();
            }
            driveLetter = extracted;
        }
        final String finalDriveLetter = driveLetter;

        String sizeStr = benchSizeCombo.getSelectionModel().getSelectedItem();
        int sizeMB = 64;
        if (sizeStr != null && !sizeStr.isBlank()) {
            try {
                sizeMB = Integer.parseInt(sizeStr.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException ignored) {
                sizeMB = 64;
            }
        }
        final int testSizeMB = sizeMB;

        // Critical fix: fail fast when free space is insufficient instead of filling
        // the drive to 0 bytes. Service + script double-check as well.
        DriveInfo benchInfo = benchDriveMap.get(selected);
        if (benchInfo != null && benchInfo.getFreeBytes() > 0) {
            long required = (long) testSizeMB * 1024 * 1024 + 100L * 1024 * 1024;
            if (benchInfo.getFreeBytes() < required) {
                new Alert(Alert.AlertType.WARNING,
                        "Insufficient free space on " + finalDriveLetter + " for a " + testSizeMB
                                + " MB benchmark (needs ~" + testSizeMB + " MB + 100 MB headroom, free "
                                + benchInfo.getFreeFormatted() + "). Free up space or choose a smaller size.")
                        .showAndWait();
                return;
            }
        }

        benchCancelled.set(false);
        benchStartBtn.setDisable(true);
        benchStopBtn.setVisible(true);
        benchStopBtn.setDisable(false);
        benchProgress.setProgress(0);
        benchProgress.setVisible(true);
        benchStatus.setText("Initializing benchmark...");

        currentBenchThread = newDaemonThread(() -> {
            try {
                BenchmarkResult result = benchmarkService.benchmark(finalDriveLetter, testSizeMB,
                        msg -> Platform.runLater(() -> benchStatus.setText(msg)),
                        benchCancelled);
                Platform.runLater(() -> {
                    benchProgress.setProgress(1);
                    if (result.isSuccess()) {
                        benchSeqWriteLabel.setText(String.format("%.1f MB/s", result.getSeqWriteMBps()));
                        benchSeqReadLabel.setText(String.format("%.1f MB/s", result.getSeqReadMBps()));
                        benchRandomReadLabel.setText(String.format("%.0f IOPS", result.getRandomReadIOPS()));
                        benchRandom4KLabel.setText(result.getRandomRead4KIOPS() > 0
                                ? String.format("%.0f IOPS", result.getRandomRead4KIOPS()) : "n/a");
                        benchLatencyLabel.setText(result.getAvg4KLatencyMs() > 0
                                ? String.format("%.3f ms", result.getAvg4KLatencyMs()) : "n/a");
                        benchStatus.setText("Benchmark completed for " + finalDriveLetter + ".");
                    } else {
                        benchStatus.setText("Benchmark failed: " + result.getMessage());
                    }
                });
            } catch (java.util.concurrent.CancellationException e) {
                Platform.runLater(() -> benchStatus.setText("Benchmark cancelled."));
            } catch (Exception e) {
                Platform.runLater(() -> {
                    benchStatus.setText("Benchmark failed.");
                    new Alert(Alert.AlertType.ERROR, "Benchmark failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    benchStartBtn.setDisable(false);
                    benchStopBtn.setVisible(false);
                    benchProgress.setVisible(false);
                });
            }
        }, "drive-benchmark");
        currentBenchThread.start();
    }

    /* ===================================================================
       SECURE ERASE TAB
       =================================================================== */

    private VBox buildSecureEraseContent() {
        Label warning = new Label("WARNING: Once a file is securely deleted or free space is wiped, "
                + "recovery is completely impossible. Proceed with caution.");
        warning.getStyleClass().addAll("label", "danger");
        warning.setWrapText(true);
        warning.setPadding(new Insets(12, 16, 12, 16));
        warning.setStyle("-fx-font-weight: bold; -fx-font-size: 13px; -fx-background-color: #ff555522; "
                + "-fx-border-color: #ff5555; -fx-border-width: 1; -fx-background-radius: 4; -fx-border-radius: 4;");

        if (!AppPaths.isWindows()) {
            Label notAvailable = new Label("Secure Erase is only available on Windows.");
            notAvailable.getStyleClass().addAll("label", "text-muted");
            notAvailable.setWrapText(true);
            notAvailable.setPadding(new Insets(20));
            VBox content = new VBox(12, warning, notAvailable);
            content.setPadding(new Insets(0));
            return content;
        }

        VBox fileSection = buildFileDeletionSection();
        VBox recycleBinSection = buildRecycleBinSection();
        VBox wipeSection = buildFreeSpaceWipeSection();

        VBox.setVgrow(recycleBinSection, Priority.SOMETIMES);
        VBox.setVgrow(wipeSection, Priority.ALWAYS);

        VBox content = new VBox(12, warning, fileSection, recycleBinSection, wipeSection);
        content.setPadding(new Insets(0));
        VBox.setVgrow(wipeSection, Priority.ALWAYS);
        return content;
    }

    /* ── Secure File Deletion ── */

    @SuppressWarnings("unchecked")
    private VBox buildFileDeletionSection() {
        Label header = new Label("Secure File / Folder Deletion");
        header.getStyleClass().addAll("label", "large", "accent");

        Label dropHint = new Label("Drag files or folders here, or use the buttons below to browse.");
        dropHint.getStyleClass().addAll("label", "text-muted");
        dropHint.setWrapText(true);

        filePathField.setPromptText("Select a file or folder to securely delete...");
        filePathField.setPrefWidth(400);
        filePathField.setEditable(false);

        Button browseFolderBtn = new Button("Browse Folder...");
        browseFolderBtn.setTooltip(new Tooltip("Browse for a folder to securely delete recursively"));

        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select file to securely delete");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("All Files", "*.*"),
                    new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf", "*.odt"),
                    new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.tiff"),
                    new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz"),
                    new FileChooser.ExtensionFilter("Executables", "*.exe", "*.msi", "*.bat", "*.cmd", "*.ps1")
            );
            File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (f != null) {
                filePathField.setText(f.getAbsolutePath());
                filePathField.setUserData(f.isDirectory());
                secureDeleteBtn.setDisable(false);
            }
        });
        browseBtn.setTooltip(new Tooltip("Browse for a single file to securely delete"));

        browseFolderBtn.setOnAction(e -> {
            DirectoryChooser dc = new DirectoryChooser();
            dc.setTitle("Select folder to securely delete");
            dc.setInitialDirectory(new File("C:\\"));
            File dir = dc.showDialog(getScene() != null ? getScene().getWindow() : null);
            if (dir != null) {
                filePathField.setText(dir.getAbsolutePath());
                filePathField.setUserData(true);
                secureDeleteBtn.setDisable(false);
                secureDeleteBtn.setText("Secure Delete Folder");
            }
        });

        filePathField.textProperty().addListener((obs, old, val) -> {
            boolean isDir = Boolean.TRUE.equals(filePathField.getUserData());
            secureDeleteBtn.setText(isDir ? "Secure Delete Folder" : "Secure Delete");
            secureDeleteBtn.setDisable(val == null || val.isBlank());
        });

        addFilesBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select files to securely delete");
            fc.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("All Files", "*.*"),
                    new FileChooser.ExtensionFilter("Documents", "*.pdf", "*.doc", "*.docx", "*.txt", "*.rtf", "*.odt"),
                    new FileChooser.ExtensionFilter("Images", "*.jpg", "*.jpeg", "*.png", "*.gif", "*.bmp", "*.tiff"),
                    new FileChooser.ExtensionFilter("Archives", "*.zip", "*.rar", "*.7z", "*.tar", "*.gz"),
                    new FileChooser.ExtensionFilter("Executables", "*.exe", "*.msi", "*.bat", "*.cmd", "*.ps1")
            );
            List<File> files = fc.showOpenMultipleDialog(getScene() != null ? getScene().getWindow() : null);
            if (files != null && !files.isEmpty()) {
                for (File file : files) {
                    boolean exists = shredderEntries.stream()
                            .anyMatch(e2 -> e2.getFilePath().equals(file.getAbsolutePath()));
                    if (!exists) {
                        ShredderFileEntry entry = new ShredderFileEntry(file.getAbsolutePath(), file.length());
                        entry.setStatusEnum(ShredderFileEntry.Status.PENDING);
                        shredderEntries.add(entry);
                    }
                }
                updateDeleteButtons();
            }
        });
        addFilesBtn.setTooltip(new Tooltip("Add multiple files for batch secure deletion"));

        secureDeleteBtn.setDisable(true);
        secureDeleteBtn.getStyleClass().add("danger");
        secureDeleteBtn.setOnAction(e -> {
            Boolean isDir = Boolean.TRUE.equals(filePathField.getUserData());
            if (Boolean.TRUE.equals(isDir)) {
                startSecureDeleteFolder();
            } else {
                startSecureDelete();
            }
        });
        secureDeleteBtn.setTooltip(new Tooltip("Securely delete the selected file or folder with multiple overwrite passes"));

        deleteAllBtn.setDisable(true);
        deleteAllBtn.getStyleClass().add("danger");
        deleteAllBtn.setOnAction(e -> startBatchDelete());
        deleteAllBtn.setTooltip(new Tooltip("Securely delete all pending files in the list"));

        stopSecureBtn.getStyleClass().add("danger");
        stopSecureBtn.setVisible(false);
        stopSecureBtn.setOnAction(e -> {
            secureCancelled.set(true);
            stopSecureBtn.setDisable(true);
            secureDeleteStatus.setText("Stopping...");
        });
        stopSecureBtn.setTooltip(new Tooltip("Stop the running secure-delete operation"));

        secureDeleteProgress.setVisible(false);
        secureDeleteProgress.setPrefWidth(150);
        secureDeleteStatus.getStyleClass().add("text-muted");

        overwritePresetCombo.getSelectionModel().select(1);
        overwritePresetCombo.setTooltip(new Tooltip("Select overwrite intensity: Quick (1 pass), Standard (3 passes DoD), or Deep (7 passes)"));

        HBox row = new HBox(8, filePathField, browseBtn, browseFolderBtn, addFilesBtn);
        row.setAlignment(Pos.CENTER_LEFT);

        HBox actionRow = new HBox(8, secureDeleteBtn, deleteAllBtn, stopSecureBtn);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        HBox presetRow = new HBox(8, new Label("Overwrite:"), overwritePresetCombo);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        HBox progressRow = new HBox(8, secureDeleteProgress, secureDeleteStatus);
        progressRow.setAlignment(Pos.CENTER_LEFT);

        shredderTable.setItems(shredderEntries);
        shredderTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        shredderTable.setPrefHeight(150);

        TableColumn<ShredderFileEntry, String> pathCol = new TableColumn<>("File Path");
        pathCol.setCellValueFactory(c -> c.getValue().filePathProperty());
        pathCol.setPrefWidth(400);

        TableColumn<ShredderFileEntry, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getSizeFormatted()));
        sizeCol.setPrefWidth(100);

        TableColumn<ShredderFileEntry, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(160);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    switch (item) {
                        case "DELETED" -> setStyle("-fx-text-fill: #50fa7b;");
                        case "SCHEDULED_FOR_REBOOT" -> setStyle("-fx-text-fill: #f1fa8c;");
                        case "FAILED" -> setStyle("-fx-text-fill: #ff5555;");
                        default -> setStyle("-fx-text-fill: #bd93f9;");
                    }
                }
            }
        });

        shredderTable.getColumns().addAll(pathCol, sizeCol, statusCol);

        VBox section = new VBox(8, header, dropHint, row, actionRow, presetRow, progressRow, shredderTable);
        section.setPadding(new Insets(8, 16, 8, 16));

        section.setOnDragOver(e -> {
            if (e.getGestureSource() != section && e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });

        section.setOnDragDropped(e -> {
            Dragboard db = e.getDragboard();
            boolean success = false;
            if (db.hasFiles()) {
                List<File> files = db.getFiles();
                for (File file : files) {
                    boolean exists = shredderEntries.stream()
                            .anyMatch(entry -> entry.getFilePath().equals(file.getAbsolutePath()));
                    if (!exists) {
                        ShredderFileEntry entry = new ShredderFileEntry(file.getAbsolutePath(), file.length());
                        entry.setStatusEnum(ShredderFileEntry.Status.PENDING);
                        shredderEntries.add(entry);
                    }
                }
                if (files.size() == 1) {
                    File f = files.get(0);
                    filePathField.setText(f.getAbsolutePath());
                    filePathField.setUserData(f.isDirectory());
                    secureDeleteBtn.setDisable(false);
                }
                updateDeleteButtons();
                success = true;
            }
            e.setDropCompleted(success);
            e.consume();
        });

        return section;
    }

    /* ── Recycle Bin Section ── */

    @SuppressWarnings("unchecked")
    private VBox buildRecycleBinSection() {
        Label header = new Label("Recycle Bin Cleanup");
        header.getStyleClass().addAll("label", "large", "accent");

        Label desc = new Label("Securely wipe all files currently in the Recycle Bin to prevent recovery.");
        desc.getStyleClass().add("text-muted");
        desc.setWrapText(true);

        recycleBinProgress.setVisible(false);
        recycleBinProgress.setPrefWidth(150);
        recycleBinStatus.getStyleClass().add("text-muted");
        recycleBinSummary.getStyleClass().addAll("label", "text-muted");

        refreshRecycleBinBtn.getStyleClass().add("accent");
        refreshRecycleBinBtn.setOnAction(e -> loadRecycleBin());
        refreshRecycleBinBtn.setTooltip(new Tooltip("List all files in the Recycle Bin"));

        secureWipeRecycleBinBtn.getStyleClass().add("danger");
        secureWipeRecycleBinBtn.setDisable(true);
        secureWipeRecycleBinBtn.setOnAction(e -> startSecureWipeRecycleBin());
        secureWipeRecycleBinBtn.setTooltip(new Tooltip("Securely overwrite all Recycle Bin contents (requires admin)"));

        HBox toolbar = new HBox(8, refreshRecycleBinBtn, secureWipeRecycleBinBtn,
                recycleBinProgress, recycleBinStatus, recycleBinSummary);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 16, 8, 16));
        toolbar.getStyleClass().add("toolbar");

        recycleBinTable.setItems(recycleBinEntries);
        recycleBinTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        recycleBinTable.setPrefHeight(150);

        TableColumn<RecycleBinEntry, String> rbNameCol = new TableColumn<>("File Name");
        rbNameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        rbNameCol.setPrefWidth(200);

        TableColumn<RecycleBinEntry, String> rbOrigCol = new TableColumn<>("Original Location");
        rbOrigCol.setCellValueFactory(c -> c.getValue().originalPathProperty());
        rbOrigCol.setPrefWidth(300);

        TableColumn<RecycleBinEntry, String> rbSizeCol = new TableColumn<>("Size");
        rbSizeCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getSizeFormatted()));
        rbSizeCol.setPrefWidth(90);

        TableColumn<RecycleBinEntry, String> rbDateCol = new TableColumn<>("Deleted");
        rbDateCol.setCellValueFactory(c -> {
            String date = c.getValue().getDeleteDate();
            return new SimpleObjectProperty<>(date != null && !date.isBlank() ? date : "-");
        });
        rbDateCol.setPrefWidth(150);

        recycleBinTable.getColumns().addAll(rbNameCol, rbOrigCol, rbSizeCol, rbDateCol);

        VBox section = new VBox(8, header, desc, toolbar, recycleBinTable);
        section.setPadding(new Insets(8, 16, 8, 16));
        VBox.setVgrow(recycleBinTable, Priority.ALWAYS);
        return section;
    }

    private void loadRecycleBin() {
        if (!AppPaths.isWindows()) return;
        refreshRecycleBinBtn.setDisable(true);
        secureWipeRecycleBinBtn.setDisable(true);
        recycleBinProgress.setProgress(-1);
        recycleBinProgress.setVisible(true);
        recycleBinStatus.setText("Loading Recycle Bin contents...");

        newDaemonThread(() -> {
            try {
                ShredderService.RecycleBinResult result = shredderService.getRecycleBinContents();
                Platform.runLater(() -> {
                    recycleBinEntries.setAll(result.entries());
                    if (result.fileCount() == 0) {
                        recycleBinStatus.setText("Recycle Bin is empty.");
                        recycleBinSummary.setText("");
                        secureWipeRecycleBinBtn.setDisable(true);
                    } else {
                        String sizeText = result.totalSizeBytes() < 1024 * 1024
                                ? (result.totalSizeBytes() / 1024) + " KB"
                                : String.format("%.1f MB", result.totalSizeBytes() / (1024.0 * 1024));
                        recycleBinStatus.setText(result.fileCount() + " item(s) in Recycle Bin.");
                        recycleBinSummary.setText("Total size: " + sizeText);
                        secureWipeRecycleBinBtn.setDisable(false);
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load Recycle Bin", e);
                Platform.runLater(() -> {
                    recycleBinStatus.setText("Failed to load Recycle Bin.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load Recycle Bin:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    refreshRecycleBinBtn.setDisable(false);
                    recycleBinProgress.setVisible(false);
                });
            }
        }, "load-recyclebin").start();
    }

    private void startSecureWipeRecycleBin() {
        List<RecycleBinEntry> entries = List.copyOf(recycleBinEntries);
        if (entries.isEmpty()) return;

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Secure Recycle Bin wipe requires administrator rights.").showAndWait();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to securely wipe all " + entries.size() + " item(s) from the Recycle Bin?\n\n"
                        + "This action is irreversible. All files will be overwritten multiple times and cannot be recovered.");
        confirm.setHeaderText("Confirm Recycle Bin Wipe");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        recycleBinBusy.set(true);
        recycleBinCancelled.set(false);
        secureWipeRecycleBinBtn.setDisable(true);
        refreshRecycleBinBtn.setDisable(true);
        recycleBinProgress.setProgress(0);
        recycleBinProgress.setVisible(true);
        recycleBinStatus.setText("Securely wiping Recycle Bin...");

        List<String> recyclePaths = entries.stream()
                .map(RecycleBinEntry::getRecyclePath)
                .filter(p -> p != null && !p.isBlank())
                .toList();

        newDaemonThread(() -> {
            try {
                int passCount = getSelectedPassCount();
                FolderDeleteResult result = shredderService.secureWipeRecycleBin(recyclePaths, passCount,
                        msg -> Platform.runLater(() -> recycleBinStatus.setText(msg)),
                        recycleBinCancelled);
                Platform.runLater(() -> {
                    if (recycleBinCancelled.get()) {
                        recycleBinStatus.setText("Recycle Bin wipe cancelled.");
                        // Refresh to reflect partial progress instead of clearing.
                        loadRecycleBin();
                    } else if (result.isSuccess()) {
                        recycleBinEntries.clear();
                        recycleBinStatus.setText("Recycle Bin securely wiped: " + result.getFilesDeleted() + " item(s) removed.");
                        recycleBinSummary.setText("");
                        secureWipeRecycleBinBtn.setDisable(true);
                        String msg = "Recycle Bin securely wiped.\n" + result.getFilesDeleted() + " file(s) overwritten.";
                        if (!result.getScheduledForReboot().isEmpty()) {
                            msg += "\n" + result.getScheduledForReboot().size() + " file(s) scheduled for deletion on next reboot.";
                        }
                        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
                    } else {
                        recycleBinStatus.setText("Recycle Bin wipe failed: " + result.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Recycle Bin wipe failed:\n" + result.getMessage()
                                + "\n\nThe list was NOT cleared. Click Refresh to reload actual contents.").showAndWait();
                        // Re-query actual Recycle Bin contents; never clear on failure.
                        loadRecycleBin();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Recycle Bin wipe failed", e);
                Platform.runLater(() -> {
                    recycleBinStatus.setText("Recycle Bin wipe failed.");
                    if (!recycleBinCancelled.get()) {
                        new Alert(Alert.AlertType.ERROR, "Recycle Bin wipe failed:\n" + e.getMessage()).showAndWait();
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    recycleBinBusy.set(false);
                    secureWipeRecycleBinBtn.setDisable(recycleBinEntries.isEmpty());
                    refreshRecycleBinBtn.setDisable(false);
                    recycleBinProgress.setVisible(false);
                });
            }
        }, "wipe-recyclebin").start();
    }

    private void startSecureDelete() {
        String filePath = filePathField.getText();
        if (filePath == null || filePath.isBlank()) return;

        // Strict blocking: protected OS locations can never be shredded.
        String blocked = ShredderSafety.validateFileForShred(filePath);
        if (blocked != null) {
            new Alert(Alert.AlertType.ERROR, "Secure delete blocked for safety:\n\n" + blocked).showAndWait();
            return;
        }

        File f = new File(filePath);
        if (!f.exists()) {
            new Alert(Alert.AlertType.ERROR, "File not found: " + filePath).showAndWait();
            return;
        }
        if (f.isDirectory()) {
            new Alert(Alert.AlertType.ERROR,
                    "Selected path is a folder. Use \"Secure Delete Folder\" (Browse Folder) for directories.")
                    .showAndWait();
            return;
        }

        if (isCriticalSystemFile(filePath)) {
            Alert warning = new Alert(Alert.AlertType.WARNING,
                    "WARNING: This is a critical system file:\n\n"
                            + filePath + "\n\n"
                            + "Deleting this file may cause system instability or prevent Windows from starting.\n"
                            + "Are you absolutely sure you want to proceed?");
            warning.setHeaderText("Critical System File Detected");
            if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to securely delete this file?\n\n"
                        + filePath + "\n\n"
                        + "This action is irreversible. The file will be overwritten multiple times and cannot be recovered.");
        confirm.setHeaderText("Confirm Secure Delete");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        secureBusy.set(true);
        secureDeleteBtn.setDisable(true);
        deleteAllBtn.setDisable(true);
        secureDeleteProgress.setProgress(-1);
        secureDeleteProgress.setVisible(true);
        secureDeleteStatus.setText("Securely deleting file...");

        ShredderFileEntry entry = new ShredderFileEntry(filePath, f.length());
        entry.setStatusEnum(ShredderFileEntry.Status.PENDING);
        shredderEntries.add(0, entry);

        newDaemonThread(() -> {
            try {
                int passCount = getSelectedPassCount();
                ShredderResult result = shredderService.secureDelete(filePath, passCount);
                Platform.runLater(() -> {
                    if (result.isSuccess() && result.isDeleted()) {
                        entry.setStatusEnum(ShredderFileEntry.Status.DELETED);
                        secureDeleteStatus.setText("File securely deleted.");
                        new Alert(Alert.AlertType.INFORMATION, "File securely deleted:\n" + filePath).showAndWait();
                    } else if (result.isScheduledForReboot()) {
                        handleScheduleForReboot(entry, filePath, result);
                    } else {
                        entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                        String msg = result.getMessage() != null ? result.getMessage() : "Unknown error";
                        new Alert(Alert.AlertType.ERROR, "Failed to delete file:\n" + msg).showAndWait();
                    }
                    updateDeleteButtons();
                });
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "";
                if (errMsg.toLowerCase().contains("in use") || errMsg.toLowerCase().contains("access denied")
                        || errMsg.toLowerCase().contains("unauthorized")) {
                    ShredderResult fakeResult = new ShredderResult(filePath, false, false, true,
                            "File is in use. Scheduling for deletion on next reboot.");
                    Platform.runLater(() -> {
                        handleScheduleForReboot(entry, filePath, fakeResult);
                        updateDeleteButtons();
                    });
                } else {
                    Platform.runLater(() -> {
                        entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                        new Alert(Alert.AlertType.ERROR, "Secure delete failed:\n" + e.getMessage()).showAndWait();
                        updateDeleteButtons();
                    });
                }
            } finally {
                Platform.runLater(() -> {
                    secureBusy.set(false);
                    secureDeleteBtn.setDisable(false);
                    secureDeleteProgress.setVisible(false);
                    stopSecureBtn.setVisible(false);
                    filePathField.clear();
                    updateDeleteButtons();
                });
            }
        }, "secure-delete").start();
    }

    private void startSecureDeleteFolder() {
        String folderPath = filePathField.getText();
        if (folderPath == null || folderPath.isBlank()) return;

        // Strict blocking: system/junction/drive-root folders can never be shredded.
        String blocked = ShredderSafety.validateFolderForShred(folderPath);
        if (blocked != null) {
            new Alert(Alert.AlertType.ERROR, "Secure folder delete blocked for safety:\n\n" + blocked).showAndWait();
            return;
        }

        File f = new File(folderPath);
        if (!f.exists() || !f.isDirectory()) {
            new Alert(Alert.AlertType.ERROR, "Folder not found: " + folderPath).showAndWait();
            return;
        }

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Secure folder deletion requires administrator rights.").showAndWait();
            return;
        }

        // Counting files can be slow for large folders - do it off FX thread to avoid UI freeze
        secureBusy.set(true);
        secureDeleteBtn.setDisable(true);
        secureDeleteProgress.setProgress(-1);
        secureDeleteProgress.setVisible(true);
        secureDeleteStatus.setText("Counting files in folder...");

        newDaemonThread(() -> {
            int fileCount = 0;
            try {
                // Default walk does NOT follow links unless FOLLOW_LINKS is passed,
                // so junctions/symlinks are never descended into here.
                try (var walk = java.nio.file.Files.walk(f.toPath(), 64)) {
                    fileCount = (int) walk.filter(p -> {
                        try {
                            return java.nio.file.Files.isRegularFile(p,
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS);
                        } catch (Exception ex) {
                            return false;
                        }
                    }).count();
                }
            } catch (Exception ignored) {
                fileCount = countFilesRecursive(f);
            }
            final int countedFiles = fileCount;
            Platform.runLater(() -> {
                secureBusy.set(false);
                secureDeleteBtn.setDisable(false);
                secureDeleteProgress.setVisible(false);
                secureDeleteStatus.setText("");

                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                        "Are you sure you want to securely delete this folder?\n\n"
                                + folderPath + "\n\n"
                                + "Contains approximately " + countedFiles + " file(s).\n"
                                + "All files will be overwritten multiple times and cannot be recovered.\n"
                                + "This action is irreversible.");
                confirm.setHeaderText("Confirm Secure Folder Delete");
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    filePathField.clear();
                    filePathField.setUserData(null);
                    secureDeleteBtn.setText("Secure Delete");
                    updateDeleteButtons();
                    return;
                }

                secureBusy.set(true);
                secureDeleteBtn.setDisable(true);
                secureDeleteProgress.setProgress(-1);
                secureDeleteProgress.setVisible(true);
                secureDeleteStatus.setText("Securely deleting folder contents...");
                startSecureDeleteFolderInternal(folderPath);
            });
        }, "count-folder-files").start();
        return;
    }

    private void startSecureDeleteFolderInternal(String folderPath) {

        secureCancelled.set(false);
        stopSecureBtn.setVisible(true);
        stopSecureBtn.setDisable(false);
        newDaemonThread(() -> {
            try {
                int passCount = getSelectedPassCount();
                FolderDeleteResult result = shredderService.secureDeleteFolder(folderPath, passCount,
                        msg -> Platform.runLater(() -> secureDeleteStatus.setText(msg)),
                        secureCancelled);
                if (result.isSuccess() && !result.getScheduledForReboot().isEmpty()) {
                    for (String path : result.getScheduledForReboot()) {
                        try {
                            shredderService.scheduleForReboot(path);
                        } catch (Exception ex) {
                            AppLogger.error("Failed to schedule reboot delete: " + path, ex);
                        }
                    }
                }
                Platform.runLater(() -> {
                    if (secureCancelled.get()) {
                        secureDeleteStatus.setText("Folder deletion cancelled.");
                    } else if (result.isSuccess()) {
                        String msg = "Folder securely deleted: " + result.getFilesDeleted() + " files, "
                                + result.getFoldersDeleted() + " folders removed.";
                        secureDeleteStatus.setText(msg);
                        if (!result.getScheduledForReboot().isEmpty()) {
                            msg += "\n\n" + result.getScheduledForReboot().size()
                                    + " file(s) scheduled for deletion on next reboot.";
                        }
                        new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
                    } else {
                        secureDeleteStatus.setText("Folder deletion failed.");
                        new Alert(Alert.AlertType.ERROR, "Failed to delete folder:\n" + result.getMessage()).showAndWait();
                    }
                    updateDeleteButtons();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    secureDeleteStatus.setText("Folder deletion failed.");
                    new Alert(Alert.AlertType.ERROR, "Secure folder delete failed:\n" + e.getMessage()).showAndWait();
                    updateDeleteButtons();
                });
            } finally {
                Platform.runLater(() -> {
                    secureBusy.set(false);
                    secureDeleteBtn.setDisable(false);
                    secureDeleteProgress.setVisible(false);
                    stopSecureBtn.setVisible(false);
                    filePathField.clear();
                    filePathField.setUserData(null);
                    secureDeleteBtn.setText("Secure Delete");
                    updateDeleteButtons();
                });
            }
        }, "secure-delete-folder").start();
    }

    private void startBatchDelete() {
        List<ShredderFileEntry> pendingEntries = shredderEntries.stream()
                .filter(e -> e.getStatusEnum() == ShredderFileEntry.Status.PENDING)
                .toList();
        if (pendingEntries.isEmpty()) return;

        // Strict blocking: drop protected paths up-front, never shred them.
        List<String> blockedPaths = pendingEntries.stream()
                .map(ShredderFileEntry::getFilePath)
                .filter(p -> ShredderSafety.validateFileForShred(p) != null)
                .toList();
        if (!blockedPaths.isEmpty()) {
            new Alert(Alert.AlertType.ERROR,
                    "Secure delete blocked for safety — protected system paths in the list:\n\n"
                            + String.join("\n", blockedPaths)
                            + "\n\nRemove them from the list to continue.").showAndWait();
            return;
        }

        List<String> criticalFiles = pendingEntries.stream()
                .map(ShredderFileEntry::getFilePath)
                .filter(this::isCriticalSystemFile)
                .toList();
        if (!criticalFiles.isEmpty()) {
            Alert warning = new Alert(Alert.AlertType.WARNING,
                    "WARNING: The following critical system files are in the list:\n\n"
                            + String.join("\n", criticalFiles) + "\n\n"
                            + "Deleting these files may cause system instability or prevent Windows from starting.\n"
                            + "Are you absolutely sure you want to proceed?");
            warning.setHeaderText("Critical System Files Detected");
            if (warning.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Are you sure you want to securely delete " + pendingEntries.size() + " file(s)?\n\n"
                        + "This action is irreversible. All files will be overwritten multiple times and cannot be recovered.");
        confirm.setHeaderText("Confirm Batch Secure Delete");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        secureBusy.set(true);
        secureCancelled.set(false);
        stopSecureBtn.setVisible(true);
        stopSecureBtn.setDisable(false);
        secureDeleteBtn.setDisable(true);
        deleteAllBtn.setDisable(true);
        secureDeleteProgress.setProgress(-1);
        secureDeleteProgress.setVisible(true);
        secureDeleteStatus.setText("Securely deleting files...");

        newDaemonThread(() -> {
            int deleted = 0;
            int failed = 0;
            int cancelledCount = 0;
            int passCount = getSelectedPassCount();
            for (ShredderFileEntry entry : pendingEntries) {
                if (secureCancelled.get()) {
                    cancelledCount++;
                    continue;
                }
                String filePath = entry.getFilePath();
                Platform.runLater(() -> secureDeleteStatus.setText("Deleting: " + filePath));
                try {
                    ShredderResult result = shredderService.secureDelete(filePath, passCount);
                    Platform.runLater(() -> {
                        if (result.isSuccess() && result.isDeleted()) {
                            entry.setStatusEnum(ShredderFileEntry.Status.DELETED);
                        } else if (result.isScheduledForReboot()) {
                            handleScheduleForReboot(entry, filePath, result);
                        } else {
                            entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                        }
                    });
                    if (result.isSuccess() && result.isDeleted()) {
                        deleted++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    String errMsg = e.getMessage() != null ? e.getMessage() : "";
                    if (errMsg.toLowerCase().contains("in use") || errMsg.toLowerCase().contains("access denied")
                            || errMsg.toLowerCase().contains("unauthorized")) {
                        ShredderResult fakeResult = new ShredderResult(filePath, false, false, true,
                                "File is in use. Scheduling for deletion on next reboot.");
                        Platform.runLater(() -> handleScheduleForReboot(entry, filePath, fakeResult));
                        failed++;
                    } else {
                        Platform.runLater(() -> entry.setStatusEnum(ShredderFileEntry.Status.FAILED));
                        failed++;
                    }
                }
            }
            int finalDeleted = deleted;
            int finalFailed = failed;
            int finalCancelled = cancelledCount;
            Platform.runLater(() -> {
                secureBusy.set(false);
                secureDeleteBtn.setDisable(false);
                secureDeleteProgress.setVisible(false);
                stopSecureBtn.setVisible(false);
                String msg = "Batch delete completed: " + finalDeleted + " deleted, " + finalFailed + " failed."
                        + (finalCancelled > 0 ? " " + finalCancelled + " skipped (cancelled)." : "");
                secureDeleteStatus.setText(msg);
                new Alert(Alert.AlertType.INFORMATION, msg).showAndWait();
                updateDeleteButtons();
            });
        }, "batch-secure-delete").start();
    }

    private void updateDeleteButtons() {
        boolean hasPending = shredderEntries.stream()
                .anyMatch(e -> e.getStatusEnum() == ShredderFileEntry.Status.PENDING);
        deleteAllBtn.setDisable(secureBusy.get() || !hasPending);
        secureDeleteBtn.setDisable(secureBusy.get() || (filePathField.getText() == null || filePathField.getText().isBlank()));
    }

    private static final Set<String> CRITICAL_SYSTEM_PATHS = Set.of(
            "\\windows\\system32\\",
            "\\windows\\syswow64\\",
            "\\windows\\winsxs\\",
            "\\windows\\servicing\\",
            "\\windows\\installer\\"
    );

    private static final Set<String> CRITICAL_SYSTEM_FILES = Set.of(
            "explorer.exe", "ntoskrnl.exe", "hal.dll", "ntdll.dll", "kernel32.dll",
            "kernelbase.dll", "advapi32.dll", "user32.dll", "gdi32.dll", "shell32.dll",
            "comctl32.dll", "msvcrt.dll", "rpcrt4.dll", "ole32.dll", "oleaut32.dll",
            "wininit.exe", "smss.exe", "csrss.exe", "lsass.exe", "services.exe",
            "svchost.exe", "winlogon.exe", "dwm.exe", "taskhostw.exe", "conhost.exe",
            "bootmgr", "ntldr", "boot.ini", "bcd", "winload.exe", "winresume.exe"
    );

    private boolean isCriticalSystemFile(String filePath) {
        if (filePath == null) return false;
        String lower = filePath.toLowerCase().replace('/', '\\');
        for (String path : CRITICAL_SYSTEM_PATHS) {
            if (lower.contains(path)) return true;
        }
        String fileName = lower.substring(lower.lastIndexOf('\\') + 1);
        return CRITICAL_SYSTEM_FILES.contains(fileName);
    }

    private int getSelectedPassCount() {
        String selected = overwritePresetCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return 3;
        if (selected.startsWith("Quick")) return 1;
        if (selected.startsWith("Deep")) return 7;
        return 3;
    }

    private int getSelectedWipePassCount() {
        String selected = wipePresetCombo.getSelectionModel().getSelectedItem();
        if (selected == null) return 3;
        if (selected.startsWith("Quick")) return 1;
        if (selected.startsWith("Deep")) return 7;
        return 3;
    }

    private int countFilesRecursive(File dir) {
        int count = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                try {
                    // Never descend into symlinks/junctions during counting.
                    if (java.nio.file.Files.isSymbolicLink(f.toPath())) continue;
                    Object reparse = java.nio.file.Files.getAttribute(f.toPath(),
                            "dos:isReparsePoint", java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (reparse instanceof Boolean && (Boolean) reparse && f.isDirectory()) continue;
                } catch (Exception ignored) {}
                if (f.isDirectory()) {
                    count += countFilesRecursive(f);
                } else {
                    count++;
                }
            }
        }
        return count;
    }

    private void handleScheduleForReboot(ShredderFileEntry entry, String filePath, ShredderResult result) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Scheduling for reboot requires administrator rights.\n\nThe file could not be deleted.")
                    .showAndWait();
            entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
            return;
        }
        try {
            ShredderResult scheduleResult = shredderService.scheduleForReboot(filePath);
            if (scheduleResult.isSuccess()) {
                entry.setStatusEnum(ShredderFileEntry.Status.SCHEDULED_FOR_REBOOT);
                new Alert(Alert.AlertType.INFORMATION,
                        "The file is in use and could not be deleted now.\n\n"
                                + "It has been scheduled for deletion on the next system restart.\n"
                                + "Please restart your computer to complete the operation.").showAndWait();
            } else {
                entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                new Alert(Alert.AlertType.ERROR,
                        "Failed to schedule deletion:\n" + scheduleResult.getMessage()).showAndWait();
            }
        } catch (Exception ex) {
            entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
            new Alert(Alert.AlertType.ERROR, "Failed to schedule deletion:\n" + ex.getMessage()).showAndWait();
        }
    }

    /* ── Free Space Wiping ── */

    private final ComboBox<String> wipePresetCombo = new ComboBox<>(
            FXCollections.observableArrayList("Quick (1 pass)", "Standard (3 passes)", "Deep (7 passes)"));

    @SuppressWarnings("unchecked")
    private VBox buildFreeSpaceWipeSection() {
        Label header = new Label("Free Space Wiping");
        header.getStyleClass().addAll("label", "large", "accent");

        Label desc = new Label("Overwrite free space to remove remnants of deleted files.");
        desc.getStyleClass().add("text-muted");

        Label capWarning = new Label("Note: Free space wiping will overwrite all free space (minus ~1 GB reserve) to securely remove remnants. "
                + "This may take a long time and cause significant disk writes. Use Quick (1 pass) for fastest operation. "
                + "SSD / Unknown-type drives are BLOCKED (wear + no erasure guarantee — use the vendor's Secure Erase instead). "
                + "System-drive wipe needs double confirmation and ample free space.");
        capWarning.getStyleClass().add("text-muted");
        capWarning.setWrapText(true);
        capWarning.setStyle("-fx-font-size: 11px;");

        wipePresetCombo.getSelectionModel().select(1);
        wipePresetCombo.setTooltip(new Tooltip("Select overwrite intensity: Quick (1 pass), Standard (3 passes DoD), or Deep (7 passes)"));

        wipeProgress.setVisible(false);
        wipeProgress.setPrefWidth(200);

        startWipeBtn.getStyleClass().add("success");
        stopWipeBtn.getStyleClass().add("danger");
        stopWipeBtn.setDisable(true);

        startWipeBtn.setOnAction(e -> startWipeFreeSpace());
        startWipeBtn.setTooltip(new Tooltip("Start wiping free space on selected drives (requires admin rights)"));
        stopWipeBtn.setOnAction(e -> {
            wipeCancelled.set(true);
            stopWipeBtn.setDisable(true);
            wipeStatus.setText("Stopping...");
        });
        stopWipeBtn.setTooltip(new Tooltip("Stop the free space wipe operation"));

        selectAllWipeCheck.setOnAction(e -> {
            boolean sel = selectAllWipeCheck.isSelected();
            for (DriveInfo d : wipeDrives) {
                BooleanProperty prop = wipeSelected.computeIfAbsent(d.getDriveLetter(), k -> new SimpleBooleanProperty(false));
                prop.set(sel);
            }
            wipeDriveTable.refresh();
            updateWipeStartButton();
        });

        HBox presetRow = new HBox(8, new Label("Overwrite:"), wipePresetCombo);
        presetRow.setAlignment(Pos.CENTER_LEFT);

        HBox controls = new HBox(8,
                selectAllWipeCheck, startWipeBtn, stopWipeBtn, wipeProgress, wipeStatus);
        controls.setAlignment(Pos.CENTER_LEFT);

        wipeDriveTable.setItems(wipeDrives);
        wipeDriveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<DriveInfo, DriveInfo> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox cb = new CheckBox();
            private BooleanProperty prevProp;
            @Override
            protected void updateItem(DriveInfo item, boolean empty) {
                super.updateItem(item, empty);
                if (prevProp != null) {
                    cb.selectedProperty().unbindBidirectional(prevProp);
                    prevProp = null;
                }
                if (empty || item == null) {
                    setGraphic(null);
                } else {
                    String key = item.getDriveLetter();
                    BooleanProperty prop = wipeSelected.computeIfAbsent(key, k -> new SimpleBooleanProperty(false));
                    cb.selectedProperty().bindBidirectional(prop);
                    prevProp = prop;
                    setGraphic(cb);
                }
            }
        });

        TableColumn<DriveInfo, String> dlCol = new TableColumn<>("Drive");
        dlCol.setCellValueFactory(c -> c.getValue().driveLetterProperty());
        dlCol.setPrefWidth(60);

        TableColumn<DriveInfo, String> vlCol = new TableColumn<>("Label");
        vlCol.setCellValueFactory(c -> {
            String label = c.getValue().getVolumeLabel();
            return new SimpleObjectProperty<>(label.isBlank() ? "-" : label);
        });
        vlCol.setPrefWidth(120);

        TableColumn<DriveInfo, String> szCol = new TableColumn<>("Total Size");
        szCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getSizeFormatted()));
        szCol.setPrefWidth(100);

        TableColumn<DriveInfo, String> frCol = new TableColumn<>("Free Space");
        frCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue().getFreeFormatted()));
        frCol.setPrefWidth(100);

        wipeDriveTable.getColumns().addAll(checkCol, dlCol, vlCol, szCol, frCol);
        wipeDriveTable.setPrefHeight(180);

        VBox section = new VBox(8, header, desc, capWarning, presetRow, controls, wipeDriveTable);
        section.setPadding(new Insets(8, 16, 12, 16));
        VBox.setVgrow(wipeDriveTable, Priority.ALWAYS);
        return section;
    }

    private void updateWipeStartButton() {
        boolean anySelected = wipeDrives.stream()
                .anyMatch(d -> wipeSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get());
        startWipeBtn.setDisable(wipeBusy.get() || !anySelected);
    }

    private void startWipeFreeSpace() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Free space wiping requires administrator rights.").showAndWait();
            return;
        }
        List<DriveInfo> selected = wipeDrives.stream()
                .filter(d -> wipeSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get()).toList();
        if (selected.isEmpty()) return;

        List<String> driveLetters = selected.stream().map(DriveInfo::getDriveLetter).toList();
        int passCount = getSelectedWipePassCount();

        // Critical fix: destructive operation previously started without any confirmation
        // and without SSD / system-drive warnings. Build an explicit confirmation showing
        // exactly what will be overwritten.
        final long reserveBytes = 1024L * 1024L * 1024L;
        String systemDrive = System.getenv("SystemDrive");
        if (systemDrive == null || systemDrive.isBlank()) systemDrive = "C:";
        systemDrive = systemDrive.replace("\\", "").trim();
        if (!systemDrive.endsWith(":")) systemDrive = systemDrive + ":";

        StringBuilder drivesInfo = new StringBuilder();
        long totalToWipe = 0;
        boolean anySsd = false;
        boolean anySystem = false;
        boolean anyUnknown = false;
        for (DriveInfo d : selected) {
            long free = Math.max(0, d.getFreeBytes());
            long toWipe = Math.max(0, free - reserveBytes);
            totalToWipe += toWipe;
            boolean isSsd = d.isSsd();
            boolean isSystem = d.getDriveLetter().equalsIgnoreCase(systemDrive);
            boolean isUnknown = d.isUnknownMedia();
            anySsd |= isSsd;
            anySystem |= isSystem;
            anyUnknown |= isUnknown;
            drivesInfo.append(d.getDriveLetter()).append(" (free ").append(d.getFreeFormatted())
                    .append(", will overwrite ~").append(formatWipeBytes(toWipe))
                    .append(", ").append(d.getMediaType());
            if (isSystem) drivesInfo.append(", SYSTEM DRIVE");
            if (isSsd) drivesInfo.append(", SSD");
            if (isUnknown) drivesInfo.append(", UNKNOWN TYPE");
            drivesInfo.append(")\n");
        }
        // Strict blocking: SSD free-space wipe is blocked outright (wear +
        // no erasure guarantee). Unknown media is treated like SSD (likely flash).
        if (anySsd || anyUnknown) {
            new Alert(Alert.AlertType.ERROR,
                    "Free-space wipe blocked on SSD / Unknown-type drives.\n\n"
                            + "Overwriting free space on flash media causes significant wear and "
                            + "CANNOT guarantee erasure (wear-leveling / over-provisioning / TRIM). "
                            + "Use the SSD vendor's Secure Erase instead.\n\n"
                            + "Deselect SSD/Unknown drives to continue with HDDs only.")
                    .showAndWait();
            return;
        }
        String passLabel = passCount <= 1 ? "Quick (1 pass)" : passCount >= 7 ? "Deep (7 passes)" : "Standard (3 passes)";

        StringBuilder confirmText = new StringBuilder("Securely wipe free space on the following drive(s)?\n\n")
                .append(drivesInfo)
                .append("\nTotal to overwrite: ~").append(formatWipeBytes(totalToWipe))
                .append(" (1 GB reserve kept per drive)")
                .append("\nOverwrite: ").append(passLabel)
                .append("\n\nRecovery of wiped remnants will be impossible. This may take a very long time.");
        if (anySystem) {
            confirmText.append("\n\nWARNING: System drive (").append(systemDrive).append(") selected. "
                    + "Filling free space while Windows is running can destabilize the OS, "
                    + "break updates/pagefile, and cause app crashes. Proceed only with ample free space.");
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, confirmText.toString());
        confirm.setHeaderText("Confirm Free Space Wipe");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        // Strict: system-drive wipe needs a second explicit confirmation.
        if (anySystem) {
            Alert second = new Alert(Alert.AlertType.CONFIRMATION,
                    "You selected the SYSTEM drive (" + systemDrive + ").\n\n"
                            + "This is the strict-safety second confirmation.\n"
                            + "Wiping free space on the running OS drive can cause instability, "
                            + "failed updates, and app crashes if free space runs low.\n\n"
                            + "Proceed with system-drive wipe?");
            second.setHeaderText("Second confirmation — system drive");
            if (second.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        }

        if (totalToWipe <= 0) {
            new Alert(Alert.AlertType.WARNING,
                    "Not enough free space to wipe (needs >1 GB free per selected drive). Free up space and retry.")
                    .showAndWait();
            return;
        }

        final int finalPassCount = passCount;

        wipeBusy.set(true);
        wipeCancelled.set(false);
        startWipeBtn.setDisable(true);
        stopWipeBtn.setDisable(false);
        wipeProgress.setProgress(0);
        wipeProgress.setVisible(true);
        wipeStatus.setText("Wiping free space on " + driveLetters.size() + " drive(s)...");

        Map<String, Integer> driveProgressMap = new HashMap<>();

        newDaemonThread(() -> {
            try {
                shredderService.wipeFreeSpace(driveLetters, prog -> {
                    Platform.runLater(() -> {
                        int totalPasses = prog.getTotalPasses();
                        int pass = prog.getPass();
                        int percent = prog.getPercent();
                        // Guard against pass 0 or negative (legacy script) and clamp 0-100
                        int safePass = Math.max(1, Math.min(pass, Math.max(1, totalPasses)));
                        int driveProgress = totalPasses > 0
                                ? (safePass - 1) * 100 / totalPasses + Math.max(0, Math.min(percent, 100)) / totalPasses
                                : 0;
                        driveProgress = Math.max(0, Math.min(100, driveProgress));
                        // If done with message indicating insufficient space, keep progress low
                        if (prog.isDone() && prog.getMessage() != null && prog.getMessage().toLowerCase().contains("insufficient")) {
                            driveProgress = 0;
                        }
                        driveProgressMap.put(prog.getDrive(), driveProgress);

                        double overallProgress = driveProgressMap.values().stream()
                                .mapToInt(Integer::intValue).average().orElse(0.0) / 100.0;
                        wipeProgress.setProgress(Math.min(1.0, overallProgress));

                        String status = "Drive " + prog.getDrive() + " - Pass " + pass
                                + "/" + totalPasses + " - " + percent + "%";
                        if (prog.isDone()) {
                            status = prog.getMessage();
                        }
                        wipeStatus.setText(status);
                    });
                }, wipeCancelled, finalPassCount);
                Platform.runLater(() -> {
                    if (wipeCancelled.get()) {
                        wipeStatus.setText("Wipe stopped by user.");
                    } else {
                        wipeStatus.setText("Free space wipe completed on " + driveLetters.size() + " drive(s).");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Free space wiping completed successfully on: "
                                        + String.join(", ", driveLetters)).showAndWait();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Free space wipe failed", e);
                Platform.runLater(() -> {
                    wipeStatus.setText("Wipe failed: " + e.getMessage());
                    if (!wipeCancelled.get()) {
                        new Alert(Alert.AlertType.ERROR, "Free space wipe failed:\n" + e.getMessage()
                                + "\n\nNo success is reported when any drive fails. Check free space and try again.").showAndWait();
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    wipeBusy.set(false);
                    startWipeBtn.setDisable(false);
                    stopWipeBtn.setDisable(true);
                    wipeProgress.setVisible(false);
                });
            }
        }, "wipe-free-space").start();
    }

    private static String formatWipeBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.2f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    public void dispose() {
        wipeCancelled.set(true);
        defragCancelled.set(true);
        benchCancelled.set(true);
        recycleBinCancelled.set(true);
        secureCancelled.set(true);
        if (currentDefragThread != null && currentDefragThread.isAlive()) currentDefragThread.interrupt();
        if (currentAnalyzeThread != null && currentAnalyzeThread.isAlive()) currentAnalyzeThread.interrupt();
        if (currentBenchThread != null && currentBenchThread.isAlive()) currentBenchThread.interrupt();
        sharedExecutor.shutdownNow();
    }
}
