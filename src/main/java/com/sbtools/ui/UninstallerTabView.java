package com.sbtools.ui;

import com.sbtools.uninstaller.InstalledApp;
import com.sbtools.uninstaller.NativeFileHelper;
import com.sbtools.uninstaller.UninstallerService;
import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.AppPaths;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinReg.HKEY;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;

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
    private final Button uninstallButton = new Button("Uninstall Selected");
    private final Button forceUninstallButton = new Button("Force Uninstall");
    private final TextField searchField = new TextField();

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final ToggleButton win32Toggle = new ToggleButton("Desktop Apps");
    private final ToggleButton appxToggle = new ToggleButton("Windows Store Apps");

    private final TableView<InstalledApp> table = new TableView<>(sortedApps);

    private final ChangeListener<Boolean> checkboxListener = (obs, oldVal, newVal) -> updateButtonStates();

    public UninstallerTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        // Scan button
        scanButton.setOnAction(e -> scan());

        // Uninstall button
        uninstallButton.setOnAction(e -> triggerUninstall());
        uninstallButton.setDisable(true);
        uninstallButton.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white;");

        // Force Uninstall button
        forceUninstallButton.setOnAction(e -> triggerForceUninstall());
        forceUninstallButton.setDisable(true);
        forceUninstallButton.getStyleClass().add("danger");

        // Search field
        searchField.setPromptText("Search apps...");
        searchField.setPrefWidth(220);
        searchField.textProperty().addListener((obs, oldVal, newVal) -> applyFilter());

        // Mode toggles
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

        // Top toolbar
        HBox top = new HBox(12,
                win32Toggle, appxToggle,
                new Separator(javafx.geometry.Orientation.VERTICAL),
                searchField, scanButton, uninstallButton, forceUninstallButton,
                progress, statusLabel
        );
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        // Build TableView
        buildTable();
        sortedApps.comparatorProperty().bind(table.comparatorProperty());

        setTop(top);
        setCenter(table);

        // Listen for checkbox state changes via list modifications
        allApps.addListener((ListChangeListener<InstalledApp>) change -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    for (InstalledApp app : change.getRemoved()) {
                        app.selectedProperty().removeListener(checkboxListener);
                    }
                }
                if (change.wasAdded()) {
                    for (InstalledApp app : change.getAddedSubList()) {
                        app.selectedProperty().addListener(checkboxListener);
                    }
                }
            }
            updateButtonStates();
        });

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

        // Checkbox column
        TableColumn<InstalledApp, InstalledApp> checkCol = new TableColumn<>(" ");
        checkCol.setPrefWidth(40);
        checkCol.setMinWidth(40);
        checkCol.setMaxWidth(40);
        checkCol.setResizable(false);
        checkCol.setSortable(false);
        checkCol.setCellValueFactory(c -> new javafx.beans.property.SimpleObjectProperty<>(c.getValue()));
        checkCol.setCellFactory(col -> new TableCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private InstalledApp previousItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2;");
            }
            @Override
            protected void updateItem(InstalledApp item, boolean empty) {
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
                    String loc = resolveAppIconPath(app);
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
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(formatSize(c.getValue().getEstimatedSize())));
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
        dateCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(formatDate(c.getValue().getInstallDate())));
        dateCol.setPrefWidth(140);
        dateCol.setCellFactory(textCellFactory);

        TableColumn<InstalledApp, String> publisherCol = new TableColumn<>("Company");
        publisherCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().getPublisher()));
        publisherCol.setPrefWidth(200);
        publisherCol.setCellFactory(textCellFactory);

        table.getColumns().addAll(checkCol, iconCol, nameCol, sizeCol, versionCol, typeCol, dateCol, publisherCol);

        table.setRowFactory(tv -> {
            TableRow<InstalledApp> row = new TableRow<>();
            row.setMinHeight(28);
            row.setPrefHeight(28);
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    uninstallSingleApp(row.getItem());
                }
            });
            return row;
        });
    }

    private static String resolveAppIconPath(InstalledApp app) {
        String loc = app.getInstallLocation();
        if (loc != null && !loc.isBlank()) {
            File file = new File(loc);
            if (file.isDirectory()) {
                String exe = findFirstExe(file);
                if (exe != null) return exe;
            } else if (file.isFile() && loc.toLowerCase().endsWith(".exe")) {
                return loc;
            }
        }
        if (app.isWin32()) {
            String uninstallStr = app.getUninstallString();
            if (uninstallStr != null && !uninstallStr.isBlank()) {
                String exePath = extractExeFromUninstallString(uninstallStr);
                if (exePath != null) return exePath;
            }
        }
        return null;
    }

    private static String findFirstExe(File dir) {
        File[] topExes = dir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".exe"));
        if (topExes != null && topExes.length > 0) {
            return topExes[0].getAbsolutePath();
        }
        File[] subDirs = dir.listFiles(File::isDirectory);
        if (subDirs != null) {
            for (File subDir : subDirs) {
                File[] subExes = subDir.listFiles(f -> f.isFile() && f.getName().toLowerCase().endsWith(".exe"));
                if (subExes != null && subExes.length > 0) {
                    return subExes[0].getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String extractExeFromUninstallString(String uninstallStr) {
        String path = uninstallStr.trim();
        if (path.startsWith("\"")) {
            int end = path.indexOf("\"", 1);
            if (end > 1) {
                path = path.substring(1, end);
            }
        } else {
            int space = path.indexOf(' ');
            if (space > 0) {
                path = path.substring(0, space);
            }
        }
        if (path.toLowerCase().endsWith(".exe")) {
            File f = new File(path);
            if (f.isFile()) return f.getAbsolutePath();
        }
        return null;
    }

    private String formatSize(int sizeKB) {
        if (sizeKB <= 0) return "";
        if (sizeKB >= 1024 * 1024) {
            return String.format("%.2f GB", sizeKB / (1024.0 * 1024.0));
        } else if (sizeKB >= 1024) {
            return String.format("%.2f MB", sizeKB / 1024.0);
        } else {
            return sizeKB + " KB";
        }
    }

    private String formatDate(String dateStr) {
        if (dateStr == null || dateStr.length() < 8) return "";
        try {
            String year = dateStr.substring(0, 4);
            String month = dateStr.substring(4, 6);
            String day = dateStr.substring(6, 8);
            String time = dateStr.length() >= 14
                    ? " " + dateStr.substring(8, 10) + ":" + dateStr.substring(10, 12) + ":" + dateStr.substring(12, 14)
                    : "";
            return year + "-" + month + "-" + day + time;
        } catch (Exception e) {
            return dateStr;
        }
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
        boolean hasSelection = allApps.stream().anyMatch(InstalledApp::isSelected);
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

    private void triggerUninstall() {
        List<InstalledApp> checked = allApps.stream()
                .filter(InstalledApp::isSelected).collect(Collectors.toList());
        if (checked.isEmpty() || busy.get()) return;

        if (checked.size() == 1) {
            uninstallSingleApp(checked.get(0));
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Bulk Uninstall");
        confirm.setHeaderText("Uninstall " + checked.size() + " applications?");
        StringBuilder sb = new StringBuilder();
        for (InstalledApp app : checked) {
            sb.append("- ").append(app.getName()).append("\n");
        }
        confirm.setContentText("The following applications will be uninstalled:\n\n" + sb.toString());
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        Alert restorePointDialog = new Alert(Alert.AlertType.CONFIRMATION);
        restorePointDialog.setTitle("System Restore Point");
        restorePointDialog.setHeaderText("Create a restore point?");
        restorePointDialog.setContentText("Would you like to create a System Restore Point before uninstalling?");
        restorePointDialog.initModality(Modality.APPLICATION_MODAL);

        boolean createRp = restorePointDialog.showAndWait().orElse(null) == ButtonType.OK;
        runBulkUninstall(checked, createRp);
    }

    private void runBulkUninstall(List<InstalledApp> apps, boolean createRestorePoint) {
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Starting bulk uninstall...");

        new Thread(() -> {
            try {
                if (createRestorePoint) {
                    new com.sbtools.util.ProcessRunner(120).run(
                            List.of("powershell.exe", "-Command",
                                    "Checkpoint-Computer -Description 'Before bulk uninstall' -RestorePointType MODIFY_SETTINGS"));
                }

                for (int i = 0; i < apps.size(); i++) {
                    InstalledApp app = apps.get(i);
                    final int idx = i;
                    final int total = apps.size();

                    Platform.runLater(() -> {
                        statusLabel.setText("Uninstalling (" + (idx + 1) + "/" + total + "): " + app.getName());
                    });

                    try {
                        AppLogger.info("Starting uninstaller for: " + app.getName());
                        ProcessResult result = service.runUninstaller(app);
                        AppLogger.info("Uninstaller completed with exit code: " + result.exitCode());
                    } catch (Exception e) {
                        AppLogger.error("Failed to uninstall " + app.getName(), e);
                    }
                }

                Platform.runLater(() -> {
                    statusLabel.setText("Bulk uninstall completed.");
                    new Alert(Alert.AlertType.INFORMATION, "Bulk uninstall completed for " + apps.size() + " application(s).").showAndWait();
                });
            } catch (Exception e) {
                AppLogger.error("Bulk uninstall failed", e);
                Platform.runLater(() -> {
                    new Alert(Alert.AlertType.ERROR, "Bulk uninstall failed:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    progress.setVisible(false);
                    scan();
                });
            }
        }, "bulk-uninstall").start();
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

    private void triggerForceUninstall() {
        List<InstalledApp> selected = allApps.stream()
                .filter(InstalledApp::isSelected).collect(Collectors.toList());
        if (selected.isEmpty() || busy.get()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Force Uninstall");
        confirm.setHeaderText("Force remove " + selected.size() + " app(s)?");
        StringBuilder sb = new StringBuilder();
        for (InstalledApp app : selected) {
            sb.append("- ").append(app.getName()).append("\n");
        }
        confirm.setContentText("This will forcefully remove all traces of the selected applications without running the standard uninstaller.\n\n" +
                "This includes: killing processes, deleting files, removing registry entries, and deleting Start Menu shortcuts.\n\n" +
                "This action cannot be undone!\n\n" + sb.toString());
        confirm.initModality(Modality.APPLICATION_MODAL);
        if (confirm.showAndWait().orElse(null) != ButtonType.OK) return;

        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Force uninstalling...");

        new Thread(() -> {
            List<String> summary = new ArrayList<>();
            List<String> errors = new ArrayList<>();

            for (InstalledApp app : selected) {
                try {
                    forceUninstallApp(app, summary, errors);
                } catch (Exception e) {
                    errors.add(app.getName() + ": " + e.getMessage());
                }
            }

            Platform.runLater(() -> {
                busy.set(false);
                progress.setVisible(false);
                showForceUninstallSummary(summary, errors);
                scan();
            });
        }, "force-uninstall").start();
    }

    private void forceUninstallApp(InstalledApp app, List<String> summary, List<String> errors) {
        String appName = app.getName();
        String lowerAppName = appName.toLowerCase();

        // a. Kill all processes matching app name
        try {
            Process taskKill = new ProcessBuilder("taskkill", "/f", "/im", appName + ".exe")
                    .redirectErrorStream(true)
                    .start();
            taskKill.waitFor(5, TimeUnit.SECONDS);
            summary.add("Attempted to kill processes for: " + appName);
        } catch (Exception e) {
            errors.add("Failed to kill processes for " + appName + ": " + e.getMessage());
        }

        // b. Delete the install directory
        String installLoc = app.getInstallLocation();
        if (installLoc != null && !installLoc.isBlank()) {
            File dir = new File(installLoc);
            if (dir.exists()) {
                if (NativeFileHelper.deleteOrQueue(dir)) {
                    summary.add("Deleted directory: " + installLoc);
                } else {
                    errors.add("Failed to delete directory: " + installLoc);
                }
            }
        }

        // c. If installLocation is empty, search common directories
        if (installLoc == null || installLoc.isBlank()) {
            List<String> roots = new ArrayList<>();
            addIfNotNull(roots, System.getenv("ProgramFiles"));
            addIfNotNull(roots, System.getenv("ProgramFiles(x86)"));
            addIfNotNull(roots, System.getenv("AppData"));
            addIfNotNull(roots, System.getenv("LocalAppData"));

            for (String root : roots) {
                File rootDir = new File(root);
                if (!rootDir.exists() || !rootDir.isDirectory()) continue;
                File[] children = rootDir.listFiles(File::isDirectory);
                if (children == null) continue;
                for (File child : children) {
                    if (child.getName().toLowerCase().contains(lowerAppName)) {
                        if (NativeFileHelper.deleteOrQueue(child)) {
                            summary.add("Deleted directory: " + child.getAbsolutePath());
                        } else {
                            errors.add("Failed to delete: " + child.getAbsolutePath());
                        }
                    }
                }
            }
        }

        // d. Delete the registry key at the app's registryKeyPath
        if (app.isWin32() && !app.getRegistryKeyPath().isEmpty()) {
            HKEY hive = "HKLM".equalsIgnoreCase(app.getRegistryHive())
                    ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;
            try {
                if (Advapi32Util.registryKeyExists(hive, app.getRegistryKeyPath())) {
                    Advapi32Util.registryDeleteKey(hive, app.getRegistryKeyPath());
                    summary.add("Deleted registry key: " + app.getRegistryHive() + "\\" + app.getRegistryKeyPath());
                }
            } catch (Exception e) {
                errors.add("Failed to delete registry key for " + appName + ": " + e.getMessage());
            }
        }

        // e. Search and delete registry keys under Uninstall paths that contain the app name
        String[][] uninstallPaths = {
                {"HKLM", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKLM", "SOFTWARE\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall"},
                {"HKCU", "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Uninstall"}
        };

        for (String[] pathInfo : uninstallPaths) {
            String hiveLabel = pathInfo[0];
            String keyPath = pathInfo[1];
            HKEY hive = "HKLM".equals(hiveLabel) ? WinReg.HKEY_LOCAL_MACHINE : WinReg.HKEY_CURRENT_USER;

            try {
                if (!Advapi32Util.registryKeyExists(hive, keyPath)) continue;
                String[] subkeys = Advapi32Util.registryGetKeys(hive, keyPath);
                if (subkeys == null) continue;

                for (String subkey : subkeys) {
                    if (subkey.toLowerCase().contains(lowerAppName)) {
                        String fullSubKey = keyPath + "\\" + subkey;
                        String formatted = hiveLabel + "\\" + fullSubKey;
                        try {
                            Advapi32Util.registryDeleteKey(hive, fullSubKey);
                            summary.add("Deleted registry key: " + formatted);
                        } catch (Exception ex) {
                            // Key may have subkeys; try recursive deletion
                            deleteRegistryKeyRecursively(hive, fullSubKey, summary, errors);
                        }
                    }
                }
            } catch (Exception e) {
                errors.add("Failed to scan registry Uninstall path " + hiveLabel + "\\" + keyPath + ": " + e.getMessage());
            }
        }

        // f. Delete Start Menu shortcuts
        String[] startMenuRoots = {
                System.getenv("ProgramData") + "\\Microsoft\\Windows\\Start Menu",
                System.getenv("AppData") + "\\Microsoft\\Windows\\Start Menu"
        };

        for (String root : startMenuRoots) {
            if (root == null || root.isBlank()) continue;
            File startMenuDir = new File(root);
            if (startMenuDir.exists() && startMenuDir.isDirectory()) {
                deleteMatchingFiles(startMenuDir, lowerAppName, summary, errors);
            }
        }
    }

    private void deleteMatchingFiles(File dir, String lowerName, List<String> summary, List<String> errors) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.getName().toLowerCase().contains(lowerName)) {
                if (NativeFileHelper.deleteOrQueue(file)) {
                    summary.add("Deleted: " + file.getAbsolutePath());
                } else {
                    errors.add("Failed to delete: " + file.getAbsolutePath());
                }
            } else if (file.isDirectory()) {
                deleteMatchingFiles(file, lowerName, summary, errors);
            }
        }
    }

    private void deleteRegistryKeyRecursively(HKEY hive, String keyPath, List<String> summary, List<String> errors) {
        try {
            if (!Advapi32Util.registryKeyExists(hive, keyPath)) return;
            String[] subkeys = Advapi32Util.registryGetKeys(hive, keyPath);
            if (subkeys != null) {
                for (String subkey : subkeys) {
                    deleteRegistryKeyRecursively(hive, keyPath + "\\" + subkey, summary, errors);
                }
            }
            Advapi32Util.registryDeleteKey(hive, keyPath);
            summary.add("Deleted registry key: " + keyPath);
        } catch (Exception e) {
            errors.add("Failed to delete registry key: " + keyPath + " - " + e.getMessage());
        }
    }

    private void showForceUninstallSummary(List<String> summary, List<String> errors) {
        String title = "Force Uninstall Summary";
        StringBuilder content = new StringBuilder();

        if (!summary.isEmpty()) {
            content.append("Actions completed:\n");
            for (String s : summary) {
                content.append("  \u2022 ").append(s).append("\n");
            }
        }

        if (!errors.isEmpty()) {
            if (!content.isEmpty()) content.append("\n");
            content.append("Errors:\n");
            for (String e : errors) {
                content.append("  \u2022 ").append(e).append("\n");
            }
        }

        Alert alert = new Alert(errors.isEmpty() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText("Force uninstall completed.");
        alert.setContentText(content.toString());
        alert.initModality(Modality.APPLICATION_MODAL);
        alert.getDialogPane().setPrefWidth(600);
        alert.showAndWait();
    }

    private void addIfNotNull(List<String> list, String value) {
        if (value != null && !value.isBlank()) {
            list.add(value);
        }
    }

    public static class LeftoverItem {
        private final BooleanProperty selected = new SimpleBooleanProperty(true);
        private final String path;
        private final boolean registry;

        public LeftoverItem(String path, boolean registry) {
            this.path = path;
            this.registry = registry;
        }

        public BooleanProperty selectedProperty() {
            return selected;
        }

        public boolean isSelected() {
            return selected.get();
        }

        public String getPath() {
            return path;
        }

        public boolean isRegistry() {
            return registry;
        }
    }
}
