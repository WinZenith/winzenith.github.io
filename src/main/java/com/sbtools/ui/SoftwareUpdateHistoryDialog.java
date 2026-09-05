package com.sbtools.ui;

import com.sbtools.software.SoftwareUpdateHistoryEntry;
import com.sbtools.software.SoftwareUpdateHistoryStore;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.beans.property.SimpleStringProperty;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public class SoftwareUpdateHistoryDialog {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String formatInstalledAt(SoftwareUpdateHistoryEntry e) {
        try {
            if (e == null || e.installedAt() == null) return "";
            return e.installedAt().atZone(ZoneId.systemDefault()).format(DATE_FMT);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.DISPLAY_NAME + " - Update History");
        dialog.setHeaderText("Software Update History");

        SoftwareUpdateHistoryStore store = new SoftwareUpdateHistoryStore();
        List<SoftwareUpdateHistoryEntry> entries = store.listAll();

        TextField searchField = new TextField();
        searchField.setPromptText("Filter by program or ID...");
        searchField.setPrefWidth(260);
        Label countLabel = new Label(entries.size() + " entr(ies)");
        countLabel.setStyle("-fx-opacity: 0.75;");
        HBox filterBar = new HBox(10, new Label("Search:"), searchField, countLabel);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        FilteredList<SoftwareUpdateHistoryEntry> filtered =
                new FilteredList<>(FXCollections.observableArrayList(entries), p -> true);
        searchField.textProperty().addListener((obs, o, n) -> {
            String q = n == null ? "" : n.trim().toLowerCase();
            filtered.setPredicate(e -> {
                if (e == null) return false;
                if (q.isEmpty()) return true;
                String name = e.packageName() == null ? "" : e.packageName().toLowerCase();
                String id = e.packageId() == null ? "" : e.packageId().toLowerCase();
                return name.contains(q) || id.contains(q);
            });
            countLabel.setText(filtered.size() + " of " + entries.size() + " entr(ies)");
        });

        TableView<SoftwareUpdateHistoryEntry> table = new TableView<>(filtered);
        table.setPlaceholder(new Label("No history yet. Successful and failed installs will appear here."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SoftwareUpdateHistoryEntry, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                formatInstalledAt(c.getValue())));
        dateCol.setPrefWidth(130);

        TableColumn<SoftwareUpdateHistoryEntry, String> nameCol = new TableColumn<>("Program");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().packageName() == null ? "" : c.getValue().packageName()));

        TableColumn<SoftwareUpdateHistoryEntry, String> oldVerCol = new TableColumn<>("Old Version");
        oldVerCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().oldVersion() == null ? "" : c.getValue().oldVersion()));
        oldVerCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateHistoryEntry, String> newVerCol = new TableColumn<>("New Version");
        newVerCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().newVersion() == null ? "" : c.getValue().newVersion()));
        newVerCol.setPrefWidth(100);

        TableColumn<SoftwareUpdateHistoryEntry, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().source() == null ? "" : c.getValue().source()));
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
        errorCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().errorMessage() == null ? "" : c.getValue().errorMessage()));
        errorCol.setPrefWidth(200);

        table.getColumns().addAll(dateCol, nameCol, oldVerCol, newVerCol, sourceCol, statusCol, errorCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button clearBtn = new Button("Clear History");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Clear all update history?");
            confirm.setHeaderText(AppInfo.DISPLAY_NAME);
            if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                store.clear();
                filtered.getSource().clear();
                countLabel.setText("0 entr(ies)");
            }
        });

        Button exportBtn = new Button("Export CSV");
        exportBtn.setOnAction(e -> exportCsv(filtered));

        HBox bottom = new HBox(8, clearBtn, exportBtn);
        bottom.setPadding(new Insets(8, 0, 0, 0));

        VBox content = new VBox(8, filterBar, table, bottom);
        content.setPadding(new Insets(10));
        content.setPrefWidth(750);
        content.setPrefHeight(400);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static void exportCsv(List<SoftwareUpdateHistoryEntry> entries) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export update history");
            chooser.setInitialFileName("software-update-history.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            java.io.File target = chooser.showSaveDialog(null);
            if (target == null) return;
            StringBuilder sb = new StringBuilder("Date,Program,PackageId,OldVersion,NewVersion,Source,Status,Error\n");
            for (SoftwareUpdateHistoryEntry e : entries) {
                sb.append(csv(formatInstalledAt(e))).append(',')
                        .append(csv(e.packageName())).append(',')
                        .append(csv(e.packageId())).append(',')
                        .append(csv(e.oldVersion())).append(',')
                        .append(csv(e.newVersion())).append(',')
                        .append(csv(e.source())).append(',')
                        .append(e.success() ? "OK" : "Failed").append(',')
                        .append(csv(e.errorMessage())).append('\n');
            }
            Path p = target.toPath();
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AppLogger.warning("History CSV export failed: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Export failed:\n" + ex.getMessage()).showAndWait();
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}
