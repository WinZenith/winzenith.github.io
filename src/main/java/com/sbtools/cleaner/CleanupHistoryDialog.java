package com.sbtools.cleaner;

import com.sbtools.util.AppInfo;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dialog showing past cleanup sessions with details.
 */
public class CleanupHistoryDialog extends Dialog<ButtonType> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public CleanupHistoryDialog(CleanerHistoryStore store) {
        setTitle(AppInfo.DISPLAY_NAME + " - Cleanup History");
        setHeaderText("Past cleanup sessions");

        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        BorderPane content = new BorderPane();

        List<CleanerHistoryStore.HistoryEntry> entries = store.load();

        long totalAllTime = entries.stream().mapToLong(CleanerHistoryStore.HistoryEntry::totalBytesFreed).sum();

        Label totalLabel = new Label("Total freed since install: " + CleanupService.formatBytes(totalAllTime));
        totalLabel.setStyle("-fx-text-fill: #50fa7b; -fx-font-size: 13px; -fx-padding: 0 0 8 0;");

        TableView<CleanerHistoryStore.HistoryEntry> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<CleanerHistoryStore.HistoryEntry, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(c -> {
            String ts = c.getValue().timestamp();
            try {
                Instant inst = Instant.parse(ts);
                return new javafx.beans.property.SimpleStringProperty(FORMATTER.format(inst));
            } catch (Exception e) {
                return new javafx.beans.property.SimpleStringProperty(ts);
            }
        });
        dateCol.setPrefWidth(170);

        TableColumn<CleanerHistoryStore.HistoryEntry, String> freedCol = new TableColumn<>("Bytes Freed");
        freedCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(
                        CleanupService.formatBytes(c.getValue().totalBytesFreed())));
        freedCol.setPrefWidth(120);

        TableColumn<CleanerHistoryStore.HistoryEntry, String> itemsCol = new TableColumn<>("Items");
        itemsCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleStringProperty(String.valueOf(c.getValue().totalItems())));
        itemsCol.setPrefWidth(80);

        TableColumn<CleanerHistoryStore.HistoryEntry, String> detailsCol = new TableColumn<>("Categories");
        detailsCol.setCellValueFactory(c -> {
            StringBuilder sb = new StringBuilder();
            c.getValue().perCategoryBytes().forEach((cat, bytes) -> {
                if (!sb.isEmpty()) sb.append(", ");
                sb.append(cat).append(": ").append(CleanupService.formatBytes(bytes));
            });
            return new javafx.beans.property.SimpleStringProperty(sb.toString());
        });
        detailsCol.setPrefWidth(400);

        table.getColumns().addAll(dateCol, freedCol, itemsCol, detailsCol);
        ObservableList<CleanerHistoryStore.HistoryEntry> data = FXCollections.observableArrayList(entries);
        table.setItems(data);
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox topBox = new VBox(8, totalLabel);
        content.setTop(topBox);
        content.setCenter(table);
        BorderPane.setMargin(topBox, new Insets(0, 0, 8, 0));

        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(750);
        getDialogPane().setPrefHeight(400);
    }
}
