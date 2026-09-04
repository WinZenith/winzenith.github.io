package com.sbtools.ui;

import com.sbtools.browserext.BrowserExtensionRow;
import com.sbtools.browserext.BrowserExtensionService;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class BrowserExtensionsTabView extends BorderPane {

    private static final List<String> FILTER_BROWSERS;

    static {
        List<String> tmp = new ArrayList<>();
        tmp.add("All");
        tmp.addAll(BrowserExtensionService.ALL_BROWSERS);
        FILTER_BROWSERS = List.copyOf(tmp);
    }

    private final BrowserExtensionService service = new BrowserExtensionService();
    private final SettingsStore settingsStore;
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;
    private volatile Thread scanThread;
    private volatile Thread toggleThread;
    private final AtomicBoolean scanCancelled = new AtomicBoolean(false);
    private final AtomicBoolean toggleCancelled = new AtomicBoolean(false);

    private final ObservableList<BrowserExtensionRow> allRows = FXCollections.observableArrayList();
    private final FilteredList<BrowserExtensionRow> filteredByBrowser = new FilteredList<>(allRows, r -> true);
    private final FilteredList<BrowserExtensionRow> filteredRows = new FilteredList<>(filteredByBrowser, r -> true);
    private final TableView<BrowserExtensionRow> table = new TableView<>(filteredRows);
    private final Map<BrowserExtensionRow, javafx.beans.value.ChangeListener<Boolean>> selectedListeners = new HashMap<>();

    private final Button scanButton = UIButton.primary("Scan All Browsers");
    private final Button enableSelectedBtn = UIButton.primary("Enable");
    private final Button disableSelectedBtn = UIButton.secondary("Disable");
    private final Button selectAllBtn = UIButton.secondary("Select All");
    private final Button deselectAllBtn = UIButton.secondary("Deselect All");
    private final Button manageIgnoredBtn = UIButton.secondary("Manage Ignored");
    private final ComboBox<String> browserFilter = new ComboBox<>(
            FXCollections.observableArrayList(FILTER_BROWSERS));
    private final TextField searchField = new TextField();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Click Scan to list browser extensions.");
    private final Label selectionLabel = new Label("");

    public BrowserExtensionsTabView(BooleanSupplier adminCheck) {
        this(adminCheck, new SettingsStore());
    }

    public BrowserExtensionsTabView(BooleanSupplier adminCheck, SettingsStore settingsStore) {
        this.adminCheck = adminCheck;
        this.settingsStore = settingsStore;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        enableSelectedBtn.setDisable(true);
        disableSelectedBtn.setDisable(true);
        selectAllBtn.setDisable(true);
        deselectAllBtn.setDisable(true);

        enableSelectedBtn.getStyleClass().add("success");
        disableSelectedBtn.getStyleClass().add("button-outlined");
        selectAllBtn.getStyleClass().add("button-outlined");
        deselectAllBtn.getStyleClass().add("button-outlined");
        manageIgnoredBtn.getStyleClass().add("button-outlined");

        searchField.setPromptText("Search extensions...");
        searchField.setPrefWidth(200);
        searchField.getStyleClass().add("sysinfo-search");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        scanButton.setOnAction(e -> startScan());
        enableSelectedBtn.setOnAction(e -> toggleSelected(true));
        disableSelectedBtn.setOnAction(e -> toggleSelected(false));
        selectAllBtn.setOnAction(e -> toggleSelectAll());
        deselectAllBtn.setOnAction(e -> deselectAll());
        manageIgnoredBtn.setOnAction(e -> showIgnoredListDialog());

        browserFilter.getSelectionModel().select(0);
        browserFilter.setOnAction(e -> applyFilters());

        HBox buttonRow = new HBox(12, scanButton, enableSelectedBtn, disableSelectedBtn, selectAllBtn, deselectAllBtn, manageIgnoredBtn);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        HBox filterRow = new HBox(12, new Label("Filter:"), browserFilter, searchField,
                progressBar, statusLabel, selectionLabel);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, buttonRow, filterRow);
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
            updateActionButtons();
            browserFilter.setDisable(newVal);
            searchField.setDisable(newVal);
            selectAllBtn.setDisable(newVal || filteredRows.isEmpty());
            deselectAllBtn.setDisable(newVal || filteredRows.isEmpty());
            manageIgnoredBtn.setDisable(newVal);
            progressBar.setVisible(newVal);
            progressBar.setProgress(newVal ? -1 : 0);
        });

        allRows.addListener((ListChangeListener<BrowserExtensionRow>) c -> {
            while (c.next()) {
                if (c.wasRemoved()) {
                    for (BrowserExtensionRow row : c.getRemoved()) {
                        javafx.beans.value.ChangeListener<Boolean> listener = selectedListeners.remove(row);
                        if (listener != null) {
                            row.selectedProperty().removeListener(listener);
                        }
                    }
                }
                if (c.wasAdded()) {
                    for (BrowserExtensionRow row : c.getAddedSubList()) {
                        javafx.beans.value.ChangeListener<Boolean> listener = (obs, ov, nv) -> updateActionButtons();
                        row.selectedProperty().addListener(listener);
                        selectedListeners.put(row, listener);
                    }
                }
            }
            updateActionButtons();
        });

        applyIgnoredFromSettings();
    }

    private static String qualifiedKey(BrowserExtensionRow row) {
        String profile = row.getProfilePath() != null ? row.getProfilePath() : "";
        return row.getBrowser() + "|" + profile + "|" + row.getExtensionId();
    }

    private static String legacyQualifiedKey(BrowserExtensionRow row) {
        return row.getBrowser() + ":" + row.getExtensionId();
    }

    private void applyIgnoredFromSettings() {
        try {
            AppSettings settings = settingsStore.load();
            List<String> ignoredIds = settings.ignoredBrowserExtensionIds();
            if (ignoredIds != null) {
                java.util.Set<String> ignoredSet = new java.util.HashSet<>(ignoredIds);
                for (BrowserExtensionRow row : allRows) {
                    boolean ignored = ignoredSet.contains(qualifiedKey(row))
                            || ignoredSet.contains(legacyQualifiedKey(row))
                            || ignoredSet.contains(row.getExtensionId());
                    row.setIgnored(ignored);
                    if (ignored) {
                        row.setSelected(false);
                    }
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to load ignored extensions: " + e.getMessage());
        }
    }

    private void saveIgnoredToSettings() {
        try {
            List<String> ignoredIds = new ArrayList<>();
            for (BrowserExtensionRow row : allRows) {
                if (row.isIgnored()) {
                    ignoredIds.add(qualifiedKey(row));
                }
            }
            final List<String> snapshot = List.copyOf(ignoredIds);
            settingsStore.update(current -> current.toBuilder()
                    .ignoredBrowserExtensionIds(snapshot)
                    .build());
        } catch (Exception e) {
            AppLogger.warning("Failed to save ignored extensions: " + e.getMessage());
        }
    }

    private int getSelectedCount() {
        return (int) allRows.stream()
                .filter(r -> r.isSelected() && !r.isIgnored())
                .count();
    }

    private int getVisibleSelectedCount() {
        return (int) filteredRows.stream()
                .filter(r -> r.isSelected() && !r.isIgnored())
                .count();
    }

    private void updateActionButtons() {
        boolean disabled = busy.get() || getSelectedCount() == 0;
        enableSelectedBtn.setDisable(disabled);
        disableSelectedBtn.setDisable(disabled);
        int selCount = getSelectedCount();
        if (selCount == 0) {
            selectionLabel.setText("");
        } else {
            int visibleSel = getVisibleSelectedCount();
            if (visibleSel == selCount) {
                selectionLabel.setText(selCount + " selected");
            } else {
                // Explicitly surface hidden (filtered-out) selections so bulk
                // actions on allRows never surprise the user (B4).
                selectionLabel.setText(selCount + " selected (" + visibleSel + " visible)");
            }
        }
    }

    private void applyFilters() {
        String browserFilterVal = browserFilter.getSelectionModel().getSelectedItem();
        String searchText = searchField.getText();

        if (browserFilterVal == null || "All".equals(browserFilterVal)) {
            filteredByBrowser.setPredicate(r -> true);
        } else {
            filteredByBrowser.setPredicate(r -> browserFilterVal.equals(r.getBrowser()));
        }

        if (searchText == null || searchText.isBlank()) {
            filteredRows.setPredicate(r -> true);
        } else {
            String lowerSearch = searchText.toLowerCase();
            filteredRows.setPredicate(r ->
                    (r.getName() != null && r.getName().toLowerCase().contains(lowerSearch))
                    || (r.getDescription() != null && r.getDescription().toLowerCase().contains(lowerSearch))
                    || (r.getExtensionId() != null && r.getExtensionId().toLowerCase().contains(lowerSearch)));
        }
        updateActionButtons();
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<BrowserExtensionRow, BrowserExtensionRow> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private BrowserExtensionRow previousItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }
            @Override
            protected void updateItem(BrowserExtensionRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (previousItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                        previousItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    // Always detach the previous bidirectional binding first —
                    // otherwise table.refresh() re-binds the same row again and
                    // accumulates duplicate bindings on one CheckBox (leak +
                    // double-toggle). Unbinding an already-detached pair is safe.
                    if (previousItem != null) {
                        try {
                            checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                        } catch (Exception ignored) {
                        }
                    }
                    if (checkBox.selectedProperty().isBound()) {
                        try {
                            checkBox.selectedProperty().unbind();
                        } catch (Exception ignored) {
                        }
                    }
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    checkBox.setDisable(item.isIgnored());
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> browserCol = new TableColumn<>("Browser");
        browserCol.setCellValueFactory(c -> c.getValue().browserProperty());
        browserCol.setPrefWidth(100);
        browserCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(item);
                    setStyle(switch (item) {
                        case "Chrome" -> "-fx-text-fill: #50fa7b; -fx-font-weight: bold;";
                        case "Chrome Canary" -> "-fx-text-fill: #50fa7b; -fx-font-weight: bold; -fx-font-style: italic;";
                        case "Edge" -> "-fx-text-fill: #8be9fd; -fx-font-weight: bold;";
                        case "Edge Beta" -> "-fx-text-fill: #8be9fd; -fx-font-weight: bold; -fx-font-style: italic;";
                        case "Edge Dev" -> "-fx-text-fill: #8be9fd; -fx-font-weight: bold; -fx-font-style: italic;";
                        case "Edge Canary" -> "-fx-text-fill: #8be9fd; -fx-font-weight: bold; -fx-font-style: italic;";
                        case "Firefox" -> "-fx-text-fill: #ffb86c; -fx-font-weight: bold;";
                        case "Brave" -> "-fx-text-fill: #ff79c6; -fx-font-weight: bold;";
                        case "Opera" -> "-fx-text-fill: #ff5555; -fx-font-weight: bold;";
                        case "Opera GX" -> "-fx-text-fill: #ff5555; -fx-font-weight: bold; -fx-font-style: italic;";
                        case "Vivaldi" -> "-fx-text-fill: #bd93f9; -fx-font-weight: bold;";
                        default -> "-fx-text-fill: #f8f8f2;";
                    });
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> nameCol = new TableColumn<>("Extension Name");
        nameCol.setCellValueFactory(c -> c.getValue().nameProperty());
        nameCol.setPrefWidth(200);
        nameCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    BrowserExtensionRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    setText(item);
                    if (row != null && row.isIgnored()) {
                        setStyle("-fx-font-weight: bold; -fx-text-fill: #6272a4;");
                    } else {
                        setStyle("-fx-font-weight: bold;");
                    }
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(80);

        TableColumn<BrowserExtensionRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().enabledProperty().asString());
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    BrowserExtensionRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.isIgnored()) {
                        setText("Ignored");
                        setStyle("-fx-text-fill: #6272a4; -fx-font-weight: bold;");
                    } else {
                        boolean isEnabled = "true".equals(item);
                        setText(isEnabled ? "Enabled" : "Disabled");
                        setStyle(isEnabled
                                ? "-fx-text-fill: #50fa7b; -fx-font-weight: bold;"
                                : "-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                    }
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> installDateCol = new TableColumn<>("Install Date");
        installDateCol.setCellValueFactory(c -> c.getValue().installDateProperty());
        installDateCol.setPrefWidth(130);

        TableColumn<BrowserExtensionRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(200);

        TableColumn<BrowserExtensionRow, String> permsCol = new TableColumn<>("Permissions");
        permsCol.setCellValueFactory(c -> c.getValue().permissionsProperty());
        permsCol.setPrefWidth(180);
        permsCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(item);
                    setStyle("-fx-font-size: 10px;");
                }
            }
        });

        table.getColumns().addAll(checkCol, browserCol, nameCol, versionCol, statusCol,
                installDateCol, descCol, permsCol);

        table.setRowFactory(tv -> {
            TableRow<BrowserExtensionRow> row = new TableRow<>() {
                @Override
                protected void updateItem(BrowserExtensionRow item, boolean empty) {
                    super.updateItem(item, empty);
                    getStyleClass().removeAll("ignored-row");
                    if (item != null && !empty && item.isIgnored()) {
                        getStyleClass().add("ignored-row");
                    }
                }
            };

            ContextMenu ctxMenu = new ContextMenu();

            MenuItem openFolderItem = new MenuItem("Open Extension Folder");
            openFolderItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) openContainingFolder(r);
            });

            MenuItem copyIdItem = new MenuItem("Copy Extension ID");
            copyIdItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) copyToClipboard(r.getExtensionId());
            });

            MenuItem copyPathItem = new MenuItem("Copy Profile Path");
            copyPathItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) {
                    String pp = r.getProfilePath();
                    if (pp != null && !pp.isBlank()) {
                        copyToClipboard(pp);
                    } else {
                        copyToClipboard(r.getPath());
                    }
                }
            });

            MenuItem toggleIgnoreItem = new MenuItem();
            toggleIgnoreItem.textProperty().bind(
                    Bindings.when(row.emptyProperty().or(
                            javafx.beans.binding.Bindings.selectBoolean(row.itemProperty(), "ignored")))
                            .then("Unignore Extension")
                            .otherwise("Ignore Extension"));
            toggleIgnoreItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) {
                    boolean nowIgnored = !r.isIgnored();
                    r.setIgnored(nowIgnored);
                    if (nowIgnored) {
                        r.setSelected(false);
                    }
                    saveIgnoredToSettings();
                    table.refresh();
                    updateActionButtons();
                }
            });

            ctxMenu.getItems().addAll(openFolderItem, copyIdItem, copyPathItem,
                    new SeparatorMenuItem(), toggleIgnoreItem);

            row.contextMenuProperty().bind(
                    Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(ctxMenu));

            return row;
        });
    }

    private void openContainingFolder(BrowserExtensionRow row) {
        // Try profilePath first (new field), then fall back to legacy path
        String profilePath = row.getProfilePath();
        String legacyPath = row.getPath();
        String targetPath = null;
        if (profilePath != null && !profilePath.isBlank()) {
            targetPath = profilePath;
        } else if (legacyPath != null && !legacyPath.isBlank()) {
            targetPath = legacyPath;
        }
        if (targetPath == null) return;
        try {
            File file = new File(targetPath);
            if (file.exists()) {
                Desktop.getDesktop().browseFileDirectory(file);
                return;
            }
        } catch (Exception ignored) {}
        try {
            ProcessBuilder pb = new ProcessBuilder("explorer", "/select," + targetPath);
            pb.start();
        } catch (Exception ex) {
            AppLogger.warning("Failed to open folder for: " + targetPath + " — " + ex.getMessage());
        }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void startScan() {
        if (busy.get()) return;
        scanCancelled.set(false);
        busy.set(true);
        statusLabel.setText("Scanning browser extensions...");
        allRows.clear();

        Thread t = new Thread(() -> {
            try {
                List<BrowserExtensionRow> results = service.scanAllBrowsersParallel(
                        AppExecutors.scanPool(),
                        browser -> Platform.runLater(() -> statusLabel.setText("Scanning " + browser + "...")),
                        scanCancelled
                );
                if (scanCancelled.get()) {
                    throw new CancellationException("Scan cancelled");
                }
                Platform.runLater(() -> {
                    if (scanCancelled.get()) return;
                    allRows.setAll(results);
                    applyIgnoredFromSettings();
                    applyFilters();
                    updateActionButtons();
                    selectAllBtn.setDisable(filteredRows.isEmpty());
                    String baseStatus = buildStatusText(results);
                    Map<String, String> errs = service.getLastScanErrors();
                    if (!errs.isEmpty()) {
                        String warnBrowsers = String.join(", ", errs.keySet());
                        baseStatus += " | Warnings: scan failed for " + warnBrowsers + " (see logs)";
                        // Also surface as non-blocking info if some results present
                        if (!results.isEmpty()) {
                            AppLogger.warning("Partial scan failures: " + errs);
                        }
                    }
                    statusLabel.setText(baseStatus);
                });
            } catch (CancellationException ce) {
                AppLogger.info("Browser extension scan cancelled");
                Platform.runLater(() -> statusLabel.setText("Scan cancelled."));
            } catch (java.io.IOException ioe) {
                AppLogger.error("Browser extension scan failed", ioe);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed: " + ioe.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Browser extension scan failed:\n" + ioe.getMessage()
                            + "\n\nIf PowerShell is blocked by policy, please allow script execution or check antivirus.").showAndWait();
                });
            } catch (Exception e) {
                AppLogger.error("Browser extension scan failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Browser extension scan failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "browser-extensions-scan");
        scanThread = t;
        t.setDaemon(true);
        t.start();
    }

    private String buildStatusText(List<BrowserExtensionRow> results) {
        if (results.isEmpty()) {
            java.util.List<String> notInstalled = new java.util.ArrayList<>();
            java.util.List<String> noProfileData = new java.util.ArrayList<>();
            for (String browser : BrowserExtensionService.ALL_BROWSERS) {
                if (!service.checkBrowserInstalled(browser)) {
                    notInstalled.add(browser);
                } else if (!service.hasProfileData(browser)) {
                    noProfileData.add(browser);
                }
            }
            String base = "No extensions found (store-installed extensions only; unpacked developer-mode extensions are not scanned).";
            if (!notInstalled.isEmpty()) {
                base += " Not installed: " + String.join(", ", notInstalled) + ".";
            }
            if (!noProfileData.isEmpty()) {
                base += " Installed but no profile data (never launched?): " + String.join(", ", noProfileData) + ".";
            }
            return base;
        }

        java.util.Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String browser : BrowserExtensionService.ALL_BROWSERS) {
            counts.put(browser, 0);
        }
        for (BrowserExtensionRow r : results) {
            counts.merge(r.getBrowser(), 1, Integer::sum);
        }

        java.util.List<String> notInstalled = new java.util.ArrayList<>();
        StringBuilder sb = new StringBuilder("Found " + results.size() + " extensions (");
        boolean first = true;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > 0) {
                if (!first) sb.append(", ");
                sb.append(entry.getKey()).append(": ").append(entry.getValue());
                first = false;
            } else if (!service.checkBrowserInstalled(entry.getKey())) {
                notInstalled.add(entry.getKey());
            }
        }
        sb.append(")");
        if (!notInstalled.isEmpty()) {
            sb.append(" Not installed: ").append(String.join(", ", notInstalled));
        }
        return sb.toString();
    }

    private static String profileShortName(BrowserExtensionRow r) {
        try {
            String pp = r.getProfilePath();
            if (pp != null && !pp.isBlank()) {
                String name = java.nio.file.Paths.get(pp).getFileName().toString();
                if (!name.isBlank()) return name;
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    private static String describeRow(BrowserExtensionRow r) {
        String name = r.getName() != null && !r.getName().isBlank() ? r.getName() : r.getExtensionId();
        String profile = profileShortName(r);
        if (!profile.isBlank()) {
            return "• " + name + " (" + r.getBrowser() + " — " + profile + ")";
        }
        return "• " + name + " (" + r.getBrowser() + ")";
    }

    private static String buildAffectedListText(List<BrowserExtensionRow> selected) {
        int maxList = 15;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(maxList, selected.size()); i++) {
            sb.append(describeRow(selected.get(i))).append("\n");
        }
        if (selected.size() > maxList) {
            sb.append("... and ").append(selected.size() - maxList).append(" more.\n");
        }
        return sb.toString();
    }

    private void toggleSelected(boolean enable) {
        if (busy.get()) return;
        // Global selection scope (allRows) is intentional, but the confirmation
        // below explicitly lists every affected row so filtered-out (hidden)
        // selections can never be toggled silently (B4).
        List<BrowserExtensionRow> selected = allRows.stream()
                .filter(r -> r.isSelected() && !r.isIgnored())
                .toList();
        if (selected.isEmpty()) return;

        String action = enable ? "enable" : "disable";
        busy.set(true);
        statusLabel.setText("Checking running browsers...");

        // Offload blocking tasklist to background pool to avoid FX freeze (Blocker 3)
        AppExecutors.ioPool().submit(() -> {
            java.util.Set<String> browsersToWarn = new java.util.HashSet<>();
            try {
                browsersToWarn = detectRunningBrowsers(selected, toggleCancelled);
            } catch (Exception ex) {
                AppLogger.warning("Failed to detect running browsers: " + ex.getMessage());
            }
            final java.util.Set<String> warnSnapshot = browsersToWarn;
            final List<BrowserExtensionRow> selectedSnapshot = List.copyOf(selected);
            Platform.runLater(() -> {
                if (toggleCancelled.get()) {
                    busy.set(false);
                    return;
                }
                busy.set(false);
                // Hard block: the Preferences/extensions.json lock probe cannot
                // reliably detect a running browser (shared read), and writing
                // while it runs loses the change on browser exit. Do NOT offer
                // "continue anyway" — require close + re-check (B2).
                if (!warnSnapshot.isEmpty()) {
                    Alert blocked = new Alert(Alert.AlertType.WARNING);
                    blocked.setTitle(AppInfo.DISPLAY_NAME);
                    blocked.setHeaderText("Close browsers before " + action + "ing");
                    blocked.setContentText(String.join(", ", warnSnapshot)
                            + " appear(s) to be running.\n\n"
                            + "Toggling now would likely fail or be reverted (locked Preferences / Secure Preferences).\n"
                            + "Please close the browsers, then click OK to re-check, or Cancel.");
                    blocked.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                    if (blocked.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
                    // Re-check after user claims browsers are closed.
                    busy.set(true);
                    statusLabel.setText("Re-checking running browsers...");
                    AppExecutors.ioPool().submit(() -> {
                        java.util.Set<String> recheck = new java.util.HashSet<>();
                        try {
                            recheck = detectRunningBrowsers(selectedSnapshot, toggleCancelled);
                        } catch (Exception ex) {
                            AppLogger.warning("Failed to re-check running browsers: " + ex.getMessage());
                        }
                        final java.util.Set<String> recheckSnapshot = recheck;
                        Platform.runLater(() -> {
                            busy.set(false);
                            if (!recheckSnapshot.isEmpty()) {
                                new Alert(Alert.AlertType.ERROR,
                                        String.join(", ", recheckSnapshot)
                                                + " still appear(s) to be running. Aborted — close them and try again.")
                                        .showAndWait();
                                statusLabel.setText("Toggle aborted: browsers still running.");
                                return;
                            }
                            confirmAndToggle(selectedSnapshot, enable, action);
                        });
                    });
                    return;
                }
                confirmAndToggle(selectedSnapshot, enable, action);
            });
        });
    }

    private void confirmAndToggle(List<BrowserExtensionRow> selected, boolean enable, String action) {
        StringBuilder msg = new StringBuilder(action.substring(0, 1).toUpperCase() + action.substring(1)
                + " " + selected.size() + " extension(s)?\n\n"
                + buildAffectedListText(selected)
                + "\nBrowsers must stay closed until the toggle finishes.");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg.toString());
        confirm.setTitle(AppInfo.DISPLAY_NAME);
        confirm.setHeaderText((enable ? "Enable" : "Disable") + " Extensions");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        // Start actual toggle
        toggleCancelled.set(false);
        busy.set(true);
        statusLabel.setText(action.substring(0, 1).toUpperCase() + action.substring(1) + "ing " + selected.size() + " extension(s)...");

        Thread t = new Thread(() -> {
            int success = 0;
            int fail = 0;
            List<BrowserExtensionRow> succeeded = new ArrayList<>();
            for (BrowserExtensionRow ext : selected) {
                if (toggleCancelled.get() || Thread.currentThread().isInterrupted()) break;
                // Re-check browser liveness before each item: if the user
                // relaunched a browser mid-batch, further writes would be lost.
                try {
                    java.util.Set<String> running = detectRunningBrowsers(List.of(ext), toggleCancelled);
                    if (!running.isEmpty()) {
                        AppLogger.warning("Toggle aborted mid-batch: " + running + " started running.");
                        fail += selected.size() - success - fail;
                        break;
                    }
                } catch (Exception ignored) {
                }
                boolean ok = service.toggleExtension(ext, enable, toggleCancelled);
                if (toggleCancelled.get()) break;
                if (ok) {
                    success++;
                    succeeded.add(ext);
                } else {
                    fail++;
                }
            }
            final int s = success;
            final int f = fail;
            final List<BrowserExtensionRow> succSnapshot = List.copyOf(succeeded);
            Platform.runLater(() -> {
                // Apply enabled state on FX thread only for verified successes
                // (service/PS now verify-after-write before returning true).
                for (BrowserExtensionRow r : succSnapshot) {
                    r.setEnabled(enable);
                }
                table.refresh();
                statusLabel.setText("Toggled " + s + " extension(s)." + (f > 0 ? " " + f + " failed." : ""));
                if (f > 0) {
                    String detail = s + " toggled, " + f + " failed."
                            + "\nClose all browsers and retry failed items."
                            + "\nIf failures persist, check logs (Secure Preferences HMAC reset, missing Preferences, or Firefox cache restore).";
                    new Alert(Alert.AlertType.WARNING, detail).showAndWait();
                }
                busy.set(false);
            });
        }, "browser-extensions-toggle");
        toggleThread = t;
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) -> {
            AppLogger.error("Browser extension toggle failed", ex);
            Platform.runLater(() -> {
                statusLabel.setText("Toggle failed: " + ex.getMessage());
                busy.set(false);
            });
        });
        t.start();
    }

    private java.util.Set<String> detectRunningBrowsers(List<BrowserExtensionRow> selected, AtomicBoolean cancelled) {
        java.util.Set<String> result = new java.util.HashSet<>();
        Process p = null;
        try {
            p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            // Wait with cancellation support
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while (p.isAlive()) {
                if (cancelled != null && cancelled.get()) {
                    p.destroyForcibly();
                    return result;
                }
                if (System.nanoTime() > deadline) {
                    p.destroyForcibly();
                    break;
                }
                try { Thread.sleep(50); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); p.destroyForcibly(); return result; }
            }
            String output = "";
            try {
                // Read after process finished; handle both normal and destroyed cases
                output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            } catch (Exception e) {
                output = "";
            }
            // Parse tasklist CSV exactly (first quoted field = image name) instead of
            // substring-matching the whole output, which false-positives on e.g.
            // updaters/installers containing "chrome" in their path or args (B2).
            java.util.Set<String> runningExes = new java.util.HashSet<>();
            for (String line : output.split("\\R")) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                // CSV: "chrome.exe","1234",...
                if (trimmed.startsWith("\"")) {
                    int end = trimmed.indexOf('"', 1);
                    if (end > 1) {
                        runningExes.add(trimmed.substring(1, end).toLowerCase());
                    }
                } else {
                    String first = trimmed.split("[,\\s]")[0].toLowerCase();
                    runningExes.add(first);
                }
            }
            for (BrowserExtensionRow ext : selected) {
                if (cancelled != null && cancelled.get()) break;
                String browserName = ext.getBrowser();
                if (browserName == null) continue;
                String expectedExe = switch (browserName) {
                    case "Chrome", "Chrome Canary" -> "chrome.exe";
                    case "Edge", "Edge Beta", "Edge Dev", "Edge Canary" -> "msedge.exe";
                    case "Firefox" -> "firefox.exe";
                    case "Brave" -> "brave.exe";
                    case "Opera", "Opera GX" -> "opera.exe";
                    case "Vivaldi" -> "vivaldi.exe";
                    default -> "";
                };
                if (!expectedExe.isEmpty() && runningExes.contains(expectedExe)) {
                    result.add(browserName);
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) {
                try { p.destroy(); } catch (Exception ignored) {}
                // Ensure process tree cleanup
                try {
                    if (p.isAlive()) p.destroyForcibly();
                } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private void toggleSelectAll() {
        // Scope = visible rows (matches user expectation for a filtered view).
        // The toggle confirmation lists ALL affected rows globally, so hidden
        // selections are never acted on silently (B4).
        boolean allSelected = filteredRows.stream()
                .filter(r -> !r.isIgnored())
                .allMatch(BrowserExtensionRow::isSelected);
        for (BrowserExtensionRow row : filteredRows) {
            if (!row.isIgnored()) {
                row.setSelected(!allSelected);
            }
        }
    }

    private void deselectAll() {
        // Global clear (not just visible) so filtered-out selections can never
        // get stuck hidden and surprise a later bulk toggle (B4).
        for (BrowserExtensionRow row : allRows) {
            row.setSelected(false);
        }
    }

    private void showIgnoredListDialog() {
        List<BrowserExtensionRow> ignored = allRows.stream()
                .filter(BrowserExtensionRow::isIgnored)
                .toList();
        if (ignored.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No ignored extensions.").showAndWait();
            return;
        }

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(AppInfo.DISPLAY_NAME);
        dialog.setHeaderText("Ignored Extensions (" + ignored.size() + ")");

        StringBuilder msg = new StringBuilder();
        for (BrowserExtensionRow r : ignored) {
            msg.append(describeRow(r)).append("\n");
        }
        dialog.setContentText(msg.toString());

        ButtonType unignoreAllBtn = new ButtonType("Unignore All");
        ButtonType closeBtn = new ButtonType("Close");
        dialog.getButtonTypes().setAll(unignoreAllBtn, closeBtn);

        dialog.showAndWait().ifPresent(result -> {
            if (result == unignoreAllBtn) {
                for (BrowserExtensionRow row : allRows) {
                    row.setIgnored(false);
                }
                saveIgnoredToSettings();
                table.refresh();
            }
        });
    }

    public void dispose() {
        scanCancelled.set(true);
        toggleCancelled.set(true);
        Thread st = scanThread;
        if (st != null) st.interrupt();
        Thread tt = toggleThread;
        if (tt != null) tt.interrupt();
    }
}
