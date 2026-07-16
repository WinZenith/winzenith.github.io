package com.sbtools.ui;

import com.sbtools.netoptimizer.ConnectionInfo;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.PortScanResult;
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

class ConnectionMonitorPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final Label statusLabel = new Label("Ready.");
    private final FilteredList<ConnectionInfo> filteredList;
    private final TableView<ConnectionInfo> table;
    private final ComboBox<String> stateFilter = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final TextField portHostField = new TextField();
    private final TextField portField = new TextField("80");
    private final Label portScanResultLabel = new Label("");

    ConnectionMonitorPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        this.filteredList = new FilteredList<>(FXCollections.observableArrayList());
        this.table = buildTable();
        getChildren().addAll(buildContent());
        VBox.setVgrow(table, Priority.ALWAYS);
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

        content.getChildren().addAll(toolbar, table);
        content.getChildren().add(buildPortScannerSection());
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

    private VBox buildPortScannerSection() {
        VBox section = new VBox(6);
        section.setPadding(new Insets(12, 0, 0, 0));
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1 0 0 0; -fx-padding: 12 0 0 0;");

        Label header = new Label("Port Scanner");
        header.getStyleClass().addAll("label", "large");
        section.getChildren().add(header);

        portHostField.setPromptText("Host (e.g. 127.0.0.1 or hostname)");
        portHostField.setPrefWidth(200);
        portField.setPrefWidth(70);

        Button scanBtn = UIButton.primary("Scan Port");
        Button commonPortsBtn = UIButton.secondary("Scan Common Ports");

        scanBtn.setOnAction(e -> runPortScan());
        commonPortsBtn.setOnAction(e -> runCommonPortScan());

        HBox row = new HBox(8, new Label("Host:"), portHostField, new Label("Port:"), portField, scanBtn, commonPortsBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(row);

        portScanResultLabel.setStyle("-fx-text-fill: #f8f8f2; -fx-font-family: 'Consolas', monospace;");
        portScanResultLabel.setWrapText(true);
        section.getChildren().add(portScanResultLabel);

        return section;
    }

    private void runPortScan() {
        String host = portHostField.getText().trim();
        if (host.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter a host to scan.").showAndWait();
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portField.getText().trim());
            if (port < 1 || port > 65535) {
                new Alert(Alert.AlertType.WARNING, "Port must be between 1 and 65535.").showAndWait();
                return;
            }
        } catch (NumberFormatException e) {
            new Alert(Alert.AlertType.WARNING, "Invalid port number.").showAndWait();
            return;
        }

        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Scanning " + host + ":" + port + "...");
        portScanResultLabel.setText("Scanning " + host + ":" + port + "...");

        new Thread(() -> {
            PortScanResult result = service.scanPort(host, port);
            Platform.runLater(() -> {
                String status = result.open() ? "OPEN" : "CLOSED";
                String color = result.open() ? "#50fa7b" : "#ff5555";
                portScanResultLabel.setText(String.format("Port %d on %s is %s (%dms)",
                        result.port(), result.host(), status, result.latencyMs()));
                portScanResultLabel.setStyle("-fx-text-fill: " + color + "; -fx-font-family: 'Consolas', monospace; -fx-font-weight: bold;");
                statusLabel.setText("Port scan complete.");
                busy.set(false);
            });
        }, "net-port-scan").start();
    }

    private void runCommonPortScan() {
        String host = portHostField.getText().trim();
        if (host.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter a host to scan.").showAndWait();
            return;
        }

        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Scanning common ports on " + host + "...");
        portScanResultLabel.setText("Scanning common ports on " + host + "...");

        int[] commonPorts = {22, 80, 443, 3389, 8080, 8443};
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            sb.append("Port scan results for ").append(host).append(":\n");
            for (int port : commonPorts) {
                PortScanResult result = service.scanPort(host, port);
                String status = result.open() ? "OPEN" : "CLOSED";
                sb.append(String.format("  Port %-6d %s (%dms)\n", result.port(), status, result.latencyMs()));
            }
            String output = sb.toString();
            Platform.runLater(() -> {
                portScanResultLabel.setText(output);
                portScanResultLabel.setStyle("-fx-text-fill: #f8f8f2; -fx-font-family: 'Consolas', monospace;");
                statusLabel.setText("Common ports scan complete.");
                busy.set(false);
            });
        }, "net-common-ports-scan").start();
    }
}
