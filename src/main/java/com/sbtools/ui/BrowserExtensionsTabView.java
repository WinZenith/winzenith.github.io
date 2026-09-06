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
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

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
    private static final List<String> FILTER_STATUS = List.of("All", "Enabled", "Disabled", "Ignored");

    static {
        List<String> tmp = new ArrayList<>();
        tmp.add("All");
        try {
            tmp.addAll(BrowserExtensionService.ALL_BROWSERS);
        } catch (Exception e) {
            tmp.addAll(List.of("Chrome", "Edge", "Firefox", "Brave", "Opera", "Opera GX", "Vivaldi"));
        }
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
    private volatile String scanTargetLabel = "all browsers";

    private final ObservableList<BrowserExtensionRow> allRows = FXCollections.observableArrayList();
    private final FilteredList<BrowserExtensionRow> filteredByBrowser = new FilteredList<>(allRows, r -> true);
    private final FilteredList<BrowserExtensionRow> filteredByStatus = new FilteredList<>(filteredByBrowser, r -> true);
    private final FilteredList<BrowserExtensionRow> filteredByProfile = new FilteredList<>(filteredByStatus, r -> true);
    private final FilteredList<BrowserExtensionRow> filteredRows = new FilteredList<>(filteredByProfile, r -> true);
    private final TableView<BrowserExtensionRow> table = new TableView<>(filteredRows);
    private final Map<BrowserExtensionRow, javafx.beans.value.ChangeListener<Boolean>> selectedListeners = new HashMap<>();

    private final Button scanButton = UIButton.primary("Scan All Browsers");
    private final Button rescanButton = UIButton.secondary("Rescan Browser");
    private final Button cancelButton = UIButton.secondary("Cancel");
    private final Button enableSelectedBtn = UIButton.primary("Enable");
    private final Button disableSelectedBtn = UIButton.secondary("Disable");
    private final Button selectAllBtn = UIButton.secondary("Select All");
    private final Button deselectAllBtn = UIButton.secondary("Deselect All");
    private final Button manageIgnoredBtn = UIButton.secondary("Manage Ignored");
    private final Button exportButton = UIButton.secondary("Export...");
    private final Button restoreBackupButton = UIButton.secondary("Restore Backup...");
    private final ComboBox<String> browserFilter = new ComboBox<>(
            FXCollections.observableArrayList(FILTER_BROWSERS));
    private final ComboBox<String> statusFilter = new ComboBox<>(
            FXCollections.observableArrayList(FILTER_STATUS));
    private final ComboBox<String> profileFilter = new ComboBox<>(
            FXCollections.observableArrayList(List.of("All")));
    private final CheckBox autoScanCheck = new CheckBox("Auto-scan on open");
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
        cancelButton.setDisable(true);

        enableSelectedBtn.getStyleClass().add("success");
        disableSelectedBtn.getStyleClass().add("button-outlined");
        selectAllBtn.getStyleClass().add("button-outlined");
        deselectAllBtn.getStyleClass().add("button-outlined");
        manageIgnoredBtn.getStyleClass().add("button-outlined");
        rescanButton.getStyleClass().add("button-outlined");
        cancelButton.getStyleClass().add("button-outlined");
        exportButton.getStyleClass().add("button-outlined");
        restoreBackupButton.getStyleClass().add("button-outlined");

        searchField.setPromptText("Search name, description, ID, permissions...");
        searchField.setPrefWidth(220);
        searchField.getStyleClass().add("sysinfo-search");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        Tooltip.install(scanButton, new Tooltip("Scan all known browsers (parallel, cancellable)"));
        Tooltip.install(rescanButton, new Tooltip("Re-scan only the browser selected in the Filter dropdown"));
        Tooltip.install(cancelButton, new Tooltip("Cancel the running scan or toggle operation"));
        Tooltip.install(exportButton, new Tooltip("Export the current (filtered) list to CSV or JSON"));
        Tooltip.install(restoreBackupButton, new Tooltip("Restore a Preferences/extensions.json backup created during a toggle"));
        Tooltip.install(statusFilter, new Tooltip("Filter by enabled / disabled / ignored state"));
        Tooltip.install(profileFilter, new Tooltip("Filter by browser profile (e.g. Default, Profile 1)"));
        Tooltip.install(autoScanCheck, new Tooltip("Automatically scan when this tab is opened"));

        restoreInitialFilters();

        scanButton.setOnAction(e -> startScan());
        rescanButton.setOnAction(e -> startRescanFiltered());
        cancelButton.setOnAction(e -> cancelRunning());
        enableSelectedBtn.setOnAction(e -> toggleSelected(true));
        disableSelectedBtn.setOnAction(e -> toggleSelected(false));
        selectAllBtn.setOnAction(e -> toggleSelectAll());
        deselectAllBtn.setOnAction(e -> deselectAll());
        manageIgnoredBtn.setOnAction(e -> showIgnoredListDialog());
        exportButton.setOnAction(e -> exportFiltered());
        restoreBackupButton.setOnAction(e -> showRestoreBackupDialog());
        autoScanCheck.setOnAction(e -> persistAutoScan());

        browserFilter.setOnAction(e -> { applyFilters(); persistFilters(); });
        statusFilter.setOnAction(e -> { applyFilters(); persistFilters(); });
        profileFilter.setOnAction(e -> applyFilters());

        HBox buttonRow = new HBox(12, scanButton, rescanButton, cancelButton, enableSelectedBtn, disableSelectedBtn, selectAllBtn, deselectAllBtn, manageIgnoredBtn, exportButton, restoreBackupButton);
        buttonRow.setAlignment(Pos.CENTER_LEFT);

        HBox filterRow = new HBox(12, new Label("Browser:"), browserFilter,
                new Label("Status:"), statusFilter,
                new Label("Profile:"), profileFilter,
                searchField, autoScanCheck);
        filterRow.setAlignment(Pos.CENTER_LEFT);

        HBox statusRow = new HBox(12, progressBar, statusLabel, selectionLabel);
        statusRow.setAlignment(Pos.CENTER_LEFT);

        VBox top = new VBox(6, buttonRow, filterRow, statusRow);
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
            rescanButton.setDisable(newVal);
            updateActionButtons();
            browserFilter.setDisable(newVal);
            statusFilter.setDisable(newVal);
            profileFilter.setDisable(newVal);
            searchField.setDisable(newVal);
            autoScanCheck.setDisable(newVal);
            selectAllBtn.setDisable(newVal || filteredRows.isEmpty());
            deselectAllBtn.setDisable(newVal || filteredRows.isEmpty());
            manageIgnoredBtn.setDisable(newVal);
            exportButton.setDisable(newVal);
            restoreBackupButton.setDisable(newVal);
            cancelButton.setDisable(!newVal);
            if (newVal) {
                progressBar.setVisible(true);
            } else {
                // Keep last determinate value visible briefly; hide on next scan start.
                progressBar.setVisible(false);
                progressBar.setProgress(0);
            }
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
            refreshProfileFilterOptions();
            updateActionButtons();
        });

        applyIgnoredFromSettings();
        maybeAutoScan();
    }

    private void restoreInitialFilters() {
        String browser = "All";
        String status = "All";
        boolean auto = false;
        try {
            AppSettings s = settingsStore.load();
            if (s.browserExtLastFilter() != null && !s.browserExtLastFilter().isBlank()) browser = s.browserExtLastFilter();
            if (s.browserExtLastStatusFilter() != null && !s.browserExtLastStatusFilter().isBlank()) status = s.browserExtLastStatusFilter();
            auto = s.browserExtAutoScan();
        } catch (Exception ignored) {
        }
        if (!FILTER_BROWSERS.contains(browser)) browser = "All";
        if (!FILTER_STATUS.contains(status)) status = "All";
        browserFilter.getSelectionModel().select(browser);
        statusFilter.getSelectionModel().select(status);
        profileFilter.getSelectionModel().select(0);
        autoScanCheck.setSelected(auto);
    }

    private void persistFilters() {
        try {
            String b = browserFilter.getSelectionModel().getSelectedItem();
            String s = statusFilter.getSelectionModel().getSelectedItem();
            settingsStore.update(cur -> cur.toBuilder()
                    .browserExtLastFilter(b != null ? b : "All")
                    .browserExtLastStatusFilter(s != null ? s : "All")
                    .build());
        } catch (Exception e) {
            AppLogger.warning("Failed to persist browser-ext filters: " + e.getMessage());
        }
    }

    private void persistAutoScan() {
        try {
            boolean auto = autoScanCheck.isSelected();
            settingsStore.update(cur -> cur.toBuilder().browserExtAutoScan(auto).build());
        } catch (Exception e) {
            AppLogger.warning("Failed to persist browser-ext autoscan: " + e.getMessage());
        }
    }

    private void maybeAutoScan() {
        try {
            if (autoScanCheck.isSelected()) {
                javafx.application.Platform.runLater(() -> {
                    if (!busy.get() && allRows.isEmpty()) startScan();
                });
            }
        } catch (Exception ignored) {
        }
    }

    private void refreshProfileFilterOptions() {
        try {
            String current = profileFilter.getSelectionModel().getSelectedItem();
            java.util.Set<String> profiles = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
            for (BrowserExtensionRow r : allRows) {
                String pn = r.getProfileName();
                if (pn != null && !pn.isBlank()) profiles.add(pn);
            }
            List<String> items = new ArrayList<>();
            items.add("All");
            items.addAll(profiles);
            // Preserve selection when possible; avoid firing applyFilters per item.
            String toSelect = (current != null && items.contains(current)) ? current : "All";
            profileFilter.setOnAction(null);
            profileFilter.getItems().setAll(items);
            profileFilter.getSelectionModel().select(toSelect);
            profileFilter.setOnAction(e -> applyFilters());
        } catch (Exception ignored) {
        }
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
        String statusVal = statusFilter.getSelectionModel().getSelectedItem();
        String profileVal = profileFilter.getSelectionModel().getSelectedItem();
        String searchText = searchField.getText();

        if (browserFilterVal == null || "All".equals(browserFilterVal)) {
            filteredByBrowser.setPredicate(r -> true);
        } else {
            filteredByBrowser.setPredicate(r -> browserFilterVal.equals(r.getBrowser()));
        }

        if (statusVal == null || "All".equals(statusVal)) {
            filteredByStatus.setPredicate(r -> true);
        } else if ("Enabled".equals(statusVal)) {
            filteredByStatus.setPredicate(r -> !r.isIgnored() && r.isEnabled());
        } else if ("Disabled".equals(statusVal)) {
            filteredByStatus.setPredicate(r -> !r.isIgnored() && !r.isEnabled());
        } else if ("Ignored".equals(statusVal)) {
            filteredByStatus.setPredicate(BrowserExtensionRow::isIgnored);
        }

        if (profileVal == null || "All".equals(profileVal)) {
            filteredByProfile.setPredicate(r -> true);
        } else {
            filteredByProfile.setPredicate(r -> profileVal.equals(r.getProfileName()));
        }

        if (searchText == null || searchText.isBlank()) {
            filteredRows.setPredicate(r -> true);
        } else {
            String lowerSearch = searchText.toLowerCase();
            filteredRows.setPredicate(r ->
                    (r.getName() != null && r.getName().toLowerCase().contains(lowerSearch))
                    || (r.getDescription() != null && r.getDescription().toLowerCase().contains(lowerSearch))
                    || (r.getExtensionId() != null && r.getExtensionId().toLowerCase().contains(lowerSearch))
                    || (r.getPermissions() != null && r.getPermissions().toLowerCase().contains(lowerSearch))
                    || (r.getProfileName() != null && r.getProfileName().toLowerCase().contains(lowerSearch))
                    || (r.getVersion() != null && r.getVersion().toLowerCase().contains(lowerSearch)));
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

        TableColumn<BrowserExtensionRow, String> profileCol = new TableColumn<>("Profile");
        profileCol.setCellValueFactory(c -> c.getValue().profileNameProperty());
        profileCol.setPrefWidth(110);
        profileCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.isBlank()) {
                    setText(null);
                    setTooltip(null);
                } else {
                    setText(item);
                    BrowserExtensionRow row = getTableRow() != null ? getTableRow().getItem() : null;
                    if (row != null && row.getProfilePath() != null && !row.getProfilePath().isBlank()) {
                        setTooltip(new Tooltip(row.getProfilePath()));
                    } else {
                        setTooltip(null);
                    }
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(200);
        descCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setTooltip(null);
                } else {
                    String short_ = item.length() > 120 ? item.substring(0, 120) + "..." : item;
                    setText(short_);
                    setTooltip(item.isBlank() ? null : new Tooltip(item));
                }
            }
        });

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
                    setTooltip(null);
                } else {
                    setText(item);
                    setStyle("-fx-font-size: 10px;");
                    setTooltip(item.isBlank() ? null : new Tooltip(item));
                }
            }
        });

        table.getColumns().addAll(checkCol, browserCol, nameCol, versionCol, statusCol,
                profileCol, installDateCol, descCol, permsCol);

        table.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                BrowserExtensionRow sel = table.getSelectionModel().getSelectedItem();
                if (sel != null) showDetailsDialog(sel);
            }
        });

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
                    applyFilters();
                    updateActionButtons();
                }
            });

            MenuItem detailsItem = new MenuItem("View Details");
            detailsItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) showDetailsDialog(r);
            });

            MenuItem copyStoreItem = new MenuItem("Copy Store URL");
            copyStoreItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) {
                    String url = com.sbtools.browserext.BrowserExtensionService.storeUrlFor(r.getBrowser(), r.getExtensionId());
                    if (url == null || url.isBlank()) {
                        statusLabel.setText("No known store URL for " + r.getBrowser() + ".");
                    } else {
                        copyToClipboard(url);
                        statusLabel.setText("Store URL copied.");
                    }
                }
            });

            MenuItem openStoreItem = new MenuItem("Open Store Page");
            openStoreItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) openStorePage(r);
            });

            ctxMenu.getItems().addAll(openFolderItem, copyIdItem, copyPathItem, copyStoreItem, openStoreItem,
                    new SeparatorMenuItem(), detailsItem,
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
        toggleCancelled.set(false);
        busy.set(true);
        scanTargetLabel = "all browsers";
        int total = BrowserExtensionService.ALL_BROWSERS.size();
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Scanning browser extensions (0/" + total + ")...");
        allRows.clear();

        Thread t = new Thread(() -> {
            try {
                List<BrowserExtensionRow> results = service.scanAllBrowsersParallel(
                        AppExecutors.scanPool(),
                        (BrowserExtensionService.ScanProgress) (browser, done, tot) -> Platform.runLater(() -> {
                            if (scanCancelled.get()) return;
                            progressBar.setProgress(tot <= 0 ? -1 : (double) done / tot);
                            statusLabel.setText("Scanning " + browser + " (" + done + "/" + tot + ")...");
                        }),
                        scanCancelled
                );
                if (scanCancelled.get()) {
                    throw new CancellationException("Scan cancelled");
                }
                Platform.runLater(() -> {
                    if (scanCancelled.get()) return;
                    progressBar.setProgress(1);
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

    private void startRescanFiltered() {
        if (busy.get()) return;
        String browser = browserFilter.getSelectionModel().getSelectedItem();
        if (browser == null || "All".equals(browser)) {
            startScan();
            return;
        }
        scanCancelled.set(false);
        toggleCancelled.set(false);
        busy.set(true);
        scanTargetLabel = browser;
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("Re-scanning " + browser + "...");

        final String target = browser;
        Thread t = new Thread(() -> {
            try {
                List<BrowserExtensionRow> fresh = service.scanBrowser(target, scanCancelled);
                if (scanCancelled.get()) throw new CancellationException("Scan cancelled");
                Platform.runLater(() -> {
                    if (scanCancelled.get()) return;
                    // Replace only rows for this browser; keep other browsers + ignored flags.
                    java.util.Set<String> ignoredBefore = new java.util.HashSet<>();
                    try {
                        AppSettings s = settingsStore.load();
                        if (s.ignoredBrowserExtensionIds() != null) ignoredBefore.addAll(s.ignoredBrowserExtensionIds());
                    } catch (Exception ignored) {
                    }
                    allRows.removeIf(r -> target.equals(r.getBrowser()));
                    for (BrowserExtensionRow r : fresh) {
                        boolean ign = ignoredBefore.contains(qualifiedKey(r))
                                || ignoredBefore.contains(legacyQualifiedKey(r))
                                || ignoredBefore.contains(r.getExtensionId());
                        r.setIgnored(ign);
                    }
                    allRows.addAll(fresh);
                    applyFilters();
                    updateActionButtons();
                    progressBar.setProgress(1);
                    statusLabel.setText("Re-scanned " + target + ": " + fresh.size() + " extension(s). " + buildStatusText(allRows.stream().toList()));
                });
            } catch (CancellationException ce) {
                AppLogger.info("Browser extension re-scan cancelled");
                Platform.runLater(() -> statusLabel.setText("Scan cancelled."));
            } catch (Exception e) {
                AppLogger.error("Browser extension re-scan failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Re-scan failed: " + e.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Re-scan of " + target + " failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "browser-extensions-rescan");
        scanThread = t;
        t.setDaemon(true);
        t.start();
    }

    private void cancelRunning() {
        scanCancelled.set(true);
        toggleCancelled.set(true);
        statusLabel.setText("Cancelling " + scanTargetLabel + "...");
        Thread st = scanThread;
        if (st != null) st.interrupt();
        Thread tt = toggleThread;
        if (tt != null) tt.interrupt();
    }

    private void exportFiltered() {
        if (busy.get()) return;
        List<BrowserExtensionRow> snapshot = List.copyOf(filteredRows);
        if (snapshot.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Nothing to export — the current filter has no rows.").showAndWait();
            return;
        }
        FileChooser fc = new FileChooser();
        fc.setTitle("Export Browser Extensions");
        fc.setInitialFileName("browser-extensions");
        FileChooser.ExtensionFilter csv = new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv");
        FileChooser.ExtensionFilter json = new FileChooser.ExtensionFilter("JSON (*.json)", "*.json");
        fc.getExtensionFilters().addAll(csv, json);
        fc.setSelectedExtensionFilter(csv);
        File file = fc.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) return;
        String lower = file.getName().toLowerCase();
        boolean wantJson = fc.getSelectedExtensionFilter() == json || lower.endsWith(".json");
        File target = file;
        if (wantJson && !lower.endsWith(".json")) target = new File(file.getParent(), file.getName() + ".json");
        if (!wantJson && !lower.endsWith(".csv")) target = new File(file.getParent(), file.getName() + ".csv");
        final File finalTarget = target;
        final boolean finalJson = wantJson;
        statusLabel.setText("Exporting " + snapshot.size() + " extension(s)...");
        AppExecutors.ioPool().submit(() -> {
            try {
                String content = finalJson ? toJson(snapshot) : toCsv(snapshot);
                java.nio.file.Files.writeString(finalTarget.toPath(), content, java.nio.charset.StandardCharsets.UTF_8);
                Platform.runLater(() -> statusLabel.setText("Exported " + snapshot.size() + " extension(s) to " + finalTarget.getName()));
            } catch (Exception ex) {
                AppLogger.error("Failed to export browser extensions", ex);
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Export failed:\n" + ex.getMessage()).showAndWait());
            }
        });
    }

    private static String toCsv(List<BrowserExtensionRow> rows) {
        StringBuilder sb = new StringBuilder("Browser,Profile,Name,ID,Version,Status,InstallDate,Path,ProfilePath,Description,Permissions\n");
        for (BrowserExtensionRow r : rows) {
            sb.append(csvCell(r.getBrowser())).append(',')
                    .append(csvCell(r.getProfileName())).append(',')
                    .append(csvCell(r.getName())).append(',')
                    .append(csvCell(r.getExtensionId())).append(',')
                    .append(csvCell(r.getVersion())).append(',')
                    .append(csvCell(r.isIgnored() ? "Ignored" : (r.isEnabled() ? "Enabled" : "Disabled"))).append(',')
                    .append(csvCell(r.getInstallDate())).append(',')
                    .append(csvCell(r.getPath())).append(',')
                    .append(csvCell(r.getProfilePath())).append(',')
                    .append(csvCell(r.getDescription())).append(',')
                    .append(csvCell(r.getPermissions())).append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(String v) {
        if (v == null) return "\"\"";
        return "\"" + v.replace("\"", "\"\"") + "\"";
    }

    private static String toJson(List<BrowserExtensionRow> rows) {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (BrowserExtensionRow r : rows) {
                Map<String, Object> m = new java.util.LinkedHashMap<>();
                m.put("browser", r.getBrowser());
                m.put("profile", r.getProfileName());
                m.put("name", r.getName());
                m.put("id", r.getExtensionId());
                m.put("version", r.getVersion());
                m.put("enabled", r.isEnabled());
                m.put("ignored", r.isIgnored());
                m.put("installDate", r.getInstallDate());
                m.put("path", r.getPath());
                m.put("profilePath", r.getProfilePath());
                m.put("description", r.getDescription());
                m.put("permissions", r.getPermissions());
                list.add(m);
            }
            return com.sbtools.util.JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(list);
        } catch (Exception e) {
            throw new RuntimeException("JSON serialization failed: " + e.getMessage(), e);
        }
    }

    private void showDetailsDialog(BrowserExtensionRow r) {
        Dialog<Void> dlg = new Dialog<>();
        dlg.setTitle(AppInfo.DISPLAY_NAME);
        dlg.setHeaderText(r.getName() != null && !r.getName().isBlank() ? r.getName() : r.getExtensionId());
        dlg.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(6);
        grid.setPadding(new Insets(12));
        int row = 0;
        row = addDetailRow(grid, row, "Browser:", nvl(r.getBrowser()));
        row = addDetailRow(grid, row, "Profile:", nvl(r.getProfileName()));
        row = addDetailRow(grid, row, "Version:", nvl(r.getVersion()));
        row = addDetailRow(grid, row, "Status:", r.isIgnored() ? "Ignored" : (r.isEnabled() ? "Enabled" : "Disabled"));
        row = addDetailRow(grid, row, "Extension ID:", nvl(r.getExtensionId()));
        row = addDetailRow(grid, row, "Install date:", nvl(r.getInstallDate()));
        row = addDetailRow(grid, row, "Profile path:", nvl(r.getProfilePath()));
        row = addDetailRow(grid, row, "Extension path:", nvl(r.getPath()));
        String store = com.sbtools.browserext.BrowserExtensionService.storeUrlFor(r.getBrowser(), r.getExtensionId());
        if (store != null && !store.isBlank()) {
            row = addDetailRow(grid, row, "Store URL:", store);
        }
        Label descTitle = new Label("Description:");
        descTitle.setStyle("-fx-font-weight: bold;");
        TextArea desc = new TextArea(nvl(r.getDescription()));
        desc.setEditable(false);
        desc.setWrapText(true);
        desc.setPrefRowCount(3);
        grid.add(descTitle, 0, row);
        grid.add(desc, 1, row);
        row++;
        Label permTitle = new Label("Permissions:");
        permTitle.setStyle("-fx-font-weight: bold;");
        TextArea perms = new TextArea(nvl(r.getPermissions()));
        perms.setEditable(false);
        perms.setWrapText(true);
        perms.setPrefRowCount(4);
        grid.add(permTitle, 0, row);
        grid.add(perms, 1, row);
        dlg.getDialogPane().setContent(grid);
        dlg.getDialogPane().setPrefWidth(620);
        dlg.showAndWait();
    }

    private static int addDetailRow(GridPane grid, int row, String label, String value) {
        Label k = new Label(label);
        k.setStyle("-fx-font-weight: bold;");
        Label v = new Label(value != null && !value.isBlank() ? value : "—");
        v.setWrapText(true);
        v.setMaxWidth(440);
        grid.add(k, 0, row);
        grid.add(v, 1, row);
        return row + 1;
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private void openStorePage(BrowserExtensionRow r) {
        String url = com.sbtools.browserext.BrowserExtensionService.storeUrlFor(r.getBrowser(), r.getExtensionId());
        if (url == null || url.isBlank()) {
            statusLabel.setText("No known store URL for " + r.getBrowser() + ".");
            return;
        }
        try {
            Desktop.getDesktop().browse(new java.net.URI(url));
        } catch (Exception ex) {
            copyToClipboard(url);
            statusLabel.setText("Could not open browser; store URL copied instead.");
        }
    }

    private void showRestoreBackupDialog() {
        java.util.Set<String> profiles = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BrowserExtensionRow r : allRows) {
            if (r.getProfilePath() != null && !r.getProfilePath().isBlank()) profiles.add(r.getProfilePath());
        }
        if (profiles.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Scan first — no profile paths are known yet.").showAndWait();
            return;
        }
        // Collect backups across known profiles (cap listing to avoid huge dialogs).
        List<java.nio.file.Path> backups = new ArrayList<>();
        for (String pp : profiles) {
            backups.addAll(com.sbtools.browserext.BrowserExtensionService.listProfileBackups(pp));
            if (backups.size() >= 30) break;
        }
        if (backups.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No Preferences/extensions.json backups found.\nBackups are created automatically during enable/disable.").showAndWait();
            return;
        }
        javafx.scene.control.ChoiceDialog<java.nio.file.Path> choice =
                new javafx.scene.control.ChoiceDialog<>(backups.get(0), backups);
        choice.setTitle(AppInfo.DISPLAY_NAME);
        choice.setHeaderText("Restore profile backup (" + backups.size() + " found)");
        choice.setContentText("Close all browsers first, then pick a backup to restore:");
        choice.showAndWait().ifPresent(sel -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Restore\n" + sel.getFileName() + "\nover its live file?\n\nClose all browsers first. This overwrites the current Preferences/extensions.json.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Restore Backup");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            boolean ok = com.sbtools.browserext.BrowserExtensionService.restoreProfileBackup(sel);
            if (ok) {
                statusLabel.setText("Backup restored. Re-scan to refresh the list.");
                new Alert(Alert.AlertType.INFORMATION, "Backup restored.\nClick Scan to refresh.").showAndWait();
            } else {
                new Alert(Alert.AlertType.ERROR, "Restore failed — see logs.").showAndWait();
            }
        });
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
            String pn = r.getProfileName();
            if (pn != null && !pn.isBlank()) return pn;
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
        try {
            if (cancelled != null && cancelled.get()) return result;
            // Cached probe: one tasklist per ~2s instead of one per row (batch-toggle optimization).
            // Exact exe-name matching avoids false positives from updater paths (B2).
            java.util.Set<String> runningExes = com.sbtools.browserext.BrowserProcessProbe.runningExes();
            for (BrowserExtensionRow ext : selected) {
                if (cancelled != null && cancelled.get()) break;
                String browserName = ext.getBrowser();
                if (browserName == null) continue;
                String expectedExe = com.sbtools.browserext.BrowserExtensionService.expectedExeFor(browserName);
                if (expectedExe != null && !expectedExe.isBlank()
                        && runningExes.contains(expectedExe.toLowerCase())) {
                    result.add(browserName);
                }
            }
        } catch (Exception ignored) {
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
                applyFilters();
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
