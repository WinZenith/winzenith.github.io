package com.sbtools.ui;

import com.sbtools.browserext.BrowserExtensionRow;
import com.sbtools.browserext.BrowserExtensionService;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
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
import java.util.List;
import java.util.function.BooleanSupplier;

public class BrowserExtensionsTabView extends BorderPane {

    private static final List<String> ALL_BROWSERS = List.of(
            "All", "Brave", "Chrome", "Chrome Canary",
            "Edge", "Edge Beta", "Edge Dev", "Edge Canary",
            "Firefox", "Opera", "Opera GX", "Vivaldi"
    );

    private final BrowserExtensionService service = new BrowserExtensionService();
    private final SettingsStore settingsStore = new SettingsStore();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;

    private final ObservableList<BrowserExtensionRow> allRows = FXCollections.observableArrayList();
    private final FilteredList<BrowserExtensionRow> filteredByBrowser = new FilteredList<>(allRows, r -> true);
    private final FilteredList<BrowserExtensionRow> filteredRows = new FilteredList<>(filteredByBrowser, r -> true);
    private final TableView<BrowserExtensionRow> table = new TableView<>(filteredRows);

    private final Button scanButton = UIButton.primary("Scan All Browsers");
    private final Button enableSelectedBtn = UIButton.primary("Enable");
    private final Button disableSelectedBtn = UIButton.secondary("Disable");
    private final Button selectAllBtn = UIButton.secondary("Select All");
    private final Button manageIgnoredBtn = UIButton.secondary("Manage Ignored");
    private final ComboBox<String> browserFilter = new ComboBox<>(
            FXCollections.observableArrayList(ALL_BROWSERS));
    private final TextField searchField = new TextField();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Label statusLabel = new Label("Click Scan to list browser extensions.");
    private final Label selectionLabel = new Label("");

    public BrowserExtensionsTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        enableSelectedBtn.setDisable(true);
        disableSelectedBtn.setDisable(true);
        selectAllBtn.setDisable(true);

        enableSelectedBtn.getStyleClass().add("success");
        disableSelectedBtn.getStyleClass().add("button-outlined");
        selectAllBtn.getStyleClass().add("button-outlined");
        manageIgnoredBtn.getStyleClass().add("button-outlined");

