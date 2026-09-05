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

        TextArea detailArea = new TextArea("Select a session to see per-category breakdown and errors.");
        detailArea.setEditable(false);
        detailArea.setWrapText(true);
        detailArea.setPrefRowCount(5);
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV == null) {
                detailArea.setText("Select a session to see per-category breakdown and errors.");
                return;
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Session: ").append(formatTimestamp(newV.timestamp())).append("\n");
            if (newV.appVersion() != null && !newV.appVersion().isBlank()) {
                sb.append("App version: ").append(newV.appVersion()).append("\n");
            }
            sb.append("Freed: ").append(CleanupService.formatBytes(newV.totalBytesFreed()))
                    .append(" (").append(newV.totalItems()).append(" items)\n\n");
            if (newV.perCategoryBytes() != null && !newV.perCategoryBytes().isEmpty()) {
                sb.append("Per-category:\n");
                newV.perCategoryBytes().forEach((cat, bytes) ->
                        sb.append("  - ").append(cat).append(": ")
                                .append(CleanupService.formatBytes(bytes)).append("\n"));
            }
            if (newV.errors() != null && !newV.errors().isEmpty()) {
                sb.append("\nErrors:\n");
                newV.errors().forEach(err -> sb.append("  - ").append(err).append("\n"));
            }
            detailArea.setText(sb.toString());
        });

        Button exportBtn = new Button("Export CSV...");
        exportBtn.setOnAction(e -> {
            javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
            chooser.setTitle("Export cleanup history");
            chooser.setInitialFileName("cleanup-history.csv");
            chooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("CSV", "*.csv"));
            java.io.File file = chooser.showSaveDialog(getDialogPane().getScene() != null
                    ? getDialogPane().getScene().getWindow() : null);
            if (file == null) return;
            try (java.io.PrintWriter out = new java.io.PrintWriter(file, java.nio.charset.StandardCharsets.UTF_8)) {
                out.println("Timestamp,BytesFreed,Items,Categories,Errors");
                for (CleanerHistoryStore.HistoryEntry en : data) {
                    StringBuilder cats = new StringBuilder();
                    if (en.perCategoryBytes() != null) {
                        en.perCategoryBytes().forEach((cat, bytes) -> {
                            if (cats.length() > 0) cats.append("; ");
                            cats.append(cat).append(": ").append(CleanupService.formatBytes(bytes));
                        });
                    }
                    String errs = en.errors() != null ? String.join("; ", en.errors()) : "";
                    out.println(csv(en.timestamp()) + "," + en.totalBytesFreed() + ","
                            + en.totalItems() + "," + csv(cats.toString()) + "," + csv(errs));
                }
            } catch (Exception ex) {
                new Alert(Alert.AlertType.ERROR, "Export failed:\n" + ex.getMessage()).showAndWait();
            }
        });

        Button clearBtn = new Button("Clear history");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete all past cleanup sessions? This cannot be undone.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setHeaderText("Clear History");
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                store.clear();
                data.clear();
                totalLabel.setText("Total freed since install: " + CleanupService.formatBytes(0));
                detailArea.setText("History cleared.");
            }
        });

        javafx.scene.layout.HBox bottomBar = new javafx.scene.layout.HBox(8, exportBtn, clearBtn);
        bottomBar.setPadding(new Insets(8, 0, 0, 0));

        VBox centerBox = new VBox(8, table, detailArea, bottomBar);
        VBox.setVgrow(table, Priority.ALWAYS);

        VBox topBox = new VBox(8, totalLabel);
        content.setTop(topBox);
        content.setCenter(centerBox);
        BorderPane.setMargin(topBox, new Insets(0, 0, 8, 0));

        getDialogPane().setContent(content);
        getDialogPane().setPrefWidth(750);
        getDialogPane().setPrefHeight(480);
    }

    private static String formatTimestamp(String ts) {
        try {
            return FORMATTER.format(Instant.parse(ts));
        } catch (Exception e) {
            return ts;
        }
    }

    private static String csv(String s) {
        if (s == null) return "\"\"";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
