package com.sbtools.ui;

import com.sbtools.browserext.BrowserExtensionRow;
import com.sbtools.browserext.BrowserExtensionService;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;

public class BrowserExtensionsTabView extends BorderPane {

    private final BrowserExtensionService service = new BrowserExtensionService();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;

    private final ObservableList<BrowserExtensionRow> allRows = FXCollections.observableArrayList();
    private final FilteredList<BrowserExtensionRow> filteredRows = new FilteredList<>(allRows, r -> true);
    private final TableView<BrowserExtensionRow> table = new TableView<>(filteredRows);

    private final Button scanButton = UIButton.primary("Scan All Browsers");
    private final Button enableSelectedBtn = UIButton.primary("Enable");
    private final Button disableSelectedBtn = UIButton.secondary("Disable");
    private final ComboBox<String> browserFilter = new ComboBox<>(
            FXCollections.observableArrayList("All", "Brave", "Chrome", "Edge", "Firefox", "Opera", "Vivaldi"));
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Label statusLabel = new Label("Click Scan to list browser extensions.");

    public BrowserExtensionsTabView(BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        spinner.setVisible(false);
        spinner.setMaxSize(24, 24);

        enableSelectedBtn.setDisable(true);
        disableSelectedBtn.setDisable(true);

        enableSelectedBtn.getStyleClass().add("success");
        disableSelectedBtn.getStyleClass().add("button-outlined");

        scanButton.setOnAction(e -> startScan());
        enableSelectedBtn.setOnAction(e -> toggleSelected(true));
        disableSelectedBtn.setOnAction(e -> toggleSelected(false));

        browserFilter.getSelectionModel().select(0);
        browserFilter.setOnAction(e -> applyFilter());

        HBox top = new HBox(12, scanButton, enableSelectedBtn, disableSelectedBtn,
                new Label("Filter:"), browserFilter, spinner, statusLabel);
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
            spinner.setVisible(newVal);
        });
    }

    private int getSelectedCount() {
        return (int) allRows.stream().filter(BrowserExtensionRow::isSelected).count();
    }

    private void updateActionButtons() {
        boolean disabled = busy.get() || getSelectedCount() == 0;
        enableSelectedBtn.setDisable(disabled);
        disableSelectedBtn.setDisable(disabled);
    }

    private void applyFilter() {
        String filter = browserFilter.getSelectionModel().getSelectedItem();
        if (filter == null || "All".equals(filter)) {
            filteredRows.setPredicate(r -> true);
        } else {
            filteredRows.setPredicate(r -> filter.equals(r.getBrowser()));
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
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> browserCol = new TableColumn<>("Browser");
        browserCol.setCellValueFactory(c -> c.getValue().browserProperty());
        browserCol.setPrefWidth(80);
        browserCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item);
                    if ("Chrome".equals(item)) setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                    else if ("Edge".equals(item)) setStyle("-fx-text-fill: #8be9fd; -fx-font-weight: bold;");
                    else if ("Firefox".equals(item)) setStyle("-fx-text-fill: #ffb86c; -fx-font-weight: bold;");
                    else if ("Brave".equals(item)) setStyle("-fx-text-fill: #ff79c6; -fx-font-weight: bold;");
                    else if ("Opera".equals(item)) setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                    else if ("Vivaldi".equals(item)) setStyle("-fx-text-fill: #bd93f9; -fx-font-weight: bold;");
                    else setStyle("-fx-text-fill: #f8f8f2;");
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
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    setStyle("-fx-font-weight: bold;");
                }
            }
        });

        TableColumn<BrowserExtensionRow, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(80);

        TableColumn<BrowserExtensionRow, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> c.getValue().enabledProperty().asString());
        statusCol.setPrefWidth(90);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    boolean isEnabled = "true".equals(item);
                    setText(isEnabled ? "Enabled" : "Disabled");
                    setStyle(isEnabled
                            ? "-fx-text-fill: #50fa7b; -fx-font-weight: bold;"
                            : "-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                }
            }
        });

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
                if (empty || item == null) setText(null);
                else {
                    setText(item);
                    setStyle("-fx-font-size: 10px;");
                }
            }
        });

        table.getColumns().addAll(checkCol, browserCol, nameCol, versionCol, statusCol, descCol, permsCol);
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
                    for (BrowserExtensionRow row : allRows) {
                        row.selectedProperty().addListener((obs2, old2, new2) -> updateActionButtons());
                    }
                    applyFilter();
                    updateActionButtons();
                    int totalChrome = (int) results.stream().filter(r -> "Chrome".equals(r.getBrowser())).count();
                    int totalEdge = (int) results.stream().filter(r -> "Edge".equals(r.getBrowser())).count();
                    int totalFirefox = (int) results.stream().filter(r -> "Firefox".equals(r.getBrowser())).count();
                    int totalBrave = (int) results.stream().filter(r -> "Brave".equals(r.getBrowser())).count();
                    int totalOpera = (int) results.stream().filter(r -> "Opera".equals(r.getBrowser())).count();
                    int totalVivaldi = (int) results.stream().filter(r -> "Vivaldi".equals(r.getBrowser())).count();
                    statusLabel.setText("Found " + results.size() + " extensions (Chrome: " + totalChrome
                            + ", Edge: " + totalEdge + ", Firefox: " + totalFirefox
                            + ", Brave: " + totalBrave + ", Opera: " + totalOpera + ", Vivaldi: " + totalVivaldi + ")");
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

    private void toggleSelected(boolean enable) {
        List<BrowserExtensionRow> selected = allRows.stream().filter(BrowserExtensionRow::isSelected).toList();
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
}
