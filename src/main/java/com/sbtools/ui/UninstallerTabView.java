package com.sbtools.ui;

import com.sbtools.uninstaller.InstalledApp;
import com.sbtools.uninstaller.LeftoverItem;
import com.sbtools.uninstaller.UninstallHistoryEntry;
import com.sbtools.uninstaller.UninstallHistoryStore;
import com.sbtools.uninstaller.UninstallerService;
import com.sbtools.util.AppIconResolver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.FormatUtils;
import com.sbtools.util.ProcessResult;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.util.Duration;
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class UninstallerTabView extends BorderPane {

    private final UninstallerService service = new UninstallerService();
    private final com.sbtools.backup.SystemRestoreService restoreService =
            new com.sbtools.backup.SystemRestoreService();
    private final UninstallHistoryStore historyStore = new UninstallHistoryStore();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<InstalledApp> allApps = FXCollections.observableArrayList();
    private final FilteredList<InstalledApp> filteredApps = new FilteredList<>(allApps);
    private final SortedList<InstalledApp> sortedApps = new SortedList<>(filteredApps);
    private volatile CancellationToken scanCancellationToken = new CancellationToken();
    private final AtomicBoolean leftoverCancel = new AtomicBoolean(false);

    private final Label statusLabel = new Label("Scan system to list installed software.");
    private final Label countLabel = new Label("");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final ProgressBar queueProgress = new ProgressBar(0);
    private final Button scanButton = new Button("Scan");
    private final Button cancelButton = new Button("Cancel");
    private final Button uninstallButton = new Button("Uninstall");
    private final Button uninstallSelectedButton = new Button("Uninstall Selected");
    private final Button forceUninstallButton = new Button("Force Uninstall");
    private final Button historyButton = new Button("History");
    private final Button exportButton = new Button("Export...");
    private final TextField searchField = new TextField();
    private final Label detailsLabel = new Label("Select an app to see details.");
    private final Button openFolderButton = new Button("Open Folder");
    private final Button copyDetailsButton = new Button("Copy");

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton win32Toggle = new ToggleButton("Desktop Apps");
    private final ToggleButton appxToggle = new ToggleButton("Windows Store Apps");

    private final TableView<InstalledApp> table = new TableView<>(sortedApps);
    private final ExecutorService iconExecutor = Executors.newFixedThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())), r -> {
        Thread t = new Thread(r, "uninstaller-icon-loader");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentHashMap<String, javafx.scene.image.Image> iconCache = new ConcurrentHashMap<>();
    private PauseTransition searchDebounce;
    private volatile boolean disposed = false;
    // Batch queue state (sequential with per-app prompts)
    private volatile boolean queueStopRequested = false;

    public UninstallerTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progress.setVisible(false);
        progress.setMaxSize(24, 24);
        queueProgress.setVisible(false);
        queueProgress.setPrefWidth(120);
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> cancelCurrentOperation());

        scanButton.setOnAction(e -> scan());
        scanButton.setTooltip(new Tooltip("Scan for installed applications"));

        uninstallButton.setOnAction(e -> {
            InstalledApp selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) uninstallSingleApp(selected);
        });
        uninstallButton.setDisable(true);
        uninstallButton.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white;");
        uninstallButton.setTooltip(new Tooltip("Uninstall the selected app (runs vendor uninstaller)"));

        uninstallSelectedButton.setOnAction(e -> uninstallSelectedQueue());
        uninstallSelectedButton.setDisable(true);
        uninstallSelectedButton.setTooltip(new Tooltip(
                "Uninstall each selected app one-by-one with per-app confirmation"));

        forceUninstallButton.setOnAction(e -> {
            InstalledApp selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) triggerForceUninstallForApp(selected);
        });
        forceUninstallButton.setDisable(true);
        forceUninstallButton.getStyleClass().add("danger");
        forceUninstallButton.setTooltip(new Tooltip("Force-remove without vendor uninstaller (last resort)"));

        historyButton.setOnAction(e -> UninstallerHistoryDialog.show());
        historyButton.setTooltip(new Tooltip("View past uninstall history"));
        exportButton.setOnAction(e -> exportAppList());
        exportButton.setTooltip(new Tooltip("Export the current app list to CSV"));
        openFolderButton.setOnAction(e -> openSelectedInstallFolder());
        copyDetailsButton.setOnAction(e -> copySelectedDetails());

        searchField.setPromptText("Search apps...");
        searchField.setPrefWidth(220);
        searchDebounce = new PauseTransition(Duration.millis(250));
        searchDebounce.setOnFinished(ev -> applyFilter());
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            searchDebounce.stop();
            searchDebounce.playFromStart();
        });

        win32Toggle.setToggleGroup(modeGroup);
        win32Toggle.setSelected(true);
        appxToggle.setToggleGroup(modeGroup);

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                if (oldVal != null) oldVal.setSelected(true);
                return;
            }
            if (busy.get()) {
                // Revert toggle without clearing list while an operation is in progress
                if (oldVal != null) {
                    Platform.runLater(() -> {
                        modeGroup.selectToggle(oldVal);
                    });
                }
                return;
            }
            allApps.clear();
            iconCache.clear();
            statusLabel.setText("Scan system to list installed software.");
            countLabel.setText("");
            scan();
        });

        HBox top = new HBox(12,
                win32Toggle, appxToggle,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                searchField, scanButton, cancelButton, uninstallButton, uninstallSelectedButton,
                forceUninstallButton, historyButton, exportButton,
                progress, queueProgress, statusLabel, countLabel
        );
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();
        sortedApps.comparatorProperty().bind(
                Bindings.when(table.comparatorProperty().isNotNull())
                        .then(table.comparatorProperty())
                        .otherwise(Comparator.comparing(InstalledApp::getName, String.CASE_INSENSITIVE_ORDER))
        );

        table.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        table.setPlaceholder(new Label("No apps found. Click Scan to list installed software."));

        HBox detailsBar = new HBox(10, detailsLabel, openFolderButton, copyDetailsButton);
        detailsBar.setAlignment(Pos.CENTER_LEFT);
        detailsBar.setPadding(new Insets(8, 16, 8, 16));
        detailsLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(detailsLabel, Priority.ALWAYS);
        detailsLabel.setStyle("-fx-opacity: 0.85; -fx-font-size: 11px;");
        openFolderButton.setDisable(true);
        copyDetailsButton.setDisable(true);

        VBox center = new VBox(table, detailsBar);
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(top);
        setCenter(center);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            updateButtonStates();
            updateDetailsBar();
        });
        table.getSelectionModel().getSelectedItems().addListener(
                (javafx.collections.ListChangeListener<InstalledApp>) c -> {
                    updateButtonStates();
                    updateDetailsBar();
                });

        busy.addListener((obs, oldVal, newVal) -> {
            boolean b = Boolean.TRUE.equals(newVal);
            scanButton.setDisable(b);
            win32Toggle.setDisable(b);
            appxToggle.setDisable(b);
            cancelButton.setDisable(!b);
            queueProgress.setVisible(false);
            if (!b) {
                progress.setVisible(false);
            }
            updateButtonStates();
        });

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            uninstallButton.setDisable(true);
            uninstallSelectedButton.setDisable(true);
            forceUninstallButton.setDisable(true);
            exportButton.setDisable(true);
            statusLabel.setText("Uninstaller is only available on Windows.");
        }
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<InstalledApp, String> iconCol = new TableColumn<>(" ");
        iconCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(""));
        iconCol.setPrefWidth(36);
        iconCol.setMinWidth(36);
        iconCol.setMaxWidth(36);
        iconCol.setResizable(false);
        iconCol.setSortable(false);
        iconCol.setCellFactory(col -> new TableCell<>() {
            private final javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView();
            private final javafx.scene.layout.StackPane iconPane = new javafx.scene.layout.StackPane(imageView);
            private final AtomicReference<Future<?>> iconTaskRef = new AtomicReference<>();
            {
                getStyleClass().add("icon-cell");
                imageView.setFitWidth(20);
                imageView.setFitHeight(20);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.setCache(true);
                iconPane.setMaxSize(24, 24);
                iconPane.setPrefSize(24, 24);
                iconPane.setMinSize(24, 24);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);

                // Cancel any previous icon extraction task for this cell
                Future<?> prev = iconTaskRef.getAndSet(null);
                if (prev != null && !prev.isDone()) {
                    prev.cancel(true);
                }

                if (empty || getItem() == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    InstalledApp app = getTableRow().getItem();
                    setGraphic(iconPane);

                    // Fast path: cached icon (avoids re-extraction on every scroll).
                    String cacheKey = iconCacheKey(app);
                    javafx.scene.image.Image cached = cacheKey == null ? null : iconCache.get(cacheKey);
                    if (cached != null) {
                        imageView.setImage(cached);
                        return;
                    }
                    imageView.setImage(null);

                    // Extract icon off the FX thread, then set on the FX thread.
                    if (disposed) return;
                    try {
                        Future<?> f = iconExecutor.submit(() -> {
                            try {
                                String loc = AppIconResolver.resolveAppIconPath(app);
                                if (loc != null && !loc.isBlank()) {
                                    String key = loc.toLowerCase();
                                    javafx.scene.image.Image hit = iconCache.get(key);
                                    if (hit != null) {
                                        Platform.runLater(() -> {
                                            if (getTableRow() != null && getTableRow().getItem() == app) {
                                                imageView.setImage(hit);
                                            }
                                        });
                                        return;
                                    }
                                    BufferedImage bimg = com.sbtools.util.IconExtractor.extractIconBuffered(loc);
                                    if (bimg != null) {
                                        final int w = bimg.getWidth();
                                        final int h = bimg.getHeight();
                                        final int[] argb = bimg.getRGB(0, 0, w, h, null, 0, w);
                                        Platform.runLater(() -> {
                                            try {
                                                javafx.scene.image.WritableImage fxImg = new javafx.scene.image.WritableImage(w, h);
                                                fxImg.getPixelWriter().setPixels(0, 0, w, h,
                                                        javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
                                                if (key != null) {
                                                    iconCache.putIfAbsent(key, fxImg);
                                                    // Bound cache to avoid unbounded growth
                                                    if (iconCache.size() > 500) iconCache.clear();
                                                }
                                                if (getTableRow() != null && getTableRow().getItem() == app) {
                                                    imageView.setImage(fxImg);
                                                }
                                            } catch (Exception ex) {
                                                AppLogger.debug("Failed to convert icon to FX image: " + ex.getMessage());
                                            }
                                        });
                                    }
                                }
                            } catch (Exception e) {
                                AppLogger.debug("Icon extraction failed: " + e.getMessage());
                            }
                        });
                        iconTaskRef.set(f);
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                        // Executor shut down — icons will remain blank
                    }
                }
            }
        });

        javafx.util.Callback<TableColumn<InstalledApp, String>, TableCell<InstalledApp, String>> textCellFactory = col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setGraphic(null);
                if (!getStyleClass().contains("text-cell")) {
                    getStyleClass().add("text-cell");
                }
            }
        };

        TableColumn<InstalledApp, String> nameCol = new TableColumn<>("Application");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(360);
        nameCol.setCellFactory(textCellFactory);

         TableColumn<InstalledApp, Number> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue().getEstimatedSize()));
        sizeCol.setPrefWidth(100);
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : FormatUtils.formatSize(item.intValue()));
                setGraphic(null);
                if (!getStyleClass().contains("text-cell")) {
                    getStyleClass().add("text-cell");
                }
            }
        });

        TableColumn<InstalledApp, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getVersion()));
        versionCol.setPrefWidth(120);
        versionCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getArchitecture()));
        typeCol.setPrefWidth(80);
        typeCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> dateCol = new TableColumn<>("Install Date");
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(FormatUtils.formatDate(c.getValue().getInstallDate())));
        dateCol.setPrefWidth(140);
        dateCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> publisherCol = new TableColumn<>("Company");
        publisherCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getPublisher()));
        publisherCol.setPrefWidth(200);
        publisherCol.setCellFactory(textCellFactory);

        table.getColumns().addAll(iconCol, nameCol, sizeCol, versionCol, typeCol, dateCol, publisherCol);

        table.setRowFactory(tv -> {
            TableRow<InstalledApp> row = new TableRow<>();
            row.setMinHeight(28);
            row.setPrefHeight(28);

            MenuItem uninstallItem = new MenuItem("Uninstall");
            uninstallItem.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> busy.get() || row.getItem() == null || !row.getItem().canUninstall(),
                    busy, row.itemProperty()));
            uninstallItem.setOnAction(e -> uninstallSingleApp(row.getItem()));

            MenuItem forceUninstallItem = new MenuItem("Force Uninstall");
            forceUninstallItem.disableProperty().bind(busy);
            forceUninstallItem.getStyleClass().add("danger-menu-item");
            forceUninstallItem.setOnAction(e -> triggerForceUninstallForApp(row.getItem()));

            MenuItem openFolderItem = new MenuItem("Open Install Location");
            openFolderItem.setOnAction(e -> {
                InstalledApp a = row.getItem();
                if (a != null) openInstallFolder(a);
            });

            MenuItem copyItem = new MenuItem("Copy Details");
            copyItem.setOnAction(e -> {
                InstalledApp a = row.getItem();
                if (a != null) copyAppDetails(a);
            });

            ContextMenu contextMenu = new ContextMenu(uninstallItem, forceUninstallItem,
                    new SeparatorMenuItem(), openFolderItem, copyItem);

            row.contextMenuProperty().bind(
                    Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu)
            );

            return row;
        });
    }

    private void applyFilter() {
        String filter = searchField.getText();
        if (filter == null || filter.isBlank()) {
            filteredApps.setPredicate(app -> true);
        } else {
            String lower = filter.toLowerCase();
            filteredApps.setPredicate(app ->
                    app.getName().toLowerCase().contains(lower) ||
                    app.getPublisher().toLowerCase().contains(lower) ||
                    app.getVersion().toLowerCase().contains(lower) ||
                    app.getArchitecture().toLowerCase().contains(lower)
            );
        }
        updateCountLabel();
    }

    private void updateCountLabel() {
        try {
            int shown = filteredApps.size();
            int total = allApps.size();
            String f = searchField.getText();
            boolean filtered = f != null && !f.isBlank();
            countLabel.setText(filtered ? (shown + " of " + total) : (total + " app(s)"));
        } catch (Exception ignored) {}
    }

    private static String iconCacheKey(InstalledApp app) {
        try {
            String loc = AppIconResolver.resolveAppIconPath(app);
            return loc == null ? null : loc.toLowerCase();
        } catch (Exception e) {
            return null;
        }
    }

    private void cancelCurrentOperation() {
        try {
            scanCancellationToken.cancel();
        } catch (Exception ignored) {}
        try {
            leftoverCancel.set(true);
        } catch (Exception ignored) {}
        statusLabel.setText("Cancelling...");
    }

    private void updateDetailsBar() {
        try {
            var sel = table.getSelectionModel().getSelectedItems();
            if (sel == null || sel.isEmpty()) {
                detailsLabel.setText("Select an app to see details.");
                openFolderButton.setDisable(true);
                copyDetailsButton.setDisable(true);
                return;
            }
            InstalledApp a = table.getSelectionModel().getSelectedItem();
            if (a == null && !sel.isEmpty()) a = sel.get(0);
            if (a == null) {
                detailsLabel.setText("Select an app to see details.");
                openFolderButton.setDisable(true);
                copyDetailsButton.setDisable(true);
                return;
            }
            String loc = a.getInstallLocation() == null ? "" : a.getInstallLocation();
            String reg = a.isWin32() ? (a.getRegistryHive() + "\\" + a.getRegistryKeyPath()) : a.getAppxPackageFullName();
            String extra = sel.size() > 1 ? ("  (" + sel.size() + " selected)") : "";
            detailsLabel.setText(a.getName() + "  " + a.getVersion()
                    + "  |  " + loc + "  |  " + reg + extra);
            detailsLabel.setTooltip(new Tooltip(detailsLabel.getText()));
            boolean hasLoc = loc != null && !loc.isBlank()
                    && new java.io.File(loc).exists();
            openFolderButton.setDisable(!hasLoc || busy.get());
            copyDetailsButton.setDisable(busy.get());
        } catch (Exception ignored) {}
    }

    private void openSelectedInstallFolder() {
        InstalledApp a = table.getSelectionModel().getSelectedItem();
        if (a != null) openInstallFolder(a);
    }

    private void openInstallFolder(InstalledApp app) {
        try {
            String loc = app.getInstallLocation();
            if (loc == null || loc.isBlank()) return;
            java.io.File f = new java.io.File(loc);
            if (!f.exists()) {
                new Alert(Alert.AlertType.WARNING, "Install location no longer exists:\n" + loc).showAndWait();
                return;
            }
            java.io.File target = f.isDirectory() ? f : f.getParentFile();
            if (target == null) target = f;
            try {
                new ProcessBuilder("explorer.exe", target.getAbsolutePath()).start();
            } catch (Exception ex2) {
                new Alert(Alert.AlertType.ERROR, "Could not open folder:\n" + ex2.getMessage()).showAndWait();
            }
        } catch (Exception e) {
            AppLogger.warning("Open install folder failed: " + e.getMessage());
        }
    }

    private void copySelectedDetails() {
        var sel = table.getSelectionModel().getSelectedItems();
        if (sel == null || sel.isEmpty()) return;
        if (sel.size() == 1) copyAppDetails(sel.get(0));
        else {
            StringBuilder sb = new StringBuilder();
            for (InstalledApp a : sel) {
                sb.append(formatAppDetails(a)).append(System.lineSeparator());
            }
            setClipboard(sb.toString());
            statusLabel.setText("Copied " + sel.size() + " app(s) to clipboard.");
        }
    }

    private static String formatAppDetails(InstalledApp a) {
        return a.getName() + " " + a.getVersion()
                + " [" + a.getPublisher() + "] "
                + (a.isWin32() ? "Desktop" : "Store")
                + " | " + a.getInstallLocation();
    }

    private void copyAppDetails(InstalledApp a) {
        setClipboard(formatAppDetails(a)
                + "\nUninstall: " + a.getUninstallString()
                + "\nRegistry: " + a.getRegistryHive() + "\\" + a.getRegistryKeyPath());
        statusLabel.setText("Copied details for " + a.getName());
    }

    private void setClipboard(String text) {
        try {
            ClipboardContent c = new ClipboardContent();
            c.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(c);
        } catch (Exception e) {
            AppLogger.warning("Clipboard copy failed: " + e.getMessage());
        }
    }

    private void exportAppList() {
        try {
            List<InstalledApp> rows = new ArrayList<>(filteredApps);
            if (rows.isEmpty()) {
                new Alert(Alert.AlertType.INFORMATION, "Nothing to export.").showAndWait();
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export installed apps");
            chooser.setInitialFileName("installed-apps.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            java.io.File target = chooser.showSaveDialog(
                    getScene() != null ? getScene().getWindow() : null);
            if (target == null) return;
            StringBuilder sb = new StringBuilder("Name,Version,Publisher,Type,Size,InstallDate,InstallLocation\n");
            for (InstalledApp a : rows) {
                sb.append(csv(a.getName())).append(',')
                        .append(csv(a.getVersion())).append(',')
                        .append(csv(a.getPublisher())).append(',')
                        .append(csv(a.isWin32() ? "Desktop-" + a.getArchitecture() : "Store")).append(',')
                        .append(csv(FormatUtils.formatSize(a.getEstimatedSize()))).append(',')
                        .append(csv(FormatUtils.formatDate(a.getInstallDate()))).append(',')
                        .append(csv(a.getInstallLocation())).append('\n');
            }
            java.nio.file.Files.writeString(target.toPath(), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            statusLabel.setText("Exported " + rows.size() + " app(s).");
        } catch (Exception e) {
            AppLogger.error("Export app list failed", e);
            new Alert(Alert.AlertType.ERROR, "Export failed:\n" + e.getMessage()).showAndWait();
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        progress.setVisible(true);
        cancelButton.setDisable(false);
        statusLabel.setText("Scanning for installed apps...");
        allApps.clear();

        boolean scanWin32 = win32Toggle.isSelected();

        // Cancel any previous scan
        scanCancellationToken.cancel();
        CancellationToken ct = new CancellationToken();
        scanCancellationToken = ct;

        Thread t = new Thread(() -> {
            try {
                List<InstalledApp> apps;
                if (scanWin32) {
                    apps = service.listWin32Apps();
                } else {
                    // Fast list first for instant display; sizes enriched lazily below.
                    apps = service.listAppxAppsFast();
                }

                if (ct.isCancelled()) return;

                Platform.runLater(() -> {
                    if (ct.isCancelled()) return;
                    allApps.setAll(apps);
                    applyFilter();
                    statusLabel.setText("Found " + apps.size() + " app(s).");
                });

                // Lazy AppX size enrichment (background, cancellable, non-blocking).
                if (!scanWin32 && !apps.isEmpty()) {
                    for (int i = 0; i < apps.size(); i++) {
                        if (ct.isCancelled() || disposed) break;
                        InstalledApp a = apps.get(i);
                        if (a.getEstimatedSize() > 0) continue;
                        int kb = 0;
                        try {
                            kb = service.computeAppxSizeKB(a);
                        } catch (Exception ignored) {}
                        if (kb > 0 && !ct.isCancelled()) {
                            final int idx = i;
                            final int sizeKb = kb;
                            final InstalledApp orig = a;
                            Platform.runLater(() -> {
                                if (ct.isCancelled() || disposed) return;
                                try {
                                    // Replace by identity (package full name); ignore if list changed.
                                    for (int j = 0; j < allApps.size(); j++) {
                                        InstalledApp cur = allApps.get(j);
                                        if (cur.getAppxPackageFullName() != null
                                                && cur.getAppxPackageFullName()
                                                        .equals(orig.getAppxPackageFullName())) {
                                            allApps.set(j, cur.withEstimatedSize(sizeKb));
                                            break;
                                        }
                                    }
                                } catch (Exception ignored) {}
                            });
                        }
                        if (i % 10 == 0) {
                            try { Thread.sleep(10); } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    Platform.runLater(() -> {
                        if (!ct.isCancelled()) table.refresh();
                    });
                }
            } catch (Exception e) {
                if (ct.isCancelled()) return;
                AppLogger.error("Failed to scan apps", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan installed apps:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    // Always clear busy/progress when this scan finishes unless a newer scan
                    // has already replaced the token (in which case newer scan owns busy).
                    if (scanCancellationToken == ct) {
                        busy.set(false);
                        progress.setVisible(false);
                        cancelButton.setDisable(true);
                    }
                });
            }
        }, "uninstaller-scan");
        t.setDaemon(true);
        t.start();
    }

    private void updateButtonStates() {
        var sel = table.getSelectionModel().getSelectedItems();
        boolean hasSelection = sel != null && !sel.isEmpty();
        boolean isBusy = busy.get();
        InstalledApp selected = table.getSelectionModel().getSelectedItem();
        // Win32 requires UninstallString, Store apps (non-Win32) require PackageFullName
        boolean canUninstall = selected != null && selected.canUninstall();
        uninstallButton.setDisable(!hasSelection || isBusy || !canUninstall);
        // Batch: enabled when 2+ selected (single still goes via Uninstall button)
        int count = sel == null ? 0 : sel.size();
        uninstallSelectedButton.setDisable(count < 2 || isBusy);
        uninstallSelectedButton.setText(count >= 2 ? ("Uninstall Selected (" + count + ")") : "Uninstall Selected");
        forceUninstallButton.setDisable(!hasSelection || isBusy);
        boolean hasLoc = selected != null && selected.getInstallLocation() != null
                && !selected.getInstallLocation().isBlank()
                && new java.io.File(selected.getInstallLocation()).exists();
        openFolderButton.setDisable(!hasSelection || isBusy || !hasLoc);
        copyDetailsButton.setDisable(!hasSelection || isBusy);
    }

    private void uninstallSelectedQueue() {
        List<InstalledApp> sel = new ArrayList<>(table.getSelectionModel().getSelectedItems());
        if (sel.size() < 2 || busy.get()) return;
        queueStopRequested = false;
        // Sequential with per-app prompts: process on FX thread step-by-step so each
        // app still gets its own confirm / mode / restore-point / leftover dialogs.
        // We chain via Platform.runLater recursion driven by completion callbacks.
        Alert info = new Alert(Alert.AlertType.CONFIRMATION);
        info.setTitle("Batch Uninstall");
        info.setHeaderText("Uninstall " + sel.size() + " apps one-by-one?");
        info.setContentText("Each app will ask for confirmation, uninstall mode, and restore point separately.\n"
                + "You can stop the queue after any app.");
        info.initModality(Modality.APPLICATION_MODAL);
        if (info.showAndWait().orElse(null) != ButtonType.OK) return;
        queueProgress.setVisible(true);
        queueProgress.setProgress(0);
        processQueueNext(new ArrayList<>(sel), 0, sel.size());
    }

    private void processQueueNext(List<InstalledApp> queue, int index, int total) {
        if (queueStopRequested || index >= queue.size()) {
            queueProgress.setVisible(false);
            queueStopRequested = false;
            statusLabel.setText("Queue finished (" + index + "/" + total + "). Refreshing...");
            scan();
            return;
        }
        InstalledApp app = queue.get(index);
        queueProgress.setProgress((double) index / Math.max(1, total));
        statusLabel.setText("Queue " + (index + 1) + "/" + total + ": " + app.getName());
        if (!app.canUninstall()) {
            Alert skip = new Alert(Alert.AlertType.WARNING);
            skip.setTitle("Skipped");
            skip.setHeaderText("No uninstaller for " + app.getName());
            skip.setContentText("This entry will be skipped. Continue with the rest of the queue?");
            skip.initModality(Modality.APPLICATION_MODAL);
            ButtonType cont = new ButtonType("Continue queue");
            ButtonType stop = new ButtonType("Stop queue", ButtonBar.ButtonData.CANCEL_CLOSE);
            skip.getButtonTypes().setAll(cont, stop);
            if (skip.showAndWait().orElse(stop) == stop) {
                queueStopRequested = true;
            }
            final boolean stopQ = queueStopRequested;
            Platform.runLater(() -> {
                if (stopQ) {
                    queueProgress.setVisible(false);
                    scan();
                } else {
                    processQueueNext(queue, index + 1, total);
                }
            });
            return;
        }
        // Hook completion: run single flow but chain to next item afterwards.
        // uninstallSingleApp is dialog-driven; we wrap by monitoring busy transitions.
        uninstallSingleAppWithCompletion(app, () -> {
            if (queueStopRequested) {
                queueProgress.setVisible(false);
                scan();
                return;
            }
            Alert next = new Alert(Alert.AlertType.CONFIRMATION);
            next.setTitle("Queue progress");
            next.setHeaderText("Continue to next app? (" + (index + 1) + "/" + total + " done)");
            next.setContentText("Next: " + (index + 1 < queue.size() ? queue.get(index + 1).getName() : "(done)"));
            next.initModality(Modality.APPLICATION_MODAL);
            ButtonType contBtn = new ButtonType("Continue");
            ButtonType stopBtn = new ButtonType("Stop queue", ButtonBar.ButtonData.CANCEL_CLOSE);
            next.getButtonTypes().setAll(contBtn, stopBtn);
            // If this was the last item, just finish.
            if (index + 1 >= queue.size()) {
                queueProgress.setVisible(false);
                scan();
                return;
            }
            ButtonType r = next.showAndWait().orElse(stopBtn);
            if (r == stopBtn) {
                queueProgress.setVisible(false);
                scan();
            } else {
                processQueueNext(queue, index + 1, total);
            }
        });
    }

    /**
     * Same as {@link #uninstallSingleApp(InstalledApp)} but invokes
     * {@code onDone} on the FX thread when the whole workflow (including
     * leftover review / cancellation / rescan trigger) settles. Implemented by
     * polling the shared busy flag rather than changing existing flows.
     */
    private void uninstallSingleAppWithCompletion(InstalledApp app, Runnable onDone) {
        uninstallSingleApp(app);
        // If user cancelled at a pre-busy dialog, busy never went high — call back immediately.
        javafx.animation.PauseTransition probe = new javafx.animation.PauseTransition(Duration.millis(500));
        probe.setOnFinished(ev -> {
            if (!busy.get()) {
                Platform.runLater(onDone);
            } else {
                // Wait until busy clears (workflow finished), then callback.
                Thread waiter = new Thread(() -> {
                    try {
                        for (int i = 0; i < 3600; i++) {
                            Thread.sleep(1000);
                            if (!busy.get()) break;
                            if (queueStopRequested) break;
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    Platform.runLater(onDone);
                }, "uninstall-queue-waiter");
                waiter.setDaemon(true);
                waiter.start();
            }
        });
        probe.play();
    }

    private void uninstallSingleApp(InstalledApp selected) {
        if (selected == null || busy.get()) return;
        if (!selected.canUninstall()) {
            offerWingetFallback(selected);
            return;
        }

        if (!adminCheck.getAsBoolean()) {
            Alert adminWarn = new Alert(Alert.AlertType.WARNING);
            adminWarn.setTitle("Administrator Privileges Required");
            adminWarn.setHeaderText("Not running as administrator");
            adminWarn.setContentText("Some uninstall operations may fail without administrator privileges.\n\n" +
                    "Consider restarting the application as administrator.\n\nContinue anyway?");
            adminWarn.initModality(Modality.APPLICATION_MODAL);
            if (adminWarn.showAndWait().orElse(null) != ButtonType.OK) return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Uninstallation");
        confirm.setHeaderText("Uninstall " + selected.getName());
        confirm.setContentText("Are you sure you want to run the default uninstaller for " + selected.getName() + "?");
        confirm.initModality(Modality.APPLICATION_MODAL);

        if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
            // When the vendor provides both interactive and silent uninstallers,
            // let the user choose. Interactive is the default — silent skips
            // vendor prompts (e.g. "keep user data") and must never run by surprise.
            boolean preferQuiet = false;
            if (selected.isWin32() && selected.hasQuietUninstallString() && selected.hasUninstallString()
                    && !selected.getQuietUninstallString().equals(selected.getUninstallString())) {
                Alert modeDialog = new Alert(Alert.AlertType.CONFIRMATION);
                modeDialog.setTitle("Uninstall Mode");
                modeDialog.setHeaderText("Choose uninstall mode for " + selected.getName());
                modeDialog.setContentText("Interactive shows the vendor's uninstall wizard (recommended).\n"
                        + "Silent runs without prompts and may remove user data without asking.");
                modeDialog.initModality(Modality.APPLICATION_MODAL);
                ButtonType interactiveBtn = new ButtonType("Interactive (Recommended)");
                ButtonType silentBtn = new ButtonType("Silent");
                ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
                modeDialog.getButtonTypes().setAll(interactiveBtn, silentBtn, cancelBtn);
                ButtonType mode = modeDialog.showAndWait().orElse(cancelBtn);
                if (mode == cancelBtn) return;
                preferQuiet = (mode == silentBtn);
            }
            final boolean useQuiet = preferQuiet;
            Alert restorePointDialog = new Alert(Alert.AlertType.CONFIRMATION);
            restorePointDialog.setTitle("System Restore Point");
            restorePointDialog.setHeaderText("Create a restore point?");
            restorePointDialog.setContentText("Would you like to create a System Restore point before uninstalling " + selected.getName() + "?");
            restorePointDialog.initModality(Modality.APPLICATION_MODAL);

            ButtonType yesBtn = new ButtonType("Yes");
            ButtonType noBtn = new ButtonType("No, continue without");
            restorePointDialog.getButtonTypes().setAll(yesBtn, noBtn);

            ButtonType result = restorePointDialog.showAndWait().orElse(null);
            if (result == yesBtn) {
                runUninstallWithRestorePoint(selected, useQuiet);
            } else if (result == noBtn) {
                runUninstallWizard(selected, useQuiet);
            }
        }
    }

    private void runUninstallWithRestorePoint(InstalledApp app) {
        runUninstallWithRestorePoint(app, false);
    }

    private void offerWingetFallback(InstalledApp app) {
        Alert warn = new Alert(Alert.AlertType.WARNING);
        warn.setTitle("No Uninstaller Available");
        warn.setHeaderText("No uninstaller is available for " + app.getName());
        warn.setContentText("This entry has no UninstallString.\n\n"
                + "You can try removing it via winget (Windows Package Manager), "
                + "use Force Uninstall, or skip.");
        warn.initModality(Modality.APPLICATION_MODAL);
        ButtonType wingetBtn = new ButtonType("Try winget");
        ButtonType forceBtn = new ButtonType("Force Uninstall");
        ButtonType cancelBtn = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        warn.getButtonTypes().setAll(wingetBtn, forceBtn, cancelBtn);
        ButtonType choice = warn.showAndWait().orElse(cancelBtn);
        if (choice == wingetBtn) {
            runWingetUninstall(app);
        } else if (choice == forceBtn) {
            triggerForceUninstallForApp(app);
        }
    }

    private void runWingetUninstall(InstalledApp app) {
        if (busy.get()) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm winget Uninstall");
        confirm.setHeaderText("Remove " + app.getName() + " via winget?");
        confirm.setContentText("This runs: winget uninstall --exact --silent --name \"" + app.getName() + "\"");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Removing " + app.getName() + " via winget...");
        Thread t = new Thread(() -> {
            try {
                ProcessResult r = service.tryWingetUninstall(app, 600);
                boolean ok = r != null && r.succeeded();
                String out = r == null ? "(no result)" : r.combinedOutput();
                recordHistory(app, ok ? "Winget" : "Winget",
                        ok, r == null ? -1 : r.exitCode(), 0,
                        ok ? "winget removal succeeded" : ("winget failed: " + truncate(out, 300)));
                if (!ok) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        progress.setVisible(false);
                        statusLabel.setText("winget uninstall failed.");
                        Alert err = new Alert(Alert.AlertType.ERROR);
                        err.setTitle("winget Uninstall Failed");
                        err.setHeaderText("Could not remove " + app.getName() + " via winget");
                        err.setContentText(truncate(out, 800));
                        err.initModality(Modality.APPLICATION_MODAL);
                        err.showAndWait();
                    });
                    return;
                }
                scanAndShowLeftovers(app, true);
            } catch (Exception e) {
                AppLogger.error("winget uninstall failed", e);
                recordHistory(app, "Winget", false, -1, 0, "winget error: " + e.getMessage());
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    statusLabel.setText("winget uninstall failed.");
                    new Alert(Alert.AlertType.ERROR,
                            "winget uninstall failed:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "winget-uninstall");
        t.setDaemon(true);
        t.start();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "... (truncated)";
    }

    private void recordHistory(InstalledApp app, String mode, boolean success,
                               int exitCode, int leftoversDeleted, String detail) {
        try {
            String type = app.isWin32() ? ("Desktop-" + app.getArchitecture()) : "Store";
            historyStore.add(new UninstallHistoryEntry(
                    app.getName(), app.getVersion(), app.getPublisher(),
                    type, mode, success, exitCode, leftoversDeleted,
                    detail == null ? "" : detail));
        } catch (Exception e) {
            AppLogger.warning("recordHistory failed: " + e.getMessage());
        }
    }

    private void runUninstallWithRestorePoint(InstalledApp app, boolean preferQuiet) {
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Creating System Restore point...");

        Thread t = new Thread(() -> {
            try {
                // Reuse the shared Backup restore service (checkpoint-restore.ps1 with
                // JSON result + frequency-limit / protection-disabled detection).
                com.sbtools.backup.SystemRestoreService.RestorePointResult rp =
                        restoreService.createRestorePoint("Before uninstalling " + app.getName());
                if (!rp.success()) {
                    String err = rp.error() == null ? "" : rp.error();
                    boolean freqLimit = err.contains("FREQUENCY_LIMIT");
                    boolean disabled = err.contains("PROTECTION_DISABLED")
                            || err.toLowerCase().contains("restore")
                            || err.toLowerCase().contains("protection");
                    String friendly = err.replace("FREQUENCY_LIMIT:", "").replace("PROTECTION_DISABLED:", "").trim();
                    if (friendly.isBlank()) friendly = "(no details)";
                    final String msg = friendly;
                    final boolean showFreq = freqLimit;
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        Alert errorAlert = new Alert(Alert.AlertType.WARNING);
                        errorAlert.setTitle("Restore Point Failed");
                        errorAlert.setHeaderText(showFreq ? "Restore point skipped (Windows 24h limit)"
                                : (disabled ? "System Restore is disabled" : "Could not create restore point"));
                        errorAlert.setContentText("Failed to create a System Restore point:\n" + msg
                                + (disabled ? "\n\nTip: Enable System Protection for the system drive to use restore points." : "")
                                + (showFreq ? "\n\nWindows allows one restore point per 24h by default. "
                                        + "You can still continue safely." : "")
                                + "\n\nDo you want to continue with the uninstall?");
                        errorAlert.initModality(Modality.APPLICATION_MODAL);

                        ButtonType yesBtn = new ButtonType("Yes");
                        ButtonType noBtn = new ButtonType("No");
                        errorAlert.getButtonTypes().setAll(yesBtn, noBtn);

                        if (errorAlert.showAndWait().orElse(noBtn) == yesBtn) {
                            runUninstallWizard(app, preferQuiet);
                        } else {
                            busy.set(false);
                            progress.setVisible(false);
                            statusLabel.setText("Uninstallation cancelled.");
                            recordHistory(app, preferQuiet ? "Silent" : "Standard",
                                    false, -1, 0, "cancelled at restore-point prompt");
                        }
                    });
                    return;
                }

                Platform.runLater(() -> {
                    statusLabel.setText("Restore point created. Starting uninstaller...");
                    runUninstallWizard(app, preferQuiet);
                });
            } catch (Exception e) {
                AppLogger.error("Failed to create restore point", e);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    busy.set(false);
                    statusLabel.setText("Restore point failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to create System Restore point:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "restore-point");
        t.setDaemon(true);
        t.start();
    }

    private void runUninstallWizard(InstalledApp app) {
        runUninstallWizard(app, false);
    }

    private void runUninstallWizard(InstalledApp app, boolean preferQuiet) {
        // Avoid double-counting busy when called from restore-point flow which already holds busy
        boolean alreadyBusy = busy.get();
        if (!alreadyBusy) {
            busy.set(true);
        }
        progress.setVisible(true);
        statusLabel.setText("Running uninstaller for " + app.getName() + "...");

        Thread t = new Thread(() -> {
            try {
                AppLogger.info("Starting uninstaller for: " + app.getName() + (preferQuiet ? " (silent mode)" : " (interactive mode)"));
                ProcessResult result = service.runUninstallerAndWait(app, 600, preferQuiet);
                AppLogger.info("Uninstaller completed with exit code: " + result.exitCode());
                // 3010/1641 mean success + reboot required — must not be reported as failure
                boolean uninstallSucceeded = result.succeeded();
                final boolean rebootRequired = result.isRebootRequired();

                // Brief pause for file system to settle after process exits
                Thread.sleep(1000);

                if (!uninstallSucceeded) {
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    AtomicReference<ButtonType> userChoice = new AtomicReference<>();
                    ButtonType scanBtn = new ButtonType("Scan Anyway");
                    ButtonType skipBtn = new ButtonType("Skip");
                    Platform.runLater(() -> {
                        // Keep busy=true to prevent re-entry while dialog is open; just hide progress spinner
                        progress.setVisible(false);
                        Alert warn = new Alert(Alert.AlertType.WARNING);
                        warn.setTitle("Uninstall Failed");
                        warn.setHeaderText("The uninstaller returned an error for: " + app.getName());
                        String out = result.combinedOutput();
                        if (out == null || out.isBlank()) out = "(no output captured)";
                        // Truncate very long output to avoid dialog overflow
                        if (out.length() > 800) out = out.substring(0, 800) + "... (truncated)";
                        warn.setContentText("The standard uninstaller reported errors:\n\n"
                                + out + "\n\n"
                                + "Would you like to scan for leftovers anyway?");
                        warn.initModality(Modality.APPLICATION_MODAL);
                        warn.getButtonTypes().setAll(scanBtn, skipBtn);
                        userChoice.set(warn.showAndWait().orElse(skipBtn));
                        latch.countDown();
                    });
                    try {
                        latch.await();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        Platform.runLater(() -> {
                            busy.set(false);
                            progress.setVisible(false);
                            statusLabel.setText("Uninstallation cancelled.");
                        });
                        return;
                    }
                    if (userChoice.get() == scanBtn) {
                        Platform.runLater(() -> {
                            progress.setVisible(true);
                            statusLabel.setText("Scanning leftovers for " + app.getName() + "...");
                        });
                        // Uninstall FAILED: exclude the live install dir from deletable results.
                        // Deleting it now would bypass the vendor uninstaller and corrupt the install.
                        scanAndShowLeftovers(app, false, result, result.exitCode(), rebootRequired);
                    } else {
                        recordHistory(app, preferQuiet ? "Silent" : "Standard",
                                false, result.exitCode(), 0,
                                "uninstaller failed; skipped leftover scan: " + truncate(result.combinedOutput(), 200));
                        Platform.runLater(() -> {
                            busy.set(false);
                            progress.setVisible(false);
                            statusLabel.setText("Uninstallation cancelled.");
                        });
                    }
                    return;
                }

                if (rebootRequired) {
                    final String rebootNote = "Uninstaller reported success but a reboot is required to finish removal (exit code "
                            + result.exitCode() + ").";
                    AppLogger.info(rebootNote);
                    Platform.runLater(() -> statusLabel.setText(app.getName() + " uninstalled — reboot required."));
                }
                scanAndShowLeftovers(app, true, result, result.exitCode(), rebootRequired);

            } catch (Exception e) {
                AppLogger.error("Error during uninstallation workflow", e);
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    statusLabel.setText("Workflow interrupted.");
                    new Alert(Alert.AlertType.ERROR, "An error occurred during uninstallation:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "uninstallation-workflow");
        t.setDaemon(true);
        t.start();
    }

    private void scanAndShowLeftovers(InstalledApp app) {
        scanAndShowLeftovers(app, true);
    }

    private void scanAndShowLeftovers(InstalledApp app, boolean includePrimaryInstallDir) {
        scanAndShowLeftovers(app, includePrimaryInstallDir, null, -1, false);
    }

    private void scanAndShowLeftovers(InstalledApp app, boolean includePrimaryInstallDir,
                                      ProcessResult uninstallResult, int exitCode, boolean rebootRequired) {
        leftoverCancel.set(false);
        Platform.runLater(() -> {
            statusLabel.setText("Scanning leftovers for " + app.getName() + "... (Cancel to skip)");
            progress.setVisible(true);
            cancelButton.setDisable(false);
        });
        // Run scans off the FX thread; cancellable between roots/hives.
        AtomicBoolean cancelFlag = leftoverCancel;
        List<String> fileLeftovers = service.scanFilesystemLeftovers(app, includePrimaryInstallDir, cancelFlag);
        if (cancelFlag.get()) {
            Platform.runLater(() -> {
                progress.setVisible(false);
                statusLabel.setText("Leftover scan cancelled.");
                busy.set(false);
                cancelButton.setDisable(true);
                recordHistory(app, "Standard", uninstallResult != null && uninstallResult.succeeded(),
                        exitCode, 0, cancelFlag.get() ? "leftover scan cancelled"
                                : ("rebootRequired=" + rebootRequired));
                scan();
            });
            return;
        }
        List<String> regLeftovers = service.scanRegistryLeftovers(app, cancelFlag);
        List<String> pathWarnings = service.scanPathWarnings(app);

        Platform.runLater(() -> {
            progress.setVisible(false);
            cancelButton.setDisable(true);
            if (cancelFlag.get()) {
                statusLabel.setText("Leftover scan cancelled.");
                busy.set(false);
                scan();
                return;
            }
            statusLabel.setText("Scanning completed.");
            showLeftoversReview(app, fileLeftovers, regLeftovers, pathWarnings,
                    uninstallResult, exitCode, rebootRequired);
        });
    }

    private void showLeftoversReview(InstalledApp app, List<String> fileLeftovers, List<String> regLeftovers) {
        showLeftoversReview(app, fileLeftovers, regLeftovers, List.of(), null, -1, false);
    }

    private void showLeftoversReview(InstalledApp app, List<String> fileLeftovers, List<String> regLeftovers, List<String> pathWarnings) {
        showLeftoversReview(app, fileLeftovers, regLeftovers, pathWarnings, null, -1, false);
    }

    private void showLeftoversReview(InstalledApp app, List<String> fileLeftovers, List<String> regLeftovers,
                                     List<String> pathWarnings, ProcessResult uninstallResult,
                                     int exitCode, boolean rebootRequired) {
        boolean hasDeletable = !fileLeftovers.isEmpty() || !regLeftovers.isEmpty();
        if (!hasDeletable) {
            String mode = uninstallResult == null ? "Standard" : "Standard";
            recordHistory(app, mode, uninstallResult == null || uninstallResult.succeeded(),
                    exitCode, 0, rebootRequired ? "uninstalled; reboot required; no leftovers"
                            : "uninstalled; no leftovers");
            if (pathWarnings.isEmpty()) {
                Alert done = new Alert(Alert.AlertType.INFORMATION);
                done.setTitle("No Leftovers Found");
                done.setHeaderText(app.getName() + " uninstalled"
                        + (rebootRequired ? " — reboot required" : ""));
                done.setContentText("No leftovers were detected in the filesystem or registry."
                        + (rebootRequired ? "\n\nPlease reboot to finish removal (exit code " + exitCode + ")." : ""));
                done.initModality(Modality.APPLICATION_MODAL);
                done.showAndWait();
            } else {
                Alert warn = new Alert(Alert.AlertType.INFORMATION);
                warn.setTitle("No Deletable Leftovers");
                warn.setHeaderText(app.getName() + " uninstalled — PATH warnings detected");
                StringBuilder sb = new StringBuilder("No deletable files/registry leftovers found.\n\n");
                sb.append("PATH entries still reference the app (remove manually from Environment Variables):\n");
                for (String w : pathWarnings) sb.append("  • ").append(w).append("\n");
                if (rebootRequired) sb.append("\nReboot required to finish removal (exit code ").append(exitCode).append(").");
                // Startup hint: stale autostart entries often survive uninstall
                sb.append("\n\nTip: check the Startup tab if the app still appears at boot.");
                warn.setContentText(sb.toString());
                warn.initModality(Modality.APPLICATION_MODAL);
                warn.getDialogPane().setPrefWidth(600);
                warn.showAndWait();
            }
            busy.set(false);
            scan();
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Leftover Remnants Detected");
        dialog.setHeaderText("Review leftovers for: " + app.getName());
        dialog.initModality(Modality.APPLICATION_MODAL);

        try {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        } catch (Exception ignored) {}

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPadding(new Insets(10));

        ObservableList<LeftoverItem> registryItems = FXCollections.observableArrayList();
        for (String key : regLeftovers) {
            // Safety: exact matches (primary uninstall key, exact app/publisher name)
            // are pre-selected; heuristic word-boundary matches default to UNSELECTED
            // to avoid one-click deletion of shared/vendor keys.
            registryItems.add(new LeftoverItem(key, true, isHighConfidenceLeftover(key, app, true)));
        }
        ListView<LeftoverItem> regListView = buildLeftoverListView(registryItems, app);
        Tab regTab = new Tab("Registry Leftovers (" + regLeftovers.size() + ")", regListView);

        ObservableList<LeftoverItem> fileItems = FXCollections.observableArrayList();
        for (String path : fileLeftovers) {
            // Same safety rule for files: exact install dir / exact name pre-selected,
            // fuzzy vendor matches left unchecked for explicit user opt-in.
            fileItems.add(new LeftoverItem(path, false, isHighConfidenceLeftover(path, app, false)));
        }
        ListView<LeftoverItem> fileListView = buildLeftoverListView(fileItems, app);
        Tab fileTab = new Tab("Files & Directories (" + fileLeftovers.size() + ")", fileListView);

        // Only add tabs that have content, but always show at least one deletable tab
        if (!registryItems.isEmpty()) tabPane.getTabs().add(regTab);
        if (!fileItems.isEmpty()) tabPane.getTabs().add(fileTab);
        if (tabPane.getTabs().isEmpty()) {
            // Should not happen (hasDeletable check above), but fallback to show both
            tabPane.getTabs().addAll(regTab, fileTab);
        }

        // PATH warnings are informational only — not deletable, read-only list
        if (pathWarnings != null && !pathWarnings.isEmpty()) {
            ListView<String> pathList = new ListView<>(FXCollections.observableArrayList(pathWarnings));
            pathList.setCellFactory(lv -> new ListCell<>() {
                @Override
                protected void updateItem(String item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item);
                    setStyle("-fx-text-fill: #f8f8f2;");
                }
            });
            Tab pathTab = new Tab("PATH Warnings (" + pathWarnings.size() + ") — read-only", pathList);
            Label hint = new Label("These PATH entries reference the app. Remove manually via System → Environment Variables.");
            hint.setWrapText(true);
            hint.setStyle("-fx-text-fill: #f1c40f; -fx-font-size: 11px;");
            VBox pathBox = new VBox(6, pathList, hint);
            pathBox.setPadding(new Insets(6));
            pathTab.setContent(pathBox);
            tabPane.getTabs().add(pathTab);
        }

        Button selectAllBtn = new Button("Select All");
        Button highConfBtn = new Button("High-confidence only");
        highConfBtn.setTooltip(new Tooltip("Select only exact matches (safest)"));
        Button deselectAllBtn = new Button("Deselect All");
        Button exportLeftoversBtn = new Button("Export List...");
        HBox selectionControls = new HBox(8, selectAllBtn, highConfBtn, deselectAllBtn, exportLeftoversBtn);
        selectionControls.setPadding(new Insets(0, 10, 10, 10));
        selectionControls.setAlignment(Pos.CENTER_RIGHT);

        selectAllBtn.setOnAction(e -> {
            for (LeftoverItem item : registryItems) item.selectedProperty().set(true);
            for (LeftoverItem item : fileItems) item.selectedProperty().set(true);
        });

        highConfBtn.setOnAction(e -> {
            for (LeftoverItem item : registryItems)
                item.selectedProperty().set(isHighConfidenceLeftover(item.getPath(), app, true));
            for (LeftoverItem item : fileItems)
                item.selectedProperty().set(isHighConfidenceLeftover(item.getPath(), app, false));
        });

        deselectAllBtn.setOnAction(e -> {
            for (LeftoverItem item : registryItems) item.selectedProperty().set(false);
            for (LeftoverItem item : fileItems) item.selectedProperty().set(false);
        });

        exportLeftoversBtn.setOnAction(e -> {
            try {
                FileChooser ch = new FileChooser();
                ch.setTitle("Export leftover list");
                ch.setInitialFileName("leftovers-" + sanitizeFileName(app.getName()) + ".csv");
                ch.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
                java.io.File target = ch.showSaveDialog(dialog.getDialogPane().getScene().getWindow());
                if (target == null) return;
                StringBuilder sb = new StringBuilder("Type,Path,Preselected\n");
                for (LeftoverItem it : registryItems)
                    sb.append("Registry,").append(csv(it.getPath())).append(',').append(it.isSelected()).append('\n');
                for (LeftoverItem it : fileItems)
                    sb.append("File,").append(csv(it.getPath())).append(',').append(it.isSelected()).append('\n');
                if (pathWarnings != null) for (String w : pathWarnings)
                    sb.append("PathWarning,").append(csv(w)).append(",false\n");
                java.nio.file.Files.writeString(target.toPath(), sb.toString(),
                        java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ex) {
                AppLogger.warning("Leftover export failed: " + ex.getMessage());
            }
        });

        // Recycle Bin preference for files (recoverable) + registry .reg backup
        RadioButton recycleBtn = new RadioButton("Move files to Recycle Bin (Recommended)");
        RadioButton permanentBtn = new RadioButton("Delete files permanently");
        ToggleGroup delGroup = new ToggleGroup();
        recycleBtn.setToggleGroup(delGroup);
        permanentBtn.setToggleGroup(delGroup);
        recycleBtn.setSelected(true);
        recycleBtn.setStyle("-fx-text-fill: #f8f8f2;");
        permanentBtn.setStyle("-fx-text-fill: #f8f8f2;");
        HBox deleteModeBox = new HBox(16, recycleBtn, permanentBtn);
        deleteModeBox.setPadding(new Insets(0, 10, 0, 10));
        deleteModeBox.setAlignment(Pos.CENTER_LEFT);

        CheckBox backupRegCheck = new CheckBox("Back up selected registry keys (.reg) before deleting");
        backupRegCheck.setSelected(true);
        backupRegCheck.setStyle("-fx-text-fill: #f8f8f2;");
        backupRegCheck.setPadding(new Insets(0, 10, 0, 10));

        VBox contentBox = new VBox(8, tabPane, selectionControls, deleteModeBox, backupRegCheck);
        contentBox.setPrefSize(680, 440);
        Label safetyHint = new Label("Only exact matches are pre-selected. Heuristic matches are left unchecked — "
                + "select them only if you are sure they belong solely to " + app.getName() + "."
                + (rebootRequired ? " Note: uninstaller requested a reboot (exit " + exitCode + ")." : ""));
        safetyHint.setWrapText(true);
        safetyHint.setStyle("-fx-text-fill: #f1c40f; -fx-font-size: 11px;");
        safetyHint.setPadding(new Insets(0, 10, 0, 10));
        contentBox.getChildren().add(safetyHint);

        dialog.getDialogPane().setContent(contentBox);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Delete Selected");

        java.util.Optional<ButtonType> dialogResult = dialog.showAndWait();
        if (dialogResult.isEmpty() || dialogResult.get() != ButtonType.OK) {
            recordHistory(app, uninstallResult == null ? "Standard" : "Standard",
                    uninstallResult == null || uninstallResult.succeeded(),
                    exitCode, 0, "uninstalled; leftover deletion cancelled by user");
            busy.set(false);
            progress.setVisible(false);
            statusLabel.setText("Cancelled — refreshing list...");
            scan();
            return;
        }

        boolean preferRecycle = recycleBtn.isSelected();
        boolean backupReg = backupRegCheck.isSelected();

        // Wizard already holds busy=true; just show progress for deletion (avoid double-count)
        progress.setVisible(true);
        statusLabel.setText("Deleting leftovers...");

        Thread cleanupThread = new Thread(() -> {
                    List<String> registryKeysToDelete = new ArrayList<>();
                    for (LeftoverItem item : registryItems) {
                        if (item.isSelected()) {
                            registryKeysToDelete.add(item.getPath());
                        }
                    }

                    List<String> filePathsToDelete = new ArrayList<>();
                    for (LeftoverItem item : fileItems) {
                        if (item.isSelected()) {
                            filePathsToDelete.add(item.getPath());
                        }
                    }

                    // Safety net: export registry keys before deleting
                    java.nio.file.Path backupDir = null;
                    int backedUp = 0;
                    if (backupReg && !registryKeysToDelete.isEmpty()) {
                        try {
                            java.nio.file.Path base = com.sbtools.util.AppPaths.ensureBackupsRoot()
                                    .resolve("uninstaller-registry");
                            String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
                                    .format(new java.util.Date());
                            backupDir = base.resolve(sanitizeFileName(app.getName()) + "-" + stamp);
                            List<java.nio.file.Path> outs =
                                    service.exportRegistryKeysForBackup(registryKeysToDelete, backupDir);
                            backedUp = outs.size();
                        } catch (Exception ex) {
                            AppLogger.warning("Registry pre-delete backup failed: " + ex.getMessage());
                        }
                    }

                    List<String> failedDeletions = new ArrayList<>();
                    List<String> recycled = new ArrayList<>();
                    service.deleteRegistryLeftovers(registryKeysToDelete, failedDeletions);
                    service.deleteFilesystemLeftovers(filePathsToDelete, failedDeletions, recycled, preferRecycle);

                    int deletedCount = registryKeysToDelete.size() + filePathsToDelete.size()
                            - failedDeletions.size();
                    boolean ok = uninstallResult == null || uninstallResult.succeeded();
                    String detail = "leftovers " + deletedCount + " removed"
                            + (recycled.isEmpty() ? "" : (" (" + recycled.size() + " recycled)"))
                            + (failedDeletions.isEmpty() ? "" : ("; " + failedDeletions.size() + " failed"))
                            + (backedUp > 0 ? ("; reg backup " + backedUp + " keys") : "")
                            + (rebootRequired ? "; reboot required" : "");
                    recordHistory(app, uninstallResult == null ? "Standard" : "Standard",
                            ok && failedDeletions.isEmpty(), exitCode, Math.max(0, deletedCount), detail);

                    final java.nio.file.Path backupDirFinal = backupDir;
                    final int backedUpFinal = backedUp;
                    final List<String> recycledFinal = new ArrayList<>(recycled);
                    Platform.runLater(() -> {
                        busy.set(false);
                        progress.setVisible(false);
                        statusLabel.setText("Cleanup completed.");

                        if (!failedDeletions.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (String path : failedDeletions) {
                                sb.append("- ").append(path).append("\n");
                            }
                            if (sb.length() > 1500) sb.setLength(1500);
                            Alert failedAlert = new Alert(Alert.AlertType.WARNING);
                            failedAlert.setTitle("Partial Cleanup");
                            failedAlert.setHeaderText("Some items could not be deleted"
                                    + (recycledFinal.isEmpty() ? "" : " (" + recycledFinal.size() + " recycled)"));
                            failedAlert.setContentText("The following items could not be deleted immediately " +
                                    "(e.g. locked files or permission-denied registry keys). " +
                                    "Files have been scheduled for deletion on next reboot where possible"
                                    + (backupDirFinal != null ? ("- Registry backup: " + backupDirFinal) : "") + ":\n\n" + sb.toString());
                            failedAlert.initModality(Modality.APPLICATION_MODAL);
                            failedAlert.showAndWait();
                        } else {
                            Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                            successAlert.setTitle("Leftovers Deleted");
                            successAlert.setHeaderText("Cleanup Successful");
                            successAlert.setContentText("Removed " + deletedCount + " item(s)"
                                    + (recycledFinal.isEmpty() ? "." : (" (" + recycledFinal.size() + " moved to Recycle Bin)."))
                                    + (backedUpFinal > 0 ? "\nRegistry backup (" + backedUpFinal + " keys):\n" + backupDirFinal : "")
                                    + (rebootRequired ? "\n\nPlease reboot to finish removal (exit " + exitCode + ")." : ""));
                            successAlert.initModality(Modality.APPLICATION_MODAL);
                            successAlert.showAndWait();
                        }
                        scan();
                    });
                }, "leftovers-cleanup");
        cleanupThread.setDaemon(true);
        cleanupThread.start();
    }

    private static String sanitizeFileName(String s) {
        if (s == null || s.isBlank()) return "app";
        String t = s.trim().replaceAll("[\\\\/:*?\"<>|]+", "_");
        if (t.length() > 60) t = t.substring(0, 60);
        return t.isBlank() ? "app" : t;
    }

    private ListView<LeftoverItem> buildLeftoverListView(ObservableList<LeftoverItem> items) {
        return buildLeftoverListView(items, null);
    }

    private ListView<LeftoverItem> buildLeftoverListView(ObservableList<LeftoverItem> items, InstalledApp app) {
        ListView<LeftoverItem> listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private final Label badge = new Label();
            private final HBox box = new HBox(6, checkBox, badge);
            private LeftoverItem currentItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2; -fx-padding: 2 0 2 0;");
                checkBox.setMaxWidth(Double.MAX_VALUE);
                HBox.setHgrow(checkBox, Priority.ALWAYS);
                badge.setStyle("-fx-font-size: 10px; -fx-padding: 1 6 1 6; -fx-background-radius: 8;");
                box.setAlignment(Pos.CENTER_LEFT);
            }
            @Override
            protected void updateItem(LeftoverItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (currentItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(currentItem.selectedProperty());
                        currentItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (currentItem != null && currentItem != item) {
                        try {
                            checkBox.selectedProperty().unbindBidirectional(currentItem.selectedProperty());
                        } catch (Exception ignored) {}
                    }
                    currentItem = item;
                    String suffix = "";
                    if (!item.isRegistry()) {
                        long sz = UninstallerService.computePathSizeBytes(item.getPath());
                        if (sz >= 0) {
                            suffix = "  (" + FormatUtils.formatBytes(sz) + ")";
                        }
                    }
                    checkBox.setText(item.getPath() + suffix);
                    try {
                        checkBox.selectedProperty().unbindBidirectional(item.selectedProperty());
                    } catch (Exception ignored) {}
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    boolean highConf = app == null || isHighConfidenceLeftover(item.getPath(), app, item.isRegistry());
                    if (highConf) {
                        badge.setText("Exact");
                        badge.setStyle(badge.getStyle() + "-fx-background-color: #2e7d32; -fx-text-fill: white;");
                    } else {
                        badge.setText("Heuristic");
                        badge.setStyle(badge.getStyle() + "-fx-background-color: #6d4c00; -fx-text-fill: #ffe082;");
                    }
                    badge.setTooltip(new Tooltip(highConf
                            ? "Exact match — safe to remove"
                            : "Heuristic match — verify it belongs solely to this app"));
                    setGraphic(box);
                }
            }
        });
        return listView;
    }

    private void triggerForceUninstallForApp(InstalledApp app) {
        if (app == null || busy.get()) return;

        if (!adminCheck.getAsBoolean()) {
            Alert adminWarn = new Alert(Alert.AlertType.WARNING);
            adminWarn.setTitle("Administrator Privileges Required");
            adminWarn.setHeaderText("Not running as administrator");
            adminWarn.setContentText("Force uninstall requires administrator privileges to kill processes and delete files.\n\n" +
                    "Consider restarting the application as administrator.\n\nContinue anyway?");
            adminWarn.initModality(Modality.APPLICATION_MODAL);
            if (adminWarn.showAndWait().orElse(null) != ButtonType.OK) return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Force Uninstall");
        confirm.setHeaderText("Force remove " + app.getName() + "?");
        if (app.isWin32()) {
            confirm.setContentText("This will forcefully remove all traces of " + app.getName() + " without running the standard uninstaller.\n\n" +
                    "This includes: killing processes, deleting files, removing registry entries, and deleting Start Menu shortcuts.\n\n" +
                    "This action cannot be undone!");
        } else {
            confirm.setContentText("This will forcefully remove the Store package " + app.getName() + " via Remove-AppxPackage "
                    + "without running any vendor uninstaller, then clean its Start Menu shortcuts.\n\n"
                    + "The protected WindowsApps folder is never deleted directly.\n\n"
                    + "This action cannot be undone!");
        }
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Force uninstalling " + app.getName() + "...");

        Thread ft = new Thread(() -> {
            UninstallerService.ForceUninstallResult result;
            try {
                result = service.forceUninstall(app);
            } catch (Exception e) {
                result = new UninstallerService.ForceUninstallResult(List.of(), List.of(app.getName() + ": " + e.getMessage()));
            }
            final UninstallerService.ForceUninstallResult finalResult = result;
            boolean ok = finalResult.errors().isEmpty();
            String detail = (!finalResult.summary().isEmpty()
                    ? String.join("; ", finalResult.summary().subList(0, Math.min(3, finalResult.summary().size())))
                    : "no actions")
                    + (ok ? "" : ("; errors: " + truncate(String.join("; ", finalResult.errors()), 200)));
            recordHistory(app, "Force", ok, ok ? 0 : -1,
                    finalResult.summary().size(), detail);
            Platform.runLater(() -> {
                busy.set(false);
                progress.setVisible(false);
                showForceUninstallSummary(finalResult);
                scan();
            });
        }, "force-uninstall");
        ft.setDaemon(true);
        ft.start();
    }

    private void showForceUninstallSummary(UninstallerService.ForceUninstallResult result) {
        StringBuilder content = new StringBuilder();

        if (!result.summary().isEmpty()) {
            content.append("Actions completed:\n");
            for (String s : result.summary()) {
                content.append("  \u2022 ").append(s).append("\n");
            }
        }

        if (!result.errors().isEmpty()) {
            if (!content.isEmpty()) content.append("\n");
            content.append("Errors:\n");
            for (String e : result.errors()) {
                content.append("  \u2022 ").append(e).append("\n");
            }
        }

        if (content.isEmpty()) {
            content.append("No actions were taken (nothing found to remove).");
        }

        Alert alert = new Alert(result.errors().isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Force Uninstall Summary");
        alert.setHeaderText("Force uninstall completed.");
        alert.setContentText(content.toString());
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.getDialogPane().setPrefWidth(600);
        alert.showAndWait();
    }

    private static String leafOf(String path) {
        if (path == null || path.isBlank()) return "";
        String trimmed = path.trim();
        // Strip trailing separators
        while (trimmed.endsWith("\\") || trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
        }
        int slash = Math.max(trimmed.lastIndexOf('\\'), trimmed.lastIndexOf('/'));
        if (slash >= 0 && slash + 1 < trimmed.length()) {
            return trimmed.substring(slash + 1);
        }
        return trimmed;
    }

    /**
     * High-confidence leftovers (pre-selected): the app's own install dir /
     * primary uninstall registry key, or an exact app/publisher name match.
     * Heuristic substring matches return false and are left unchecked.
     */
    private static boolean isHighConfidenceLeftover(String path, InstalledApp app, boolean isRegistry) {
        if (path == null || app == null) return false;
        try {
            if (isRegistry) {
                String expected = (app.getRegistryHive() + "\\" + app.getRegistryKeyPath()).trim();
                if (!expected.isBlank() && !app.getRegistryKeyPath().isBlank()
                        && path.trim().equalsIgnoreCase(expected)) {
                    return true;
                }
            } else {
                String loc = app.getInstallLocation();
                if (loc != null && !loc.isBlank()) {
                    String a = loc.trim().replace('/', '\\');
                    String b = path.trim().replace('/', '\\');
                    while (a.endsWith("\\") && a.length() > 3) a = a.substring(0, a.length() - 1);
                    while (b.endsWith("\\") && b.length() > 3) b = b.substring(0, b.length() - 1);
                    if (a.equalsIgnoreCase(b)) return true;
                }
            }
            return com.sbtools.uninstaller.UninstallerService.isExactMatch(leafOf(path), app);
        } catch (Exception e) {
            return false;
        }
    }

    public void dispose() {
        disposed = true;
        try {
            if (searchDebounce != null) searchDebounce.stop();
        } catch (Exception ignored) {}
        try {
            scanCancellationToken.cancel();
        } catch (Exception ignored) {}
        leftoverCancel.set(true);
        queueStopRequested = true;
        iconExecutor.shutdownNow();
    }
}
