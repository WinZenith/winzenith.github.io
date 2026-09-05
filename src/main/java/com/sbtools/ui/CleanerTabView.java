package com.sbtools.ui;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupHistoryDialog;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanupService;
import com.sbtools.cleaner.CleanerHistoryStore;
import com.sbtools.cleaner.CleanerPresets;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppLogger;
import com.sbtools.util.CancelableCompletableFuture;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.WindowsVersionUtil;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

    public class CleanerTabView extends BorderPane {

    private final CleanupService service = new CleanupService();
    private final CleanerHistoryStore historyStore = new CleanerHistoryStore();
    private final BooleanProperty busy;
    private final java.util.function.BooleanSupplier adminCheck;
    private final SettingsStore settingsStore;
    private CancelableCompletableFuture<java.util.List<CleanupRow>> activeScanFuture;
    private CancelableCompletableFuture<CleanupService.CleanSummary> activeCleanFuture;
    private CancellationToken activeScanToken;
    private CancellationToken activeCleanToken;
    private final ObservableList<CleanupRow> sessionRows = FXCollections.observableArrayList();
    private volatile boolean hasScanned = false;
    private final AtomicBoolean cancelling = new AtomicBoolean(false);

    private TableView<CleanupRow> table;
    private Label statusLabel;
    private ProgressBar progressBar;
    private Label summaryLabel;
    private Button scanButton;
    private Button selectAllButton;
    private Button deselectAllButton;
    private Button presetButton;
    private Button cleanButton;
    private Button historyButton;
    private Button cancelButton;
    private Button exportButton;
    private Button refreshButton;
    private javafx.scene.control.TextField searchField;
    private javafx.scene.control.ComboBox<String> riskFilter;
    private javafx.collections.transformation.FilteredList<CleanupRow> filteredRows;

    public CleanerTabView(BooleanProperty busy, java.util.function.BooleanSupplier adminCheck) {
        this(busy, adminCheck, new SettingsStore());
    }

    public CleanerTabView(BooleanProperty busy, java.util.function.BooleanSupplier adminCheck, SettingsStore settingsStore) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        this.settingsStore = settingsStore;
        setCenter(buildSystemCleanupContent());
    }

    private VBox buildSystemCleanupContent() {
        statusLabel = new Label("Click Scan to analyze cleanup opportunities.");
        progressBar = new ProgressBar(0);
        scanButton = new Button("Scan");
        selectAllButton = new Button("Select All");
        deselectAllButton = new Button("Deselect All");
        presetButton = new Button("Presets...");
        cleanButton = new Button("Clean Selected");
        historyButton = new Button("History");
        cancelButton = new Button("Cancel");
        exportButton = new Button("Export...");
        refreshButton = new Button("Refresh selected");
        searchField = new javafx.scene.control.TextField();
        searchField.setPromptText("Filter categories...");
        searchField.setPrefWidth(160);
        riskFilter = new javafx.scene.control.ComboBox<>();
        riskFilter.getItems().addAll("All risks", "Low", "Medium", "High");
        riskFilter.setValue("All risks");
        riskFilter.setPrefWidth(110);
        table = new TableView<>();
        filteredRows = new javafx.collections.transformation.FilteredList<>(sessionRows, r -> true);
        table.setItems(filteredRows);
        searchField.textProperty().addListener((obs, o, n) -> applyTableFilter());
        riskFilter.valueProperty().addListener((obs, o, n) -> applyTableFilter());

        summaryLabel = new Label();
        summaryLabel.setStyle("-fx-text-fill: #50fa7b; -fx-font-size: 13px; -fx-padding: 4 0 0 0;");
        summaryLabel.setVisible(false);

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        cleanButton.setDisable(true);
        cleanButton.getStyleClass().add("danger");
        cancelButton.setDisable(true);

        scanButton.setOnAction(e -> startScan());
        selectAllButton.setOnAction(e -> {
            for (CleanupRow row : sessionRows) row.setSelected(true);
            updateCleanButtonState();
        });
        deselectAllButton.setOnAction(e -> {
            for (CleanupRow row : sessionRows) row.setSelected(false);
            updateCleanButtonState();
        });
        presetButton.setOnAction(e -> {
            showPresetMenu();
            updateCleanButtonState();
        });
        cleanButton.setOnAction(e -> startClean());
        historyButton.setOnAction(e -> {
            CleanupHistoryDialog dialog = new CleanupHistoryDialog(historyStore);
            dialog.showAndWait();
        });
        cancelButton.setOnAction(e -> cancelActive());
        exportButton.setOnAction(e -> exportScanCsv());
        refreshButton.setOnAction(e -> refreshSelected());

        HBox top = new HBox(6, scanButton, refreshButton, selectAllButton, deselectAllButton, presetButton,
                cleanButton, historyButton, exportButton, progressBar, statusLabel, cancelButton);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        HBox filterRow = new HBox(8, new Label("Search:"), searchField, new Label("Risk:"), riskFilter);
        filterRow.setAlignment(Pos.CENTER_LEFT);
        filterRow.setPadding(new Insets(0, 16, 0, 16));

        buildTable();

        VBox center = new VBox(8, table, summaryLabel);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            refreshButton.setDisable(newVal || getSelectedCount() == 0);
            selectAllButton.setDisable(newVal);
            deselectAllButton.setDisable(newVal);
            presetButton.setDisable(newVal);
            exportButton.setDisable(newVal || sessionRows.isEmpty());
            cleanButton.setDisable(newVal || getSelectedCount() == 0);
            cancelButton.setDisable(!newVal);
        });

        sessionRows.addListener((javafx.collections.ListChangeListener<CleanupRow>) c -> {
            updateSummary();
            applyTableFilter();
            if (!busy.get()) {
                cleanButton.setDisable(getSelectedCount() == 0);
                refreshButton.setDisable(getSelectedCount() == 0);
                exportButton.setDisable(sessionRows.isEmpty());
            }
        });

        exportButton.setDisable(true);
        refreshButton.setDisable(true);

        VBox content = new VBox(top, filterRow, center);
        VBox.setVgrow(center, Priority.ALWAYS);
        return content;
    }

    private void applyTableFilter() {
        if (filteredRows == null) return;
        String q = searchField != null && searchField.getText() != null
                ? searchField.getText().trim().toLowerCase() : "";
        String risk = riskFilter != null && riskFilter.getValue() != null ? riskFilter.getValue() : "All risks";
        filteredRows.setPredicate(row -> {
            if (row == null) return false;
            if (!"All risks".equals(risk)) {
                String rowRisk = row.getCategory().getRiskLevel().getDisplayName();
                if (!risk.equalsIgnoreCase(rowRisk)) return false;
            }
            if (!q.isEmpty()) {
                String hay = (row.getCategory().getDisplayName() + " "
                        + row.getCategory().getDescription()).toLowerCase();
                if (!hay.contains(q)) return false;
            }
            return true;
        });
    }

    private void exportScanCsv() {
        if (sessionRows.isEmpty()) return;
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Export cleanup scan");
        chooser.setInitialFileName("cleanup-scan.csv");
        chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
        java.io.File file = chooser.showSaveDialog(table.getScene() != null ? table.getScene().getWindow() : null);
        if (file == null) return;
        try (java.io.PrintWriter out = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
            out.println("Category,Description,Risk,SizeBytes,Items,Status,Selected");
            for (CleanupRow r : sessionRows) {
                out.println(csv(r.getCategory().getDisplayName()) + ","
                        + csv(r.getCategory().getDescription()) + ","
                        + csv(r.getCategory().getRiskLevel().getDisplayName()) + ","
                        + r.getTotalBytes() + "," + r.getItemCount() + ","
                        + csv(r.getScanStatus().getDisplayText()) + ","
                        + (r.isSelected() ? "yes" : "no"));
            }
            statusLabel.setText("Scan exported to " + file.getName());
        } catch (Exception e) {
            AppLogger.warning("Failed to export cleanup scan: " + e.getMessage());
            new Alert(Alert.AlertType.ERROR, "Export failed:\n" + e.getMessage()).showAndWait();
        }
    }

    private static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private void saveCleanReport(String reportText) {
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Save cleanup report");
        chooser.setInitialFileName("cleanup-report.txt");
        chooser.getExtensionFilters().addAll(
                new javafx.stage.FileChooser.ExtensionFilter("Text", "*.txt"),
                new javafx.stage.FileChooser.ExtensionFilter("All", "*.*"));
        java.io.File file = chooser.showSaveDialog(table.getScene() != null ? table.getScene().getWindow() : null);
        if (file == null) return;
        try {
            java.nio.file.Files.writeString(file.toPath(), reportText, java.nio.charset.StandardCharsets.UTF_8);
            statusLabel.setText("Report saved to " + file.getName());
        } catch (Exception e) {
            AppLogger.warning("Failed to save cleanup report: " + e.getMessage());
            new Alert(Alert.AlertType.ERROR, "Save failed:\n" + e.getMessage()).showAndWait();
        }
    }

    private void ignoreCategory(CleanupRow row) {
        try {
            settingsStore.update(current -> {
                java.util.List<String> ignored = new java.util.ArrayList<>(
                        current.ignoredCleanupCategories() != null
                                ? current.ignoredCleanupCategories() : java.util.Collections.emptyList());
                if (!ignored.contains(row.getCategory().name())) {
                    ignored.add(row.getCategory().name());
                }
                return current.toBuilder().ignoredCleanupCategories(ignored).build();
            });
            sessionRows.remove(row);
            statusLabel.setText(row.getCategory().getDisplayName() + " will be ignored in future scans.");
        } catch (Exception e) {
            AppLogger.warning("Failed to ignore cleanup category: " + e.getMessage());
            new Alert(Alert.AlertType.ERROR, "Could not ignore category:\n" + e.getMessage()).showAndWait();
        }
    }

    // Rescan state — wired to Cancel button (B5)
    private CancelableCompletableFuture<java.util.List<CleanupRow>> activeRescanFuture;
    private CancellationToken activeRescanToken;
    private volatile java.util.concurrent.CompletableFuture<?> activeRestoreFuture;

    private void cancelActive() {
        cancelling.set(true);
        try {
            if (activeScanToken != null) activeScanToken.cancel();
            if (activeCleanToken != null) activeCleanToken.cancel();
            if (activeRescanToken != null) activeRescanToken.cancel();
            if (activeScanFuture != null && !activeScanFuture.isDone()) activeScanFuture.cancel(true);
            if (activeCleanFuture != null && !activeCleanFuture.isDone()) activeCleanFuture.cancel(true);
            if (activeRescanFuture != null && !activeRescanFuture.isDone()) activeRescanFuture.cancel(true);
            if (activeRestoreFuture != null && !activeRestoreFuture.isDone()) activeRestoreFuture.cancel(true);
        } catch (Exception ignored) {}
    }

    private void buildTable() {
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
            private javafx.beans.value.ChangeListener<Boolean> selectionListener;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
                checkBox.setOnAction(e -> updateCleanButtonState());
            }
            @Override
            protected void updateItem(CleanupRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (previousItem != null) {
                        try { checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty()); } catch (Exception ignored) {}
                        if (selectionListener != null) {
                            previousItem.selectedProperty().removeListener(selectionListener);
                        }
                        previousItem = null;
                        selectionListener = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (previousItem != null && previousItem != item) {
                        try { checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty()); } catch (Exception ignored) {}
                        if (selectionListener != null) {
                            previousItem.selectedProperty().removeListener(selectionListener);
                        }
                        selectionListener = null;
                    } else if (previousItem == item) {
                        // Same item re-rendered (e.g., table refresh) — already bound, just ensure graphic
                        setGraphic(checkBox);
                        return;
                    }
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    selectionListener = (obs, oldVal, newVal) -> updateCleanButtonState();
                    item.selectedProperty().addListener(selectionListener);
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<CleanupRow, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(c -> c.getValue().categoryNameProperty());
        categoryCol.setPrefWidth(170);

        TableColumn<CleanupRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(250);
        descCol.setCellFactory(col -> new TableCell<>() {
            private final Tooltip tooltip = new Tooltip();
            {
                tooltip.setStyle("-fx-font-size: 12px;");
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    tooltip.setText(item);
                    setTooltip(tooltip);
                }
            }
        });

        TableColumn<CleanupRow, String> sizeCol = new TableColumn<>("Size / Count");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeOrCountTextProperty());
        sizeCol.setPrefWidth(140);

        TableColumn<CleanupRow, String> riskCol = new TableColumn<>("Risk");
        riskCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().getCategory().getRiskLevel().getDisplayName()));
        riskCol.setPrefWidth(70);
        riskCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    setTooltip(null);
                } else {
                    setText(item);
                    CleanupRow row = getTableRow() != null ? (CleanupRow) getTableRow().getItem() : null;
                    String tip = row != null ? row.getCategory().getRiskLevel().getDescription() : item;
                    setTooltip(new Tooltip(tip));
                    if ("High".equals(item)) {
                        setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                    } else if ("Medium".equals(item)) {
                        setStyle("-fx-text-fill: #f1fa8c;");
                    } else {
                        setStyle("-fx-text-fill: #50fa7b;");
                    }
                }
            }
        });

        TableColumn<CleanupRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusTextProperty());
        statusCol.setPrefWidth(90);

        TableColumn<CleanupRow, String> durationCol = new TableColumn<>("Took");
        durationCol.setCellValueFactory(c -> {
            long ms = c.getValue().getScanDurationMs();
            String text = ms > 0 ? (ms >= 1000 ? String.format("%.1fs", ms / 1000.0) : ms + " ms") : "-";
            return new javafx.beans.property.SimpleStringProperty(text);
        });
        durationCol.setPrefWidth(70);

        table.getColumns().addAll(checkCol, categoryCol, descCol, sizeCol, riskCol, statusCol, durationCol);

        table.setRowFactory(tv -> {
            TableRow<CleanupRow> row = new TableRow<>();
            row.setOnMouseClicked(ev -> {
                if (ev.getClickCount() == 2 && !row.isEmpty()) {
                    com.sbtools.cleaner.CleanupCategoryDetailDialog dialog =
                            new com.sbtools.cleaner.CleanupCategoryDetailDialog(row.getItem());
                    dialog.showAndWait();
                }
            });
            ContextMenu ctx = new ContextMenu();
            MenuItem detailsItem = new MenuItem("View details...");
            detailsItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    new com.sbtools.cleaner.CleanupCategoryDetailDialog(row.getItem()).showAndWait();
                }
            });
            MenuItem ignoreItem = new MenuItem("Ignore category in future scans");
            ignoreItem.setOnAction(e -> {
                if (!row.isEmpty()) ignoreCategory(row.getItem());
            });
            MenuItem copyItem = new MenuItem("Copy row");
            copyItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    CleanupRow r = row.getItem();
                    String text = r.getCategory().getDisplayName() + " | "
                            + r.sizeOrCountTextProperty().get() + " | "
                            + r.getCategory().getRiskLevel().getDisplayName();
                    javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
                    content.putString(text);
                    javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
                }
            });
            ctx.getItems().addAll(detailsItem, ignoreItem, copyItem);
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null).otherwise(ctx));
            return row;
        });
    }

    private void showPresetMenu() {
        ContextMenu menu = new ContextMenu();
        for (CleanerPresets preset : CleanerPresets.values()) {
            MenuItem item = new MenuItem(preset.getDisplayName() + " — " + preset.getDescription());
            item.setStyle("-fx-font-size: 12px;");
            item.setOnAction(e -> {
                Set<CleanupCategory> cats = preset.getCategories();
                for (CleanupRow row : sessionRows) {
                    row.setSelected(cats.contains(row.getCategory()));
                }
            });
            menu.getItems().add(item);
        }
        javafx.scene.control.SeparatorMenuItem sep = new javafx.scene.control.SeparatorMenuItem();
        menu.getItems().add(sep);
        MenuItem invertItem = new MenuItem("Invert selection");
        invertItem.setStyle("-fx-font-size: 12px;");
        invertItem.setOnAction(e -> {
            for (CleanupRow row : sessionRows) {
                row.setSelected(!row.isSelected());
            }
        });
        menu.getItems().add(invertItem);
        menu.show(presetButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void updateSummary() {
        long totalBytes = sessionRows.stream().mapToLong(CleanupRow::getTotalBytes).sum();
        int selectedCount = getSelectedCount();
        long selectedBytes = sessionRows.stream().filter(CleanupRow::isSelected).mapToLong(CleanupRow::getTotalBytes).sum();

        StringBuilder sb = new StringBuilder();
        if (hasScanned) {
            sb.append("Total: ").append(CleanupService.formatBytes(totalBytes))
                    .append(" across ").append(sessionRows.size()).append(" categories");
            if (selectedCount > 0) {
                sb.append(" | Selected: ").append(selectedCount).append(" categories (")
                        .append(CleanupService.formatBytes(selectedBytes)).append(")");
            }
            // Show main summary immediately; append all-time async to avoid FX hitch
            summaryLabel.setText(sb.toString());
            summaryLabel.setVisible(hasScanned);
            String prefix = sb.toString();
            java.util.concurrent.CompletableFuture.supplyAsync(historyStore::getTotalBytesFreedAllTime, com.sbtools.util.AppExecutors.ioPool())
                    .thenAccept(allTime -> Platform.runLater(() -> {
                        if (allTime > 0 && hasScanned) {
                            summaryLabel.setText(prefix + " | All-time freed: " + CleanupService.formatBytes(allTime));
                        }
                    }));
            return;
        }
        summaryLabel.setText(sb.toString());
        summaryLabel.setVisible(hasScanned);
    }

    private int getSelectedCount() {
        return (int) sessionRows.stream().filter(CleanupRow::isSelected).count();
    }

    private void updateCleanButtonState() {
        if (!busy.get()) {
            cleanButton.setDisable(getSelectedCount() == 0);
            refreshButton.setDisable(getSelectedCount() == 0);
        }
    }

    private void refreshSelected() {
        if (busy.get() || !hasScanned || sessionRows.isEmpty()) return;
        java.util.List<CleanupRow> selected = sessionRows.stream().filter(CleanupRow::isSelected).toList();
        if (selected.isEmpty()) return;
        busy.set(true);
        cancelling.set(false);
        statusLabel.setText("Refreshing " + selected.size() + " categories...");
        progressBar.setProgress(-1);
        progressBar.setVisible(true);
        cancelButton.setDisable(false);

        java.util.List<CleanupCategory> cats = selected.stream().map(CleanupRow::getCategory).toList();
        activeRescanToken = new CancellationToken();
        activeRescanFuture = service.scanCategoriesAsync(cats, () -> {}, activeRescanToken);
        activeRescanFuture.whenComplete((results, ex) -> Platform.runLater(() -> {
            if (ex != null) {
                if (cancelling.get() || (activeRescanFuture != null && activeRescanFuture.isCancelled())) {
                    statusLabel.setText("Refresh canceled.");
                } else {
                    statusLabel.setText("Refresh failed.");
                    new Alert(Alert.AlertType.ERROR, "Refresh failed:\n" + ex.getMessage()).showAndWait();
                }
            } else if (results != null) {
                java.util.Map<CleanupCategory, CleanupRow> map = new java.util.HashMap<>();
                for (CleanupRow rr : results) map.put(rr.getCategory(), rr);
                for (CleanupRow existing : sessionRows) {
                    CleanupRow refreshed = map.get(existing.getCategory());
                    if (refreshed != null) {
                        existing.setTotalBytes(refreshed.getTotalBytes());
                        existing.setItemCount(refreshed.getItemCount());
                        existing.setSizeOrCountText(refreshed.sizeOrCountTextProperty().get());
                        existing.setScanStatus(refreshed.getScanStatus());
                        existing.setErrorMessage(refreshed.getErrorMessage());
                        existing.setScanDurationMs(refreshed.getScanDurationMs());
                    }
                }
                long totalBytes = sessionRows.stream().mapToLong(CleanupRow::getTotalBytes).sum();
                statusLabel.setText("Refresh complete - " + CleanupService.formatBytes(totalBytes) + " identified.");
                updateSummary();
            }
            progressBar.setVisible(false);
            cancelButton.setDisable(true);
            cancelling.set(false);
            activeRescanToken = null;
            busy.set(false);
        }));
    }

    private void startScan() {
        if (busy.get()) return;
        busy.set(true);
        hasScanned = false;
        cancelling.set(false);
        statusLabel.setText("Scanning system...");
        cleanButton.setDisable(true);
        sessionRows.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(true);

        int totalCategories = CleanupCategory.values().length;
        AtomicInteger scanned = new AtomicInteger();

        activeScanToken = new CancellationToken();
        activeScanFuture = service.scanAsync(() -> {
            int done = scanned.incrementAndGet();
            Platform.runLater(() -> {
                progressBar.setProgress((double) done / totalCategories);
                statusLabel.setText("Scanning: " + done + "/" + totalCategories + "...");
            });
        }, activeScanToken);
        cancelButton.setDisable(false);

        activeScanFuture.whenComplete((results, ex) -> {
            Platform.runLater(() -> {
                if (ex != null) {
                    if (cancelling.get() || activeScanFuture.isCancelled()) {
                        statusLabel.setText("Scan canceled.");
                    } else {
                        statusLabel.setText("Scan failed.");
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    }
                    progressBar.setVisible(false);
                    cancelButton.setDisable(true);
                } else {
                    AppSettings settings = settingsStore.load();
                    List<String> ignored = settings.ignoredCleanupCategories();
                    List<CleanupRow> filtered = results.stream()
                            .filter(r -> ignored == null || !ignored.contains(r.getCategory().name()))
                            .toList();
                    sessionRows.setAll(filtered);
                    for (CleanupRow row : filtered) {
                        row.setSelected(row.getCategory().getRiskLevel() != CleanupCategory.RiskLevel.HIGH);
                    }
                    hasScanned = true;
                    long totalBytes = filtered.stream().mapToLong(CleanupRow::getTotalBytes).sum();
                    statusLabel.setText("Scan complete - " + CleanupService.formatBytes(totalBytes) + " identified.");
                    cleanButton.setDisable(getSelectedCount() == 0);
                    progressBar.setVisible(false);
                    cancelButton.setDisable(true);
                    updateSummary();
                }
                cancelling.set(false);
                activeScanToken = null;
                busy.set(false);
            });
        });
    }

    private void startClean() {
        if (busy.get()) return;
        if (!hasScanned || sessionRows.isEmpty()) return;
        java.util.List<CleanupRow> initialSelection = sessionRows.stream().filter(CleanupRow::isSelected).toList();
        if (initialSelection.isEmpty()) return;

        busy.set(true);
        cancelling.set(false);

        java.util.Set<CleanupCategory> adminRequired = new java.util.HashSet<>();
        for (com.sbtools.cleaner.CleanerExtension ext : com.sbtools.cleaner.CleanerRegistry.all()) {
            if (ext.requiresAdmin()) adminRequired.add(ext.getCategory());
        }

        boolean isAdmin = adminCheck.getAsBoolean();
        java.util.List<CleanupRow> adminBlocked;
        final java.util.List<CleanupRow> selected;
        if (!isAdmin) {
            adminBlocked = initialSelection.stream()
                    .filter(r -> adminRequired.contains(r.getCategory()))
                    .toList();
            selected = initialSelection.stream()
                    .filter(r -> !adminRequired.contains(r.getCategory()))
                    .toList();
        } else {
            adminBlocked = java.util.List.of();
            selected = initialSelection;
        }

        if (selected.isEmpty()) {
            if (!adminBlocked.isEmpty()) {
                Alert a = new Alert(Alert.AlertType.WARNING,
                        "The selected categories require administrator rights:\n\n"
                                + adminBlocked.stream().map(r -> "  - " + r.getCategory().getDisplayName())
                                .collect(java.util.stream.Collectors.joining("\n"))
                                + "\n\nPlease run as administrator to clean these categories.");
                a.setHeaderText("Administrator Rights Required");
                a.showAndWait();
            }
            busy.set(false);
            return;
        }

        if (!isAdmin && !adminBlocked.isEmpty()) {
            Alert a = new Alert(Alert.AlertType.INFORMATION,
                    "Proceeding with " + selected.size() + " non-admin categories.\n\n"
                            + "The following categories require administrator rights and will be skipped:\n"
                            + adminBlocked.stream().map(r -> "  - " + r.getCategory().getDisplayName())
                            .collect(java.util.stream.Collectors.joining("\n")));
            a.setHeaderText("Some Categories Skipped");
            a.showAndWait();
        }

        boolean registrySelected = selected.stream()
                .anyMatch(r -> r.getCategory() == CleanupCategory.REGISTRY);

        boolean hasHighRisk = selected.stream()
                .anyMatch(r -> r.getCategory().getRiskLevel() == CleanupCategory.RiskLevel.HIGH);

        StringBuilder dialogMsg = new StringBuilder();
        if (WindowsVersionUtil.isNewerThanKnownSafeBuild()) {
            dialogMsg.append("WARNING: Running on a newer Windows version (")
                    .append(WindowsVersionUtil.getWindowsVersionString())
                    .append("). Some cleanup operations will be skipped for safety.\n\n");
        }
        String riskLabel = hasHighRisk ? " (includes HIGH-risk categories)" : "";
        dialogMsg.append("Do you confirm the cleanup of ").append(selected.size()).append(" categories")
                .append(riskLabel).append("?\n\n");
        for (CleanupRow r : selected) {
            String prefix = r.getCategory().getRiskLevel() == CleanupCategory.RiskLevel.HIGH ? "  [!] " : "  - ";
            dialogMsg.append(prefix).append(r.getCategory().getDisplayName())
                    .append(" (").append(r.getCategory().getRiskLevel().getDisplayName()).append(")\n");
        }
        if (hasHighRisk) {
            dialogMsg.append("\nHIGH-RISK CATEGORIES:\n");
            for (CleanupRow r : selected) {
                if (r.getCategory().getRiskLevel() == CleanupCategory.RiskLevel.HIGH) {
                    dialogMsg.append("  - ").append(r.getCategory().getDisplayName())
                            .append(": ").append(r.getCategory().getDescription()).append("\n");
                }
            }
        }
        dialogMsg.append("\nThis action cannot be undone.");

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION, null,
                ButtonType.OK, ButtonType.CANCEL);
        Label msgLabel = new Label(dialogMsg.toString());
        msgLabel.setWrapText(true);
        msgLabel.setMaxWidth(380);
        ScrollPane scrollPane = new ScrollPane(msgLabel);
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefHeight(Math.min(
                javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.6, 450));
        scrollPane.setMinHeight(100);
        confirmAlert.getDialogPane().setContent(scrollPane);
        confirmAlert.setHeaderText("Confirm Cleanup");
        confirmAlert.getDialogPane().setMinWidth(400);
        confirmAlert.getDialogPane().setMaxHeight(Math.min(
                javafx.stage.Screen.getPrimary().getVisualBounds().getHeight() * 0.75, 600));
        if (confirmAlert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
            busy.set(false);
            return;
        }

        // Second explicit confirmation for irreversible user-data destruction
        // (iOS backups, Docker images/containers, previous Windows install).
        java.util.List<CleanupRow> destructive = selected.stream()
                .filter(r -> r.getCategory() == CleanupCategory.ITUNES_BACKUPS
                        || r.getCategory() == CleanupCategory.DOCKER_CACHE
                        || r.getCategory() == CleanupCategory.OLD_WINDOWS_INSTALL)
                .toList();
        if (!destructive.isEmpty()) {
            Alert destructiveAlert = new Alert(Alert.AlertType.WARNING,
                    "You selected categories that PERMANENTLY delete user data or system rollback state:\n\n"
                            + destructive.stream()
                                    .map(r -> "  [!] " + r.getCategory().getDisplayName() + " — " + r.getCategory().getDescription())
                                    .collect(java.util.stream.Collectors.joining("\n"))
                            + "\n\niTunes backups cannot be recovered. Docker prune deletes unused images/containers. "
                            + "Removing Windows.old prevents rollback to the previous Windows version.\n\n"
                            + "Type-understanding: click OK only if you have independent backups.",
                    ButtonType.OK, ButtonType.CANCEL);
            destructiveAlert.setHeaderText("Irreversible Deletion — Confirm Again");
            if (destructiveAlert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
                busy.set(false);
                return;
            }
        }

        boolean registryBackupRaw = false;
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
            if (result == ButtonType.CANCEL) {
                busy.set(false);
                return;
            }
            registryBackupRaw = result == yesBtn;
        }
        final boolean registryBackup = registryBackupRaw;

        AppSettings settings = settingsStore.load();
        final boolean createRestorePoint = settings.autoCreateRestoreBeforeCleanup();

        Runnable doClean = () -> {
            statusLabel.setText("Cleaning...");
            cleanButton.setDisable(true);
            progressBar.setProgress(0);
            progressBar.setVisible(true);

            int totalCategories = selected.size();
            AtomicInteger cleaned = new AtomicInteger();

            activeCleanToken = new CancellationToken();
            activeCleanFuture = service.cleanAsync(selected, registryBackup, () -> {
                int done = cleaned.incrementAndGet();
                Platform.runLater(() -> {
                    progressBar.setProgress((double) done / totalCategories);
                    statusLabel.setText("Cleaning: " + done + "/" + totalCategories + "...");
                });
            }, activeCleanToken);
            cancelButton.setDisable(false);

            activeCleanFuture.whenComplete((summary, ex) -> {
                if (ex != null) {
                    Platform.runLater(() -> {
                        if (cancelling.get() || activeCleanFuture.isCancelled()) {
                            statusLabel.setText("Cleanup canceled.");
                        } else {
                            statusLabel.setText("Cleanup failed.");
                            new Alert(Alert.AlertType.ERROR, "Cleanup failed:\n" + ex.getMessage()).showAndWait();
                        }
                        progressBar.setVisible(false);
                        cancelButton.setDisable(true);
                        busy.set(false);
                    });
                } else {
                    boolean wasCanceled = cancelling.get() || (activeCleanToken != null && activeCleanToken.isCancelled());
                    // Only append history for meaningful successful cleans (not canceled).
                    // Item-only categories (registry entries, empty folders) report 0 bytes
                    // with >0 items, so include them via totalItems.
                    boolean meaningful = summary.getTotalBytes() > 0 || summary.getTotalItems() > 0;
                    if (!wasCanceled && meaningful) {
                        try { historyStore.append(summary); } catch (Exception e) { AppLogger.warning("Failed to append history: " + e.getMessage()); }
                    } else if (!wasCanceled && summary.hasErrors() && !meaningful) {
                        // Don't pollute history with failed zero-byte sessions
                        AppLogger.info("Skipping history append for failed/zero-byte clean");
                    }

                    Platform.runLater(() -> {
                        statusLabel.setText("Re-scanning cleaned categories...");
                        progressBar.setProgress(-1);
                    });

                    java.util.List<CleanupCategory> cleanedCategories = selected.stream()
                            .map(CleanupRow::getCategory).toList();

                    activeRescanToken = new CancellationToken();
                    activeRescanFuture = service.scanCategoriesAsync(cleanedCategories, () -> {}, activeRescanToken);

                    activeRescanFuture.whenComplete((rescanResults, rescanEx) -> {
                        Platform.runLater(() -> {
                            if (rescanEx == null && rescanResults != null) {
                                java.util.Map<CleanupCategory, CleanupRow> rescanMap = new java.util.HashMap<>();
                                for (CleanupRow rr : rescanResults) {
                                    rescanMap.put(rr.getCategory(), rr);
                                }
                                for (int i = 0; i < sessionRows.size(); i++) {
                                    CleanupRow existing = sessionRows.get(i);
                                    CleanupRow refreshed = rescanMap.get(existing.getCategory());
                                    if (refreshed != null) {
                                        existing.setTotalBytes(refreshed.getTotalBytes());
                                        existing.setItemCount(refreshed.getItemCount());
                                        existing.setSizeOrCountText(refreshed.sizeOrCountTextProperty().get());
                                        existing.setScanStatus(refreshed.getScanStatus());
                                        existing.setErrorMessage(refreshed.getErrorMessage());
                                        existing.setScanDurationMs(refreshed.getScanDurationMs());
                                    }
                                }
                            } else if (rescanEx != null) {
                                if (activeRescanFuture != null && activeRescanFuture.isCancelled() || (activeRescanToken != null && activeRescanToken.isCancelled())) {
                                    statusLabel.setText("Cleanup completed - rescan canceled");
                                    AppLogger.info("Rescan canceled: " + rescanEx.getMessage());
                                } else {
                                    statusLabel.setText("Cleanup completed - rescan failed: " + rescanEx.getMessage());
                                    AppLogger.warning("Rescan failed: " + rescanEx.getMessage());
                                }
                                // Don't update rows on rescan failure — keep previous scanned values
                            }

                            StringBuilder sb = new StringBuilder();
                            if (wasCanceled) sb.append("Cleanup canceled.\n\n");
                            else sb.append("Cleanup completed.\n\n");
                            sb.append("Total freed: ").append(CleanupService.formatBytes(summary.getTotalBytes()));
                            sb.append(" (").append(summary.getTotalItems()).append(" items)\n");
                            if (!summary.getPerCategory().isEmpty()) {
                                sb.append("\nPer-category breakdown:\n");
                                summary.getPerCategory().forEach((cat, bytes) ->
                                        sb.append("  - ").append(cat.getDisplayName()).append(": ")
                                                .append(CleanupService.formatBytes(bytes)).append("\n"));
                            }
                            if (summary.hasErrors()) {
                                sb.append("\nErrors encountered:\n");
                                summary.getErrors().forEach(err ->
                                        sb.append("  - ").append(err).append("\n"));
                            }
                            if (rescanEx != null && !(rescanEx instanceof java.util.concurrent.CancellationException)) {
                                sb.append("\nNote: Post-clean rescan failed (").append(rescanEx.getMessage()).append(") — table may show stale sizes. Click Scan to refresh.\n");
                            }
                            if (!wasCanceled) statusLabel.setText("Cleanup completed - " + CleanupService.formatBytes(summary.getTotalBytes()) + " freed.");
                            else statusLabel.setText("Cleanup canceled - " + CleanupService.formatBytes(summary.getTotalBytes()) + " freed before cancel.");
                            progressBar.setVisible(false);
                            cancelButton.setDisable(true);
                            updateSummary();

                            Alert resultAlert = new Alert(wasCanceled ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION, sb.toString());
                            resultAlert.setHeaderText(wasCanceled ? "Cleanup Canceled" : "Cleanup Results");
                            ButtonType saveReportBtn = new ButtonType("Save report...");
                            resultAlert.getButtonTypes().add(saveReportBtn);
                            var chosen = resultAlert.showAndWait().orElse(ButtonType.OK);
                            if (chosen == saveReportBtn) {
                                saveCleanReport(sb.toString());
                            }

                            cancelling.set(false);
                            activeCleanToken = null;
                            activeRescanToken = null;
                            activeRescanFuture = null;
                            busy.set(false);
                        });
                    });
                }
            });
        };

        if (createRestorePoint) {
            statusLabel.setText("Creating System Restore point...");
            progressBar.setProgress(-1);
            progressBar.setVisible(true);
            cancelButton.setDisable(false);

            activeRestoreFuture = CompletableFuture.runAsync(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                            "Checkpoint-Computer -Description 'WinZenith Cleanup Pre-Clean' -RestorePointType MODIFY_SETTINGS");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = false;
                    long deadline = System.currentTimeMillis() + 120_000L;
                    while (System.currentTimeMillis() < deadline) {
                        if (cancelling.get()) {
                            p.destroyForcibly();
                            throw new java.util.concurrent.CancellationException("Restore point canceled");
                        }
                        try {
                            if (p.waitFor(1, java.util.concurrent.TimeUnit.SECONDS)) { finished = true; break; }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            p.destroyForcibly();
                            throw new java.util.concurrent.CancellationException("Restore point canceled");
                        }
                    }
                    if (cancelling.get()) {
                        p.destroyForcibly();
                        throw new java.util.concurrent.CancellationException("Restore point canceled");
                    }
                    if (!finished) {
                        p.destroyForcibly();
                        AppLogger.warning("System Restore point creation timed out");
                    } else if (p.exitValue() != 0) {
                        String err = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        AppLogger.warning("System Restore point creation failed: " + err.trim());
                        Platform.runLater(() -> {
                            Alert alert = new Alert(Alert.AlertType.INFORMATION,
                                    "Could not create a System Restore point.\n\n"
                                            + "System Protection may be disabled on this drive.\n"
                                            + "Cleanup will continue without a restore point.\n\n"
                                            + "You can enable System Protection in System Properties > System Protection.");
                            alert.setHeaderText("Restore Point Unavailable");
                            alert.showAndWait();
                        });
                    }
                } catch (java.util.concurrent.CancellationException ce) {
                    AppLogger.info("System Restore point creation canceled by user");
                    throw ce;
                } catch (Exception e) {
                    AppLogger.warning("Failed to create System Restore point: " + e.getMessage());
                }
            });
            activeRestoreFuture.whenComplete((v, ex) -> Platform.runLater(() -> {
                activeRestoreFuture = null;
                if (ex != null || cancelling.get()) {
                    statusLabel.setText("Cleanup canceled before start.");
                    progressBar.setVisible(false);
                    cancelButton.setDisable(true);
                    cancelling.set(false);
                    busy.set(false);
                    return;
                }
                doClean.run();
            }));
        } else {
            doClean.run();
        }
    }

    public void dispose() {
        cancelActive();
    }
}
