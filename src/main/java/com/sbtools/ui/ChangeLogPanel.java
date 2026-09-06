package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkChangeEntry;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

class ChangeLogPanel extends VBox {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());
    private static final int DISPLAY_LIMIT = 100;

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final ObservableList<NetworkChangeEntry> entries = FXCollections.observableArrayList();
    private final FilteredList<NetworkChangeEntry> filtered = new FilteredList<>(entries, e -> true);
    private final TableView<NetworkChangeEntry> table = new TableView<>(filtered);
    private final TextArea detailsArea = new TextArea();
    private final TextField filterField = new TextField();
    private final ComboBox<String> resultFilter = new ComboBox<>();
    private volatile Future<?> currentTask;

    ChangeLogPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildContent());
        VBox.setVgrow(table, Priority.ALWAYS);
        setPadding(new Insets(12, 16, 12, 16));
    }

    void loadEntries() {
        if (busy.get()) return;
        busy.set(true);
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.getChangeLog();
                Platform.runLater(() -> {
                    entries.setAll(result);
                    applyFilter();
                });
            } catch (Exception e) {
                AppLogger.warning("Failed to load changelog: " + e.getMessage());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
    }

    private void applyFilter() {
        String q = filterField.getText() != null ? filterField.getText().trim().toLowerCase() : "";
        String res = resultFilter.getSelectionModel().getSelectedItem();
        filtered.setPredicate(e -> {
            if ("OK only".equals(res) && !e.success()) return false;
            if ("Failed only".equals(res) && e.success()) return false;
            if (q.isEmpty()) return true;
            return (e.operation() != null && e.operation().toLowerCase().contains(q))
                    || (e.target() != null && e.target().toLowerCase().contains(q))
                    || (e.details() != null && e.details().toLowerCase().contains(q));
        });
    }

    private VBox buildContent() {
        VBox content = new VBox(8);

        Label header = new Label("Change History");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Label sub = new Label("Shows the last " + DISPLAY_LIMIT
                + " network operations. Snapshots are stored separately (Optimization → Snapshots…).");
        sub.setStyle("-fx-text-fill: #6272a4;");
        sub.setWrapText(true);
        content.getChildren().add(sub);

        buildTable();
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            if (sel != null) {
                detailsArea.setText(sel.details() != null ? sel.details() : "");
            } else {
                detailsArea.clear();
            }
        });

        filterField.setPromptText("Filter operation / target / details…");
        filterField.setPrefWidth(220);
        filterField.textProperty().addListener((obs, o, n) -> applyFilter());
        resultFilter.getItems().addAll("All results", "OK only", "Failed only");
        resultFilter.getSelectionModel().selectFirst();
        resultFilter.setOnAction(e -> applyFilter());

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> loadEntries());

        Button clearBtn = UIButton.secondary("Clear History");
        clearBtn.setOnAction(e -> {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Clear all change history?", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Clear History");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
                AppExecutors.ioPool().submit(() -> {
                    service.clearChangeLog();
                    Platform.runLater(() -> entries.clear());
                });
            }
        });

        Button exportBtn = UIButton.secondary("Export CSV");
        exportBtn.setOnAction(e -> exportCsv());

        Button snapshotsBtn = UIButton.secondary("View Snapshots…");
        snapshotsBtn.setOnAction(e -> showSnapshots());

        HBox btnBox = new HBox(12, refreshBtn, clearBtn, exportBtn, snapshotsBtn, filterField, resultFilter);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        btnBox.setPadding(new Insets(0, 0, 8, 0));

        detailsArea.setEditable(false);
        detailsArea.setPrefRowCount(4);
        detailsArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        detailsArea.setPromptText("Select a row to see full details...");

        content.getChildren().addAll(btnBox, table, new Label("Details:"), detailsArea);
        return content;
    }

    private void exportCsv() {
        if (filtered.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Nothing to export.").showAndWait();
            return;
        }
        AppExecutors.ioPool().submit(() -> {
            try {
                StringBuilder sb = new StringBuilder("Time,Operation,Target,Details,Result\n");
                for (NetworkChangeEntry e : filtered) {
                    String t = e.timestamp() != null ? e.timestamp() : "";
                    try {
                        t = FORMATTER.format(Instant.parse(e.timestamp()));
                    } catch (Exception ignored) {}
                    sb.append(csv(t)).append(",").append(csv(e.operation())).append(",")
                            .append(csv(e.target())).append(",").append(csv(e.details())).append(",")
                            .append(e.success() ? "OK" : "FAIL").append("\n");
                }
                java.nio.file.Path base = com.sbtools.util.AppPaths.portableBaseDir();
                java.nio.file.Path dir = base != null
                        ? base.resolve(".winzenith").resolve("exports")
                        : java.nio.file.Path.of(System.getProperty("user.home"), ".winzenith", "exports");
                java.nio.file.Files.createDirectories(dir);
                String stamp = java.time.LocalDateTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                java.nio.file.Path out = dir.resolve("network-history-" + stamp + ".csv");
                java.nio.file.Files.writeString(out, sb.toString());
                Platform.runLater(() -> new Alert(Alert.AlertType.INFORMATION, "Saved to:\n" + out).showAndWait());
            } catch (Exception ex) {
                AppLogger.warning("History export failed: " + ex.getMessage());
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Export failed: " + ex.getMessage()).showAndWait());
            }
        });
    }

    private void showSnapshots() {
        AppExecutors.ioPool().submit(() -> {
            try {
                var snaps = service.listSnapshots();
                Platform.runLater(() -> {
                    if (snaps.isEmpty()) {
                        new Alert(Alert.AlertType.INFORMATION, "No snapshots yet.").showAndWait();
                        return;
                    }
                    StringBuilder sb = new StringBuilder();
                    for (var s : snaps) sb.append("• ").append(s.summary()).append("\n");
                    var info = service.describeRestore(snaps.get(0));
                    if (info.details() != null) sb.append("\n--- Newest ---\n").append(info.details());
                    Alert a = new Alert(Alert.AlertType.INFORMATION);
                    a.setTitle("Network Snapshots");
                    a.setHeaderText(snaps.size() + " snapshot(s)");
                    TextArea area = new TextArea(sb.toString());
                    area.setEditable(false);
                    area.setPrefRowCount(18);
                    area.setPrefColumnCount(70);
                    a.getDialogPane().setContent(area);
                    a.showAndWait();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Failed: " + ex.getMessage()).showAndWait());
            }
        });
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v.replace("\"", "\"\"").replace("\r", " ").replace("\n", " | ");
        return s.contains(",") || s.contains("\"") ? "\"" + s + "\"" : s;
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<NetworkChangeEntry, String> timeCol = new TableColumn<>("Time");
        timeCol.setCellValueFactory(c -> {
            String raw = c.getValue().timestamp();
            String formatted;
            try {
                formatted = FORMATTER.format(Instant.parse(raw));
            } catch (Exception ex) {
                formatted = raw != null ? raw : "";
            }
            return new javafx.beans.property.SimpleStringProperty(formatted);
        });
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
