package com.basicsdriverupdate.ui;

import com.basicsdriverupdate.util.AppPaths;
import com.basicsdriverupdate.windowsupdate.OsUpdateEntry;
import com.basicsdriverupdate.windowsupdate.WindowsUpdateService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

public class WindowsUpdateTabView extends BorderPane {

    private final WindowsUpdateService wuService = new WindowsUpdateService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<OsUpdateRow> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Scan for pending Windows updates (software, not drivers).");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button scanButton = new Button("Scan");
    private final Button installButton = new Button("Install selected");

    public WindowsUpdateTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        progress.setVisible(false);
        progress.setMaxSize(24, 24);
        scanButton.setOnAction(e -> scan());
        installButton.setOnAction(e -> installSelected());
        installButton.setDisable(true);

        HBox top = new HBox(12, scanButton, installButton, progress, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(8));

        TableView<OsUpdateRow> table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);
        setTop(top);
        setCenter(table);
        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            installButton.setDisable(true);
        }
    }

    private TableView<OsUpdateRow> buildTable() {
        TableView<OsUpdateRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<OsUpdateRow, Boolean> selCol = new TableColumn<>("Install");
        selCol.setCellValueFactory(c -> c.getValue().selectedProperty());
        selCol.setCellFactory(CheckBoxTableCell.forTableColumn(selCol));
        selCol.setPrefWidth(60);

        TableColumn<OsUpdateRow, String> titleCol = new TableColumn<>("Title");
        titleCol.setCellValueFactory(new PropertyValueFactory<>("title"));

        TableColumn<OsUpdateRow, String> kbCol = new TableColumn<>("KB");
        kbCol.setCellValueFactory(new PropertyValueFactory<>("kb"));
        kbCol.setPrefWidth(80);

        TableColumn<OsUpdateRow, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeCol.setPrefWidth(80);

        TableColumn<OsUpdateRow, String> impCol = new TableColumn<>("Importance");
        impCol.setCellValueFactory(new PropertyValueFactory<>("importance"));
        impCol.setPrefWidth(90);

        table.getColumns().addAll(selCol, titleCol, kbCol, sizeCol, impCol);
        return table;
    }

    private void scan() {
        if (busy.get()) {
            return;
        }
        busy.set(true);
        progress.setVisible(true);
        scanButton.setDisable(true);
        statusLabel.setText("Searching Windows Update…");
        new Thread(() -> {
            try {
                List<OsUpdateEntry> updates = wuService.scanOsUpdates();
                ObservableList<OsUpdateRow> newRows = FXCollections.observableArrayList();
                for (OsUpdateEntry u : updates) {
                    newRows.add(new OsUpdateRow(u));
                }
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    statusLabel.setText(updates.size() + " update(s) available.");
                    installButton.setDisable(updates.isEmpty());
                });
            } catch (Exception ex) {
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, ex.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    scanButton.setDisable(false);
                });
            }
        }, "wu-scan").start();
    }

    private void installSelected() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Installing updates requires administrator rights.").showAndWait();
            return;
        }
        List<String> ids = rows.stream()
                .filter(r -> r.selectedProperty().get())
                .map(r -> r.entry().updateId())
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            new Alert(Alert.AlertType.INFORMATION, "Select at least one update.").showAndWait();
            return;
        }
        busy.set(true);
        statusLabel.setText("Installing " + ids.size() + " update(s)…");
        new Thread(() -> {
            try {
                WindowsUpdateService.InstallOsResult result = wuService.install(ids);
                Platform.runLater(() -> {
                    statusLabel.setText("Install finished.");
                    if (result.rebootRequired()) {
                        new Alert(Alert.AlertType.INFORMATION, "Restart required to complete updates.").showAndWait();
                    }
                    scan();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Install failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "wu-install").start();
    }

    public static class OsUpdateRow {
        private final OsUpdateEntry entry;
        private final SimpleBooleanProperty selected = new SimpleBooleanProperty(true);
        private final SimpleStringProperty title = new SimpleStringProperty();
        private final SimpleStringProperty kb = new SimpleStringProperty();
        private final SimpleStringProperty size = new SimpleStringProperty();
        private final SimpleStringProperty importance = new SimpleStringProperty();

        OsUpdateRow(OsUpdateEntry entry) {
            this.entry = entry;
            title.set(entry.title());
            kb.set(entry.kbArticle() != null && !entry.kbArticle().isBlank() ? entry.kbArticle() : "—");
            size.set(entry.sizeDisplay());
            importance.set(entry.importance() != null && !entry.importance().isBlank() ? entry.importance() : "—");
        }

        public OsUpdateEntry entry() {
            return entry;
        }

        public SimpleBooleanProperty selectedProperty() {
            return selected;
        }

        public String getTitle() {
            return title.get();
        }

        public String getKb() {
            return kb.get();
        }

        public String getSize() {
            return size.get();
        }

        public String getImportance() {
            return importance.get();
        }
    }
}
