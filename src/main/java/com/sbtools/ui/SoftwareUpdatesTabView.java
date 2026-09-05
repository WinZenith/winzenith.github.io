package com.sbtools.ui;

import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateViewModel;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppPaths;
import com.sbtools.util.VersionCompare;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;

public class SoftwareUpdatesTabView extends BorderPane {

    private final SoftwareUpdateViewModel viewModel;
    private final BooleanProperty busy;

    private final ProgressIndicator progress = new ProgressIndicator();
    private final ProgressBar batchProgressBar = new ProgressBar(0);
    private final Label batchProgressLabel = new Label();
    private final Button scanButton = new Button("Scan");
    private final Button stopScanButton = new Button("Stop scan");
    private final Button updateSelectedButton = new Button("Update Selected");
    private final Button selectAllButton = new Button("Select All");
    private final Button deselectAllButton = new Button("Deselect All");
    private final Button retryFailedButton = new Button("Retry Failed");
    private final Button ignoredListButton = new Button("Ignored List");
    private final Button historyButton = new Button("History");
    private final Label statusLabel = new Label();

    // For leak-free dispose
    private javafx.collections.ListChangeListener<SoftwareUpdateEntry> rowsListener;
    private java.util.Map<SoftwareUpdateEntry, javafx.beans.value.ChangeListener<Boolean>> selectedListeners;
    private javafx.beans.value.ChangeListener<Boolean> refreshListener;
    private TableView<SoftwareUpdateEntry> tableRef;
    private javafx.collections.ListChangeListener<SoftwareUpdateEntry> refreshRowsListener;
    private javafx.beans.value.ChangeListener<Boolean> busyListener;
    private javafx.beans.value.ChangeListener<Boolean> batchProgressListener;
    private javafx.beans.value.ChangeListener<Boolean> retryFailedListener;

    // In-memory (session-only) filters — deliberately not persisted to AppSettings
    private final TextField searchField = new TextField();
    private final ComboBox<String> sourceFilter = new ComboBox<>();
    private final CheckBox failedOnlyCheck = new CheckBox("Failed only");
    private final Label filterCountLabel = new Label();
    private FilteredList<SoftwareUpdateEntry> filteredRows;
    private SortedList<SoftwareUpdateEntry> sortedRows;
    private javafx.collections.ListChangeListener<SoftwareUpdateEntry> filterCountListener;

    public SoftwareUpdatesTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.viewModel = new SoftwareUpdateViewModel(busy, adminCheck::getAsBoolean);

        progress.setVisible(false);
        progress.setMaxSize(24, 24);
        batchProgressBar.setVisible(false);
        batchProgressBar.setPrefWidth(150);
        batchProgressLabel.setVisible(false);
        retryFailedButton.setVisible(false);
        retryFailedButton.setDisable(true);

        bindViewModel();
        wireButtons();

        HBox top = new HBox(12, scanButton, stopScanButton, updateSelectedButton,
                selectAllButton, deselectAllButton, retryFailedButton,
                ignoredListButton, historyButton, progress, batchProgressBar, batchProgressLabel, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        // Session-only filter bar (search + source + failed-only). Not persisted.
        searchField.setPromptText("Filter by name or ID...");
        searchField.setPrefWidth(220);
        sourceFilter.getItems().setAll("All sources", "winget", "WindowsUpdate");
        sourceFilter.setValue("All sources");
        sourceFilter.setPrefWidth(130);
        filterCountLabel.setStyle("-fx-opacity: 0.75;");
        HBox filterBar = new HBox(10, new Label("Search:"), searchField,
                new Label("Source:"), sourceFilter, failedOnlyCheck, filterCountLabel);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(0, 16, 8, 16));
        VBox topBox = new VBox(top, filterBar);

        filteredRows = new FilteredList<>(viewModel.getRows(), p -> true);
        sortedRows = new SortedList<>(filteredRows);
        searchField.textProperty().addListener((obs, o, n) -> updateFilterPredicate());
        sourceFilter.valueProperty().addListener((obs, o, n) -> updateFilterPredicate());
        failedOnlyCheck.selectedProperty().addListener((obs, o, n) -> updateFilterPredicate());

