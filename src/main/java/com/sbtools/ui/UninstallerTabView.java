package com.sbtools.ui;

import com.sbtools.uninstaller.InstalledApp;
import com.sbtools.uninstaller.LeftoverItem;
import com.sbtools.uninstaller.UninstallerService;
import com.sbtools.util.AppIconResolver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancellationToken;
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
import java.awt.image.BufferedImage;
import java.util.Comparator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

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
    private volatile CancellationToken scanCancellationToken = new CancellationToken();

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
    private static final ExecutorService ICON_EXECUTOR = Executors.newFixedThreadPool(Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())));

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
            scan();
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
        sortedApps.comparatorProperty().bind(
                Bindings.when(table.comparatorProperty().isNotNull())
                        .then(table.comparatorProperty())
                        .otherwise(Comparator.comparing(InstalledApp::getName, String.CASE_INSENSITIVE_ORDER))
        );

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
            private final AtomicReference<Future<?>> iconTaskRef = new AtomicReference<>();
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

                // Cancel any previous icon extraction task for this cell
                Future<?> prev = iconTaskRef.getAndSet(null);
                if (prev != null && !prev.isDone()) {
                    prev.cancel(true);
                }

                if (empty || getItem() == null || getTableRow() == null || getTableRow().getItem() == null) {
                    setGraphic(null);
                } else {
                    InstalledApp app = getTableRow().getItem();
                    imageView.setImage(null);
                    setGraphic(iconPane);

                    // Extract icon off the FX thread, then set on the FX thread.
                    Future<?> f = ICON_EXECUTOR.submit(() -> {
                        try {
                            String loc = AppIconResolver.resolveAppIconPath(app);
                            if (loc != null && !loc.isBlank()) {
                                BufferedImage bimg = com.sbtools.util.IconExtractor.extractIconBuffered(loc);
                                if (bimg != null) {
                                    final int w = bimg.getWidth();
                                    final int h = bimg.getHeight();
                                    final int[] argb = bimg.getRGB(0, 0, w, h, null, 0, w);
                                    Platform.runLater(() -> {
                                        try {
                                            javafx.scene.image.WritableImage fxImg = new javafx.scene.image.WritableImage(w, h);
                                            fxImg.getPixelWriter().setPixels(0, 0, w, h,
                                                    javafx.scene.image.PixelFormat.getIntArgbInstance(), argb, 0, w);
                                            if (getTableRow() != null && getTableRow().getItem() == app) {
                                                imageView.setImage(fxImg);
                                            }
                                        } catch (Exception ex) {
                                            AppLogger.debug("Failed to convert icon to FX image: " + ex.getMessage());
                                        }
                                    });
                                }
                            }
                        } catch (Exception e) {
                            AppLogger.debug("Icon extraction failed: " + e.getMessage());
                        }
                    });
                    iconTaskRef.set(f);
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

         TableColumn<InstalledApp, Number> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> new javafx.beans.property.ReadOnlyObjectWrapper<>(c.getValue().getEstimatedSize()));
        sizeCol.setPrefWidth(100);
        sizeCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(Number item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : FormatUtils.formatSize(item.intValue()));
                setGraphic(null);
                if (!getStyleClass().contains("text-cell")) {
                    getStyleClass().add("text-cell");
                }
            }
        });

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
            uninstallItem.disableProperty().bind(busy);
            uninstallItem.setOnAction(e -> uninstallSingleApp(row.getItem()));

            MenuItem forceUninstallItem = new MenuItem("Force Uninstall");
            forceUninstallItem.disableProperty().bind(busy);
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

        // Cancel any previous scan
        scanCancellationToken.cancel();
        CancellationToken ct = new CancellationToken();
        scanCancellationToken = ct;

        new Thread(() -> {
            try {
                List<InstalledApp> apps;
                if (scanWin32) {
                    apps = service.listWin32Apps();
                } else {
                    apps = service.listAppxApps();
                }

                if (ct.isCancelled()) return;

                Platform.runLater(() -> {
                    allApps.setAll(apps);
                    applyFilter();
                    statusLabel.setText("Found " + apps.size() + " app(s).");
                });
            } catch (Exception e) {
                if (ct.isCancelled()) return;
                AppLogger.error("Failed to scan apps", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Scan failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to scan installed apps:\n" + e.getMessage()).showAndWait();
                });
            } finally {
                if (!ct.isCancelled()) {
                    Platform.runLater(() -> {
                        busy.set(false);
                        progress.setVisible(false);
                    });
                }
            }
        }, "uninstaller-scan").start();
    }

    private void updateButtonStates() {
        boolean hasSelection = table.getSelectionModel().getSelectedItem() != null;
        boolean isBusy = busy.get();
        InstalledApp selected = table.getSelectionModel().getSelectedItem();
        boolean canUninstall = selected != null && selected.hasUninstallString();
        uninstallButton.setDisable(!hasSelection || isBusy || !canUninstall);
        forceUninstallButton.setDisable(!hasSelection || isBusy);
    }

    private void uninstallSingleApp(InstalledApp selected) {
        if (selected == null || busy.get()) return;

        if (!adminCheck.getAsBoolean()) {
            Alert adminWarn = new Alert(Alert.AlertType.WARNING);
            adminWarn.setTitle("Administrator Privileges Required");
            adminWarn.setHeaderText("Not running as administrator");
            adminWarn.setContentText("Some uninstall operations may fail without administrator privileges.\n\n" +
                    "Consider restarting the application as administrator.\n\nContinue anyway?");
            adminWarn.initModality(Modality.APPLICATION_MODAL);
            if (adminWarn.showAndWait().orElse(null) != ButtonType.OK) return;
        }

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

            ButtonType yesBtn = new ButtonType("Yes");
            ButtonType noBtn = new ButtonType("No, continue without");
            restorePointDialog.getButtonTypes().setAll(yesBtn, noBtn);

            ButtonType result = restorePointDialog.showAndWait().orElse(null);
            if (result == yesBtn) {
                runUninstallWithRestorePoint(selected);
            } else if (result == noBtn) {
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
                ProcessResult result = new com.sbtools.util.ProcessRunner(300).run(
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
                ProcessResult result = service.runUninstallerAndWait(app, 600);
                AppLogger.info("Uninstaller completed with exit code: " + result.exitCode());
                boolean uninstallSucceeded = result.success();

                // Brief pause for file system to settle after process exits
                Thread.sleep(1000);

                if (!uninstallSucceeded) {
                    AtomicReference<ButtonType> userChoice = new AtomicReference<>();
                    Platform.runLater(() -> {
                        progress.setVisible(false);
                        busy.set(false);
                        Alert warn = new Alert(Alert.AlertType.WARNING);
                        warn.setTitle("Uninstall Failed");
                        warn.setHeaderText("The uninstaller returned an error for: " + app.getName());
                        warn.setContentText("The standard uninstaller reported errors:\n\n"
                                + result.combinedOutput() + "\n\n"
                                + "Would you like to scan for leftovers anyway?");
                        warn.initModality(Modality.APPLICATION_MODAL);

                        ButtonType scanBtn = new ButtonType("Scan Anyway");
                        ButtonType skipBtn = new ButtonType("Skip");
                        warn.getButtonTypes().setAll(scanBtn, skipBtn);

                        userChoice.set(warn.showAndWait().orElse(skipBtn));
                    });
                    // Wait for the dialog to close (FX thread handles it), with timeout
                    long dialogDeadline = System.currentTimeMillis() + 60_000;
                    while (userChoice.get() == null && System.currentTimeMillis() < dialogDeadline) {
                        Thread.sleep(50);
                    }
                    if (userChoice.get() != null && userChoice.get().getText().equals("Scan Anyway")) {
                        scanAndShowLeftovers(app);
                    } else {
                        Platform.runLater(() -> statusLabel.setText("Uninstallation cancelled."));
                    }
                    return;
                }

                scanAndShowLeftovers(app);

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

    private void scanAndShowLeftovers(InstalledApp app) {
        Platform.runLater(() -> statusLabel.setText("Scanning leftovers for " + app.getName() + "..."));
        List<String> fileLeftovers = service.scanFilesystemLeftovers(app);
        List<String> regLeftovers = service.scanRegistryLeftovers(app);

        Platform.runLater(() -> {
            progress.setVisible(false);
            statusLabel.setText("Scanning completed.");
            showLeftoversReview(app, fileLeftovers, regLeftovers);
        });
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

        java.util.Optional<ButtonType> dialogResult = dialog.showAndWait();
        if (dialogResult.isEmpty() || dialogResult.get() != ButtonType.OK) {
            busy.set(false);
            scan();
            return;
        }

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

                    List<String> failedDeletions = new ArrayList<>();
                    service.deleteRegistryLeftovers(registryKeysToDelete, failedDeletions);
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
                            Alert failedAlert = new Alert(Alert.AlertType.WARNING);
                            failedAlert.setTitle("Partial Cleanup");
                            failedAlert.setHeaderText("Some items could not be deleted");
                            failedAlert.setContentText("The following items could not be deleted immediately " +
                                    "(e.g. locked files or permission-denied registry keys). " +
                                    "Files have been scheduled for deletion on next reboot where possible:\n\n" + sb.toString());
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
    }

    private ListView<LeftoverItem> buildLeftoverListView(ObservableList<LeftoverItem> items) {
        ListView<LeftoverItem> listView = new ListView<>(items);
        listView.setCellFactory(lv -> new ListCell<>() {
            private final CheckBox checkBox = new CheckBox();
            private LeftoverItem currentItem;
            {
                checkBox.setStyle("-fx-text-fill: #f8f8f2; -fx-padding: 2 0 2 0;");
            }
            @Override
            protected void updateItem(LeftoverItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    if (currentItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(currentItem.selectedProperty());
                        currentItem = null;
                    }
                    setGraphic(null);
                    setText(null);
                } else {
                    if (currentItem != null) {
                        checkBox.selectedProperty().unbindBidirectional(currentItem.selectedProperty());
                    }
                    currentItem = item;
                    checkBox.setText(item.getPath());
                    checkBox.selectedProperty().bindBidirectional(item.selectedProperty());
                    setGraphic(checkBox);
                }
            }
        });
        return listView;
    }

    private void triggerForceUninstallForApp(InstalledApp app) {
        if (app == null || busy.get()) return;

        if (!adminCheck.getAsBoolean()) {
            Alert adminWarn = new Alert(Alert.AlertType.WARNING);
            adminWarn.setTitle("Administrator Privileges Required");
            adminWarn.setHeaderText("Not running as administrator");
            adminWarn.setContentText("Force uninstall requires administrator privileges to kill processes and delete files.\n\n" +
                    "Consider restarting the application as administrator.\n\nContinue anyway?");
            adminWarn.initModality(Modality.APPLICATION_MODAL);
            if (adminWarn.showAndWait().orElse(null) != ButtonType.OK) return;
        }

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

    public void dispose() {
        ICON_EXECUTOR.shutdownNow();
    }
}
