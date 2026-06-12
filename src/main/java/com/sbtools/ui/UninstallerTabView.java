package com.sbtools.ui;

import com.sbtools.uninstaller.InstalledApp;
import com.sbtools.uninstaller.LeftoverItem;
import com.sbtools.uninstaller.UninstallerService;
import com.sbtools.util.AppIconResolver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.FormatUtils;
import com.sbtools.util.ProcessResult;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class UninstallerTabView extends BorderPane {

    private final UninstallerService service = new UninstallerService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final ObservableList<InstalledApp> allApps = FXCollections.observableArrayList();
    private final FilteredList<InstalledApp> filteredApps = new FilteredList<>(allApps);
    private final SortedList<InstalledApp> sortedApps = new SortedList<>(filteredApps);

    private final Label statusLabel = new Label("Scan system to list installed software.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button scanButton = new Button("Scan");
    private final Button uninstallButton = new Button("Uninstall");
    private final Button forceUninstallButton = new Button("Force Uninstall");
    private final TextField searchField = new TextField();

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton win32Toggle = new ToggleButton("Desktop Apps");
    private final ToggleButton appxToggle = new ToggleButton("Windows Store Apps");

    private final TableView<InstalledApp> table = new TableView<>(sortedApps);

    public UninstallerTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        scanButton.setOnAction(e -> scan());

        uninstallButton.setOnAction(e -> {
            InstalledApp selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) uninstallSingleApp(selected);
        });
        uninstallButton.setDisable(true);
        uninstallButton.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white;");

        forceUninstallButton.setOnAction(e -> {
            InstalledApp selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) triggerForceUninstallForApp(selected);
        });
        forceUninstallButton.setDisable(true);
        forceUninstallButton.getStyleClass().add("danger");

        searchField.setPromptText("Search apps...");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        win32Toggle.setToggleGroup(modeGroup);
        win32Toggle.setSelected(true);
        appxToggle.setToggleGroup(modeGroup);

        modeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null) {
                oldVal.setSelected(true);
                return;
            }
            allApps.clear();
            statusLabel.setText("Scan system to list installed software.");
        });

        HBox top = new HBox(12,
                win32Toggle, appxToggle,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                searchField, scanButton, uninstallButton, forceUninstallButton,
                progress, statusLabel
        );
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildTable();
        sortedApps.comparatorProperty().bind(table.comparatorProperty());

        setTop(top);
        setCenter(table);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> updateButtonStates());

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            searchField.setDisable(newVal);
            win32Toggle.setDisable(newVal);
            appxToggle.setDisable(newVal);
            updateButtonStates();
        });

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            uninstallButton.setDisable(true);
            forceUninstallButton.setDisable(true);
            statusLabel.setText("Uninstaller is only available on Windows.");
        }
    }

    private void buildTable() {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<InstalledApp, String> iconCol = new TableColumn<>(" ");
        iconCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(""));
        iconCol.setPrefWidth(36);
        iconCol.setMinWidth(36);
        iconCol.setMaxWidth(36);
        iconCol.setResizable(false);
        iconCol.setSortable(false);
        iconCol.setCellFactory(col -> new TableCell<>() {
            private final javafx.scene.image.ImageView imageView = new javafx.scene.image.ImageView();
            private final javafx.scene.layout.StackPane iconPane = new javafx.scene.layout.StackPane(imageView);
            {
                getStyleClass().add("icon-cell");
                imageView.setFitWidth(20);
                imageView.setFitHeight(20);
                imageView.setPreserveRatio(true);
                imageView.setSmooth(true);
                imageView.setCache(true);
                iconPane.setMaxSize(24, 24);
                iconPane.setPrefSize(24, 24);
                iconPane.setMinSize(24, 24);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(null);
                if (empty || getItem() == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    InstalledApp app = getTableRow().getItem();
                    imageView.setImage(null);
                    String loc = AppIconResolver.resolveAppIconPath(app);
                    if (loc != null) {
                        imageView.setImage(com.sbtools.util.IconExtractor.extractIcon(loc));
                    }
                    setGraphic(iconPane);
                }
            }
        });

        javafx.util.Callback<TableColumn<InstalledApp, String>, TableCell<InstalledApp, String>> textCellFactory = col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item);
                setGraphic(null);
                if (!getStyleClass().contains("text-cell")) {
                    getStyleClass().add("text-cell");
                }
            }
        };

        TableColumn<InstalledApp, String> nameCol = new TableColumn<>("Application");
        nameCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(360);
        nameCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(FormatUtils.formatSize(c.getValue().getEstimatedSize())));
        sizeCol.setPrefWidth(100);
        sizeCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> versionCol = new TableColumn<>("Version");
        versionCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getVersion()));
        versionCol.setPrefWidth(120);
        versionCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> typeCol = new TableColumn<>("Type");
        typeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getArchitecture()));
        typeCol.setPrefWidth(80);
        typeCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> dateCol = new TableColumn<>("Install Date");
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(FormatUtils.formatDate(c.getValue().getInstallDate())));
        dateCol.setPrefWidth(140);
        dateCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> publisherCol = new TableColumn<>("Company");
        publisherCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getPublisher()));
        publisherCol.setPrefWidth(200);
        publisherCol.setCellFactory(textCellFactory);

        table.getColumns().addAll(iconCol, nameCol, sizeCol, versionCol, typeCol, dateCol, publisherCol);

        table.setRowFactory(tv -> {
            TableRow<InstalledApp> row = new TableRow<>();
            row.setMinHeight(28);
            row.setPrefHeight(28);

            MenuItem uninstallItem = new MenuItem("Uninstall");
            uninstallItem.setOnAction(e -> uninstallSingleApp(row.getItem()));

            MenuItem forceUninstallItem = new MenuItem("Force Uninstall");
            forceUninstallItem.getStyleClass().add("danger-menu-item");
            forceUninstallItem.setOnAction(e -> triggerForceUninstallForApp(row.getItem()));

            ContextMenu contextMenu = new ContextMenu(uninstallItem, forceUninstallItem);

            row.contextMenuProperty().bind(
                    Bindings.when(row.emptyProperty())
                            .then((ContextMenu) null)
                            .otherwise(contextMenu)
            );

            return row;
        });
    }

    private void applyFilter() {
        String filter = searchField.getText();
        if (filter == null || filter.isBlank()) {
            filteredApps.setPredicate(app -> true);
        } else {
            String lower = filter.toLowerCase();
            filteredApps.setPredicate(app ->
                    app.getName().toLowerCase().contains(lower) ||
                    app.getPublisher().toLowerCase().contains(lower) ||
                    app.getVersion().toLowerCase().contains(lower) ||
                    app.getArchitecture().toLowerCase().contains(lower)
            );
        }
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Scanning for installed apps...");
        allApps.clear();

        boolean scanWin32 = win32Toggle.isSelected();

        new Thread(() -> {
            try {
                List<InstalledApp> apps;
                if (scanWin32) {
                    apps = service.listWin32Apps();
                } else {
                    apps = service.listAppxApps();
                }

                Platform.runLater(() -> {
                    allApps.setAll(apps);
                    applyFilter();
                    statusLabel.setText("Found " + apps.size() + " app(s).");
                });
            } catch (Exception e) {
                AppLogger.error("Failed to scan apps", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan installed apps:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                });
            }
        }, "uninstaller-scan").start();
    }

    private void updateButtonStates() {
        boolean hasSelection = table.getSelectionModel().getSelectedItem() != null;
        boolean isBusy = busy.get();
        uninstallButton.setDisable(!hasSelection || isBusy);
        forceUninstallButton.setDisable(!hasSelection || isBusy);
    }

    private void uninstallSingleApp(InstalledApp selected) {
        if (selected == null || busy.get()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Uninstallation");
        confirm.setHeaderText("Uninstall " + selected.getName());
        confirm.setContentText("Are you sure you want to run the default uninstaller for " + selected.getName() + "?");
        confirm.initModality(Modality.APPLICATION_MODAL);

        if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
            Alert restorePointDialog = new Alert(Alert.AlertType.CONFIRMATION);
            restorePointDialog.setTitle("System Restore Point");
            restorePointDialog.setHeaderText("Create a restore point?");
            restorePointDialog.setContentText("Would you like to create a System Restore point before uninstalling " + selected.getName() + "?");
            restorePointDialog.initModality(Modality.APPLICATION_MODAL);

            if (restorePointDialog.showAndWait().orElse(null) == ButtonType.OK) {
                runUninstallWithRestorePoint(selected);
            } else {
                runUninstallWizard(selected);
            }
        }
    }

    private void runUninstallWithRestorePoint(InstalledApp app) {
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Creating System Restore point...");

        new Thread(() -> {
            try {
                ProcessResult result = new com.sbtools.util.ProcessRunner(120).run(
                        List.of("powershell.exe", "-Command",
                                "Checkpoint-Computer -Description 'Before uninstalling " + app.getName().replace("'", "''") + "' -RestorePointType MODIFY_SETTINGS"));
                if (!result.success()) {
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        Alert errorAlert = new Alert(Alert.AlertType.WARNING);
                        errorAlert.setTitle("Restore Point Failed");
                        errorAlert.setHeaderText("Could not create restore point");
                        errorAlert.setContentText("Failed to create a System Restore point:\n" + result.combinedOutput()
                                + "\n\nDo you want to continue with the uninstall?");
                        errorAlert.initModality(Modality.APPLICATION_MODAL);

                        ButtonType yesBtn = new ButtonType("Yes");
                        ButtonType noBtn = new ButtonType("No");
                        errorAlert.getButtonTypes().setAll(yesBtn, noBtn);

                        if (errorAlert.showAndWait().orElse(noBtn) == yesBtn) {
                            runUninstallWizard(app);
                        } else {
                            busy.set(false);
                            statusLabel.setText("Uninstallation cancelled.");
                        }
                    });
                    return;
                }

                Platform.runLater(() -> {
                    statusLabel.setText("Restore point created. Starting uninstaller...");
                    runUninstallWizard(app);
                });
            } catch (Exception e) {
                AppLogger.error("Failed to create restore point", e);
                Platform.runLater(() -> {
                    progress.setVisible(false);
                    busy.set(false);
                    statusLabel.setText("Restore point failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to create System Restore point:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "restore-point").start();
    }

    private void runUninstallWizard(InstalledApp app) {
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Running uninstaller for " + app.getName() + "...");

        new Thread(() -> {
            try {
                AppLogger.info("Starting uninstaller for: " + app.getName());
                ProcessResult result = service.runUninstaller(app);
                AppLogger.info("Uninstaller completed with exit code: " + result.exitCode());

                waitForUninstallCompletion(app);

                Platform.runLater(() -> statusLabel.setText("Scanning leftovers for " + app.getName() + "..."));
                List<String> fileLeftovers = service.scanFilesystemLeftovers(app);
                List<String> regLeftovers = service.scanRegistryLeftovers(app);

                Platform.runLater(() -> {
                    progress.setVisible(false);
                    statusLabel.setText("Scanning completed.");
                    showLeftoversReview(app, fileLeftovers, regLeftovers);
                });

            } catch (Exception e) {
                AppLogger.error("Error during uninstallation workflow", e);
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    statusLabel.setText("Workflow interrupted.");
                    new Alert(Alert.AlertType.ERROR, "An error occurred during uninstallation:\n" + e.getMessage()).showAndWait();
                });
            }
        }, "uninstallation-workflow").start();
    }

    private void waitForUninstallCompletion(InstalledApp app) throws InterruptedException {
        String installLocation = app.getInstallLocation();
        if (installLocation != null && !installLocation.isBlank()) {
            File installDir = new File(installLocation);
            if (installDir.exists()) {
                AppLogger.info("Waiting for uninstaller to finish - polling install directory: " + installLocation);
                long deadline = System.currentTimeMillis() + 30000;
                while (installDir.exists() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(500);
                }
                if (installDir.exists()) {
                    AppLogger.info("Timeout reached waiting for install directory removal, proceeding with scan");
                } else {
                    AppLogger.info("Install directory removed, uninstaller confirmed complete");
                }
            }
        } else {
            Thread.sleep(2000);
        }
    }

    private void showLeftoversReview(InstalledApp app, List<String> fileLeftovers, List<String> regLeftovers) {
        if (fileLeftovers.isEmpty() && regLeftovers.isEmpty()) {
            Alert done = new Alert(Alert.AlertType.INFORMATION);
            done.setTitle("No Leftovers Found");
            done.setHeaderText(app.getName() + " uninstalled");
            done.setContentText("No leftovers were detected in the filesystem or registry.");
            done.initModality(Modality.APPLICATION_MODAL);
            done.showAndWait();
            busy.set(false);
            scan();
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Leftover Remnants Detected");
        dialog.setHeaderText("Review leftovers for: " + app.getName());
        dialog.initModality(Modality.APPLICATION_MODAL);

        try {
            dialog.getDialogPane().getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        } catch (Exception ignored) {}

        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPadding(new Insets(10));

        ObservableList<LeftoverItem> registryItems = FXCollections.observableArrayList();
        for (String key : regLeftovers) {
            registryItems.add(new LeftoverItem(key, true));
        }
        ListView<LeftoverItem> regListView = buildLeftoverListView(registryItems);
        Tab regTab = new Tab("Registry Leftovers (" + regLeftovers.size() + ")", regListView);

        ObservableList<LeftoverItem> fileItems = FXCollections.observableArrayList();
        for (String path : fileLeftovers) {
            fileItems.add(new LeftoverItem(path, false));
        }
        ListView<LeftoverItem> fileListView = buildLeftoverListView(fileItems);
        Tab fileTab = new Tab("Files & Directories (" + fileLeftovers.size() + ")", fileListView);

        tabPane.getTabs().addAll(regTab, fileTab);

        Button selectAllBtn = new Button("Select All");
        Button deselectAllBtn = new Button("Deselect All");
        HBox selectionControls = new HBox(8, selectAllBtn, deselectAllBtn);
        selectionControls.setPadding(new Insets(0, 10, 10, 10));
        selectionControls.setAlignment(Pos.CENTER_RIGHT);

        selectAllBtn.setOnAction(e -> {
            for (LeftoverItem item : registryItems) item.selectedProperty().set(true);
            for (LeftoverItem item : fileItems) item.selectedProperty().set(true);
        });

        deselectAllBtn.setOnAction(e -> {
            for (LeftoverItem item : registryItems) item.selectedProperty().set(false);
            for (LeftoverItem item : fileItems) item.selectedProperty().set(false);
        });

        VBox contentBox = new VBox(8, tabPane, selectionControls);
        contentBox.setPrefSize(640, 400);

        dialog.getDialogPane().setContent(contentBox);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        Button okBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okBtn.setText("Delete Selected");

        dialog.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                busy.set(true);
                progress.setVisible(true);
                statusLabel.setText("Deleting leftovers...");

                new Thread(() -> {
                    List<String> registryKeysToDelete = new ArrayList<>();
                    for (LeftoverItem item : registryItems) {
                        if (item.isSelected()) {
                            registryKeysToDelete.add(item.getPath());
                        }
                    }

                    List<String> filePathsToDelete = new ArrayList<>();
                    for (LeftoverItem item : fileItems) {
                        if (item.isSelected()) {
                            filePathsToDelete.add(item.getPath());
                        }
                    }

                    service.deleteRegistryLeftovers(registryKeysToDelete);

                    List<String> failedDeletions = new ArrayList<>();
                    service.deleteFilesystemLeftovers(filePathsToDelete, failedDeletions);

                    Platform.runLater(() -> {
                        busy.set(false);
                        progress.setVisible(false);
                        statusLabel.setText("Cleanup completed.");

                        if (!failedDeletions.isEmpty()) {
                            StringBuilder sb = new StringBuilder();
                            for (String path : failedDeletions) {
                                sb.append("- ").append(path).append("\n");
                            }
                            Alert failedAlert = new Alert(Alert.AlertType.INFORMATION);
                            failedAlert.setTitle("Files Scheduled for Reboot Deletion");
                            failedAlert.setHeaderText("Some files are currently locked or in use");
                            failedAlert.setContentText("The following items could not be deleted immediately. " +
                                    "They have been successfully scheduled to be deleted automatically when the system restarts:\n\n" + sb.toString());
                            failedAlert.initModality(Modality.APPLICATION_MODAL);
                            failedAlert.showAndWait();
                        } else {
                            Alert successAlert = new Alert(Alert.AlertType.INFORMATION);
                            successAlert.setTitle("Leftovers Deleted");
                            successAlert.setHeaderText("Cleanup Successful");
                            successAlert.setContentText("All selected leftovers have been successfully deleted.");
                            successAlert.initModality(Modality.APPLICATION_MODAL);
                            successAlert.showAndWait();
                        }
                        scan();
                    });
                }, "leftovers-cleanup").start();
            } else {
                busy.set(false);
                scan();
            }
        });
    }

    private ListView<LeftoverItem> buildLeftoverListView(ObservableList<LeftoverItem> items) {
        ListView<LeftoverItem> listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2; -fx-padding: 2 0 2 0;");
            }
            @Override
            protected void updateItem(LeftoverItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setGraphic(null);
                    setText(null);
                } else {
                    checkBox.setText(item.getPath());
                    checkBox.selectedProperty().unbind();
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    setGraphic(checkBox);
                }
            }
        });
        return listView;
    }

    private void triggerForceUninstallForApp(InstalledApp app) {
        if (app == null || busy.get()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Force Uninstall");
        confirm.setHeaderText("Force remove " + app.getName() + "?");
        confirm.setContentText("This will forcefully remove all traces of " + app.getName() + " without running the standard uninstaller.\n\n" +
                "This includes: killing processes, deleting files, removing registry entries, and deleting Start Menu shortcuts.\n\n" +
                "This action cannot be undone!");
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Force uninstalling " + app.getName() + "...");

        new Thread(() -> {
            UninstallerService.ForceUninstallResult result;
            try {
                result = service.forceUninstall(app);
            } catch (Exception e) {
                result = new UninstallerService.ForceUninstallResult(List.of(), List.of(app.getName() + ": " + e.getMessage()));
            }
            final UninstallerService.ForceUninstallResult finalResult = result;
            Platform.runLater(() -> {
                busy.set(false);
                progress.setVisible(false);
                showForceUninstallSummary(finalResult);
                scan();
            });
        }, "force-uninstall").start();
    }

    private void showForceUninstallSummary(UninstallerService.ForceUninstallResult result) {
        StringBuilder content = new StringBuilder();

        if (!result.summary().isEmpty()) {
            content.append("Actions completed:\n");
            for (String s : result.summary()) {
                content.append("  \u2022 ").append(s).append("\n");
            }
        }

        if (!result.errors().isEmpty()) {
            if (!content.isEmpty()) content.append("\n");
            content.append("Errors:\n");
            for (String e : result.errors()) {
                content.append("  \u2022 ").append(e).append("\n");
            }
        }

        Alert alert = new Alert(result.errors().isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("Force Uninstall Summary");
        alert.setHeaderText("Force uninstall completed.");
        alert.setContentText(content.toString());
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.getDialogPane().setPrefWidth(600);
        alert.showAndWait();
    }
}
