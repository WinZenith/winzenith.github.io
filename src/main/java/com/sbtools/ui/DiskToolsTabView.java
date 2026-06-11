package com.sbtools.ui;

import com.sbtools.defrag.DefragService;
import com.sbtools.defrag.DriveInfo;
import com.sbtools.shredder.ShredderFileEntry;
import com.sbtools.shredder.ShredderResult;
import com.sbtools.shredder.ShredderService;
import com.sbtools.shredder.WipeProgress;
import com.sbtools.util.AppLogger;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
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
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class DiskToolsTabView extends BorderPane {

    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;
    private final DefragService defragService = new DefragService();
    private final ShredderService shredderService = new ShredderService();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean wipeCancelled = new AtomicBoolean(false);

    /* ───── Defrag tab components ───── */
    private final TableView<DriveInfo> driveTable = new TableView<>();
    private final ObservableList<DriveInfo> allDrives = FXCollections.observableArrayList();
    private final FilteredList<DriveInfo> filteredDrives = new FilteredList<>(allDrives, d -> true);
    private final ComboBox<String> filterCombo = new ComboBox<>(
            FXCollections.observableArrayList("All", "HDD", "SSD"));
    private final Button analyzeBtn = new Button("Analyze");
    private final Button trimBtn = new Button("Trim");
    private final Button fastDefragBtn = new Button("Fast Defrag");
    private final Button fullDefragBtn = new Button("Full Defrag");
    private final Button freeSpaceDefragBtn = new Button("Free Space Defrag");
    private final Button stopBtn = new Button("Stop");
    private final ProgressBar defragProgress = new ProgressBar(0);
    private final Label defragStatus = new Label("Select a drive and click Analyze.");
    private Thread currentOperationThread;
    private final Canvas blockCanvas = new Canvas(400, 200);
    private final Label fragCountLabel = new Label();
    private final Label fragPercentLabel = new Label();
    private final VBox visualizationPanel = new VBox(8);

    /* ───── Secure Erase tab components ───── */
    private final TableView<ShredderFileEntry> shredderTable = new TableView<>();
    private final ObservableList<ShredderFileEntry> shredderEntries = FXCollections.observableArrayList();
    private final TextField filePathField = new TextField();
    private final Button browseBtn = new Button("Browse...");
    private final Button secureDeleteBtn = new Button("Secure Delete");

    private final TableView<DriveInfo> wipeDriveTable = new TableView<>();
    private final ObservableList<DriveInfo> wipeDrives = FXCollections.observableArrayList();
    private final Button startWipeBtn = new Button("Start");
    private final Button stopWipeBtn = new Button("Stop");
    private final ProgressBar wipeProgress = new ProgressBar(0);
    private final Label wipeStatus = new Label("Select drives and click Start.");
    private final CheckBox selectAllWipeCheck = new CheckBox("Select All");
    private final Map<String, BooleanProperty> wipeSelected = new HashMap<>();

    public DiskToolsTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        // Clean up any orphaned temp files from previous interrupted wipe sessions
        ShredderService.sweepOrphanedTempFiles();

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab defragTab = new Tab("Defrag", buildDefragContent());
        Tab secureEraseTab = new Tab("Secure Erase", buildSecureEraseContent());

        tabPane.getTabs().addAll(defragTab, secureEraseTab);
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

        trimBtn.setDisable(true);
        fastDefragBtn.setDisable(true);
        fullDefragBtn.setDisable(true);
        freeSpaceDefragBtn.setDisable(true);
        trimBtn.setTooltip(new Tooltip("Trim an SSD drive"));
        fastDefragBtn.setTooltip(new Tooltip("Quick defrag targeting large fragments"));
        fullDefragBtn.setTooltip(new Tooltip("Full defragmentation of all files"));
        freeSpaceDefragBtn.setTooltip(new Tooltip("Consolidate free space"));

        stopBtn.getStyleClass().add("danger");
        stopBtn.setVisible(false);
        stopBtn.setOnAction(e -> stopOperation());

        analyzeBtn.setOnAction(e -> startAnalyze());
        trimBtn.setOnAction(e -> startDefragOrTrim("trim"));
        fastDefragBtn.setOnAction(e -> startDefragOrTrim("fast"));
        fullDefragBtn.setOnAction(e -> startDefragOrTrim("full"));
        freeSpaceDefragBtn.setOnAction(e -> startDefragOrTrim("freespace"));

        HBox defragToolbar = new HBox(8,
                new Label("Filter:"), filterCombo,
                analyzeBtn, trimBtn, fastDefragBtn, fullDefragBtn, freeSpaceDefragBtn,
                stopBtn, defragProgress, defragStatus);
        defragToolbar.setAlignment(Pos.CENTER_LEFT);
        defragToolbar.setPadding(new Insets(12, 16, 12, 16));
        defragToolbar.getStyleClass().add("toolbar");

        buildDriveTable();

        VBox center = new VBox(4, driveTable);
        center.setPadding(new Insets(8, 16, 4, 16));

        visualizationPanel.setPadding(new Insets(4, 16, 12, 16));
        visualizationPanel.setVisible(false);

        HBox blockBox = new HBox(blockCanvas);
        blockBox.setAlignment(Pos.CENTER);

        HBox statsBox = new HBox(24, fragCountLabel, fragPercentLabel);
        statsBox.setAlignment(Pos.CENTER);

        HBox legendBox = new HBox(8);
        legendBox.setAlignment(Pos.CENTER);
        legendBox.setId("defrag-legend");

        visualizationPanel.getChildren().addAll(blockBox, legendBox, statsBox);

        busy.addListener((obs, oldVal, newVal) -> {
            analyzeBtn.setDisable(newVal);
            trimBtn.setDisable(newVal);
            fastDefragBtn.setDisable(newVal);
            fullDefragBtn.setDisable(newVal);
            freeSpaceDefragBtn.setDisable(newVal);
            stopBtn.setVisible(newVal);
            if (!newVal) {
                defragProgress.setProgress(0);
                stopBtn.setDisable(false);
            }
        });

        driveTable.getSelectionModel().selectedItemProperty().addListener((obs, old, sel) -> {
            boolean hasSelection = sel != null && !busy.get();
            if (hasSelection) {
                boolean isSsd = sel.isSsd();
                trimBtn.setDisable(!isSsd);
                fastDefragBtn.setDisable(isSsd);
                fullDefragBtn.setDisable(isSsd);
                freeSpaceDefragBtn.setDisable(isSsd);
            } else {
                trimBtn.setDisable(true);
                fastDefragBtn.setDisable(true);
                fullDefragBtn.setDisable(true);
                freeSpaceDefragBtn.setDisable(true);
            }
        });

        VBox content = new VBox(defragToolbar, center, visualizationPanel);
        VBox.setVgrow(visualizationPanel, Priority.ALWAYS);
        return content;
    }

    private void buildDriveTable() {
        driveTable.setItems(filteredDrives);
        driveTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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

        TableColumn<DriveInfo, String> fragCol = new TableColumn<>("Fragments");
        fragCol.setCellValueFactory(c -> new SimpleObjectProperty<>(
                c.getValue().getFragmentsFound() == 0 ? "-" : c.getValue().getFragmentsFormatted()));
        fragCol.setPrefWidth(90);

        TableColumn<DriveInfo, Number> fragPctCol = new TableColumn<>("Frag. %");
        fragPctCol.setCellValueFactory(c -> c.getValue().fragmentationPercentProperty());
        fragPctCol.setPrefWidth(70);
        fragPctCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.longValue() == 0) {
                    setText("-");
                } else {
                    setText(item.longValue() + "%");
                }
            }
        });

        driveTable.getColumns().addAll(letterCol, labelCol, typeCol, fsCol, sizeCol, freeCol, fragCol, fragPctCol);

        driveTable.setFixedCellSize(32);

        driveTable.setRowFactory(tv -> {
            javafx.scene.control.TableRow<DriveInfo> row = new javafx.scene.control.TableRow<>() {
                @Override
                protected void updateItem(DriveInfo item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setStyle("");
                    } else if (item.isSummaryRow()) {
                        setStyle("-fx-background-color: #282a36; -fx-border-color: #44475a transparent transparent transparent; "
                                + "-fx-font-weight: bold; -fx-text-fill: #8be9fd;");
                    } else {
                        setStyle("");
                    }
                }
            };
            return row;
        });

        updateTableHeight();
        filteredDrives.addListener((javafx.collections.ListChangeListener<DriveInfo>) c -> updateTableHeight());
    }

    private void updateTableHeight() {
        int rows = filteredDrives.size();
        double header = 28;
        double rowH = driveTable.getFixedCellSize();
        driveTable.setPrefHeight(header + rows * rowH + 4);
        driveTable.setMaxHeight(driveTable.getPrefHeight());
    }

    private void applyFilter() {
        String filter = filterCombo.getSelectionModel().getSelectedItem();
        if (filter == null || "All".equals(filter)) {
            filteredDrives.setPredicate(d -> true);
        } else {
            filteredDrives.setPredicate(d -> filter.equalsIgnoreCase(d.getMediaType()));
        }
    }

    private void updateSummaryRows() {
        // Remove old summary rows
        allDrives.removeIf(DriveInfo::isSummaryRow);

        DriveInfo hddSum = DriveInfo.createHddSummary(allDrives);
        DriveInfo ssdSum = DriveInfo.createSsdSummary(allDrives);

        if (hddSum != null) allDrives.add(hddSum);
        if (ssdSum != null) allDrives.add(ssdSum);

        applyFilter();
    }

    private void loadDrives() {
        new Thread(() -> {
            try {
                List<DriveInfo> drives = defragService.getDrives();
                Platform.runLater(() -> {
                    allDrives.setAll(drives);
                    wipeDrives.setAll(drives);
                    updateSummaryRows();
                    defragStatus.setText("Found " + drives.size() + " drive(s). Select one and click Analyze.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to load drives", e);
                Platform.runLater(() -> {
                    defragStatus.setText("Failed to load drives.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load drives:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "load-drives").start();
    }

    private void stopOperation() {
        cancelled.set(true);
        stopBtn.setDisable(true);
        defragStatus.setText("Stopping...");
        defragProgress.setProgress(-1);
        if (currentOperationThread != null && currentOperationThread.isAlive()) {
            currentOperationThread.interrupt();
        }
    }

    private void startAnalyze() {
        DriveInfo selected = driveTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;
        busy.set(true);
        cancelled.set(false);
        defragStatus.setText("Analyzing " + selected.getDriveLetter() + "...");
        defragProgress.setProgress(-1);
        defragProgress.setVisible(true);

        DriveInfo driveCopy = selected;
        currentOperationThread = new Thread(() -> {
            try {
                defragService.analyze(driveCopy, msg -> Platform.runLater(() -> {
                    defragStatus.setText(msg);
                }), cancelled);
                Platform.runLater(() -> {
                    driveTable.refresh();
                    updateRichBlockVisualization(driveCopy);
                    updateSummaryRows();
                    defragStatus.setText("Analysis complete - "
                            + driveCopy.getFragmentsFormatted() + " fragments, "
                            + driveCopy.getFragmentationPercent() + "% fragmentation.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    String msg = e.getMessage() != null ? e.getMessage() : "Analysis failed.";
                    defragStatus.setText("Analysis failed.");
                    new Alert(Alert.AlertType.ERROR, msg).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    defragProgress.setVisible(false);
                });
            }
        }, "analyze-drive");
        currentOperationThread.start();
    }

    private void updateRichBlockVisualization(DriveInfo drive) {
        visualizationPanel.setVisible(true);
        GraphicsContext gc = blockCanvas.getGraphicsContext2D();
        gc.clearRect(0, 0, blockCanvas.getWidth(), blockCanvas.getHeight());

        long total = drive.getSizeBytes();
        long used = drive.getUsedBytes();
        long free = drive.getFreeBytes();

        double usedRatio = total > 0 ? (double) used / total : 0;

        long mftBytes = drive.getMftSizeBytes();
        long pageBytes = drive.getPageFileSizeBytes() + drive.getHiberFileSizeBytes() + drive.getSwapFileSizeBytes();
        long fragBytes = drive.getFragmentsFound();
        long dirBytes = drive.getTotalDirectories() * 4096L;
        long knownBytes = mftBytes + pageBytes + dirBytes + fragBytes;
        long remainingUsed = Math.max(0, used - knownBytes);
        long frequentlyUsedBytes = (long) (remainingUsed * 0.10);
        long normalBytes = Math.max(0, remainingUsed - frequentlyUsedBytes);

        // Color palette (Dracula-themed)
        Color colSys       = Color.rgb(139, 233, 253);  // cyan - System/MFT
        Color colDir       = Color.rgb(189, 147, 249);  // purple - Directories
        Color colFreq      = Color.rgb(241, 250, 140);  // yellow - Frequently Used
        Color colNormal    = Color.rgb(80, 250, 123);   // green - Normal Files
        Color colFrag      = Color.rgb(255, 85, 85);    // red - Fragmented
        Color colPage      = Color.rgb(255, 184, 108);  // orange - Page/Hibernation
        Color colFree      = Color.rgb(68, 71, 90);     // gray - Free Space
        Color colBg        = Color.rgb(40, 42, 54);     // dark bg

        int cols = 50;
        int rows = 16;
        int totalCells = cols * rows;
        double cellW = blockCanvas.getWidth() / cols;
        double cellH = blockCanvas.getHeight() / rows;

        // Estimate cell counts per category based on byte proportions
        int usedCells = (int) Math.round(totalCells * usedRatio);
        int freeCells = totalCells - usedCells;

        int mftCells  = bytesToCells(mftBytes, total, totalCells);
        int pageCells = bytesToCells(pageBytes, total, totalCells);
        int dirCells  = bytesToCells(dirBytes, total, totalCells);
        int fragCells = bytesToCells(fragBytes, total, totalCells);
        int freqCells = bytesToCells(frequentlyUsedBytes, total, totalCells);

        // Clamp: special categories cannot exceed used cells
        int totalSpecial = mftCells + pageCells + dirCells + fragCells + freqCells;
        if (totalSpecial > usedCells && totalSpecial > 0) {
            double scale = (double) usedCells / totalSpecial;
            mftCells  = Math.max(0, (int) (mftCells * scale));
            pageCells = Math.max(0, (int) (pageCells * scale));
            dirCells  = Math.max(0, (int) (dirCells * scale));
            fragCells = Math.max(0, (int) (fragCells * scale));
            freqCells = Math.max(0, (int) (freqCells * scale));
        }
        int normalCells = Math.max(0, usedCells - (mftCells + pageCells + dirCells + fragCells + freqCells));

        // Build grid filled with free space
        Color[][] grid = new Color[rows][cols];
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                grid[r][c] = colFree;
            }
        }

        Random rng = new Random(drive.getDriveLetter().hashCode());

        // Phase 1: Place MFT/System blocks at start (first 1-2 columns)
        for (int i = 0; i < mftCells; i++) {
            int r = i % rows;
            int c = i / rows;
            if (c < cols) grid[r][c] = colSys;
        }

        // Phase 2: Place directory blocks right after system area
        for (int i = 0; i < dirCells; i++) {
            int idx = (mftCells + i);
            int r = idx % rows;
            int c = idx / rows;
            if (c < cols && grid[r][c] == colFree) grid[r][c] = colDir;
        }

        // Phase 3: Place page/hibernation as contiguous block(s)
        if (pageCells > 0) {
            int pr = rows / 4 + rng.nextInt(rows / 2);
            int pc = cols / 3 + rng.nextInt(cols / 3);
            int placed = 0;
            int prCol = pc;
            int prRow = pr;
            while (placed < pageCells && prRow < rows) {
                if (grid[prRow][prCol] == colFree) {
                    grid[prRow][prCol] = colPage;
                    placed++;
                }
                prCol++;
                if (prCol >= cols) { prCol = pc; prRow++; }
            }
        }

        // Build list of all used cell positions (non-free)
        List<int[]> usedPositions = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] != colFree) {
                    usedPositions.add(new int[]{r, c});
                }
            }
        }

        // Build list of available (free) positions for remaining categories
        List<int[]> availablePositions = new ArrayList<>();
        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                if (grid[r][c] == colFree) {
                    availablePositions.add(new int[]{r, c});
                }
            }
        }

        // Shuffle available positions for pseudo-random placement
        Collections.shuffle(availablePositions, rng);

        // Phase 4: Place fragmented blocks scattered randomly
        int availIdx = 0;
        for (int i = 0; i < fragCells && availIdx < availablePositions.size(); i++) {
            int[] pos = availablePositions.get(availIdx++);
            grid[pos[0]][pos[1]] = colFrag;
        }

        // Phase 5: Place frequently-used blocks scattered
        for (int i = 0; i < freqCells && availIdx < availablePositions.size(); i++) {
            int[] pos = availablePositions.get(availIdx++);
            grid[pos[0]][pos[1]] = colFreq;
        }

        // Phase 6: Place normal files fill remaining used area
        int normalPlaced = 0;
        while (normalPlaced < normalCells && availIdx < availablePositions.size()) {
            int[] pos = availablePositions.get(availIdx++);
            grid[pos[0]][pos[1]] = colNormal;
            normalPlaced++;
        }

        // Remaining availablePositions stay as Free

        // ── Render grid ──
        gc.setStroke(colBg);
        gc.setLineWidth(1);

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                gc.setFill(grid[r][c]);
                gc.fillRect(c * cellW + 1, r * cellH + 1, cellW - 2, cellH - 2);
            }
        }

        // Summary bar below the grid
        int barY = rows * (int) cellH + 8;
        int barH = 10;
        int barW = (int) blockCanvas.getWidth();
        int barX = 0;

        // Calculate total used cell count for bar (match displayed)
        int displayedUsed = mftCells + dirCells + pageCells + fragCells + freqCells + normalCells;
        double displayUsedRatio = totalCells > 0 ? (double) displayedUsed / totalCells : 0;
        double displayFragRatio = totalCells > 0 ? (double) fragCells / totalCells : 0;
        double displayFreeRatio = 1.0 - displayUsedRatio;

        // Bar: segments proportionally by category
        int barFragW  = (int) (barW * displayFragRatio);
        int barFreqW  = (int) (barW * (totalCells > 0 ? (double) freqCells / totalCells : 0));
        int barSysW   = (int) (barW * (totalCells > 0 ? (double) mftCells / totalCells : 0));
        int barDirW   = (int) (barW * (totalCells > 0 ? (double) dirCells / totalCells : 0));
        int barPageW  = (int) (barW * (totalCells > 0 ? (double) pageCells / totalCells : 0));
        int barNormW  = (int) (barW * (totalCells > 0 ? (double) normalCells / totalCells : 0));
        int barFreeW  = barW - barFragW - barFreqW - barSysW - barDirW - barPageW - barNormW;

        int barOffset = 0;
        gc.setFill(colSys); gc.fillRect(barX + barOffset, barY, Math.max(1, barSysW), barH); barOffset += barSysW;
        gc.setFill(colDir); gc.fillRect(barX + barOffset, barY, Math.max(1, barDirW), barH); barOffset += barDirW;
        gc.setFill(colPage); gc.fillRect(barX + barOffset, barY, Math.max(1, barPageW), barH); barOffset += barPageW;
        gc.setFill(colFreq); gc.fillRect(barX + barOffset, barY, Math.max(1, barFreqW), barH); barOffset += barFreqW;
        gc.setFill(colNormal); gc.fillRect(barX + barOffset, barY, Math.max(1, barNormW), barH); barOffset += barNormW;
        gc.setFill(colFrag); gc.fillRect(barX + barOffset, barY, Math.max(1, barFragW), barH); barOffset += barFragW;
        gc.setFill(colFree); gc.fillRect(barX + barOffset, barY, Math.max(1, barFreeW), barH);

        gc.setStroke(colBg);
        gc.setLineWidth(1);
        gc.strokeRect(barX, barY, barW, barH);

        // ── Update stats labels ──
        fragCountLabel.setText("Fragments: " + drive.getFragmentsFormatted()
                + "  |  Fragmented files: " + drive.getFragmentedFileCount()
                + "  |  Total files: " + drive.getTotalFileCount());
        fragCountLabel.getStyleClass().addAll("label", "warning");
        fragPercentLabel.setText("Fragmentation: " + drive.getFragmentationPercent() + "%"
                + "  |  Avg fragments/file: " + String.format("%.1f", drive.getAverageFragmentsPerFile()));
        fragPercentLabel.getStyleClass().addAll("label", drive.getFragmentationPercent() > 20 ? "danger" : "success");

        // ── Update legend dynamically ──
        HBox legendBox = (HBox) visualizationPanel.lookup("#defrag-legend");
        if (legendBox != null) {
            legendBox.getChildren().setAll(
                    createLegendItem(colSys, "System/MFT"),
                    createLegendItem(colDir, "Directories"),
                    createLegendItem(colFreq, "Frequently Used"),
                    createLegendItem(colNormal, "Normal"),
                    createLegendItem(colFrag, "Fragmented"),
                    createLegendItem(colPage, "Page/Hibernation"),
                    createLegendItem(colFree, "Free")
            );
        }
    }

    private int bytesToCells(long bytes, long totalBytes, int totalCells) {
        if (totalBytes <= 0 || bytes <= 0) return 0;
        return Math.max(1, (int) Math.round((double) bytes / totalBytes * totalCells));
    }

    private HBox createLegendItem(Color color, String label) {
        Label colorBox = new Label("  ");
        colorBox.setStyle(String.format("-fx-background-color: #%02x%02x%02x; "
                + "-fx-min-width: 12px; -fx-min-height: 12px; "
                + "-fx-max-width: 12px; -fx-max-height: 12px; "
                + "-fx-border-color: #6272a4; -fx-border-width: 1;", 
                (int)(color.getRed()*255), (int)(color.getGreen()*255), (int)(color.getBlue()*255)));
        Label text = new Label(label);
        text.setStyle("-fx-text-fill: #f8f8f2; -fx-font-size: 11px;");
        HBox item = new HBox(4, colorBox, text);
        item.setAlignment(Pos.CENTER);
        return item;
    }

    private void startDefragOrTrim(String mode) {
        DriveInfo selected = driveTable.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Defrag/Trim operations require administrator rights.").showAndWait();
            return;
        }

        String modeLabel = switch (mode) {
            case "trim" -> "Trim";
            case "fast" -> "Fast Defrag";
            case "full" -> "Full Defrag";
            case "freespace" -> "Free Space Defrag";
            default -> "Operation";
        };

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                modeLabel + " on " + selected.getDriveLetter() + "?");
        confirm.setHeaderText("Confirm " + modeLabel);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        busy.set(true);
        cancelled.set(false);
        defragStatus.setText(modeLabel + " in progress on " + selected.getDriveLetter() + "...");
        defragProgress.setProgress(0);
        defragProgress.setVisible(true);

        DriveInfo driveCopy = selected;
        currentOperationThread = new Thread(() -> {
            try {
                if ("trim".equals(mode)) {
                    defragService.trim(driveCopy,
                            msg -> Platform.runLater(() -> defragStatus.setText(msg)),
                            pct -> Platform.runLater(() -> {
                                defragProgress.setProgress(pct);
                                defragStatus.setText(modeLabel + " " + Math.round(pct * 100) + "%");
                            }),
                            cancelled);
                } else {
                    DefragService.DefragOption option = switch (mode) {
                        case "fast" -> DefragService.DefragOption.FAST;
                        case "full" -> DefragService.DefragOption.FULL;
                        case "freespace" -> DefragService.DefragOption.FREE_SPACE;
                        default -> DefragService.DefragOption.FULL;
                    };
                    defragService.defrag(driveCopy, option,
                            msg -> Platform.runLater(() -> defragStatus.setText(msg)),
                            pct -> Platform.runLater(() -> {
                                defragProgress.setProgress(pct);
                                defragStatus.setText(modeLabel + " " + Math.round(pct * 100) + "%");
                            }),
                            cancelled);
                }
                Platform.runLater(() -> {
                    defragProgress.setProgress(1);
                    defragStatus.setText(modeLabel + " completed on " + driveCopy.getDriveLetter());
                    new Alert(Alert.AlertType.INFORMATION, modeLabel + " completed successfully.").showAndWait();
                });
            } catch (java.util.concurrent.CancellationException e) {
                Platform.runLater(() -> {
                    defragStatus.setText(modeLabel + " cancelled.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    defragStatus.setText(modeLabel + " failed.");
                    new Alert(Alert.AlertType.ERROR, modeLabel + " failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    defragProgress.setVisible(false);
                });
            }
        }, "defrag-" + mode);
        currentOperationThread.start();
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

        VBox fileSection = buildFileDeletionSection();
        VBox wipeSection = buildFreeSpaceWipeSection();

        VBox content = new VBox(12, warning, fileSection, wipeSection);
        content.setPadding(new Insets(0));
        VBox.setVgrow(wipeSection, Priority.ALWAYS);
        return content;
    }

    /* ── Secure File Deletion ── */

    @SuppressWarnings("unchecked")
    private VBox buildFileDeletionSection() {
        Label header = new Label("Secure File Deletion");
        header.getStyleClass().addAll("label", "large", "accent");

        filePathField.setPromptText("Select a file to securely delete...");
        filePathField.setPrefWidth(400);
        filePathField.setEditable(false);

        browseBtn.setOnAction(e -> {
            FileChooser fc = new FileChooser();
            fc.setTitle("Select file to securely delete");
            File f = fc.showOpenDialog(getScene() != null ? getScene().getWindow() : null);
            if (f != null) {
                filePathField.setText(f.getAbsolutePath());
                secureDeleteBtn.setDisable(false);
            }
        });

        secureDeleteBtn.setDisable(true);
        secureDeleteBtn.getStyleClass().add("danger");
        secureDeleteBtn.setOnAction(e -> startSecureDelete());

        HBox row = new HBox(8, filePathField, browseBtn, secureDeleteBtn);
        row.setAlignment(Pos.CENTER_LEFT);

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

        shredderTable.getColumns().addAll(pathCol, sizeCol, statusCol);

        VBox section = new VBox(8, header, row, shredderTable);
        section.setPadding(new Insets(8, 16, 8, 16));
        return section;
    }

    private void startSecureDelete() {
        String filePath = filePathField.getText();
        if (filePath == null || filePath.isBlank()) return;

        File f = new File(filePath);
        if (!f.exists()) {
            new Alert(Alert.AlertType.ERROR, "File not found: " + filePath).showAndWait();
            return;
        }

        busy.set(true);
        secureDeleteBtn.setDisable(true);

        ShredderFileEntry entry = new ShredderFileEntry(filePath, f.length());
        entry.setStatusEnum(ShredderFileEntry.Status.PENDING);
        shredderEntries.add(0, entry);

        new Thread(() -> {
            try {
                ShredderResult result = shredderService.secureDelete(filePath);
                Platform.runLater(() -> {
                    if (result.isSuccess() && result.isDeleted()) {
                        entry.setStatusEnum(ShredderFileEntry.Status.DELETED);
                        defragStatus.setText("File securely deleted.");
                        new Alert(Alert.AlertType.INFORMATION, "File securely deleted:\n" + filePath).showAndWait();
                    } else if (result.isScheduledForReboot()) {
                        handleScheduleForReboot(entry, filePath, result);
                    } else {
                        entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                        String msg = result.getMessage() != null ? result.getMessage() : "Unknown error";
                        new Alert(Alert.AlertType.ERROR, "Failed to delete file:\n" + msg).showAndWait();
                    }
                });
            } catch (Exception e) {
                String errMsg = e.getMessage() != null ? e.getMessage() : "";
                if (errMsg.toLowerCase().contains("in use") || errMsg.toLowerCase().contains("access denied")
                        || errMsg.toLowerCase().contains("unauthorized")) {
                    ShredderResult fakeResult = new ShredderResult(filePath, false, false, true,
                            "File is in use. Scheduling for deletion on next reboot.");
                    Platform.runLater(() -> handleScheduleForReboot(entry, filePath, fakeResult));
                } else {
                    Platform.runLater(() -> {
                        entry.setStatusEnum(ShredderFileEntry.Status.FAILED);
                        new Alert(Alert.AlertType.ERROR, "Secure delete failed:\n" + e.getMessage()).showAndWait();
                    });
                }
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    secureDeleteBtn.setDisable(false);
                    filePathField.clear();
                });
            }
        }, "secure-delete").start();
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

    @SuppressWarnings("unchecked")
    private VBox buildFreeSpaceWipeSection() {
        Label header = new Label("Free Space Wiping");
        header.getStyleClass().addAll("label", "large", "accent");

        Label desc = new Label("Overwrite free space to remove remnants of deleted files.");
        desc.getStyleClass().add("text-muted");

        wipeProgress.setVisible(false);
        wipeProgress.setPrefWidth(200);

        startWipeBtn.getStyleClass().add("success");
        stopWipeBtn.getStyleClass().add("danger");
        stopWipeBtn.setDisable(true);

        startWipeBtn.setOnAction(e -> startWipeFreeSpace());
        stopWipeBtn.setOnAction(e -> {
            wipeCancelled.set(true);
            stopWipeBtn.setDisable(true);
            wipeStatus.setText("Stopping...");
        });

        selectAllWipeCheck.setOnAction(e -> {
            boolean sel = selectAllWipeCheck.isSelected();
            for (DriveInfo d : wipeDrives) {
                BooleanProperty prop = wipeSelected.computeIfAbsent(d.getDriveLetter(), k -> new SimpleBooleanProperty(false));
                prop.set(sel);
            }
            wipeDriveTable.refresh();
            updateWipeStartButton();
        });

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

        VBox section = new VBox(8, header, desc, controls, wipeDriveTable);
        section.setPadding(new Insets(8, 16, 12, 16));
        VBox.setVgrow(wipeDriveTable, Priority.ALWAYS);
        return section;
    }

    private void updateWipeStartButton() {
        boolean anySelected = wipeDrives.stream()
                .anyMatch(d -> wipeSelected.getOrDefault(d.getDriveLetter(), new SimpleBooleanProperty(false)).get());
        startWipeBtn.setDisable(busy.get() || !anySelected);
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

        busy.set(true);
        wipeCancelled.set(false);
        startWipeBtn.setDisable(true);
        stopWipeBtn.setDisable(false);
        wipeProgress.setProgress(0);
        wipeProgress.setVisible(true);
        wipeStatus.setText("Wiping free space on " + driveLetters.size() + " drive(s)...");

        new Thread(() -> {
            try {
                shredderService.wipeFreeSpace(driveLetters, prog -> {
                    Platform.runLater(() -> {
                        int total = driveLetters.size();
                        int overall = (int) (((prog.getPass() - 1) * 100.0 / prog.getTotalPasses()
                                + prog.getPercent() / (double) prog.getTotalPasses()) / total);
                        wipeProgress.setProgress(Math.min(1.0, overall / 100.0));
                        String status = "Drive " + prog.getDrive() + " - Pass " + prog.getPass()
                                + "/" + prog.getTotalPasses() + " - " + prog.getPercent() + "%";
                        if (prog.isDone()) {
                            status = prog.getMessage();
                        }
                        wipeStatus.setText(status);
                    });
                }, wipeCancelled);
                Platform.runLater(() -> {
                    if (wipeCancelled.get()) {
                        wipeStatus.setText("Wipe stopped by user.");
                    } else {
                        wipeStatus.setText("Free space wipe completed.");
                        new Alert(Alert.AlertType.INFORMATION, "Free space wiping completed successfully.").showAndWait();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Free space wipe failed", e);
                Platform.runLater(() -> {
                    wipeStatus.setText("Wipe failed.");
                    if (!wipeCancelled.get()) {
                        new Alert(Alert.AlertType.ERROR, "Free space wipe failed:\n" + e.getMessage()).showAndWait();
                    }
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    startWipeBtn.setDisable(false);
                    stopWipeBtn.setDisable(true);
                    wipeProgress.setVisible(false);
                });
            }
        }, "wipe-free-space").start();
    }
}
