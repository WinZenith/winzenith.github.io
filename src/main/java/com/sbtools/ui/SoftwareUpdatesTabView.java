package com.sbtools.ui;

import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateViewModel;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppPaths;
import com.sbtools.util.VersionCompare;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
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

        TableView<SoftwareUpdateEntry> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(top);
        setCenter(table);

        viewModel.getRows().addListener((ListChangeListener<SoftwareUpdateEntry>) c -> {
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

    private void bindViewModel() {
        statusLabel.textProperty().bind(viewModel.statusTextProperty());
        batchProgressBar.progressProperty().bind(viewModel.batchProgressProperty());
        batchProgressLabel.textProperty().bind(viewModel.batchProgressTextProperty());

        viewModel.showBatchProgressProperty().addListener((obs, oldVal, newVal) -> {
            batchProgressBar.setVisible(newVal);
            batchProgressLabel.setVisible(newVal);
        });

        viewModel.showRetryFailedProperty().addListener((obs, oldVal, newVal) -> {
            retryFailedButton.setVisible(newVal);
            retryFailedButton.setDisable(!newVal);
        });

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
        selectAllButton.setOnAction(e -> viewModel.getRows().forEach(r -> r.setSelected(true)));

        deselectAllButton.setDisable(true);
        deselectAllButton.setOnAction(e -> viewModel.getRows().forEach(r -> r.setSelected(false)));

        retryFailedButton.setOnAction(e -> viewModel.retryFailed());

        ignoredListButton.setOnAction(e -> showIgnoredListDialog());
        historyButton.setOnAction(e -> showHistoryDialog());

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            stopScanButton.setDisable(!newVal);
            updateSelectedButton.setDisable(newVal || viewModel.getRows().stream().noneMatch(r -> r.selectedProperty().get()));
        });
    }

    private TableView<SoftwareUpdateEntry> buildTable() {
        TableView<SoftwareUpdateEntry> table = new TableView<>(viewModel.getRows());
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

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
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) viewModel.updateSingle(entry);
                });

                ignoreBtn.setOnAction(e -> {
                    SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                    if (entry != null) viewModel.skipEntry(entry);
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    unbindEntry();
                    setGraphic(null);
                    return;
                }

                SoftwareUpdateEntry entry = getTableView().getItems().get(getIndex());
                boolean disabled = entry == null || busy.get();
                updateBtn.setDisable(disabled);
                ignoreBtn.setDisable(disabled);

                unbindEntry();

                boundEntry = entry;
                if (entry != null) {
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
                } else {
                    downloadProgress.setVisible(false);
                    installingLabel.setVisible(false);
                    sizeLabel.setVisible(false);
                }

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

        table.getColumns().addAll(selCol, nameCol, currentCol, availCol, sourceCol, statusCol, sizeCol, actionCol);

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
        boolean any = viewModel.getRows().stream().anyMatch(r -> r.selectedProperty().get());
        boolean hasRows = !viewModel.getRows().isEmpty();
        updateSelectedButton.setDisable(!any || busy.get());
        selectAllButton.setDisable(!hasRows || busy.get());
        deselectAllButton.setDisable(!hasRows || busy.get());
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
