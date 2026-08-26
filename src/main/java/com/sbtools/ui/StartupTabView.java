package com.sbtools.ui;

import com.sbtools.startup.StartupItem;
import com.sbtools.startup.StartupItemType;
import com.sbtools.startup.StartupImpactService;
import com.sbtools.startup.StartupService;
import com.sbtools.startup.StartupService.StartupBackupEntry;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
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
import java.util.ArrayList;
import java.util.stream.Collectors;
import javafx.beans.binding.Bindings;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import java.awt.Desktop;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class StartupTabView extends BorderPane {

    private static final String TAB_REGISTRY = "Startup apps";
    private static final String TAB_TASKS = "Scheduled tasks";
    private static final String TAB_SERVICES = "Windows services";

    private final StartupService service = new StartupService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "startup-worker");
        t.setDaemon(true);
        return t;
    });

    private final ObservableList<StartupItem> registryItems = FXCollections.observableArrayList();
    private final ObservableList<StartupItem> taskItems = FXCollections.observableArrayList();
    private final ObservableList<StartupItem> serviceItems = FXCollections.observableArrayList();

    private final FilteredList<StartupItem> filteredRegistry = new FilteredList<>(registryItems);
    private final FilteredList<StartupItem> filteredTasks = new FilteredList<>(taskItems);
    private final FilteredList<StartupItem> filteredServices = new FilteredList<>(serviceItems);

    private final SortedList<StartupItem> sortedRegistry = new SortedList<>(filteredRegistry);
    private final SortedList<StartupItem> sortedTasks = new SortedList<>(filteredTasks);
    private final SortedList<StartupItem> sortedServices = new SortedList<>(filteredServices);

    private final Label statusLabel = new Label("Scan system to list startup items.");
    private final Label bootDelayLabel = new Label("");
    private final ProgressIndicator progress = new ProgressIndicator();

    private final Button scanButton = new Button("Scan");
    private final Button toggleButton = new Button("Enable/Disable");
    private final Button deleteButton = new Button("Delete");
    private final Button backupsButton = new Button("Backups & Restore");

    private final TextField registrySearch = new TextField();
    private final TextField taskSearch = new TextField();
    private final TextField serviceSearch = new TextField();

    private final TableView<StartupItem> registryTable = new TableView<>(sortedRegistry);
    private final TableView<StartupItem> taskTable = new TableView<>(sortedTasks);
    private final TableView<StartupItem> serviceTable = new TableView<>(sortedServices);

    private final TabPane tabPane = new TabPane();

    public StartupTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progress.setVisible(false);
        progress.setMaxSize(24, 24);

        scanButton.setOnAction(e -> scan());
        toggleButton.setOnAction(e -> triggerToggle());
        toggleButton.setDisable(true);
        toggleButton.getStyleClass().add("button-outlined");
        deleteButton.setOnAction(e -> triggerDelete());
        deleteButton.setDisable(true);
        deleteButton.getStyleClass().add("danger");
        backupsButton.setOnAction(e -> showBackupsDialog());
        backupsButton.getStyleClass().add("button-outlined");

        registrySearch.setPromptText("Search startup apps...");
        registrySearch.setPrefWidth(200);
        registrySearch.textProperty().addListener((obs, oldVal, newVal) -> applyRegistryFilter());

        taskSearch.setPromptText("Search scheduled tasks...");
        taskSearch.setPrefWidth(200);
        taskSearch.textProperty().addListener((obs, oldVal, newVal) -> applyTaskFilter());

        serviceSearch.setPromptText("Search services...");
        serviceSearch.setPrefWidth(200);
        serviceSearch.textProperty().addListener((obs, oldVal, newVal) -> applyServiceFilter());

        buildTable(registryTable, "Startup Item Name", "Publisher", "Location", "Command / Execution Path");
        buildTable(taskTable, "Task Name", "Publisher", "Location", "Actions / Command");
        buildTable(serviceTable, "Service Name", "Display Name", "Start Type", "Binary Path");

        // Allow multi-selection for bulk operations
        registryTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        taskTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        serviceTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);

        sortedRegistry.comparatorProperty().bind(registryTable.comparatorProperty());
        sortedTasks.comparatorProperty().bind(taskTable.comparatorProperty());
        sortedServices.comparatorProperty().bind(serviceTable.comparatorProperty());

        Tab registryTab = createTab(TAB_REGISTRY, registryTable, registrySearch);
        Tab taskTab = createTab(TAB_TASKS, taskTable, taskSearch);
        Tab serviceTab = createTab(TAB_SERVICES, serviceTable, serviceSearch);

        tabPane.getTabs().addAll(registryTab, taskTab, serviceTab);
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            updateButtonStates();
        });

        HBox top = new HBox(12,
                scanButton, toggleButton, deleteButton, backupsButton,
                new Separator(Orientation.VERTICAL),
                progress, statusLabel, bootDelayLabel
        );
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        setTop(top);
        setCenter(tabPane);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            boolean hasSelection = !getSelectedTable().getSelectionModel().getSelectedItems().isEmpty();
            toggleButton.setDisable(newVal || !hasSelection);
            deleteButton.setDisable(newVal || !hasSelection);
            backupsButton.setDisable(newVal);
            registrySearch.setDisable(newVal);
            taskSearch.setDisable(newVal);
            serviceSearch.setDisable(newVal);
            tabPane.setDisable(newVal);
        });

        if (!AppPaths.isWindows()) {
            scanButton.setDisable(true);
            statusLabel.setText("Startup manager is only available on Windows.");
        }

        setOnKeyPressed(event -> {
            if (event.isControlDown()) {
                switch (event.getCode()) {
                    case R -> scan();
                    case E -> triggerToggle();
                    case B -> showBackupsDialog();
                }
            } else if (event.getCode() == javafx.scene.input.KeyCode.DELETE) {
                triggerDelete();
            }
        });
        setFocusTraversable(true);
    }

    private Tab createTab(String title, TableView<StartupItem> table, TextField searchField) {
        Tab tab = new Tab(title);
        HBox searchBar = new HBox(8, searchField);
        searchBar.setAlignment(Pos.CENTER_RIGHT);
        searchBar.setPadding(new Insets(0, 8, 0, 0));

        VBox content = new VBox(0, searchBar, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        tab.setContent(content);

        table.getSelectionModel().selectedItemProperty().addListener((obs, oldSel, newSel) -> {
            updateButtonStates();
        });

        return tab;
    }

    private void buildTable(TableView<StartupItem> table, String nameHeader, String publisherHeader,
                            String locationHeader, String pathHeader) {
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<StartupItem, String> nameCol = new TableColumn<>(nameHeader);
        nameCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getName()));
        nameCol.setPrefWidth(220);

        TableColumn<StartupItem, String> publisherCol = new TableColumn<>(publisherHeader);
        publisherCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getPublisher()));
        publisherCol.setPrefWidth(180);

        TableColumn<StartupItem, String> locationCol = new TableColumn<>(locationHeader);
        locationCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getLocation()));
        locationCol.setPrefWidth(160);

        TableColumn<StartupItem, String> pathCol = new TableColumn<>(pathHeader);
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

        TableColumn<StartupItem, String> impactCol = new TableColumn<>("Boot Impact");
        impactCol.setCellValueFactory(c -> {
            double ms = c.getValue().getEstimatedBootImpactMs();
            String label;
            if (ms < 100) {
                label = "Low";
            } else if (ms <= 300) {
                label = "Medium";
            } else {
                label = "High";
            }
            return new SimpleStringProperty(label);
        });
        impactCol.setPrefWidth(100);
        impactCol.setComparator((a, b) -> {
            double order = switch (a) {
                case "High" -> 3;
                case "Medium" -> 2;
                default -> 1;
            };
            double orderB = switch (b) {
                case "High" -> 3;
                case "Medium" -> 2;
                default -> 1;
            };
            return Double.compare(order, orderB);
        });
        impactCol.setCellFactory(col -> new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setStyle("");
                } else {
                    setText(item);
                    StartupItem rowItem = getTableRow() != null ? getTableRow().getItem() : null;
                    if (rowItem == null) {
                        // Fallback safe guard for sorted list index
                        int idx = getIndex();
                        if (idx >= 0 && idx < getTableView().getItems().size()) {
                            rowItem = getTableView().getItems().get(idx);
                        }
                    }
                    if (rowItem != null) {
                        double ms = rowItem.getEstimatedBootImpactMs();
                        if (ms < 100) {
                            setStyle("-fx-text-fill: #50fa7b; -fx-font-weight: bold;");
                        } else if (ms <= 300) {
                            setStyle("-fx-text-fill: #f1fa8c; -fx-font-weight: bold;");
                        } else {
                            setStyle("-fx-text-fill: #ff5555; -fx-font-weight: bold;");
                        }
                    } else {
                        setStyle("");
                    }
                }
            }
        });
        // Default sort: High impact first (descending)
        impactCol.setSortType(TableColumn.SortType.DESCENDING);

        table.getColumns().addAll(nameCol, publisherCol, locationCol, pathCol, statusCol, impactCol);

        table.setRowFactory(tv -> {
            TableRow<StartupItem> row = new TableRow<>();

            // Context menu for quick actions
            ContextMenu ctx = new ContextMenu();
            MenuItem openLoc = new MenuItem("Open file location");
            MenuItem copyCmd = new MenuItem("Copy command");
            MenuItem details = new MenuItem("Show details");
            MenuItem searchOnline = new MenuItem("Search online");
            ctx.getItems().addAll(openLoc, copyCmd, details, searchOnline);

            openLoc.setOnAction(evt -> {
                StartupItem it = row.getItem();
                if (it == null) return;
                try {
                    // For startup folder items, open the actual file location
                    String loc = it.getLocation();
                    if (loc != null && loc.startsWith("Startup Folder") && it.getFilePath() != null && !it.getFilePath().isBlank()) {
                        java.io.File f = new java.io.File(it.getFilePath());
                        java.io.File disabled = new java.io.File(it.getFilePath() + ".disabled");
                        java.io.File target = f.exists() ? f : (disabled.exists() ? disabled : f);
                        if (target.exists()) {
                            new ProcessBuilder("explorer.exe", "/select," + target.getAbsolutePath()).start();
                        } else if (target.getParentFile() != null && target.getParentFile().exists()) {
                            new ProcessBuilder("explorer.exe", target.getParentFile().getAbsolutePath()).start();
                        } else {
                            new Alert(Alert.AlertType.INFORMATION, "File not found: " + target.getAbsolutePath()).showAndWait();
                        }
                        return;
                    }
                    String exe = com.sbtools.startup.StartupService.extractExecutablePath(it.getPath());
                    if (exe == null || exe.isBlank()) exe = it.getPath();
                    exe = com.sbtools.startup.StartupService.expandEnvVars(exe);
                    java.io.File f = new java.io.File(exe);
                    if (f.exists()) {
                        new ProcessBuilder("explorer.exe", "/select," + f.getAbsolutePath()).start();
                    } else {
                        // Try parent dir if file not found (e.g., quoted args)
                        java.io.File parent = f.getParentFile();
                        if (parent != null && parent.exists()) {
                            new ProcessBuilder("explorer.exe", parent.getAbsolutePath()).start();
                        } else {
                            new Alert(Alert.AlertType.INFORMATION, "File not found: " + exe).showAndWait();
                        }
                    }
                } catch (Exception ex) {
                    AppLogger.error("Failed to open file location", ex);
                    new Alert(Alert.AlertType.ERROR, "Failed to open file location:\n" + ex.getMessage()).showAndWait();
                }
            });

            copyCmd.setOnAction(evt -> {
                StartupItem it = row.getItem();
                if (it == null) return;
                Clipboard cb = Clipboard.getSystemClipboard();
                ClipboardContent content = new ClipboardContent();
                content.putString(it.getPath());
                cb.setContent(content);
            });

            details.setOnAction(evt -> showDetailsDialog(row.getItem()));

            searchOnline.setOnAction(evt -> {
                StartupItem it = row.getItem();
                if (it == null) return;
                try {
                    String q = URLEncoder.encode(it.getName() + " " + it.getPublisher(), StandardCharsets.UTF_8.toString());
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(new URI("https://www.google.com/search?q=" + q));
                    }
                } catch (Exception ex) {
                    AppLogger.error("Failed to open browser", ex);
                }
            });

            row.contextMenuProperty().bind(Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(ctx));

            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && (!row.isEmpty())) {
                    triggerToggle();
                }
            });
            return row;
        });
    }

    private TableView<StartupItem> getSelectedTable() {
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab == null) return registryTable;
        String title = selectedTab.getText();
        return switch (title) {
            case TAB_REGISTRY -> registryTable;
            case TAB_TASKS -> taskTable;
            case TAB_SERVICES -> serviceTable;
            default -> registryTable;
        };
    }

    private void updateButtonStates() {
        TableView<StartupItem> table = getSelectedTable();
        boolean hasSelection = !table.getSelectionModel().getSelectedItems().isEmpty();
        toggleButton.setDisable(!hasSelection || busy.get());
        deleteButton.setDisable(!hasSelection || busy.get());

        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab != null && TAB_SERVICES.equals(selectedTab.getText())) {
            deleteButton.setDisable(true);
        }
    }

    private void applyRegistryFilter() {
        String filter = registrySearch.getText();
        filteredRegistry.setPredicate(item -> matchesSearch(item, filter));
    }

    private void applyTaskFilter() {
        String filter = taskSearch.getText();
        filteredTasks.setPredicate(item -> matchesSearch(item, filter));
    }

    private void applyServiceFilter() {
        String filter = serviceSearch.getText();
        filteredServices.setPredicate(item -> matchesSearch(item, filter));
    }

    private boolean matchesSearch(StartupItem item, String filter) {
        if (filter == null || filter.isBlank()) return true;
        String lower = filter.toLowerCase();
        return item.getName().toLowerCase().contains(lower) ||
               item.getPublisher().toLowerCase().contains(lower) ||
               item.getPath().toLowerCase().contains(lower) ||
               item.getLocation().toLowerCase().contains(lower);
    }

    private static boolean isSystemTask(StartupItem item) {
        if (item == null || item.getType() != StartupItemType.TASK) return false;
        String tp = item.getTaskPath();
        if (tp == null) return false;
        String lower = tp.toLowerCase();
        return lower.startsWith("\\microsoft\\") || lower.startsWith("\\windows\\") || lower.contains("\\microsoft\\windows");
    }

    private void scan() {
        if (busy.get()) return;
        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Scanning startup items...");
        registryItems.clear();
        taskItems.clear();
        serviceItems.clear();

        executor.execute(() -> {
            try {
                List<StartupItem> allItems = service.listAllParallel();

                for (StartupItem item : allItems) {
                    item.setEstimatedBootImpactMs(StartupImpactService.estimateBootImpactMs(item));
                }

                List<StartupItem> regItems = allItems.stream().filter(i -> i.getType() == StartupItemType.REGISTRY).collect(Collectors.toList());
                List<StartupItem> taskItemsResult = allItems.stream().filter(i -> i.getType() == StartupItemType.TASK).collect(Collectors.toList());
                List<StartupItem> svcItems = allItems.stream().filter(i -> i.getType() == StartupItemType.SERVICE).collect(Collectors.toList());

                double totalMs = allItems.stream().mapToDouble(StartupItem::getEstimatedBootImpactMs).sum();
                final String formattedTotal = StartupImpactService.formatImpact(totalMs);
                Platform.runLater(() -> {
                    registryItems.setAll(regItems);
                    taskItems.setAll(taskItemsResult);
                    serviceItems.setAll(svcItems);
                    applyRegistryFilter();
                    applyTaskFilter();
                    applyServiceFilter();
                    int total = allItems.size();
                    statusLabel.setText("Found " + total + " startup item(s).");
                    bootDelayLabel.setText("Total estimated boot delay: " + formattedTotal);

                    List<String> errors = service.drainScanErrors();
                    if (!errors.isEmpty()) {
                        StringBuilder sb = new StringBuilder("Scan completed with warnings:\n");
                        for (String err : errors) {
                            sb.append("- ").append(err).append("\n");
                        }
                        new Alert(Alert.AlertType.WARNING, sb.toString()).showAndWait();
                    }
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
        });
    }

    private void triggerToggle() {
        List<StartupItem> selected = new ArrayList<>(getSelectedTable().getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || busy.get()) return;

        List<StartupItem> serviceItems = selected.stream()
                .filter(i -> i.getType() == StartupItemType.SERVICE).toList();
        List<StartupItem> hklmItems = selected.stream()
                .filter(i -> i.getType() == StartupItemType.REGISTRY && i.getLocation() != null && i.getLocation().contains("HKLM")).toList();
        List<StartupItem> hkcuItems = selected.stream()
                .filter(i -> i.getType() == StartupItemType.REGISTRY && i.getLocation() != null && i.getLocation().contains("HKCU")).toList();
        List<StartupItem> commonFolderItems = selected.stream()
                .filter(i -> i.getLocation() != null && i.getLocation().contains("Common")).toList();
        List<StartupItem> systemTaskItems = selected.stream()
                .filter(i -> i.getType() == StartupItemType.TASK && isSystemTask(i)).toList();
        List<StartupItem> nonServiceItems = selected.stream()
                .filter(i -> i.getType() != StartupItemType.SERVICE).toList();

        boolean needsAdmin = (!serviceItems.isEmpty() || !hklmItems.isEmpty() || !commonFolderItems.isEmpty() || !systemTaskItems.isEmpty()) && !adminCheck.getAsBoolean();
        if (needsAdmin) {
            long totalAdmin = serviceItems.size() + hklmItems.size() + commonFolderItems.size() + systemTaskItems.size();
            // Deduplicate overlapping (service is not HKLM, but count may double if same item matches multiple categories)
            // Use set semantics: count distinct items requiring admin
            java.util.Set<StartupItem> adminSet = new java.util.HashSet<>();
            adminSet.addAll(serviceItems); adminSet.addAll(hklmItems); adminSet.addAll(commonFolderItems); adminSet.addAll(systemTaskItems);
            totalAdmin = adminSet.size();
            if (adminSet.size() == selected.size()) {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Administrator Required");
                alert.setHeaderText("Modification requires elevation");
                String detail;
                if (!serviceItems.isEmpty() && systemTaskItems.isEmpty() && hklmItems.isEmpty()) {
                    detail = "Modifying Windows service start types requires administrator privileges.\n";
                } else if (!systemTaskItems.isEmpty() && serviceItems.isEmpty() && hklmItems.isEmpty()) {
                    detail = "Modifying system scheduled tasks (\\Microsoft\\Windows) requires administrator privileges.\n";
                } else {
                    detail = "Modifying HKLM / Common Startup items, services or system tasks requires administrator privileges.\n";
                }
                alert.setContentText(detail + "Please run the application as administrator.");
                alert.initModality(Modality.APPLICATION_MODAL);
                alert.showAndWait();
                return;
            } else {
                Alert alert = new Alert(Alert.AlertType.WARNING);
                alert.setTitle("Administrator Required");
                alert.setHeaderText("Some items require elevation");
                alert.setContentText(totalAdmin + " item(s) require administrator privileges (HKLM/services/Common/system tasks) and will be skipped.\n"
                        + "Only non-privileged items will be toggled. Run as administrator to modify all.");
                alert.initModality(Modality.APPLICATION_MODAL);
                alert.showAndWait();
                // Keep only items that don't need admin: HKCU registry + non-system tasks
                List<StartupItem> allowed = new ArrayList<>();
                for (StartupItem it : selected) {
                    if (it.getType() == StartupItemType.SERVICE) continue;
                    if (it.getLocation() != null && (it.getLocation().contains("HKLM") || it.getLocation().contains("Common"))) continue;
                    if (isSystemTask(it)) continue;
                    allowed.add(it);
                }
                if (allowed.isEmpty()) return;
                selected = allowed;
            }
        }

        if (selected.size() == 1) {
            StartupItem item = selected.get(0);
            String action = item.isEnabled() ? "disable" : "enable";
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Confirm Toggle");
            confirm.setHeaderText("Change startup item status");
            confirm.setContentText("Are you sure you want to " + action + " \"" + item.getName() + "\"?");
            confirm.initModality(Modality.APPLICATION_MODAL);
            if (confirm.showAndWait().orElse(null) != ButtonType.OK) {
                return;
            }
        }

        final List<StartupItem> itemsToToggle = selected;

        busy.set(true);
        progress.setVisible(true);
        statusLabel.setText("Toggling " + itemsToToggle.size() + " item(s)...");

        executor.execute(() -> {
            List<String> errors = new ArrayList<>();
            for (StartupItem item : itemsToToggle) {
                try {
                    service.toggleStatus(item);
                } catch (Exception e) {
                    AppLogger.error("Failed to toggle status for " + item.getName(), e);
                    errors.add(item.getName() + ": " + e.getMessage());
                }
            }

            Platform.runLater(() -> {
                for (StartupItem item : itemsToToggle) {
                    item.setEstimatedBootImpactMs(StartupImpactService.estimateBootImpactMs(item));
                }
                filteredRegistry.setPredicate(null);
                filteredTasks.setPredicate(null);
                filteredServices.setPredicate(null);
                applyRegistryFilter();
                applyTaskFilter();
                applyServiceFilter();
                getSelectedTable().refresh();
                if (errors.isEmpty()) {
                    statusLabel.setText("Toggled " + itemsToToggle.size() + " item(s) successfully.");
                } else {
                    statusLabel.setText("Completed with errors.");
                    new Alert(Alert.AlertType.ERROR, "Some items failed:\n" + String.join("\n", errors)).showAndWait();
                }
                busy.set(false);
                progress.setVisible(false);
            });
        });
    }

    private void triggerDelete() {
        List<StartupItem> selected = new ArrayList<>(getSelectedTable().getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || busy.get()) return;

        // Admin check for HKLM / Common items deletion + system tasks
        List<StartupItem> adminNeeded = selected.stream()
                .filter(i -> (i.getLocation() != null && (i.getLocation().contains("HKLM") || i.getLocation().contains("Common"))) || isSystemTask(i))
                .toList();
        if (!adminNeeded.isEmpty() && !adminCheck.getAsBoolean()) {
            Alert warn = new Alert(Alert.AlertType.WARNING);
            warn.setTitle("Administrator Required");
            warn.setHeaderText("Deletion requires elevation");
            warn.setContentText(adminNeeded.size() + " selected item(s) are HKLM / Common / system tasks and require administrator privileges.\n"
                    + "Only non-privileged items will be deleted. Run as administrator to delete all.");
            warn.initModality(Modality.APPLICATION_MODAL);
            warn.showAndWait();
            List<StartupItem> allowed = new ArrayList<>();
            for (StartupItem it : selected) {
                if (it.getLocation() != null && (it.getLocation().contains("HKLM") || it.getLocation().contains("Common"))) continue;
                if (isSystemTask(it)) continue;
                allowed.add(it);
            }
            if (allowed.isEmpty()) return;
            selected = allowed;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Confirm Deletion");
        if (selected.size() == 1) {
            confirm.setHeaderText("Delete Startup Item: " + selected.get(0).getName());
        } else {
            confirm.setHeaderText("Delete " + selected.size() + " startup items");
        }
        confirm.setContentText("Are you sure you want to permanently delete the selected startup item(s)?\n" +
                "A backup will be created automatically for each item.");
        confirm.initModality(Modality.APPLICATION_MODAL);

        if (confirm.showAndWait().orElse(null) == ButtonType.OK) {
            final List<StartupItem> toDelete = new ArrayList<>(selected);
            busy.set(true);
            progress.setVisible(true);
            statusLabel.setText("Deleting " + toDelete.size() + " item(s)...");

            executor.execute(() -> {
                List<String> errors = new ArrayList<>();
                List<StartupItem> toRemoveRegistry = new ArrayList<>();
                List<StartupItem> toRemoveTask = new ArrayList<>();
                for (StartupItem item : toDelete) {
                    try {
                        service.deleteItem(item);
                        if (item.getType() == StartupItemType.REGISTRY) {
                            toRemoveRegistry.add(item);
                        } else if (item.getType() == StartupItemType.TASK) {
                            toRemoveTask.add(item);
                        }
                    } catch (Exception e) {
                        AppLogger.error("Failed to delete startup item " + item.getName(), e);
                        errors.add(item.getName() + ": " + e.getMessage());
                    }
                }

                Platform.runLater(() -> {
                    registryItems.removeAll(toRemoveRegistry);
                    taskItems.removeAll(toRemoveTask);
                    applyRegistryFilter();
                    applyTaskFilter();
                    if (errors.isEmpty()) {
                        int removed = toRemoveRegistry.size() + toRemoveTask.size();
                        statusLabel.setText("Deleted " + removed + " item(s) successfully.");
                        new Alert(Alert.AlertType.INFORMATION, "The selected startup item(s) have been deleted. You can restore them from the Backups panel.").showAndWait();
                    } else {
                        statusLabel.setText("Deletion completed with errors.");
                        new Alert(Alert.AlertType.ERROR, "Some items failed to delete:\n" + String.join("\n", errors)).showAndWait();
                    }
                    busy.set(false);
                    progress.setVisible(false);
                });
            });
        }
    }

    private void showDetailsDialog(StartupItem item) {
        if (item == null) return;
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Startup item details");
        dialog.initModality(Modality.APPLICATION_MODAL);
        try {
            var cssUrl = getClass().getResource("/custom.css");
            if (cssUrl != null) {
                dialog.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
            }
        } catch (Exception ignored) {}

        VBox content = new VBox(8);
        content.setPadding(new Insets(10));

        Label name = new Label("Name: " + item.getName());
        Label publisher = new Label("Publisher: " + item.getPublisher());
        Label location = new Label("Location: " + item.getLocation());
        Label status = new Label("Status: " + (item.isEnabled() ? "Enabled" : "Disabled"));
        Label impact = new Label("Estimated boot impact: " + StartupImpactService.formatImpact(item.getEstimatedBootImpactMs()));

        TextArea cmd = new TextArea(item.getPath() == null ? "" : item.getPath());
        cmd.setEditable(false);
        cmd.setWrapText(true);
        cmd.setPrefRowCount(4);

        content.getChildren().addAll(name, publisher, location, status, impact, new Label("Command / Path:"), cmd);

        if (item.getType() == StartupItemType.SERVICE && item.getDependencies() != null && !item.getDependencies().isEmpty()) {
            content.getChildren().add(new Label("Dependencies: " + String.join(", ", item.getDependencies())));
        }

        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        dialog.showAndWait();
    }

    // ── Backup Manager Dialog ──────────────────────────────────────────────────

    private void showBackupsDialog() {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("Startup Backups & Restore");
        dialog.setHeaderText("Restore previously deleted or modified startup items.");
        dialog.initModality(Modality.APPLICATION_MODAL);

        try {
            var cssUrl = getClass().getResource("/custom.css");
            if (cssUrl != null) {
                dialog.getDialogPane().getStylesheets().add(cssUrl.toExternalForm());
            }
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
                scan();
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

    public void dispose() {
        executor.shutdownNow();
    }
}
