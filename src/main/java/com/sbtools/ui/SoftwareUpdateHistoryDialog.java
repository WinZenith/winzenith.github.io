package com.sbtools.ui;

import com.sbtools.software.SoftwareUpdateHistoryEntry;
import com.sbtools.software.SoftwareUpdateHistoryStore;
import com.sbtools.util.AppInfo;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class SoftwareUpdateHistoryDialog {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public static void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.DISPLAY_NAME + " - Update History");
        dialog.setHeaderText("Software Update History");

        SoftwareUpdateHistoryStore store = new SoftwareUpdateHistoryStore();
        List<SoftwareUpdateHistoryEntry> entries = store.listAll();

        TableView<SoftwareUpdateHistoryEntry> table = new TableView<>(FXCollections.observableArrayList(entries));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SoftwareUpdateHistoryEntry, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().installedAt().atZone(ZoneId.systemDefault()).format(DATE_FMT)));
        dateCol.setPrefWidth(130);

        TableColumn<SoftwareUpdateHistoryEntry, String> nameCol = new TableColumn<>("Program");
        nameCol.setCellValueFactory(new PropertyValueFactory<>("packageName"));

        TableColumn<SoftwareUpdateHistoryEntry, String> oldVerCol = new TableColumn<>("Old Version");
        oldVerCol.setCellValueFactory(new PropertyValueFactory<>("oldVersion"));
        oldVerCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateHistoryEntry, String> newVerCol = new TableColumn<>("New Version");
        newVerCol.setCellValueFactory(new PropertyValueFactory<>("newVersion"));
        newVerCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateHistoryEntry, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(new PropertyValueFactory<>("source"));
        sourceCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateHistoryEntry, Boolean> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new javafx.beans.property.SimpleBooleanProperty(c.getValue().success()));
        statusCol.setPrefWidth(70);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Boolean success, boolean empty) {
                super.updateItem(success, empty);
                if (empty || success == null) {
                    setText(null);
                    setStyle(null);
                } else if (success) {
                    setText("OK");
                    setStyle("-fx-text-fill: #50fa7b;");
                } else {
                    setText("Failed");
                    setStyle("-fx-text-fill: #ff5555;");
                }
            }
        });

        TableColumn<SoftwareUpdateHistoryEntry, String> errorCol = new TableColumn<>("Error");
        errorCol.setCellValueFactory(new PropertyValueFactory<>("errorMessage"));
        errorCol.setPrefWidth(200);

        table.getColumns().addAll(dateCol, nameCol, oldVerCol, newVerCol, sourceCol, statusCol, errorCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button clearBtn = new Button("Clear History");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Clear all update history?");
            confirm.setHeaderText(AppInfo.DISPLAY_NAME);
            if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                store.clear();
                table.setItems(FXCollections.observableArrayList());
            }
        });

        HBox bottom = new HBox(8, clearBtn);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(8, table, bottom);
        content.setPadding(new Insets(10));
        content.setPrefWidth(750);
        content.setPrefHeight(400);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
