package com.sbtools.ui;

import com.sbtools.netoptimizer.AdapterProperties;
import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.util.AppExecutors;
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

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

class AdapterSettingsPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final ComboBox<String> adapterCombo = new ComboBox<>();
    private final ObservableList<Map.Entry<String, String>> propertyRows = FXCollections.observableArrayList();
    private final TableView<Map.Entry<String, String>> propTable = new TableView<>(propertyRows);
    private final Label statusLabel = new Label("Ready.");
    private volatile Future<?> currentTask;
    // Coalesce concurrent refreshes (tab selects + Refresh buttons) and track them
    // separately from loadProperties so neither handle clobbers the other.
    private final java.util.concurrent.atomic.AtomicBoolean refreshingAdapters = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> refreshTask;

    AdapterSettingsPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildContent());
        VBox.setVgrow(propTable, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    void refreshAdapters() {
        if (!refreshingAdapters.compareAndSet(false, true)) return;
        refreshTask = AppExecutors.ioPool().submit(() -> {
            try {
                List<NetworkAdapterRow> adapters = service.listAdapters();
                Platform.runLater(() -> {
                    String prev = adapterCombo.getSelectionModel().getSelectedItem();
                    adapterCombo.getItems().clear();
                    for (NetworkAdapterRow a : adapters) {
                        adapterCombo.getItems().add(a.getName());
                    }
                    if (!adapterCombo.getItems().isEmpty()) {
                        if (prev != null && adapterCombo.getItems().contains(prev)) {
                            adapterCombo.getSelectionModel().select(prev);
                        } else {
                            adapterCombo.getSelectionModel().selectFirst();
                        }
                    }
                });
            } finally {
                refreshingAdapters.set(false);
            }
        });
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
        Future<?> r = refreshTask;
        if (r != null) r.cancel(true);
        refreshingAdapters.set(false);
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
        if (adapter == null) {
            new Alert(Alert.AlertType.WARNING, "Please select an adapter.").showAndWait();
            return;
        }
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading properties for " + adapter + "...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                AdapterProperties props = service.getAdapterProperties(adapter);
                Platform.runLater(() -> {
                    propertyRows.clear();
                    if (props.properties() != null && !props.properties().isEmpty()) {
                        // Copy entries to avoid live view issues
                        List<Map.Entry<String, String>> copy = new ArrayList<>();
                        for (Map.Entry<String, String> e : props.properties().entrySet()) {
                            copy.add(new AbstractMap.SimpleEntry<>(e.getKey(), e.getValue()));
                        }
                        propertyRows.addAll(copy);
                        statusLabel.setText("Loaded " + propertyRows.size() + " properties.");
                    } else {
                        statusLabel.setText("No properties returned. Try 'Refresh Adapters' or run as Administrator.");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to load properties.");
                    new Alert(Alert.AlertType.ERROR, "Failed to load adapter properties:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }
}
