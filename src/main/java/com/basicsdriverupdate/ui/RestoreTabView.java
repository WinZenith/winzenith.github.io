package com.basicsdriverupdate.ui;

import com.basicsdriverupdate.backup.DriverBackupService;
import com.basicsdriverupdate.backup.RestoreRow;
import com.basicsdriverupdate.util.AdminCheck;
import com.basicsdriverupdate.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.BooleanSupplier;

public class RestoreTabView extends BorderPane {

    private final DriverBackupService backupService = new DriverBackupService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<RestoreRow> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Driver backups appear here (up to 3 versions per device).");
    private final Button refreshButton = new Button("Refresh");

    public RestoreTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        refreshButton.setOnAction(e -> refresh());
        HBox top = new HBox(12, refreshButton, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");
        setTop(top);
        setCenter(buildTable());
        if (!AppPaths.isWindows()) {
            refreshButton.setDisable(true);
        } else {
            refresh();
        }
    }

    private TableView<RestoreRow> buildTable() {
        TableView<RestoreRow> table = new TableView<>(rows);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        VBox.setVgrow(table, Priority.ALWAYS);

        TableColumn<RestoreRow, String> deviceCol = new TableColumn<>("Device");
        deviceCol.setCellValueFactory(c -> c.getValue().deviceNameProperty());

        TableColumn<RestoreRow, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> c.getValue().versionProperty());
        versionCol.setPrefWidth(100);

        TableColumn<RestoreRow, String> dateCol = new TableColumn<>("Backed up");
        dateCol.setCellValueFactory(c -> c.getValue().backedUpAtProperty());
        dateCol.setPrefWidth(140);

        TableColumn<RestoreRow, Void> actionCol = new TableColumn<>("Action");
        actionCol.setPrefWidth(90);
        actionCol.setCellFactory(col -> new TableCell<>() {
            private final UIButton revertBtn = UIButton.small("Revert");

            {
                revertBtn.setOnAction(e -> {
                    RestoreRow row = getTableView().getItems().get(getIndex());
                    if (row != null) {
                        revert(row);
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                if (empty) {
                    setGraphic(null);
                } else {
                    revertBtn.setDisable(busy.get());
                    setGraphic(revertBtn);
                }
            }
        });

        table.getColumns().addAll(deviceCol, versionCol, dateCol, actionCol);
        return table;
    }

    public void refresh() {
        new Thread(() -> {
            try {
                var entries = backupService.listAll();
                ObservableList<RestoreRow> newRows = FXCollections.observableArrayList();
                for (var e : entries) {
                    newRows.add(new RestoreRow(e));
                }
                Platform.runLater(() -> {
                    rows.setAll(newRows);
                    statusLabel.setText(entries.isEmpty()
                            ? "No backups yet. Backups are created automatically before driver updates."
                            : entries.size() + " backup(s) available.");
                });
            } catch (Exception ex) {
                Platform.runLater(() -> statusLabel.setText("Failed to load backups: " + ex.getMessage()));
            }
        }, "restore-refresh").start();
    }

    private void revert(RestoreRow row) {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Reverting drivers requires administrator rights.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Revert driver for:\n" + row.entry().friendlyName()
                        + "\n\nTo version: " + row.entry().version()
                        + "\n\nBacked up: " + row.backedUpAtProperty().get());
        if (confirm.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
            return;
        }
        busy.set(true);
        new Thread(() -> {
            try {
                backupService.revert(row.entry());
                Platform.runLater(() -> {
                    statusLabel.setText("Reverted " + row.entry().friendlyName());
                    new Alert(Alert.AlertType.INFORMATION, "Driver reverted. Restart if devices do not work correctly.").showAndWait();
                });
            } catch (Exception ex) {
                Platform.runLater(() -> new Alert(Alert.AlertType.ERROR, "Revert failed:\n" + ex.getMessage()).showAndWait());
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "driver-revert").start();
    }
}
