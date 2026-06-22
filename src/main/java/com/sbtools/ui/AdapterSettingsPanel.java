package com.sbtools.ui;

import com.sbtools.netoptimizer.AdapterProperties;
import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;

class AdapterSettingsPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final ComboBox<String> adapterCombo = new ComboBox<>();
    private final ObservableList<Map.Entry<String, String>> propertyRows = FXCollections.observableArrayList();
    private final TableView<Map.Entry<String, String>> propTable = new TableView<>(propertyRows);
    private final Label statusLabel = new Label("Ready.");

    AdapterSettingsPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildContent());
        VBox.setVgrow(propTable, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    void refreshAdapters() {
        new Thread(() -> {
            List<NetworkAdapterRow> adapters = service.listAdapters();
            Platform.runLater(() -> {
                adapterCombo.getItems().clear();
                for (NetworkAdapterRow a : adapters) {
                    adapterCombo.getItems().add(a.getName());
                }
                if (!adapterCombo.getItems().isEmpty()) {
                    adapterCombo.getSelectionModel().selectFirst();
                }
            });
        }, "net-adapter-props-load-adapters").start();
    }

    private VBox buildContent() {
        VBox content = new VBox(8);

        Label header = new Label("Adapter Settings");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Label sub = new Label("Read-only view of advanced adapter properties.");
        sub.setStyle("-fx-text-fill: #6272a4;");
        content.getChildren().add(sub);

        adapterCombo.setPrefWidth(250);
        adapterCombo.setOnAction(e -> loadProperties());

        Button refreshAdaptersBtn = UIButton.secondary("Refresh Adapters");
        refreshAdaptersBtn.setOnAction(e -> refreshAdapters());

        Button refreshPropsBtn = UIButton.primary("Refresh Properties");
        refreshPropsBtn.setOnAction(e -> loadProperties());

        HBox adapterRow = new HBox(8, new Label("Adapter:"), adapterCombo, refreshAdaptersBtn, refreshPropsBtn, statusLabel);
        adapterRow.setAlignment(Pos.CENTER_LEFT);
        adapterRow.setPadding(new Insets(0, 0, 8, 0));
        content.getChildren().add(adapterRow);

        buildTable();
        VBox.setVgrow(propTable, Priority.ALWAYS);
        content.getChildren().add(propTable);

        return content;
    }

    private void buildTable() {
        propTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Map.Entry<String, String>, String> nameCol = new TableColumn<>("Property");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getKey()));
        nameCol.setPrefWidth(280);

        TableColumn<Map.Entry<String, String>, String> valueCol = new TableColumn<>("Value");
        valueCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getValue()));
        valueCol.setPrefWidth(280);

        propTable.getColumns().addAll(nameCol, valueCol);
    }

    private void loadProperties() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null || busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading properties...");

        new Thread(() -> {
            try {
                AdapterProperties props = service.getAdapterProperties(adapter);
                Platform.runLater(() -> {
                    propertyRows.clear();
                    if (props != null && props.properties() != null) {
                        propertyRows.addAll(props.properties().entrySet());
                    }
                    statusLabel.setText("Loaded " + propertyRows.size() + " properties.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to load properties.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load adapter properties:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "net-load-adapter-props").start();
    }
}
