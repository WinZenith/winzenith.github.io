package com.sbtools.ui;

import com.sbtools.uninstaller.UninstallHistoryEntry;
import com.sbtools.uninstaller.UninstallHistoryStore;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Read-only audit view for past uninstall / force-remove operations.
 */
public class UninstallerHistoryDialog {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static String fmt(UninstallHistoryEntry e) {
        try {
            if (e == null || e.uninstalledAt() == null) return "";
            return e.uninstalledAt().atZone(ZoneId.systemDefault()).format(DATE_FMT);
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void show() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(AppInfo.DISPLAY_NAME + " - Uninstall History");
        dialog.setHeaderText("Uninstall History");

        UninstallHistoryStore store = new UninstallHistoryStore();
        List<UninstallHistoryEntry> entries = store.listAll();

        TextField searchField = new TextField();
        searchField.setPromptText("Filter by app name...");
        searchField.setPrefWidth(260);
        Label countLabel = new Label(entries.size() + " entr(ies)");
        countLabel.setStyle("-fx-opacity: 0.75;");
        HBox filterBar = new HBox(10, new Label("Search:"), searchField, countLabel);
        filterBar.setAlignment(Pos.CENTER_LEFT);

        FilteredList<UninstallHistoryEntry> filtered =
                new FilteredList<>(FXCollections.observableArrayList(entries), p -> true);
        searchField.textProperty().addListener((obs, o, n) -> {
            String q = n == null ? "" : n.trim().toLowerCase();
            filtered.setPredicate(e -> {
                if (e == null) return false;
                if (q.isEmpty()) return true;
                String name = e.appName() == null ? "" : e.appName().toLowerCase();
                return name.contains(q);
            });
            countLabel.setText(filtered.size() + " of " + entries.size() + " entr(ies)");
        });

        TableView<UninstallHistoryEntry> table = new TableView<>(filtered);
        table.setPlaceholder(new Label("No uninstalls recorded yet."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<UninstallHistoryEntry, String> dateCol = new TableColumn<>("Date");
        dateCol.setCellValueFactory(c -> new SimpleStringProperty(fmt(c.getValue())));
        dateCol.setPrefWidth(120);

        TableColumn<UninstallHistoryEntry, String> nameCol = new TableColumn<>("Application");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().appName() == null ? "" : c.getValue().appName()));
        nameCol.setPrefWidth(200);

        TableColumn<UninstallHistoryEntry, String> modeCol = new TableColumn<>("Mode");
        modeCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().mode() == null ? "" : c.getValue().mode()));
        modeCol.setPrefWidth(90);

        TableColumn<UninstallHistoryEntry, Boolean> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c ->
                new javafx.beans.property.SimpleBooleanProperty(c.getValue().success()));
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

        TableColumn<UninstallHistoryEntry, String> detailCol = new TableColumn<>("Detail");
        detailCol.setCellValueFactory(c -> new SimpleStringProperty(
                c.getValue().detail() == null ? "" : c.getValue().detail()));

        table.getColumns().addAll(dateCol, nameCol, modeCol, statusCol, detailCol);
        VBox.setVgrow(table, Priority.ALWAYS);

        Button clearBtn = new Button("Clear History");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "Clear all uninstall history?");
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
        content.setPrefWidth(720);
        content.setPrefHeight(400);

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    private static void exportCsv(List<UninstallHistoryEntry> entries) {
        try {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("Export uninstall history");
            chooser.setInitialFileName("uninstall-history.csv");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV", "*.csv"));
            java.io.File target = chooser.showSaveDialog(null);
            if (target == null) return;
            StringBuilder sb = new StringBuilder(
                    "Date,Application,Version,Publisher,Type,Mode,Status,ExitCode,Leftovers,Detail\n");
            for (UninstallHistoryEntry e : entries) {
                sb.append(csv(fmt(e))).append(',')
                        .append(csv(e.appName())).append(',')
                        .append(csv(e.version())).append(',')
                        .append(csv(e.publisher())).append(',')
                        .append(csv(e.appType())).append(',')
                        .append(csv(e.mode())).append(',')
                        .append(e.success() ? "OK" : "Failed").append(',')
                        .append(e.exitCode()).append(',')
                        .append(e.leftoversDeleted()).append(',')
                        .append(csv(e.detail())).append('\n');
            }
            Files.writeString(target.toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AppLogger.warning("Uninstall history CSV export failed: " + ex.getMessage());
            new Alert(Alert.AlertType.ERROR, "Export failed:\n" + ex.getMessage()).showAndWait();
        }
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"");
        return s.contains(",") || s.contains("\"") || s.contains("\n") ? "\"" + s + "\"" : s;
    }
}
