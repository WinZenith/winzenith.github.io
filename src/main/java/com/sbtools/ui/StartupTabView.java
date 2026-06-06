package com.sbtools.ui;

import com.sbtools.startup.StartupItem;
import com.sbtools.startup.StartupService;
import com.sbtools.startup.StartupService.StartupBackupEntry;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.BooleanSupplier;

public class StartupTabView extends BorderPane {

    private final StartupService service = new StartupService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<StartupItem> allItems = FXCollections.observableArrayList();
    private final FilteredList<StartupItem> filteredItems = new FilteredList<>(allItems);

    private final Label statusLabel = new Label("Scan system to list startup items.");
    private final ProgressIndicator progress = new ProgressIndicator();
    
    private final Button scanButton = new Button("Scan");
    private final Button toggleButton = new Button("Enable/Disable");
    private final Button deleteButton = new Button("Delete");
    private final Button backupsButton = new Button("Backups & Restore");
    private final TextField searchField = new TextField();

    private final ToggleGroup categoryGroup = new ToggleGroup();
    private final ToggleButton allToggle = new ToggleButton("All");
    private final ToggleButton registryToggle = new ToggleButton("Registry");
    private final ToggleButton folderToggle = new ToggleButton("Startup Folders");
    private final ToggleButton tasksToggle = new ToggleButton("Scheduled Tasks");

    private final TableView<StartupItem> table = new TableView<>(filteredItems);

    public StartupTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        // Scan button
        scanButton.setOnAction(e -> scan());

        // Toggle button
        toggleButton.setOnAction(e -> triggerToggle());
        toggleButton.setDisable(true);
        toggleButton.getStyleClass().add("button-outlined");

        // Delete button
        deleteButton.setOnAction(e -> triggerDelete());
        deleteButton.setDisable(true);
        deleteButton.getStyleClass().add("danger");

        // Backups button
        backupsButton.setOnAction(e -> showBackupsDialog());
        backupsButton.getStyleClass().add("button-outlined");

        // Search field
        searchField.setPromptText("Search items...");
        searchField.setPrefWidth(200);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        // Category toggles
        allToggle.setToggleGroup(categoryGroup);
        allToggle.setSelected(true);
        registryToggle.setToggleGroup(categoryGroup);
        folderToggle.setToggleGroup(categoryGroup);
        tasksToggle.setToggleGroup(categoryGroup);

        categoryGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                oldVal.setSelected(true); // Prevent unselecting everything
                return;
            }
            applyFilter();
        });

        // Top toolbar
        HBox top = new HBox(12,
                allToggle, registryToggle, folderToggle, tasksToggle,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                searchField, scanButton, toggleButton, deleteButton, backupsButton,
                progress, statusLabel
        );
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        // Build TableView
        buildTable();

        setTop(top);
        setCenter(table);

        // Table selection listener
        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSelection, newSelection) -> {
            boolean hasSelection = newSelection != null;
            toggleButton.setDisable(!hasSelection || busy.get());
            deleteButton.setDisable(!hasSelection || busy.get());
        });

        // Global busy listener
        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            toggleButton.setDisable(newVal || table.getSelectionModel().getSelectedItem() == null);
            deleteButton.setDisable(newVal || table.getSelectionModel().getSelectedItem() == null);
            backupsButton.setDisable(newVal);
            searchField.setDisable(newVal);
            allToggle.setDisable(newVal);
            registryToggle.setDisable(newVal);
            folderToggle.setDisable(newVal);
            tasksToggle.setDisable(newVal);
        });

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            statusLabel.setText("Startup manager is only available on Windows.");
        } else {
            // Auto scan on load
            Platform.runLater(this::scan);
        }
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<StartupItem, String> nameCol = new TableColumn<>("Startup Item Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(220);

        TableColumn<StartupItem, String> publisherCol = new TableColumn<>("Publisher");
        publisherCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPublisher()));
        publisherCol.setPrefWidth(180);

        TableColumn<StartupItem, String> locationCol = new TableColumn<>("Location");
        locationCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLocation()));
        locationCol.setPrefWidth(160);

        TableColumn<StartupItem, String> pathCol = new TableColumn<>("Command / Execution Path");
        pathCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPath()));
        pathCol.setPrefWidth(300);

        TableColumn<StartupItem, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().isEnabled() ? "Enabled" : "Disabled"));
        statusCol.setPrefWidth(100);
        statusCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    setText(item);
                    if ("Enabled".equalsIgnoreCase(item)) {
                        setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                    } else {
                        setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                    }
                }
            }
        });

        table.getColumns().addAll(nameCol, publisherCol, locationCol, pathCol, statusCol);

        table.setRowFactory(tv -> {
            TableRow<StartupItem> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    triggerToggle();
                }
            });
            return row;
        });
    }

    private void applyFilter() {
        String filter = searchField.getText();
        boolean filterReg = registryToggle.isSelected();
        boolean filterFolder = folderToggle.isSelected();
        boolean filterTasks = tasksToggle.isSelected();

        filteredItems.setPredicate(item -> {
            // Category filter
            if (filterReg && !item.getLocation().contains("Run")) return false;
            if (filterFolder && !item.getLocation().contains("Startup Folder")) return false;
            if (filterTasks && !"Scheduled Task".equals(item.getLocation())) return false;

            // Search text filter
            if (filter == null || filter.isBlank()) {
                return true;
            }
            String lower = filter.toLowerCase();
            return item.getName().toLowerCase().contains(lower) ||
                   item.getPublisher().toLowerCase().contains(lower) ||
                   item.getPath().toLowerCase().contains(lower) ||
                   item.getLocation().toLowerCase().contains(lower);
        });
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Scanning startup items...");
        allItems.clear();

        new Thread(() -> {
            try {
                List<StartupItem> items = service.listAll();
                Platform.runLater(() -> {
                    allItems.setAll(items);
                    applyFilter();
                    statusLabel.setText("Found " + items.size() + " startup item(s).");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to scan startup items", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan startup items:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                });
            }
        }, "startup-scan").start();
    }

    private void triggerToggle() {
        StartupItem selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        busy.set(true);
        progress.setVisible(true);
        String actionName = selected.isEnabled() ? "Disabling" : "Enabling";
        statusLabel.setText(actionName + " " + selected.getName() + "...");

        new Thread(() -> {
            try {
                service.toggleStatus(selected);
                Platform.runLater(() -> {
                    table.refresh();
                    applyFilter();
                    statusLabel.setText("Item " + (selected.isEnabled() ? "enabled" : "disabled") + " successfully.");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to toggle status", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Action failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to toggle startup item status:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                });
            }
        }, "startup-toggle").start();
    }

    private void triggerDelete() {
        StartupItem selected = table.getSelectionModel().getSelectedItem();
        if (selected == null || busy.get()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Deletion");
        confirm.setHeaderText("Delete Startup Item: " + selected.getName());
        confirm.setContentText("Are you sure you want to permanently delete this startup item?\n" +
                "This action will remove it from the system startup locations. A backup will be created automatically.");
        confirm.initModality(Modality.APPLICATION_MODAL);

        if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
            busy.set(true);
            progress.setVisible(true);
            statusLabel.setText("Deleting " + selected.getName() + "...");

            new Thread(() -> {
                try {
                    service.deleteItem(selected);
                    Platform.runLater(() -> {
                        allItems.remove(selected);
                        applyFilter();
                        statusLabel.setText("Startup item deleted successfully.");
                        new Alert(Alert.AlertType.INFORMATION, "The startup item has been deleted. You can restore it anytime from the Backups panel.").showAndWait();
                    });
                } catch (Exception e) {
                    AppLogger.error("Failed to delete startup item", e);
                    Platform.runLater(() -> {
                        statusLabel.setText("Deletion failed.");
                        new Alert(Alert.AlertType.ERROR, "Failed to delete startup item:\n" + e.getMessage()).showAndWait();
                    });
                } finally {
                    Platform.runLater(() -> {
                        busy.set(false);
                        progress.setVisible(false);
                    });
                }
            }, "startup-delete").start();
        }
    }

    // ── Backup Manager Dialog ──────────────────────────────────────────────────

    private void showBackupsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Startup Backups & Restore");
        dialog.setHeaderText("Restore previously deleted or modified startup items.");
        dialog.initModality(Modality.APPLICATION_MODAL);

        try {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        } catch (Exception ignored) {}

        ObservableList<StartupBackupEntry> backups = FXCollections.observableArrayList();
        try {
            backups.setAll(service.listBackups());
        } catch (Exception e) {
            AppLogger.error("Failed to load backups list", e);
        }

        TableView<StartupBackupEntry> backupTable = new TableView<>(backups);
        backupTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<StartupBackupEntry, String> nameCol = new TableColumn<>("Name");
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(160);

        TableColumn<StartupBackupEntry, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getType()));
        typeCol.setPrefWidth(90);

        TableColumn<StartupBackupEntry, String> dateCol = new TableColumn<>("Backup Date");
        dateCol.setCellValueFactory(c -> {
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            return new SimpleStringProperty(df.format(new Date(c.getValue().getBackupTime())));
        });
        dateCol.setPrefWidth(140);

        TableColumn<StartupBackupEntry, String> originalCol = new TableColumn<>("Original Location");
        originalCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLocation()));
        originalCol.setPrefWidth(160);

        TableColumn<StartupBackupEntry, String> commandCol = new TableColumn<>("Command");
        commandCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getCommand()));
        commandCol.setPrefWidth(240);

        backupTable.getColumns().addAll(nameCol, typeCol, dateCol, originalCol, commandCol);

        Button restoreBtn = new Button("Restore Selected");
        Button deleteBackupBtn = new Button("Delete Backup");
        
        restoreBtn.setDisable(true);
        deleteBackupBtn.setDisable(true);

        backupTable.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            boolean hasSel = newSel != null;
            restoreBtn.setDisable(!hasSel);
            deleteBackupBtn.setDisable(!hasSel);
        });

        restoreBtn.setOnAction(e -> {
            StartupBackupEntry selected = backupTable.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            try {
                service.restoreBackup(selected);
                backups.remove(selected);
                new Alert(Alert.AlertType.INFORMATION, "Startup item restored successfully.").showAndWait();
                scan(); // Refresh the main startup items list
            } catch (Exception ex) {
                AppLogger.error("Failed to restore startup item", ex);
                new Alert(Alert.AlertType.ERROR, "Failed to restore backup:\n" + ex.getMessage()).showAndWait();
            }
        });

        deleteBackupBtn.setOnAction(e -> {
            StartupBackupEntry selected = backupTable.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Deletion");
            confirm.setHeaderText("Delete Backup Entry");
            confirm.setContentText("Are you sure you want to permanently delete this backup? You will no longer be able to restore it.");
            if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
                try {
                    service.removeBackup(selected);
                    backups.remove(selected);
                } catch (Exception ex) {
                    AppLogger.error("Failed to delete backup entry", ex);
                    new Alert(Alert.AlertType.ERROR, "Failed to delete backup:\n" + ex.getMessage()).showAndWait();
                }
            }
        });

        HBox dialogControls = new HBox(10, restoreBtn, deleteBackupBtn);
        dialogControls.setAlignment(Pos.CENTER_RIGHT);
        dialogControls.setPadding(new Insets(10, 0, 0, 0));

        VBox layout = new VBox(8, backupTable, dialogControls);
        layout.setPrefSize(780, 360);
        layout.setPadding(new Insets(10));

        dialog.getDialogPane().setContent(layout);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);

        dialog.showAndWait();
    }
}