        TableView<SoftwareUpdateEntry> table = buildTable(sortedRows);
        sortedRows.comparatorProperty().bind(table.comparatorProperty());
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(topBox);
        setCenter(table);

        filterCountListener = ch -> updateFilterCount();
        viewModel.getRows().addListener(filterCountListener);
        filteredRows.addListener((ListChangeListener<SoftwareUpdateEntry>) ch -> updateFilterCount());
        updateFilterPredicate();

        // Track per-entry selected listeners so we can remove on dispose / removal (fix leak B7)
        java.util.Map<SoftwareUpdateEntry, javafx.beans.value.ChangeListener<Boolean>> selectedListeners = new java.util.HashMap<>();
        javafx.collections.ListChangeListener<SoftwareUpdateEntry> rowsListener = c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (SoftwareUpdateEntry entry : c.getAddedSubList()) {
                        javafx.beans.value.ChangeListener<Boolean> l = (obs, oldVal, newVal) -> updateInstallButtonState();
                        entry.selectedProperty().addListener(l);
                        selectedListeners.put(entry, l);
                    }
                }
                if (c.wasRemoved()) {
                    for (SoftwareUpdateEntry entry : c.getRemoved()) {
                        javafx.beans.value.ChangeListener<Boolean> l = selectedListeners.remove(entry);
                        if (l != null) try { entry.selectedProperty().removeListener(l); } catch (Exception ignored) {}
                    }
                }
            }
            updateInstallButtonState();
        };
        viewModel.getRows().addListener(rowsListener);
        // Attach for existing rows (if any)
        for (SoftwareUpdateEntry e : viewModel.getRows()) {
            javafx.beans.value.ChangeListener<Boolean> l = (obs, o, n) -> updateInstallButtonState();
            e.selectedProperty().addListener(l);
            selectedListeners.put(e, l);
        }
        // Store for dispose cleanup
        this.rowsListener = rowsListener;
        this.selectedListeners = selectedListeners;

        // Refresh table when either global or local busy changes (progress/status bindings)
        javafx.beans.value.ChangeListener<Boolean> refreshListener = (obs, oldVal, newVal) -> table.refresh();
        busy.addListener(refreshListener);
        viewModel.busyProperty().addListener(refreshListener);
        this.refreshListener = refreshListener;
        this.tableRef = table;
        // Single listener for row changes to refresh (merged to avoid duplicate)
        javafx.collections.ListChangeListener<SoftwareUpdateEntry> refreshRowsListener = ch -> table.refresh();
        viewModel.getRows().addListener(refreshRowsListener);
        this.refreshRowsListener = refreshRowsListener;

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            updateSelectedButton.setDisable(true);
            statusLabel.setText("This application requires Windows.");
        }
    }

    private void bindViewModel() {
        statusLabel.textProperty().bind(viewModel.statusTextProperty());
        batchProgressBar.progressProperty().bind(viewModel.batchProgressProperty());
        batchProgressLabel.textProperty().bind(viewModel.batchProgressTextProperty());

        batchProgressListener = (obs, oldVal, newVal) -> {
            batchProgressBar.setVisible(Boolean.TRUE.equals(newVal));
            batchProgressLabel.setVisible(Boolean.TRUE.equals(newVal));
        };
        viewModel.showBatchProgressProperty().addListener(batchProgressListener);
        // Sync initial
        batchProgressListener.changed(null, null, viewModel.showBatchProgressProperty().get());

        retryFailedListener = (obs, oldVal, newVal) -> {
            retryFailedButton.setVisible(Boolean.TRUE.equals(newVal));
            retryFailedButton.setDisable(!Boolean.TRUE.equals(newVal));
        };
        viewModel.showRetryFailedProperty().addListener(retryFailedListener);
        retryFailedListener.changed(null, null, viewModel.showRetryFailedProperty().get());

        viewModel.setOnWingetNotAvailable(this::showWingetNotAvailableDialog);
    }

    private void wireButtons() {
        scanButton.setOnAction(e -> viewModel.scan());
        stopScanButton.setOnAction(e -> {
            if (viewModel.isInstallRunning()) {
                viewModel.cancelInstall();
            } else {
                viewModel.stopScan();
            }
        });
        stopScanButton.setDisable(true);

        updateSelectedButton.setOnAction(e -> updateSelected());
        updateSelectedButton.setDisable(true);

        selectAllButton.setDisable(true);
        // Select/Deselect operate on the currently visible (filtered) rows — intuitive with search active.
        selectAllButton.setOnAction(e -> {
            java.util.List<SoftwareUpdateEntry> visible = tableRef != null ? tableRef.getItems() : viewModel.getRows();
            visible.forEach(r -> r.setSelected(true));
        });

        deselectAllButton.setDisable(true);
        deselectAllButton.setOnAction(e -> {
            java.util.List<SoftwareUpdateEntry> visible = tableRef != null ? tableRef.getItems() : viewModel.getRows();
            visible.forEach(r -> r.setSelected(false));
        });

        retryFailedButton.setOnAction(e -> viewModel.retryFailed());

        ignoredListButton.setOnAction(e -> showIgnoredListDialog());
        historyButton.setOnAction(e -> showHistoryDialog());

        // Handle both global and local busy – disable scan when either is busy; enable stop when local busy
        busyListener = (obs, o, n) -> {
            boolean isBusy = viewModel.busyProperty().get() || busy.get();
            boolean localBusy = viewModel.busyProperty().get();
            scanButton.setDisable(isBusy);
            // Stop enabled only when local software tab is busy (scan or install)
            stopScanButton.setDisable(!localBusy);
            updateSelectedButton.setDisable(isBusy || viewModel.getRows().stream().noneMatch(r -> r.selectedProperty().get()));
            selectAllButton.setDisable(isBusy || viewModel.getRows().isEmpty());
            deselectAllButton.setDisable(isBusy || viewModel.getRows().isEmpty());
        };
        busy.addListener(busyListener);
        viewModel.busyProperty().addListener(busyListener);
        // Initial sync
        busyListener.changed(null, false, busy.get());
    }

    private TableView<SoftwareUpdateEntry> buildTable(javafx.collections.ObservableList<SoftwareUpdateEntry> items) {
        TableView<SoftwareUpdateEntry> table = new TableView<>(items);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No updates to show. Press Scan to check for app and Windows updates."));

        TableColumn<SoftwareUpdateEntry, Boolean> selCol = new TableColumn<>("Install");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setPrefWidth(60);
        selCol.setSortable(false);

        TableColumn<SoftwareUpdateEntry, String> nameCol = new TableColumn<>("Program");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setComparator(String::compareToIgnoreCase);

        TableColumn<SoftwareUpdateEntry, String> currentCol = new TableColumn<>("Current Version");
        currentCol.setCellValueFactory(c -> c.getValue().currentVersionProperty());
        currentCol.setPrefWidth(120);
        currentCol.setComparator(VersionCompare::compare);

        TableColumn<SoftwareUpdateEntry, String> availCol = new TableColumn<>("Available Version");
        availCol.setCellValueFactory(c -> c.getValue().availableVersionProperty());
        availCol.setPrefWidth(120);
        availCol.setComparator(VersionCompare::compare);

        TableColumn<SoftwareUpdateEntry, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(100);
        sourceCol.setComparator(String::compareToIgnoreCase);

        TableColumn<SoftwareUpdateEntry, SoftwareUpdateEntry> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue()));
        sizeCol.setPrefWidth(80);
        sizeCol.setComparator(java.util.Comparator.comparingLong(SoftwareUpdateEntry::sizeBytes));
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(SoftwareUpdateEntry item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : formatBytes(item.sizeBytes()));
            }
        });

        TableColumn<SoftwareUpdateEntry, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().statusProperty());
        statusCol.setPrefWidth(100);
        statusCol.setSortable(false);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null || value.isBlank()) {
                    setText(null);
                    setStyle(null);
                } else if ("Failed".equals(value)) {
                    setText(value);
                    setStyle("-fx-text-fill: #ff5555;");
                } else if (value.startsWith("Installing")) {
                    setText(value);
                    setStyle("-fx-text-fill: #ffb86c;");
                } else {
                    setText(value);
                    setStyle("-fx-text-fill: #50fa7b;");
                }
            }
        });

        TableColumn<SoftwareUpdateEntry, Void> actionCol = new TableColumn<>("Action");
        actionCol.setSortable(false);
        actionCol.setPrefWidth(350);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton updateBtn = UIButton.small("Update");
            private final UIButton ignoreBtn = UIButton.small("Ignore");
            private final ProgressBar downloadProgress = new ProgressBar(ProgressBar.INDETERMINATE_PROGRESS);
            private final Label sizeLabel = new Label("Installing...");
            private final Label installingLabel = new Label("Installing update. Please wait...");
            private final ProgressIndicator spinner = new ProgressIndicator();
            private SoftwareUpdateEntry boundEntry = null;
            private javafx.beans.value.ChangeListener<String> statusListener = null;

            {
                spinner.setPrefSize(48, 48);
                spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                spinner.setVisible(false);
                installingLabel.setVisible(false);
                downloadProgress.setPrefWidth(80);
                downloadProgress.setVisible(false);
                sizeLabel.setVisible(false);

                updateBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getEntrySafely();
                    if (entry != null) viewModel.updateSingle(entry);
                });

                ignoreBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getEntrySafely();
                    if (entry != null) viewModel.skipEntry(entry);
                });
            }

            private SoftwareUpdateEntry getEntrySafely() {
                // Use TableRow item when available; fallback to index bounds-checked access
                TableRow<SoftwareUpdateEntry> row = getTableRow();
                if (row != null && row.getItem() != null) return row.getItem();
                int idx = getIndex();
                TableView<SoftwareUpdateEntry> tv = getTableView();
                if (tv == null || tv.getItems() == null) return null;
                if (idx < 0 || idx >= tv.getItems().size()) return null;
                try { return tv.getItems().get(idx); } catch (IndexOutOfBoundsException ex) { return null; }
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    unbindEntry();
                    setGraphic(null);
                    return;
                }

                SoftwareUpdateEntry entry = getEntrySafely();
                // Guard against virtual flow recycling where getIndex() may be stale
                if (entry == null) {
                    unbindEntry();
                    setGraphic(null);
                    return;
                }
                boolean isBusy = viewModel.busyProperty().get() || busy.get();
                boolean disabled = isBusy;
                updateBtn.setDisable(disabled);
                ignoreBtn.setDisable(disabled);

                unbindEntry();

                boundEntry = entry;
                try {
                    downloadProgress.progressProperty().bind(entry.progressProperty());
                } catch (Exception ignored) {}
                installingLabel.textProperty().bind(entry.statusProperty());
                statusListener = (obs, oldVal, newVal) -> Platform.runLater(() -> {
                    boolean show = newVal != null && !newVal.isBlank();
                    downloadProgress.setVisible(show);
                    installingLabel.setVisible(show);
                    sizeLabel.setVisible(show);
                });
                entry.statusProperty().addListener(statusListener);
                boolean showNow = entry.getStatus() != null && !entry.getStatus().isBlank();
                downloadProgress.setVisible(showNow);
                installingLabel.setVisible(showNow);
                sizeLabel.setVisible(showNow);

                HBox container = new HBox(6, updateBtn, ignoreBtn, sizeLabel, downloadProgress, installingLabel, spinner);
                container.setAlignment(Pos.CENTER_LEFT);
                setGraphic(container);
            }

            private void unbindEntry() {
                if (boundEntry != null) {
                    try { downloadProgress.progressProperty().unbind(); } catch (Exception ignored) {}
                    try { installingLabel.textProperty().unbind(); } catch (Exception ignored) {}
                    if (statusListener != null) {
                        try { boundEntry.statusProperty().removeListener(statusListener); } catch (Exception ignored) {}
                        statusListener = null;
                    }
                    boundEntry = null;
                }
            }
        });

        // Size before Status: numeric info groups with versions, status stays near Action.
        table.getColumns().addAll(selCol, nameCol, currentCol, availCol, sourceCol, sizeCol, statusCol, actionCol);

        table.setRowFactory(tv -> {
            TableRow<SoftwareUpdateEntry> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty() && row.getItem() != null
                        && "Failed".equals(row.getItem().getStatus())) {
                    showErrorDetailsDialog(row.getItem());
                    return;
                }
                if (!row.isEmpty() && !viewModel.busyProperty().get() && !busy.get()) {
                    SoftwareUpdateEntry entry = row.getItem();
                    if (entry != null) entry.selectedProperty().set(!entry.selectedProperty().get());
                }
            });
            // Tooltip shows identifiers without extra winget calls (portable, offline-safe).
            row.itemProperty().addListener((obs, oldItem, newItem) -> {
                if (newItem == null) {
                    row.setTooltip(null);
                    row.setContextMenu(null);
                } else {
                    String tip = newItem.getName() + "\nID: " + newItem.id()
                            + ("WindowsUpdate".equals(newItem.source()) && newItem.updateId() != null
                                    ? "\nUpdateID: " + newItem.updateId() : "")
                            + "\nSource: " + newItem.source()
                            + (newItem.sizeBytes() > 0 ? "\nSize: " + formatBytes(newItem.sizeBytes())
                                    : "\nSize: unknown until download");
                    row.setTooltip(new Tooltip(tip));
                    MenuItem copyId = new MenuItem("Copy ID");
                    copyId.setOnAction(e -> copyToClipboard(newItem.id()));
                    MenuItem showError = new MenuItem("Show error details...");
                    showError.setOnAction(e -> showErrorDetailsDialog(newItem));
                    row.setContextMenu(new ContextMenu(copyId, showError));
                }
            });
            return row;
        });

        return table;
    }

    private void updateFilterPredicate() {
        if (filteredRows == null) return;
        String q = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase();
        String src = sourceFilter.getValue();
        boolean failedOnly = failedOnlyCheck.isSelected();
        filteredRows.setPredicate(e -> {
            if (e == null) return false;
            if (failedOnly && !"Failed".equals(e.getStatus())) return false;
            if (src != null && !"All sources".equals(src) && !src.equals(e.source())) return false;
            if (!q.isEmpty()) {
                String name = e.getName() == null ? "" : e.getName().toLowerCase();
                String id = e.id() == null ? "" : e.id().toLowerCase();
                if (!name.contains(q) && !id.contains(q)) return false;
            }
            return true;
        });
        updateFilterCount();
    }

    private void updateFilterCount() {
        if (filterCountLabel == null || filteredRows == null) return;
        int shown = filteredRows.size();
        int total = viewModel.getRows().size();
        filterCountLabel.setText(shown == total
                ? total + " item(s)"
                : "Showing " + shown + " of " + total);
    }

    private static void copyToClipboard(String text) {
        try {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(content);
        } catch (Exception ignored) {}
    }

    private void showErrorDetailsDialog(SoftwareUpdateEntry entry) {
        if (entry == null) return;
        String status = entry.getStatus() == null ? "" : entry.getStatus();
        String err = entry.getLastError() == null ? "" : entry.getLastError();
        TextArea ta = new TextArea(status + (err.isBlank() ? "" : "\n\n" + err));
        ta.setEditable(false);
        ta.setWrapText(true);
        ta.setPrefRowCount(10);
        ta.setPrefColumnCount(70);
        Button copyBtn = new Button("Copy");
        copyBtn.setOnAction(e -> copyToClipboard(ta.getText()));
        HBox btnBox = new HBox(8, copyBtn);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        VBox content = new VBox(8,
                new Label("Details for " + entry.getName() + " (" + entry.id() + "):"), ta, btnBox);
        Alert a = new Alert(Alert.AlertType.INFORMATION);
        a.setTitle(AppInfo.DISPLAY_NAME);
        a.setHeaderText("Update details — " + entry.getName());
        a.getDialogPane().setContent(content);
        a.showAndWait();
    }

    private void updateInstallButtonState() {
        boolean any = viewModel.getRows().stream().anyMatch(r -> r.selectedProperty().get());
        boolean hasRows = !viewModel.getRows().isEmpty();
        boolean isBusy = viewModel.busyProperty().get() || busy.get();
        updateSelectedButton.setDisable(!any || isBusy);
        selectAllButton.setDisable(!hasRows || isBusy);
        deselectAllButton.setDisable(!hasRows || isBusy);
    }

    private void updateSelected() {
        List<SoftwareUpdateEntry> selected = viewModel.getRows().stream()
                .filter(r -> r.selectedProperty().get())
                .collect(Collectors.toList());
        viewModel.updateSelected(selected);
    }

    private void showWingetNotAvailableDialog(String diagnostics) {
        statusLabel.setText("winget not found. Checking Windows Update...");
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
    }

    private void showIgnoredListDialog() {
        SettingsStore settingsStore = new SettingsStore();
        AppSettings current = settingsStore.load();
        IgnoredListDialog.show("Ignored Software Updates", current.skippedSoftwareIds(), (updated, ignored) -> {
            try {
                AppSettings curr = settingsStore.load();
                settingsStore.save(curr.toBuilder().skippedSoftwareIds(updated).build());
            } catch (Exception ex) {
                com.sbtools.util.AppLogger.warning("Failed to update ignored list: " + ex.getMessage());
            }
        });
    }

    private void showHistoryDialog() {
        SoftwareUpdateHistoryDialog.show();
    }

    public void dispose() {
        // Unbind and remove listeners to prevent leak and phantom updates (B7)
        try { statusLabel.textProperty().unbind(); } catch (Exception ignored) {}
        try { batchProgressBar.progressProperty().unbind(); } catch (Exception ignored) {}
        try { batchProgressLabel.textProperty().unbind(); } catch (Exception ignored) {}
        if (batchProgressListener != null) {
            try { viewModel.showBatchProgressProperty().removeListener(batchProgressListener); } catch (Exception ignored) {}
        }
        if (retryFailedListener != null) {
            try { viewModel.showRetryFailedProperty().removeListener(retryFailedListener); } catch (Exception ignored) {}
        }
        if (busyListener != null) {
            try { busy.removeListener(busyListener); } catch (Exception ignored) {}
            try { viewModel.busyProperty().removeListener(busyListener); } catch (Exception ignored) {}
        }
        if (refreshListener != null && tableRef != null) {
            try { busy.removeListener(refreshListener); } catch (Exception ignored) {}
            try { viewModel.busyProperty().removeListener(refreshListener); } catch (Exception ignored) {}
        }
        if (rowsListener != null) {
            try { viewModel.getRows().removeListener(rowsListener); } catch (Exception ignored) {}
        }
        if (refreshRowsListener != null) {
            try { viewModel.getRows().removeListener(refreshRowsListener); } catch (Exception ignored) {}
        }
        if (filterCountListener != null) {
            try { viewModel.getRows().removeListener(filterCountListener); } catch (Exception ignored) {}
        }
        if (sortedRows != null) {
            try { sortedRows.comparatorProperty().unbind(); } catch (Exception ignored) {}
        }
        if (selectedListeners != null) {
            for (var e : new java.util.ArrayList<>(selectedListeners.entrySet())) {
                try { e.getKey().selectedProperty().removeListener(e.getValue()); } catch (Exception ignored) {}
            }
            selectedListeners.clear();
        }
        viewModel.dispose();
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