        searchField.setPromptText("Search extensions...");
        searchField.setPrefWidth(200);
        searchField.getStyleClass().add("sysinfo-search");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilters());

        scanButton.setOnAction(e -> startScan());
        enableSelectedBtn.setOnAction(e -> toggleSelected(true));
        disableSelectedBtn.setOnAction(e -> toggleSelected(false));
        selectAllBtn.setOnAction(e -> toggleSelectAll());
        manageIgnoredBtn.setOnAction(e -> showIgnoredListDialog());

        browserFilter.getSelectionModel().select(0);
        browserFilter.setOnAction(e -> applyFilters());

        HBox top = new HBox(12, scanButton, enableSelectedBtn, disableSelectedBtn, selectAllBtn,
                new Label("Filter:"), browserFilter, searchField,
                progressBar, statusLabel, selectionLabel, manageIgnoredBtn);
        top.setAlignment(Pos.CENTER_LEFT);
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
            selectAllBtn.setDisable(newVal || allRows.isEmpty());
            progressBar.setVisible(newVal);
            progressBar.setProgress(newVal ? -1 : 0);
        });

        allRows.addListener((ListChangeListener<BrowserExtensionRow>) c -> {
            while (c.next()) {
                if (c.wasAdded()) {
                    for (BrowserExtensionRow row : c.getAddedSubList()) {
                        row.selectedProperty().addListener((obs, ov, nv) -> updateActionButtons());
                    }
                }
            }
            updateActionButtons();
        });

        applyIgnoredFromSettings();
    }

    private void applyIgnoredFromSettings() {
        try {
            AppSettings settings = settingsStore.load();
            List<String> ignoredIds = settings.ignoredBrowserExtensionIds();
            if (ignoredIds != null) {
                for (BrowserExtensionRow row : allRows) {
                    row.setIgnored(ignoredIds.contains(row.getExtensionId()));
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
                    ignoredIds.add(row.getExtensionId());
                }
            }
            AppSettings current = settingsStore.load();
            settingsStore.save(current.toBuilder()
                    .ignoredBrowserExtensionIds(ignoredIds)
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

    private void updateActionButtons() {
        boolean disabled = busy.get() || getSelectedCount() == 0;
        enableSelectedBtn.setDisable(disabled);
        disableSelectedBtn.setDisable(disabled);
        int selCount = getSelectedCount();
        selectionLabel.setText(selCount > 0 ? selCount + " selected" : "");
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
                    if (previousItem != null && previousItem != item) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                    }
                    if (checkBox.selectedProperty().isBound()) {
                        checkBox.selectedProperty().unbind();
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
                    BrowserExtensionRow row = getTableView().getItems().get(getIndex());
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
                    BrowserExtensionRow row = getTableView().getItems().get(getIndex());
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
                if (r != null) openContainingFolder(r.getPath());
            });

            MenuItem copyIdItem = new MenuItem("Copy Extension ID");
            copyIdItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) copyToClipboard(r.getExtensionId());
            });

            MenuItem copyPathItem = new MenuItem("Copy Path");
            copyPathItem.setOnAction(e -> {
                BrowserExtensionRow r = row.getItem();
                if (r != null) copyToClipboard(r.getPath());
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
                    r.setIgnored(!r.isIgnored());
                    saveIgnoredToSettings();
                    table.refresh();
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

    private void openContainingFolder(String path) {
        try {
            File file = new File(path);
            if (file.exists()) {
                Desktop.getDesktop().browseFileDirectory(file);
            }
        } catch (Exception e) {
            try {
                Runtime.getRuntime().exec(new String[]{"explorer", "/select,", path});
            } catch (Exception ex) {
                AppLogger.warning("Failed to open folder for: " + path + " — " + ex.getMessage());
            }
        }
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void startScan() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Scanning browser extensions...");
        allRows.clear();

        new Thread(() -> {
            try {
                List<BrowserExtensionRow> results = service.scanAllBrowsers(() -> {});
                Platform.runLater(() -> {
                    allRows.setAll(results);
                    applyIgnoredFromSettings();
                    applyFilters();
                    updateActionButtons();
                    selectAllBtn.setDisable(allRows.isEmpty());
                    statusLabel.setText(buildStatusText(results));
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
        }, "browser-extensions-scan").start();
    }

    private String buildStatusText(List<BrowserExtensionRow> results) {
        if (results.isEmpty()) return "No extensions found.";

        StringBuilder sb = new StringBuilder("Found " + results.size() + " extensions (");
        boolean first = true;
        for (String browser : ALL_BROWSERS) {
            if ("All".equals(browser)) continue;
            long count = results.stream().filter(r -> browser.equals(r.getBrowser())).count();
            if (count > 0) {
                if (!first) sb.append(", ");
                sb.append(browser).append(": ").append(count);
                first = false;
            }
        }
        sb.append(")");
        return sb.toString();
    }

    private void toggleSelected(boolean enable) {
        List<BrowserExtensionRow> selected = allRows.stream()
                .filter(r -> r.isSelected() && !r.isIgnored())
                .toList();
        if (selected.isEmpty()) return;

        String action = enable ? "enable" : "disable";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                action + " " + selected.size() + " extension(s)?");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        new Thread(() -> {
            int success = 0;
            int fail = 0;
            for (BrowserExtensionRow ext : selected) {
                if (service.toggleExtension(ext, enable)) {
                    success++;
                } else {
                    fail++;
                }
            }
            final int s = success;
            final int f = fail;
            Platform.runLater(() -> {
                table.refresh();
                statusLabel.setText("Toggled " + s + " extension(s)." + (f > 0 ? " " + f + " failed." : ""));
                if (f > 0) {
                    new Alert(Alert.AlertType.WARNING, s + " toggled, " + f + " failed.").showAndWait();
                }
            });
        }, "browser-extensions-toggle").start();
    }

    private void toggleSelectAll() {
        boolean allSelected = filteredRows.stream()
                .filter(r -> !r.isIgnored())
                .allMatch(BrowserExtensionRow::isSelected);
        for (BrowserExtensionRow row : filteredRows) {
            if (!row.isIgnored()) {
                row.setSelected(!allSelected);
            }
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
            msg.append("• ").append(r.getName())
               .append(" (").append(r.getBrowser()).append(")")
               .append("\n");
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
}
