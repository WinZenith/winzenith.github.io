package com.sbtools.ui;

import com.sbtools.netoptimizer.ConnectionInfo;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.Predicate;

class ConnectionMonitorPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final Label statusLabel = new Label("Ready.");
    private final FilteredList<ConnectionInfo> filteredList;
    private final ComboBox<String> stateFilter = new ComboBox<>();
    private final TextField searchField = new TextField();

    ConnectionMonitorPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        this.filteredList = new FilteredList<>(FXCollections.observableArrayList());
        getChildren().addAll(buildContent());
        VBox.setVgrow(buildTable(), Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    private TableView<ConnectionInfo> buildTable() {
        TableView<ConnectionInfo> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<ConnectionInfo, String> protoCol = new TableColumn<>("Proto");
        protoCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().protocol()));
        protoCol.setPrefWidth(60);

        TableColumn<ConnectionInfo, String> localCol = new TableColumn<>("Local Address");
        localCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().localAddress()));
        localCol.setPrefWidth(180);

        TableColumn<ConnectionInfo, String> remoteCol = new TableColumn<>("Remote Address");
        remoteCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().remoteAddress()));
        remoteCol.setPrefWidth(180);

        TableColumn<ConnectionInfo, String> stateCol = new TableColumn<>("State");
        stateCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().state()));
        stateCol.setPrefWidth(120);
        stateCol.setCellFactory(col -> new javafx.scene.control.TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(item);
                    if ("ESTABLISHED".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                    } else if ("LISTENING".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #8be9fd;");
                    } else if ("TIME_WAIT".equalsIgnoreCase(item) || "CLOSE_WAIT".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #f1fa8c;");
                    } else {
                        setStyle("-fx-text-fill: #f8f8f2;");
                    }
                }
            }
        });

        TableColumn<ConnectionInfo, String> pidCol = new TableColumn<>("PID");
        pidCol.setCellValueFactory(c -> new SimpleStringProperty(String.valueOf(c.getValue().pid())));
        pidCol.setPrefWidth(70);

        TableColumn<ConnectionInfo, String> procCol = new TableColumn<>("Process");
        procCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().processName()));
        procCol.setPrefWidth(140);

        table.getColumns().addAll(protoCol, localCol, remoteCol, stateCol, pidCol, procCol);

        SortedList<ConnectionInfo> sorted = new SortedList<>(filteredList);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);

        return table;
    }

    void loadConnections() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading connections...");

        String stateFilterVal = stateFilter.getSelectionModel().getSelectedItem();
        String state = (stateFilterVal == null || "All".equals(stateFilterVal)) ? "" : stateFilterVal;

        new Thread(() -> {
            try {
                List<ConnectionInfo> connections = service.getActiveConnections(state);
                Platform.runLater(() -> {
                    filteredList.setAll(connections);
                    statusLabel.setText("Found " + connections.size() + " connection(s).");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to load connections.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load connections:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "net-load-connections").start();
    }

    private VBox buildContent() {
        VBox content = new VBox(8);

        Label header = new Label("Connection Monitor");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        stateFilter.setPrefWidth(140);
        stateFilter.getItems().addAll("All", "ESTABLISHED", "LISTENING", "TIME_WAIT", "CLOSE_WAIT");
        stateFilter.getSelectionModel().selectFirst();
        stateFilter.setOnAction(e -> applyFilters());

        searchField.setPromptText("Search connections...");
        searchField.setPrefWidth(250);
        searchField.textProperty().addListener((obs, old, val) -> applyFilters());

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> loadConnections());

        HBox toolbar = new HBox(8, new Label("State:"), stateFilter, searchField, refreshBtn, statusLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 8, 0));

        TableView<ConnectionInfo> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        content.getChildren().addAll(toolbar, table);
        return content;
    }

    private void applyFilters() {
        String search = searchField.getText() == null ? "" : searchField.getText().toLowerCase();
        String state = stateFilter.getSelectionModel().getSelectedItem();
        boolean filterAll = state == null || "All".equals(state);

        filteredList.setPredicate(entry -> {
            boolean stateMatch = filterAll || entry.state().equalsIgnoreCase(state);
            if (search.isEmpty()) return stateMatch;
            return stateMatch && (
                    entry.protocol().toLowerCase().contains(search)
                    || entry.localAddress().toLowerCase().contains(search)
                    || entry.remoteAddress().toLowerCase().contains(search)
                    || entry.state().toLowerCase().contains(search)
                    || entry.processName().toLowerCase().contains(search)
                    || String.valueOf(entry.pid()).contains(search)
            );
        });
    }
}
