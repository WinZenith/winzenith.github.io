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
        table = new TableView<>(sessionRows);

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

        HBox top = new HBox(6, scanButton, selectAllButton, deselectAllButton, presetButton,
                cleanButton, historyButton, progressBar, statusLabel, cancelButton);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();

        VBox center = new VBox(8, table, summaryLabel);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            selectAllButton.setDisable(newVal);
            deselectAllButton.setDisable(newVal);
            presetButton.setDisable(newVal);
            cleanButton.setDisable(newVal || getSelectedCount() == 0);
            cancelButton.setDisable(!newVal);
        });

        sessionRows.addListener((javafx.collections.ListChangeListener<CleanupRow>) c -> {
            updateSummary();
            if (!busy.get()) {
                cleanButton.setDisable(getSelectedCount() == 0);
            }
        });

        VBox content = new VBox(top, center);
        VBox.setVgrow(center, Priority.ALWAYS);
        return content;
    }

    private void cancelActive() {
        cancelling.set(true);
        try {
            if (activeScanToken != null) activeScanToken.cancel();
            if (activeCleanToken != null) activeCleanToken.cancel();
            if (activeScanFuture != null && !activeScanFuture.isDone()) activeScanFuture.cancel(true);
            if (activeCleanFuture != null && !activeCleanFuture.isDone()) activeCleanFuture.cancel(true);
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
            }
            @Override
            protected void updateItem(CleanupRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (previousItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
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
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                        if (selectionListener != null) {
                            previousItem.selectedProperty().removeListener(selectionListener);
                        }
                    }
                    if (checkBox.selectedProperty().isBound()) {
                        checkBox.selectedProperty().unbind();
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

        table.getColumns().addAll(checkCol, categoryCol, descCol, sizeCol);
    }

    private void showPresetMenu() {
        ContextMenu menu = new ContextMenu();
        for (CleanerPresets preset : CleanerPresets.values()) {
            MenuItem item = new MenuItem(preset.getDisplayName());
            item.setStyle("-fx-font-size: 12px;");
            item.setOnAction(e -> {
                Set<CleanupCategory> cats = preset.getCategories();
                for (CleanupRow row : sessionRows) {
                    row.setSelected(cats.contains(row.getCategory()));
                }
            });
            menu.getItems().add(item);
        }
        menu.show(presetButton, javafx.geometry.Side.BOTTOM, 0, 0);
    }

    private void updateSummary() {
        long totalBytes = sessionRows.stream().mapToLong(CleanupRow::getTotalBytes).sum();
        int selectedCount = getSelectedCount();
        long selectedBytes = sessionRows.stream().filter(CleanupRow::isSelected).mapToLong(CleanupRow::getTotalBytes).sum();
        long allTimeFreed = historyStore.getTotalBytesFreedAllTime();

        StringBuilder sb = new StringBuilder();
        if (hasScanned) {
            sb.append("Total: ").append(CleanupService.formatBytes(totalBytes))
                    .append(" across ").append(sessionRows.size()).append(" categories");
            if (selectedCount > 0) {
                sb.append(" | Selected: ").append(selectedCount).append(" categories (")
                        .append(CleanupService.formatBytes(selectedBytes)).append(")");
            }
            if (allTimeFreed > 0) {
                sb.append(" | All-time freed: ").append(CleanupService.formatBytes(allTimeFreed));
            }
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
        }
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

        java.util.Set<CleanupCategory> adminRequired = java.util.Set.of(
                CleanupCategory.REGISTRY,
                CleanupCategory.WINDOWS_UPDATE_CLEANUP,
                CleanupCategory.OLD_WINDOWS_INSTALL,
                CleanupCategory.WINDOWS_DEFENDER_CACHE,
                CleanupCategory.WINDOWS_LOG_FILES,
                CleanupCategory.FONT_CACHE,
                CleanupCategory.WINDOWS_SEARCH_CACHE
        );

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

        String riskLabel = hasHighRisk ? " (includes HIGH-risk categories)" : "";
        String dialogMessage = "Do you confirm the cleanup of " + selected.size() + " categories"
                + riskLabel + "?\n\n"
                + selected.stream().map(r -> "  - " + r.getCategory().getDisplayName())
                .collect(java.util.stream.Collectors.joining("\n"))
                + "\n\nThis action cannot be undone.";

        Alert confirmAlert = new Alert(Alert.AlertType.CONFIRMATION, dialogMessage,
                ButtonType.OK, ButtonType.CANCEL);
        confirmAlert.setHeaderText("Confirm Cleanup");
        confirmAlert.getDialogPane().setMinWidth(400);
        if (confirmAlert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
            busy.set(false);
            return;
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
                    historyStore.append(summary);

                    Platform.runLater(() -> {
                        statusLabel.setText("Re-scanning cleaned categories...");
                        progressBar.setProgress(-1);
                    });

                    java.util.List<CleanupCategory> cleanedCategories = selected.stream()
                            .map(CleanupRow::getCategory).toList();

                    CancelableCompletableFuture<java.util.List<CleanupRow>> rescanFuture =
                            service.scanCategoriesAsync(cleanedCategories, () -> {}, activeCleanToken);

                    rescanFuture.whenComplete((rescanResults, rescanEx) -> {
                        Platform.runLater(() -> {
                            if (rescanEx == null) {
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
                            }

                            StringBuilder sb = new StringBuilder();
                            sb.append("Cleanup completed.\n\n");
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
                            statusLabel.setText("Cleanup completed - " + CleanupService.formatBytes(summary.getTotalBytes()) + " freed.");
                            progressBar.setVisible(false);
                            cancelButton.setDisable(true);
                            updateSummary();

                            Alert resultAlert = new Alert(Alert.AlertType.INFORMATION, sb.toString());
                            resultAlert.setHeaderText("Cleanup Results");
                            resultAlert.showAndWait();

                            cancelling.set(false);
                            activeCleanToken = null;
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

            CompletableFuture.runAsync(() -> {
                try {
                    ProcessBuilder pb = new ProcessBuilder("powershell", "-Command",
                            "Checkpoint-Computer -Description 'WinZenith Cleanup Pre-Clean' -RestorePointType MODIFY_SETTINGS");
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    boolean finished = p.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
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
                } catch (Exception e) {
                    AppLogger.warning("Failed to create System Restore point: " + e.getMessage());
                }
            }).whenComplete((v, ex) -> Platform.runLater(doClean));
        } else {
            doClean.run();
        }
    }
}
