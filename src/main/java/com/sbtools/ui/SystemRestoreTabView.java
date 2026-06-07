package com.sbtools.ui;

import com.sbtools.backup.SystemRestoreRow;
import com.sbtools.backup.SystemRestoreService;
import com.sbtools.util.AdminCheck;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.function.BooleanSupplier;

public class SystemRestoreTabView extends BorderPane {

    private final SystemRestoreService service = new SystemRestoreService();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);
    private final BooleanSupplier adminCheck;

    private final ObservableList<SystemRestoreRow> rows = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Click Scan to list system restore points.");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final Button scanButton = new Button("Scan");
    private final Button createButton = new Button("Create new restore point");
    private final Button launchButton = new Button("Launch restore point");
    private final TableView<SystemRestoreRow> table = new TableView<>(rows);

    public SystemRestoreTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.adminCheck = adminCheck;

        spinner.setVisible(false);
        spinner.setMaxSize(20, 20);

        scanButton.setOnAction(e -> scan());
        createButton.setOnAction(e -> createRestorePoint());
        launchButton.setOnAction(e -> launchRestore());

        createButton.getStyleClass().add("success");

        HBox top = new HBox(12, scanButton, createButton, launchButton,
                spinner, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();

        VBox center = new VBox(8, table);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(top);
        setCenter(center);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            createButton.setDisable(newVal);
            launchButton.setDisable(newVal);
            spinner.setVisible(newVal);
        });

        scan();
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<SystemRestoreRow, SystemRestoreRow> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private SystemRestoreRow previousItem;

            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }

            @Override
            protected void updateItem(SystemRestoreRow item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (previousItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                        previousItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (previousItem != null && previousItem != item) {
                        checkBox.selectedProperty().unbindBidirectional(previousItem.selectedProperty());
                    }
                    if (checkBox.selectedProperty().isBound()) {
                        checkBox.selectedProperty().unbind();
                    }
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    previousItem = item;
                    setGraphic(checkBox);
                }
            }
        });

        TableColumn<SystemRestoreRow, String> descCol = new TableColumn<>("Description");
        descCol.setCellValueFactory(c -> c.getValue().descriptionProperty());
        descCol.setPrefWidth(300);

        TableColumn<SystemRestoreRow, String> dateCol = new TableColumn<>("Creation Date/Time");
        dateCol.setCellValueFactory(c -> c.getValue().creationTimeProperty());
        dateCol.setPrefWidth(160);

        TableColumn<SystemRestoreRow, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> c.getValue().eventTypeProperty());
        typeCol.setPrefWidth(140);

        table.getColumns().addAll(checkCol, descCol, dateCol, typeCol);
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Scanning restore points...");
        rows.clear();

        new Thread(() -> {
            try {
                List<SystemRestoreRow> results = service.listRestorePoints();
                Platform.runLater(() -> {
                    rows.setAll(results);
                    statusLabel.setText(results.size() + " restore point(s) found.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to scan restore points", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan restore points:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "restore-scan").start();
    }

    private void createRestorePoint() {
        if (busy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING,
                    "Creating system restore points requires administrator rights.").showAndWait();
            try {
                AdminCheck.requestElevation();
            } catch (java.io.IOException ex) {
                AppLogger.warning("Failed to request elevation: " + ex.getMessage());
            }
            return;
        }

        TextInputDialog dialog = new TextInputDialog("Manual Restore Point");
        dialog.setTitle("Create Restore Point");
        dialog.setHeaderText("Enter a description for the new restore point:");
        dialog.setContentText("Description:");
        String description = dialog.showAndWait().orElse(null);
        if (description == null || description.isBlank()) return;

        busy.set(true);
        statusLabel.setText("Creating restore point...");
        final String desc = description;

        new Thread(() -> {
            try {
                boolean ok = service.createRestorePoint(desc);
                Platform.runLater(() -> {
                    if (ok) {
                        statusLabel.setText("Restore point created.");
                        new Alert(Alert.AlertType.INFORMATION,
                                "Restore point '" + desc + "' created successfully.").showAndWait();
                    } else {
                        statusLabel.setText("Failed to create restore point.");
                        new Alert(Alert.AlertType.ERROR,
                                "Failed to create restore point. Ensure System Protection is enabled for your system drive.")
                                .showAndWait();
                    }
                });
            } catch (Exception e) {
                AppLogger.error("Failed to create restore point", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Creation failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to create restore point:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    scan();
                });
            }
        }, "restore-create").start();
    }

    private void launchRestore() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Launch System Restore");
        confirm.setHeaderText("Start Windows System Restore?");
        confirm.setContentText("This will launch the System Restore wizard and may reboot your computer.\n\n"
                + "Ensure all work is saved before proceeding.");
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        try {
            service.launchSystemRestore();
            statusLabel.setText("System Restore launched.");
        } catch (Exception e) {
            AppLogger.error("Failed to launch System Restore", e);
            new Alert(Alert.AlertType.ERROR, "Failed to launch System Restore:\n" + e.getMessage()).showAndWait();
        }
    }
}
