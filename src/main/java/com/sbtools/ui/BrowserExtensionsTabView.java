package com.sbtools.ui;

import com.sbtools.browserext.BrowserExtensionRow;
import com.sbtools.browserext.BrowserExtensionService;
import com.sbtools.browserext.BrowserProfileToggle;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

public class BrowserExtensionsTabView extends BorderPane {

    private static final List<String> FILTER_BROWSERS;
    private static final List<String> FILTER_STATUS = List.of("All", "Enabled", "Disabled", "Managed", "Ignored");

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
    /** Shared App busy for window-close guard; null in tests. */
    private final BooleanProperty globalBusy;
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
        this(null, adminCheck, new SettingsStore());
    }

    public BrowserExtensionsTabView(BooleanSupplier adminCheck, SettingsStore settingsStore) {
        this(null, adminCheck, settingsStore);
    }

    public BrowserExtensionsTabView(BooleanProperty globalBusy, BooleanSupplier adminCheck, SettingsStore settingsStore) {
        this.globalBusy = globalBusy;
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
        return BrowserExtensionService.ignoredKey(row);
    }

    private static String legacyQualifiedKey(BrowserExtensionRow row) {
        return BrowserExtensionService.legacyIgnoredKey(row);
    }

    private void applyIgnoredFromSettings() {
        try {
            AppSettings settings = settingsStore.load();
            List<String> ignoredIds = settings.ignoredBrowserExtensionIds();
            if (ignoredIds != null) {
                java.util.Set<String> ignoredSet = new java.util.HashSet<>(ignoredIds);
                for (BrowserExtensionRow row : allRows) {
                    boolean ignored = ignoredSet.contains(qualifiedKey(row))
                            || ignoredSet.contains(legacyQualifiedKey(row));
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
            List<BrowserExtensionRow> snapshotRows = List.copyOf(allRows);
            settingsStore.update(current -> current.toBuilder()
                    .ignoredBrowserExtensionIds(
                            BrowserExtensionService.mergeIgnoredIds(current.ignoredBrowserExtensionIds(), snapshotRows))
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
        } else if ("Managed".equals(statusVal)) {
            filteredByStatus.setPredicate(BrowserExtensionRow::isManaged);
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

        TableColumn<BrowserExtensionRow, BrowserExtensionRow> checkCol = UiColumn.of(" ");
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

        TableColumn<BrowserExtensionRow, String> browserCol = UiColumn.of("Browser");
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

        TableColumn<BrowserExtensionRow, String> nameCol = UiColumn.of("Extension name");
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

        TableColumn<BrowserExtensionRow, String> versionCol = UiColumn.of("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(80);

        TableColumn<BrowserExtensionRow, String> statusCol = UiColumn.of("Status");
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
                        setTooltip(null);
                    } else if (row != null && row.isOrphaned()) {
                        setText("Orphaned");
                        setStyle("-fx-text-fill: #6272a4; -fx-font-weight: bold;");
                        setTooltip(new Tooltip("Not present in Preferences — leftover folder, cannot be toggled."));
                    } else if (row != null && row.isManaged()) {
                        boolean isEnabled = "true".equals(item);
                        setText(isEnabled ? "Managed (On)" : "Managed (Off)");
                        setStyle("-fx-text-fill: #ffb86c; -fx-font-weight: bold;");
                        String src = row.getInstallSource();
                        setTooltip(src != null && !src.isBlank()
                                ? new Tooltip("Managed by " + (src.equals("policy") ? "enterprise policy"
                                        : src.equals("system") ? "the browser (system add-on)"
                                        : "default installation") + " — cannot be toggled here.")
                                : new Tooltip("Managed by the browser or policy — cannot be toggled here."));
                    } else {
                        boolean isEnabled = "true".equals(item);
                        setText(isEnabled ? "Enabled" : "Disabled");
                        setStyle(isEnabled
                                ? "-fx-text-fill: #50fa7b; -fx-font-weight: bold;"
                                : "-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                        setTooltip(null);
                    }
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> installDateCol = UiColumn.of("Install date");
        installDateCol.setCellValueFactory(c -> c.getValue().installDateProperty());
        installDateCol.setPrefWidth(130);

        TableColumn<BrowserExtensionRow, String> profileCol = UiColumn.of("Profile");
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

        TableColumn<BrowserExtensionRow, String> descCol = UiColumn.of("Description");
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

        TableColumn<BrowserExtensionRow, String> permsCol = UiColumn.of("Permissions");
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
        String targetPath = resolveExtensionFolderPath(row);
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
        setBusy(true);
        scanTargetLabel = "all browsers";
        int total = BrowserExtensionService.ALL_BROWSERS.size();
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        statusLabel.setText("Scanning browser extensions (0/" + total + ")...");

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
                Platform.runLater(() -> setBusy(false));
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
        setBusy(true);
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
                                || ignoredBefore.contains(legacyQualifiedKey(r));
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
                Platform.runLater(() -> setBusy(false));
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
        fc.setTitle(com.sbtools.util.UiText.label("Export browser extensions"));
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
        StringBuilder sb = new StringBuilder("Browser,Profile,Name,ID,Version,Status,InstallDate,Path,ProfilePath,Description,Permissions,Managed,InstallSource\n");
        for (BrowserExtensionRow r : rows) {
            sb.append(csvCell(r.getBrowser())).append(',')
                    .append(csvCell(r.getProfileName())).append(',')
                    .append(csvCell(r.getName())).append(',')
                    .append(csvCell(r.getExtensionId())).append(',')
                    .append(csvCell(r.getVersion())).append(',')
                    .append(csvCell(r.isIgnored() ? "Ignored" : (r.isOrphaned() ? "Orphaned" : (r.isEnabled() ? "Enabled" : "Disabled")))).append(',')
                    .append(csvCell(r.getInstallDate())).append(',')
                    .append(csvCell(r.getPath())).append(',')
                    .append(csvCell(r.getProfilePath())).append(',')
                    .append(csvCell(r.getDescription())).append(',')
                    .append(csvCell(r.getPermissions())).append(',')
                    .append(csvCell(r.isManaged() ? "Yes" : "No")).append(',')
                    .append(csvCell(r.getInstallSource())).append('\n');
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
                m.put("managed", r.isManaged());
                m.put("installSource", r.getInstallSource());
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
        row = addDetailRow(grid, row, "Status:", r.isIgnored() ? "Ignored"
                : (r.isOrphaned() ? "Orphaned" : (r.isEnabled() ? "Enabled" : "Disabled")));
        row = addDetailRow(grid, row, "Managed:", r.isManaged()
                ? "Yes" + (r.getInstallSource() != null && !r.getInstallSource().isBlank()
                        ? " (" + r.getInstallSource() + ")" : "")
                : "No");
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
        if (BrowserProfileToggle.shouldRefuseNewUiOp(busy.get(), globalBusyHeld())) {
            if (!busy.get()) {
                statusLabel.setText("Another operation is running — try again when it finishes.");
            }
            return;
        }
        java.util.Set<String> profiles = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (BrowserExtensionRow r : allRows) {
            if (r.getProfilePath() != null && !r.getProfilePath().isBlank()) profiles.add(r.getProfilePath());
        }
        if (profiles.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Scan first — no profile paths are known yet.").showAndWait();
            return;
        }
        List<java.nio.file.Path> backups = new ArrayList<>();
        for (String pp : profiles) {
            backups.addAll(com.sbtools.browserext.BrowserExtensionService.listProfileBackups(pp));
        }
        backups.sort((a, b) -> {
            try {
                return Files.getLastModifiedTime(b).compareTo(Files.getLastModifiedTime(a));
            } catch (Exception e) {
                return 0;
            }
        });
        if (backups.size() > 30) {
            backups = new ArrayList<>(backups.subList(0, 30));
        }
        if (backups.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No Preferences/extensions.json backups found.\nBackups are created automatically during enable/disable.").showAndWait();
            return;
        }
        // Hold busy through the picker so Scan/Enable cannot start and then
        // look like "we own the slot" via shouldRefuseAfterDialog.
        setBusy(true);
        toggleCancelled.set(false);
        javafx.scene.control.ChoiceDialog<java.nio.file.Path> choice =
                new javafx.scene.control.ChoiceDialog<>(backups.get(0), backups);
        choice.setTitle(AppInfo.DISPLAY_NAME);
        choice.setHeaderText("Restore profile backup (" + backups.size() + " found)");
        choice.setContentText("Close all browsers first, then pick a backup to restore:");
        java.util.Optional<java.nio.file.Path> picked = choice.showAndWait();
        if (picked.isEmpty()) {
            setBusy(false);
            return;
        }
        picked.ifPresent(sel -> {
            if (BrowserProfileToggle.shouldRefuseAfterDialog(busy.get(), globalBusyHeld())) {
                statusLabel.setText("Another operation is running — try again when it finishes.");
                if (busy.get()) setBusy(false);
                return;
            }
            // Hard block like toggle: restoring while the browser runs is silently
            // lost (browser overwrites Preferences/extensions.json on exit).
            // Probe off the FX thread, then re-check pattern.
            statusLabel.setText("Checking running browsers...");
            AppExecutors.ioPool().submit(() -> {
                java.util.Set<String> browsers = browsersForProfile(sel.getParent() != null
                        ? sel.getParent().toString() : "");
                List<BrowserExtensionRow> probes = allRows.stream()
                        .filter(r -> browsers.contains(r.getBrowser()))
                        .toList();
                List<BrowserExtensionRow> probeRows = probes.isEmpty()
                        ? allRows.stream().toList() : probes;
                BrowserRunningState runningState = BrowserRunningState.empty();
                try {
                    runningState = probeRunningState(probeRows, true);
                } catch (Exception ex) {
                    AppLogger.warning("Failed to detect running browsers before restore: " + ex.getMessage());
                }
                final BrowserRunningState stateSnapshot = runningState;
                final java.util.Set<String> unknownSnapshot = unknownExeBrowsers(probeRows);
                Platform.runLater(() -> {
                    if (toggleCancelled.get()) {
                        setBusy(false);
                        return;
                    }
                    if (!stateSnapshot.runningLabels().isEmpty()) {
                        new Alert(Alert.AlertType.ERROR,
                                formatRunningBrowserWarning(stateSnapshot.runningLabels())
                                        + "\n\nAborted — close all browsers and try again.\n"
                                        + "Restoring now would be silently overwritten on browser exit.")
                                .showAndWait();
                        statusLabel.setText("Restore aborted: browsers still running.");
                        setBusy(false);
                        return;
                    }
                    if (runningStateUnverified(stateSnapshot, unknownSnapshot)) {
                        StringBuilder why = new StringBuilder(
                                "The running-process check could not verify all browsers are closed");
                        appendRunningProbeWarnings(why, stateSnapshot.probeOk(), unknownSnapshot,
                                stateSnapshot.unverifiedLabels());
                        why.append(".\nRestoring while a browser runs is silently lost on browser exit."
                                + "\n\nClose ALL browsers now, then click OK to restore, or Cancel to abort.");
                        Alert unreliable = new Alert(Alert.AlertType.WARNING);
                        unreliable.setTitle(AppInfo.DISPLAY_NAME);
                        unreliable.setHeaderText("Could not verify browsers are closed");
                        unreliable.setContentText(why.toString());
                        unreliable.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                        if (unreliable.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                            statusLabel.setText("Restore aborted: running state unverified.");
                            setBusy(false);
                            return;
                        }
                    }
                    restoreBackupAfterGuard(sel);
                });
            });
        });
    }

    private java.util.Set<String> browsersForProfile(String profilePath) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            if (profilePath == null || profilePath.isBlank()) return out;
            String norm = java.nio.file.Paths.get(profilePath).toAbsolutePath().normalize().toString();
            for (BrowserExtensionRow r : allRows) {
                try {
                    String pp = r.getProfilePath();
                    if (pp == null || pp.isBlank()) continue;
                    String rowNorm = java.nio.file.Paths.get(pp).toAbsolutePath().normalize().toString();
                    if (rowNorm.equalsIgnoreCase(norm) && r.getBrowser() != null) {
                        out.add(r.getBrowser());
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private void restoreBackupAfterGuard(java.nio.file.Path sel) {
            if (BrowserProfileToggle.shouldRefuseAfterDialog(busy.get(), globalBusyHeld())) {
                statusLabel.setText("Another operation is running — try again when it finishes.");
                if (busy.get()) setBusy(false);
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Restore\n" + sel.getFileName() + "\nover its live file?\n\nClose all browsers first. This overwrites the current Preferences/extensions.json.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText(com.sbtools.util.UiText.label("Restore backup"));
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                setBusy(false);
                return;
            }
            if (!busy.get()) setBusy(true);
            statusLabel.setText("Re-checking running browsers...");
            AppExecutors.ioPool().submit(() -> {
                java.util.Set<String> browsers = browsersForProfile(sel.getParent() != null
                        ? sel.getParent().toString() : "");
                List<BrowserExtensionRow> probes = allRows.stream()
                        .filter(r -> browsers.contains(r.getBrowser()))
                        .toList();
                List<BrowserExtensionRow> probeRows = probes.isEmpty()
                        ? allRows.stream().toList() : probes;
                BrowserRunningState runningState = BrowserRunningState.empty();
                try {
                    runningState = probeRunningState(probeRows, true);
                } catch (Exception ex) {
                    AppLogger.warning("Failed to re-check running browsers before restore: " + ex.getMessage());
                }
                final BrowserRunningState recheck = runningState;
                if (!recheck.runningLabels().isEmpty()
                        || runningStateUnverified(recheck.probeOk(), java.util.Set.of(),
                                recheck.unverifiedLabels())
                        || !recheck.probeOk()) {
                    Platform.runLater(() -> {
                        String msg = !recheck.runningLabels().isEmpty()
                                ? formatRunningBrowserWarning(recheck.runningLabels())
                                + "\n\nAborted — close all browsers and try again.\n"
                                + "Restoring now would be silently overwritten on browser exit."
                                : "Could not verify browsers are closed (process check failed).\n"
                                + "Aborted — close all browsers and try again.";
                        new Alert(Alert.AlertType.ERROR, msg).showAndWait();
                        statusLabel.setText("Restore aborted: browsers running or unverified.");
                        setBusy(false);
                    });
                    return;
                }
                boolean ok = BrowserExtensionService.restoreProfileBackup(sel);
                Platform.runLater(() -> {
                    if (ok) {
                        statusLabel.setText("Backup restored. Re-scan to refresh the list.");
                        new Alert(Alert.AlertType.INFORMATION, "Backup restored.\nClick Scan to refresh.").showAndWait();
                    } else {
                        new Alert(Alert.AlertType.ERROR, "Restore failed — see logs.").showAndWait();
                    }
                    setBusy(false);
                });
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
        if (BrowserProfileToggle.shouldRefuseNewUiOp(busy.get(), globalBusyHeld())) {
            if (!busy.get()) {
                statusLabel.setText("Another operation is running — try again when it finishes.");
            }
            return;
        }
        // Global selection scope (allRows) is intentional, but the confirmation
        // below explicitly lists every affected row so filtered-out (hidden)
        // selections can never be toggled silently (B4).
        List<BrowserExtensionRow> selected = allRows.stream()
                .filter(r -> r.isSelected() && !r.isIgnored())
                .toList();
        if (selected.isEmpty()) return;

        String action = enable ? "enable" : "disable";
        toggleCancelled.set(false);
        setBusy(true);
        statusLabel.setText("Checking running browsers...");

        // Offload blocking tasklist to background pool to avoid FX freeze (Blocker 3)
        AppExecutors.ioPool().submit(() -> {
            java.util.Set<String> browsersToWarn = new java.util.HashSet<>();
            java.util.Set<String> unknownExes = new java.util.HashSet<>();
            java.util.Set<String> unverifiedChannels = new java.util.HashSet<>();
            BrowserRunningState runningState = BrowserRunningState.empty();
            try {
                runningState = probeRunningState(selected, false);
                browsersToWarn = runningState.runningLabels();
                unverifiedChannels = runningState.unverifiedLabels();
                unknownExes = unknownExeBrowsers(selected);
            } catch (Exception ex) {
                AppLogger.warning("Failed to detect running browsers: " + ex.getMessage());
            }
            final java.util.Set<String> warnSnapshot = browsersToWarn;
            final java.util.Set<String> unknownSnapshot = unknownExes;
            final java.util.Set<String> unverifiedSnapshot = unverifiedChannels;
            final boolean probeOkSnapshot = runningState.probeOk();
            final List<BrowserExtensionRow> selectedSnapshot = List.copyOf(selected);
            Platform.runLater(() -> {
                if (toggleCancelled.get()) {
                    setBusy(false);
                    return;
                }
                // Keep busy through confirm so Scan/Enable/Restore cannot stack.
                // Fail-closed on unreliable probe: an empty result after a
                // failed tasklist (or a browser with no known exe image) means
                // "unknown", not "not running". Require informed consent
                // instead of silently toggling while a browser may be open.
                if (warnSnapshot.isEmpty()
                        && runningStateUnverified(probeOkSnapshot, unknownSnapshot, unverifiedSnapshot)) {
                    StringBuilder why = new StringBuilder();
                    appendRunningProbeWarnings(why, probeOkSnapshot, unknownSnapshot, unverifiedSnapshot);
                    Alert unreliable = new Alert(Alert.AlertType.WARNING);
                    unreliable.setTitle(AppInfo.DISPLAY_NAME);
                    unreliable.setHeaderText("Could not verify browsers are closed");
                    unreliable.setContentText(why
                            + "\nToggling while a browser runs is silently reverted on browser exit."
                            + "\n\nClose ALL browsers now, then click OK to continue, or Cancel to abort.");
                    unreliable.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                    if (unreliable.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                        statusLabel.setText("Toggle aborted: running state unverified.");
                        setBusy(false);
                        return;
                    }
                }
                // Hard block: the Preferences/extensions.json lock probe cannot
                // reliably detect a running browser (shared read), and writing
                // while it runs loses the change on browser exit. Do NOT offer
                // "continue anyway" — require close + re-check (B2).
                if (!warnSnapshot.isEmpty()) {
                    Alert blocked = new Alert(Alert.AlertType.WARNING);
                    blocked.setTitle(AppInfo.DISPLAY_NAME);
                    blocked.setHeaderText("Close browsers before " + action + "ing");
                    blocked.setContentText(formatRunningBrowserWarning(warnSnapshot)
                            + "\n\nToggling now would likely fail or be reverted (locked Preferences / Secure Preferences).\n"
                            + "Please close the browsers, then click OK to re-check, or Cancel.");
                    blocked.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
                    if (blocked.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                        setBusy(false);
                        return;
                    }
                    statusLabel.setText("Re-checking running browsers...");
                    AppExecutors.ioPool().submit(() -> {
                        BrowserRunningState recheckState = BrowserRunningState.empty();
                        try {
                            recheckState = probeRunningState(selectedSnapshot, true);
                        } catch (Exception ex) {
                            AppLogger.warning("Failed to re-check running browsers: " + ex.getMessage());
                        }
                        final BrowserRunningState recheckSnapshot = recheckState;
                        Platform.runLater(() -> {
                            if (runningStateUnverified(recheckSnapshot.probeOk(), java.util.Set.of(),
                                    recheckSnapshot.unverifiedLabels())
                                    || !recheckSnapshot.probeOk()) {
                                new Alert(Alert.AlertType.ERROR,
                                        "Could not verify browsers are closed (process check failed).\n"
                                                + "Aborted — close all browsers and try again.")
                                        .showAndWait();
                                statusLabel.setText("Toggle aborted: running state unverified.");
                                setBusy(false);
                                return;
                            }
                            if (!recheckSnapshot.runningLabels().isEmpty()) {
                                new Alert(Alert.AlertType.ERROR,
                                        formatRunningBrowserWarning(recheckSnapshot.runningLabels())
                                                + "\n\nAborted — close them and try again.")
                                        .showAndWait();
                                statusLabel.setText("Toggle aborted: browsers still running.");
                                setBusy(false);
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
        if (BrowserProfileToggle.shouldRefuseAfterDialog(busy.get(), globalBusyHeld())) {
            statusLabel.setText("Another operation is running — try again when it finishes.");
            if (busy.get()) setBusy(false);
            return;
        }
        // Fail-closed for managed / leftover folders: policy items are re-enforced
        // by the browser, and orphaned dirs have no Preferences entry to edit.
        List<BrowserExtensionRow> blocked = selected.stream()
                .filter(r -> r.isManaged() || r.isOrphaned())
                .toList();
        if (!blocked.isEmpty()) {
            StringBuilder blockedMsg = new StringBuilder(
                    "Cannot " + action + ": " + blocked.size() + " selected extension(s) "
                            + "are managed by policy, default installation, the browser itself, "
                            + "or leftover folders not present in Preferences.\n\n"
                            + buildAffectedListText(blocked)
                            + "\nDeselect them and retry (use the Managed filter to find policy items).");
            new Alert(Alert.AlertType.ERROR, blockedMsg.toString()).showAndWait();
            statusLabel.setText("Toggle aborted: selection contains managed or leftover extensions.");
            setBusy(false);
            return;
        }
        StringBuilder msg = new StringBuilder(action.substring(0, 1).toUpperCase() + action.substring(1)
                + " " + selected.size() + " extension(s)?\n\n"
                + buildAffectedListText(selected)
                + "\nBrowsers must stay closed until the toggle finishes.");
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, msg.toString());
        confirm.setTitle(AppInfo.DISPLAY_NAME);
        confirm.setHeaderText((enable ? "Enable" : "Disable") + " Extensions");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            setBusy(false);
            return;
        }
        if (BrowserProfileToggle.shouldRefuseAfterDialog(busy.get(), globalBusyHeld())) {
            statusLabel.setText("Another operation is running — try again when it finishes.");
            if (busy.get()) setBusy(false);
            return;
        }
        if (!busy.get()) setBusy(true);
        toggleCancelled.set(false);
        statusLabel.setText(action.substring(0, 1).toUpperCase() + action.substring(1) + "ing " + selected.size() + " extension(s)...");

        Thread t = new Thread(() -> {
            int success = 0;
            int fail = 0;
            int skipped = 0;
            List<BrowserExtensionRow> succeeded = new ArrayList<>();
            for (BrowserExtensionRow ext : selected) {
                if (toggleCancelled.get() || Thread.currentThread().isInterrupted()) break;
                // Re-check browser liveness before each item with a FRESH probe:
                // a cached entry could miss a browser relaunched inside the
                // TTL window and further writes would be silently lost.
                try {
                    BrowserRunningState perItem = probeRunningState(List.of(ext), true);
                    if (runningStateUnverified(perItem.probeOk(), java.util.Set.of(),
                            perItem.unverifiedLabels()) || !perItem.probeOk()) {
                        AppLogger.warning("Toggle aborted mid-batch: process probe unreliable.");
                        skipped = selected.size() - success - fail;
                        break;
                    }
                    if (!perItem.runningLabels().isEmpty()) {
                        AppLogger.warning("Toggle aborted mid-batch: " + perItem.runningLabels() + " started running.");
                        skipped = selected.size() - success - fail;
                        break;
                    }
                } catch (Exception ex) {
                    AppLogger.warning("Toggle aborted mid-batch: " + ex.getMessage());
                    skipped = selected.size() - success - fail;
                    break;
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
            final int sk = skipped;
            final List<BrowserExtensionRow> succSnapshot = List.copyOf(succeeded);
            Platform.runLater(() -> {
                // Apply enabled state on FX thread only for verified successes
                // (service/PS now verify-after-write before returning true).
                for (BrowserExtensionRow r : succSnapshot) {
                    r.setEnabled(enable);
                }
                table.refresh();
                StringBuilder status = new StringBuilder("Toggled " + s + " extension(s).");
                if (f > 0) status.append(' ').append(f).append(" failed.");
                if (sk > 0) status.append(' ').append(sk).append(" skipped.");
                statusLabel.setText(status.toString());
                if (f > 0 || sk > 0) {
                    StringBuilder detail = new StringBuilder();
                    detail.append(s).append(" toggled");
                    if (f > 0) detail.append(", ").append(f).append(" failed");
                    if (sk > 0) detail.append(", ").append(sk).append(" skipped (browser started running or probe failed)");
                    detail.append('.');
                    if (f > 0) {
                        detail.append("\nClose all browsers and retry failed items."
                                + "\nIf failures persist, check logs (Secure Preferences HMAC reset, missing Preferences, or Firefox cache restore).");
                    }
                    new Alert(Alert.AlertType.WARNING, detail.toString()).showAndWait();
                }
                setBusy(false);
            });
        }, "browser-extensions-toggle");
        toggleThread = t;
        t.setDaemon(true);
        t.setUncaughtExceptionHandler((thread, ex) -> {
            AppLogger.error("Browser extension toggle failed", ex);
            Platform.runLater(() -> {
                statusLabel.setText("Toggle failed: " + ex.getMessage());
                setBusy(false);
            });
        });
        t.start();
    }

    private void setBusy(boolean value) {
        busy.set(value);
        setGlobalBusy(value);
    }

    private boolean globalBusyHeld() {
        try {
            return globalBusy != null && globalBusy.get();
        } catch (Exception e) {
            return false;
        }
    }

    private void setGlobalBusy(boolean value) {
        if (globalBusy == null) return;
        try {
            if (Platform.isFxApplicationThread()) {
                globalBusy.set(value);
            } else {
                Platform.runLater(() -> {
                    try {
                        globalBusy.set(value);
                    } catch (Exception ignored) {
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private static String resolveExtensionFolderPath(BrowserExtensionRow row) {
        String extPath = row.getPath();
        String extId = row.getExtensionId();
        if (extPath != null && !extPath.isBlank() && extId != null && !extId.isBlank()) {
            Path idDir = Paths.get(extPath, extId);
            if (Files.isDirectory(idDir)) {
                return idDir.toString();
            }
        }
        if (extPath != null && !extPath.isBlank()) {
            Path extensionsDir = Paths.get(extPath);
            if (Files.isDirectory(extensionsDir)) {
                return extPath;
            }
        }
        String profilePath = row.getProfilePath();
        if (profilePath != null && !profilePath.isBlank()) {
            return profilePath;
        }
        return null;
    }

    private static String formatRunningBrowserWarning(java.util.Set<String> browserLabels) {
        if (browserLabels == null || browserLabels.isEmpty()) {
            return "";
        }
        Map<String, List<String>> byExe = new LinkedHashMap<>();
        for (String label : browserLabels) {
            if (label == null || label.isBlank()) continue;
            String exe = BrowserExtensionService.expectedExeFor(label);
            if (exe == null || exe.isBlank()) {
                exe = "(unknown)";
            }
            byExe.computeIfAbsent(exe.toLowerCase(), k -> new ArrayList<>()).add(label);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : byExe.entrySet()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            LinkedHashSet<String> unique = new LinkedHashSet<>(entry.getValue());
            sb.append(entry.getKey()).append(" is running (covers: ")
                    .append(String.join(", ", unique))
                    .append(").\nClose all of those browser channels before continuing.");
        }
        return sb.toString();
    }

    private record BrowserRunningState(
            java.util.Set<String> runningLabels,
            java.util.Set<String> unverifiedLabels,
            boolean probeOk) {
        static BrowserRunningState empty() {
            return new BrowserRunningState(new java.util.HashSet<>(), new java.util.HashSet<>(), false);
        }
    }

    private BrowserRunningState probeRunningState(List<BrowserExtensionRow> selected, boolean fresh) {
        if (selected == null || selected.isEmpty()) {
            com.sbtools.browserext.BrowserProcessProbe.Snapshot snap = fresh
                    ? com.sbtools.browserext.BrowserProcessProbe.snapshotFresh()
                    : com.sbtools.browserext.BrowserProcessProbe.snapshot();
            return new BrowserRunningState(new java.util.HashSet<>(), new java.util.HashSet<>(), snap.probeOk());
        }
        com.sbtools.browserext.BrowserProcessProbe.Snapshot snap = fresh
                ? com.sbtools.browserext.BrowserProcessProbe.snapshotFresh()
                : com.sbtools.browserext.BrowserProcessProbe.snapshot();
        java.util.Set<String> running =
                com.sbtools.browserext.BrowserChannelRunning.runningAmong(selected, snap);
        java.util.Set<String> unverified =
                com.sbtools.browserext.BrowserChannelRunning.multiChannelExeWithoutDetailAmong(selected, snap);
        unverified.addAll(com.sbtools.browserext.BrowserChannelRunning
                .ambiguousMultiChannelExeBrowsers(selected, snap));
        return new BrowserRunningState(running, unverified, snap.probeOk());
    }

    private static boolean runningStateUnverified(BrowserRunningState state,
                                                  java.util.Set<String> unknownExeBrowsers) {
        return runningStateUnverified(state.probeOk(), unknownExeBrowsers, state.unverifiedLabels());
    }

    private static boolean runningStateUnverified(boolean probeOk,
                                                  java.util.Set<String> unknownExeBrowsers,
                                                  java.util.Set<String> unverifiedChannels) {
        return !probeOk
                || (unknownExeBrowsers != null && !unknownExeBrowsers.isEmpty())
                || (unverifiedChannels != null && !unverifiedChannels.isEmpty());
    }

    private static void appendRunningProbeWarnings(StringBuilder why, boolean probeOk,
                                                   java.util.Set<String> unknownExeBrowsers,
                                                   java.util.Set<String> unverifiedChannels) {
        if (!probeOk) {
            why.append("The running-process check failed, so open browsers may not have been detected.\n");
        }
        if (unknownExeBrowsers != null && !unknownExeBrowsers.isEmpty()) {
            why.append("No known executable image for: ")
                    .append(String.join(", ", unknownExeBrowsers))
                    .append(" — a running instance cannot be detected.\n");
        }
        if (unverifiedChannels != null && !unverifiedChannels.isEmpty()) {
            why.append("A shared browser executable is running but the channel could not be verified for: ")
                    .append(String.join(", ", unverifiedChannels))
                    .append(".\n");
        }
    }

    /**
     * Browsers in the selection for which no executable image is known, so a
     * running browser cannot be detected. Callers must warn (never silently
     * assume "not running").
     */
    private java.util.Set<String> unknownExeBrowsers(List<BrowserExtensionRow> selected) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            for (BrowserExtensionRow ext : selected) {
                String browserName = ext.getBrowser();
                if (browserName == null) continue;
                String expectedExe = com.sbtools.browserext.BrowserExtensionService.expectedExeFor(browserName);
                if (expectedExe == null || expectedExe.isBlank()) out.add(browserName);
            }
        } catch (Exception ignored) {
        }
        return out;
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
        List<String> persisted = List.of();
        try {
            List<String> ids = settingsStore.load().ignoredBrowserExtensionIds();
            if (ids != null) persisted = ids;
        } catch (Exception e) {
            AppLogger.warning("Failed to load ignored extensions: " + e.getMessage());
        }
        List<BrowserExtensionRow> ignoredRows = allRows.stream()
                .filter(BrowserExtensionRow::isIgnored)
                .toList();
        List<String> unmatched = BrowserExtensionService.unmatchedIgnoredIds(persisted, allRows);
        if (ignoredRows.isEmpty() && unmatched.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "No ignored extensions.").showAndWait();
            return;
        }

        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle(AppInfo.DISPLAY_NAME);
        int total = ignoredRows.size() + unmatched.size();
        dialog.setHeaderText(com.sbtools.util.UiText.label("Ignored extensions (" + total + ")"));

        StringBuilder msg = new StringBuilder();
        for (BrowserExtensionRow r : ignoredRows) {
            msg.append(describeRow(r)).append("\n");
        }
        for (String id : unmatched) {
            msg.append(BrowserExtensionService.describeIgnoredId(id)).append("\n");
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
                try {
                    settingsStore.update(current -> current.toBuilder()
                            .ignoredBrowserExtensionIds(List.of())
                            .build());
                } catch (Exception e) {
                    AppLogger.warning("Failed to clear ignored extensions: " + e.getMessage());
                }
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
