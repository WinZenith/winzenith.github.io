package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkChangeEntry;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

class ChangeLogPanel extends VBox {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final ObservableList<NetworkChangeEntry> entries = FXCollections.observableArrayList();
    private final TableView<NetworkChangeEntry> table = new TableView<>(entries);

    ChangeLogPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildContent());
        VBox.setVgrow(table, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    void loadEntries() {
        entries.setAll(service.getChangeLog());
    }

    private VBox buildContent() {
        VBox content = new VBox(8);

        Label header = new Label("Change History");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Label sub = new Label("Shows the last 3 network operations.");
        sub.setStyle("-fx-text-fill: #6272a4;");
        content.getChildren().add(sub);

        buildTable();

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> loadEntries());

        Button clearBtn = UIButton.secondary("Clear History");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Clear all change history?", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Clear History");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                service.clearChangeLog();
                entries.clear();
            }
        });

        HBox btnBox = new HBox(12, refreshBtn, clearBtn);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        btnBox.setPadding(new Insets(0, 0, 8, 0));

        content.getChildren().addAll(btnBox, table);
        return content;
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<NetworkChangeEntry, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                FORMATTER.format(Instant.parse(c.getValue().timestamp()))));
        timeCol.setPrefWidth(150);

        TableColumn<NetworkChangeEntry, String> opCol = new TableColumn<>("Operation");
        opCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().operation()));
        opCol.setPrefWidth(160);

        TableColumn<NetworkChangeEntry, String> targetCol = new TableColumn<>("Target");
        targetCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().target()));
        targetCol.setPrefWidth(140);

        TableColumn<NetworkChangeEntry, String> detailsCol = new TableColumn<>("Details");
        detailsCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().details() != null ? c.getValue().details() : ""));
        detailsCol.setPrefWidth(200);

        TableColumn<NetworkChangeEntry, Boolean> resultCol = new TableColumn<>("Result");
        resultCol.setCellValueFactory(c -> new javafx.beans.property.SimpleBooleanProperty(c.getValue().success()));
        resultCol.setPrefWidth(80);
        resultCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle(null);
                } else {
                    setText(item ? "OK" : "FAIL");
                    setStyle(item
                            ? "-fx-text-fill: #50fa7b; -fx-font-weight: bold;"
                            : "-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                }
            }
        });

        table.getColumns().addAll(timeCol, opCol, targetCol, detailsCol, resultCol);
    }
}
