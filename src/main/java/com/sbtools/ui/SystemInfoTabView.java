package com.sbtools.ui;

import com.sbtools.systeminfo.BiosInfo;
import com.sbtools.systeminfo.CpuInfo;
import com.sbtools.systeminfo.GpuInfo;
import com.sbtools.systeminfo.MotherboardInfo;
import com.sbtools.systeminfo.OtherDevice;
import com.sbtools.systeminfo.OsInfo;
import com.sbtools.systeminfo.RamInfo;
import com.sbtools.systeminfo.StorageInfo;
import com.sbtools.systeminfo.SystemInfoData;
import com.sbtools.systeminfo.SystemInfoService;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

public class SystemInfoTabView extends BorderPane {

    private final SystemInfoService service = new SystemInfoService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "system-info");
        t.setDaemon(true);
        return t;
    });

    private final Label statusLabel = new Label("Click Load to query system information.");
    private final Button loadButton = new Button("Load System Info");
    private final Button refreshButton = new Button("Refresh");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TabPane tabPane = new TabPane();

    public SystemInfoTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        spinner.setPrefSize(24, 24);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setVisible(false);

        progressBar.setPrefWidth(200);
        progressBar.setVisible(false);

        loadButton.setOnAction(e -> loadInfo());
        refreshButton.setOnAction(e -> { service.invalidateCache(); loadInfo(); });
        refreshButton.setDisable(true);

        HBox top = new HBox(12, loadButton, refreshButton, spinner, progressBar, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPadding(new Insets(8, 0, 0, 0));

        setTop(top);
        setCenter(tabPane);

        if (!AppPaths.isWindows()) {
            statusLabel.setText("System information is only available on Windows.");
            loadButton.setDisable(true);
        }
    }

    private void loadInfo() {
        if (busy.get()) return;
        busy.set(true);
        loadButton.setDisable(true);
        refreshButton.setDisable(true);
        spinner.setVisible(true);
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        statusLabel.setText("Querying system information\u2026");

        executor.submit(() -> {
            try {
                SystemInfoData data = service.gatherSystemInfo(
                        (section, progress) -> Platform.runLater(() -> {
                            statusLabel.setText("Loading " + section + "\u2026");
                            progressBar.setProgress(progress);
                        })
                );
                Platform.runLater(() -> {
                    buildTabs(data);
                    statusLabel.setText("System information loaded.");
                    progressBar.setProgress(1);
                    refreshButton.setDisable(false);
                });
            } catch (Exception ex) {
                AppLogger.error("Failed to load system info", ex);
                Platform.runLater(() -> {
                    statusLabel.setText("Failed: " + ex.getMessage());
                    new Alert(Alert.AlertType.ERROR, "Failed to load system information:\n" + ex.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> {
                    busy.set(false);
                    loadButton.setDisable(false);
                    spinner.setVisible(false);
                    progressBar.setVisible(false);
                });
            }
        });
    }

    private void buildTabs(SystemInfoData data) {
        tabPane.getTabs().clear();

        if (data.cpu() != null) {
            tabPane.getTabs().add(buildCpuTab(data.cpu()));
        }
        if (data.gpu() != null && !data.gpu().isEmpty()) {
            tabPane.getTabs().add(buildGpuTab(data.gpu()));
        }
        if (data.ram() != null) {
            tabPane.getTabs().add(buildRamTab(data.ram()));
        }
        if (data.os() != null) {
            tabPane.getTabs().add(buildOsTab(data.os()));
        }
        if (data.storage() != null) {
            tabPane.getTabs().add(buildStorageTab(data.storage()));
        }
        if (data.motherboard() != null || data.bios() != null) {
            tabPane.getTabs().add(buildMotherboardTab(data.motherboard(), data.bios()));
        }
        if (data.others() != null && !data.others().isEmpty()) {
            tabPane.getTabs().add(buildOthersTab(data.others()));
        }
    }

    // ── CPU ──────────────────────────────────────────────────────────────────

    private Tab buildCpuTab(CpuInfo cpu) {
        GridPane grid = createInfoGrid();
        int row = 0;
        row = addRow(grid, row, "Name", cpu.name());
        row = addRow(grid, row, "Manufacturer", cpu.manufacturer());
        row = addRow(grid, row, "Architecture", cpu.architecture());
        row = addRow(grid, row, "Socket", cpu.socket());
        row = addRow(grid, row, "Cores", String.valueOf(cpu.cores()));
        row = addRow(grid, row, "Threads", String.valueOf(cpu.logicalCpus()));
        row = addRow(grid, row, "Base Clock", cpu.formatBaseClock());
        row = addRow(grid, row, "Current Clock", cpu.formatCurrentClock());
        row = addRow(grid, row, "L2 Cache", cpu.formatL2Cache());
        row = addRow(grid, row, "L3 Cache", cpu.formatL3Cache());
        row = addRow(grid, row, "Voltage", cpu.voltage());
        row = addRow(grid, row, "Stepping", cpu.stepping());
        row = addRow(grid, row, "Revision", cpu.revision());
        return new Tab("CPU", wrapGrid(grid));
    }

    // ── GPU ──────────────────────────────────────────────────────────────────

    private Tab buildGpuTab(List<GpuInfo> gpus) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        for (int i = 0; i < gpus.size(); i++) {
            GpuInfo gpu = gpus.get(i);
            if (gpus.size() > 1) {
                container.getChildren().add(UILabel.sectionTitle("GPU " + (i + 1)));
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", gpu.name());
            row = addRow(grid, row, "Manufacturer", gpu.manufacturer());
            row = addRow(grid, row, "Video Processor", gpu.videoProcessor());
            row = addRow(grid, row, "VRAM", gpu.formatVram());
            row = addRow(grid, row, "Memory Type", gpu.memoryType());
            row = addRow(grid, row, "Driver Version", gpu.driverVersion());
            row = addRow(grid, row, "Driver Date", gpu.driverDate());
            row = addRow(grid, row, "Resolution", gpu.resolution());
            row = addRow(grid, row, "Color Depth", gpu.colorDepth());
            container.getChildren().add(wrapGrid(grid));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("GPU");
        tab.setContent(scroll);
        return tab;
    }

    // ── RAM ──────────────────────────────────────────────────────────────────

    private Tab buildRamTab(RamInfo ram) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        GridPane summary = createInfoGrid();
        int row = 0;
        row = addRow(summary, row, "Total Memory", ram.formatTotal());
        row = addRow(summary, row, "Channel", ram.channel());
        container.getChildren().add(wrapGrid(summary));

        if (ram.sticks() != null && !ram.sticks().isEmpty()) {
            for (int i = 0; i < ram.sticks().size(); i++) {
                RamInfo.RamStick stick = ram.sticks().get(i);
                container.getChildren().add(UILabel.sectionTitle("Slot " + (i + 1)));
                GridPane stickGrid = createInfoGrid();
                int r = 0;
                r = addRow(stickGrid, r, "Capacity", stick.formatCapacity());
                r = addRow(stickGrid, r, "Type", stick.memoryType());
                r = addRow(stickGrid, r, "Speed", stick.formatSpeed());
                r = addRow(stickGrid, r, "Manufacturer", stick.manufacturer());
                r = addRow(stickGrid, r, "Form Factor", stick.formFactor());
                r = addRow(stickGrid, r, "Part Number", stick.partNumber());
                container.getChildren().add(wrapGrid(stickGrid));
            }
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("RAM");
        tab.setContent(scroll);
        return tab;
    }

    // ── OS ───────────────────────────────────────────────────────────────────

    private Tab buildOsTab(OsInfo os) {
        GridPane grid = createInfoGrid();
        int row = 0;
        row = addRow(grid, row, "Operating System", os.name());
        row = addRow(grid, row, "Version", os.version());
        row = addRow(grid, row, "Build Number", os.buildNumber());
        row = addRow(grid, row, "Architecture", os.architecture());
        row = addRow(grid, row, "Computer Name", os.computerName());
        row = addRow(grid, row, "Install Date", os.installDate());
        row = addRow(grid, row, "Last Boot", os.lastBoot());
        row = addRow(grid, row, "Windows Directory", os.windowsDir());
        row = addRow(grid, row, "Serial Number", os.serialNumber());
        return new Tab("OS", wrapGrid(grid));
    }

    // ── Storage ──────────────────────────────────────────────────────────────

    private Tab buildStorageTab(StorageInfo storage) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        if (storage.disks() != null && !storage.disks().isEmpty()) {
            for (int i = 0; i < storage.disks().size(); i++) {
                StorageInfo.Disk disk = storage.disks().get(i);
                container.getChildren().add(UILabel.sectionTitle("Disk " + (i + 1)));
                GridPane grid = createInfoGrid();
                int row = 0;
                row = addRow(grid, row, "Model", disk.model());
                row = addRow(grid, row, "Manufacturer", disk.manufacturer());
                row = addRow(grid, row, "Size", disk.formatSize());
                row = addRow(grid, row, "Media Type", disk.mediType());
                row = addRow(grid, row, "Interface", disk.interfaceType());
                row = addRow(grid, row, "Serial Number", disk.serialNumber());
                row = addRow(grid, row, "Partitions", String.valueOf(disk.partitions()));
                container.getChildren().add(wrapGrid(grid));

                // Show partitions belonging to this disk
                if (storage.partitions() != null) {
                    final int diskIdx = i;
                    List<StorageInfo.Partition> diskParts = storage.partitions().stream()
                            .filter(p -> p.diskIndex() == diskIdx)
                            .toList();
                    if (!diskParts.isEmpty()) {
                        container.getChildren().add(UILabel.sectionTitle("  Partitions on Disk " + (i + 1)));
                        for (StorageInfo.Partition part : diskParts) {
                            GridPane partGrid = createInfoGrid();
                            int r = 0;
                            r = addRow(partGrid, r, "Drive", part.deviceID());
                            r = addRow(partGrid, r, "Volume Name", part.volumeName());
                            r = addRow(partGrid, r, "File System", part.fsType());
                            r = addRow(partGrid, r, "Total Size", part.formatSize());
                            r = addRow(partGrid, r, "Used", part.formatUsed());
                            r = addRow(partGrid, r, "Free", part.formatFree());
                            r = addUsageRow(partGrid, r, part.usagePercent());
                            container.getChildren().add(wrapGrid(partGrid));
                        }
                    }
                }
            }
        }

        // Show partitions not assigned to any disk
        if (storage.partitions() != null && !storage.partitions().isEmpty()) {
            List<StorageInfo.Partition> unassigned = storage.partitions().stream()
                    .filter(p -> p.diskIndex() < 0)
                    .toList();
            if (!unassigned.isEmpty()) {
                container.getChildren().add(UILabel.sectionTitle("Other Partitions"));
                for (StorageInfo.Partition part : unassigned) {
                    GridPane grid = createInfoGrid();
                    int row = 0;
                    row = addRow(grid, row, "Drive", part.deviceID());
                    row = addRow(grid, row, "Volume Name", part.volumeName());
                    row = addRow(grid, row, "File System", part.fsType());
                    row = addRow(grid, row, "Total Size", part.formatSize());
                    row = addRow(grid, row, "Used", part.formatUsed());
                    row = addRow(grid, row, "Free", part.formatFree());
                    row = addUsageRow(grid, row, part.usagePercent());
                    container.getChildren().add(wrapGrid(grid));
                }
            }
        }

        if (storage.nvmes() != null && !storage.nvmes().isEmpty()) {
            container.getChildren().add(UILabel.sectionTitle("NVMe Drives"));
            for (StorageInfo.Nvme nvme : storage.nvmes()) {
                GridPane grid = createInfoGrid();
                int row = 0;
                row = addRow(grid, row, "Serial Number", nvme.serialNumber());
                row = addRow(grid, row, "Media Type", nvme.mediaType());
                row = addRow(grid, row, "Bus Type", nvme.busType());
                container.getChildren().add(wrapGrid(grid));
            }
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Storage");
        tab.setContent(scroll);
        return tab;
    }

    // ── Motherboard / BIOS ───────────────────────────────────────────────────

    private Tab buildMotherboardTab(MotherboardInfo mb, BiosInfo bios) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        if (mb != null) {
            container.getChildren().add(UILabel.sectionTitle("Motherboard"));
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Manufacturer", mb.manufacturer());
            row = addRow(grid, row, "Model", mb.model());
            row = addRow(grid, row, "Version", mb.version());
            row = addRow(grid, row, "Chipset", mb.chipset());
            row = addRow(grid, row, "Southbridge", mb.southbridge());
            row = addRow(grid, row, "Serial Number", mb.serialNumber());
            container.getChildren().add(wrapGrid(grid));
        }

        if (bios != null) {
            container.getChildren().add(UILabel.sectionTitle("BIOS"));
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Manufacturer", bios.manufacturer());
            row = addRow(grid, row, "Version", bios.version());
            row = addRow(grid, row, "Release Date", bios.releaseDate());
            row = addRow(grid, row, "SMBIOS Version", bios.formatSmbios());
            container.getChildren().add(wrapGrid(grid));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Motherboard");
        tab.setContent(scroll);
        return tab;
    }

    // ── Others ───────────────────────────────────────────────────────────────

    private Tab buildOthersTab(List<OtherDevice> devices) {
        VBox container = new VBox(8);
        container.setPadding(new Insets(12));

        TreeMap<String, List<OtherDevice>> grouped = new TreeMap<>();
        for (OtherDevice dev : devices) {
            String cls = (dev.deviceClass() != null && !dev.deviceClass().isBlank())
                    ? dev.deviceClass() : "Other";
            grouped.computeIfAbsent(cls, k -> new ArrayList<>()).add(dev);
        }

        ObservableList<String> categories = FXCollections.observableArrayList(grouped.keySet());
        FilteredList<String> filteredCategories = new FilteredList<>(categories);

        TextField searchField = new TextField();
        searchField.setPromptText("Search devices\u2026");
        searchField.getStyleClass().add("sysinfo-search");
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String lower = newVal == null ? "" : newVal.toLowerCase();
            filteredCategories.setPredicate(cat -> {
                if (lower.isEmpty()) return true;
                List<OtherDevice> devs = grouped.get(cat);
                if (devs == null) return false;
                if (cat.toLowerCase().contains(lower)) return true;
                for (OtherDevice d : devs) {
                    if ((d.name() != null && d.name().toLowerCase().contains(lower))
                            || (d.manufacturer() != null && d.manufacturer().toLowerCase().contains(lower))) {
                        return true;
                    }
                }
                return false;
            });
        });

        ListView<String> categoryList = new ListView<>(filteredCategories);
        categoryList.setPrefHeight(400);
        categoryList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                } else {
                    List<OtherDevice> devs = grouped.get(item);
                    int count = devs != null ? devs.size() : 0;
                    setText(item + " (" + count + ")");
                }
            }
        });

        VBox rightPanel = new VBox(8);
        rightPanel.setPadding(new Insets(0, 0, 0, 12));
        rightPanel.getChildren().add(new Label("Select a category:"));
        rightPanel.getChildren().add(categoryList);

        VBox deviceList = new VBox(8);
        deviceList.setPadding(new Insets(0));
        ScrollableContainer deviceScroll = new ScrollableContainer(deviceList);

        categoryList.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, cat) -> {
            deviceList.getChildren().clear();
            if (cat == null) return;
            List<OtherDevice> devs = grouped.get(cat);
            if (devs == null) return;
            GridPane grid = new GridPane();
            grid.setHgap(0);
            grid.setVgap(0);
            ColumnConstraints nameCol = new ColumnConstraints();
            nameCol.setPrefWidth(360);
            nameCol.setMinWidth(200);
            nameCol.setHgrow(Priority.ALWAYS);
            ColumnConstraints mfrCol = new ColumnConstraints();
            mfrCol.setMinWidth(150);
            mfrCol.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(nameCol, mfrCol);
            int r = 0;
            for (OtherDevice dev : devs) {
                r = addRow(grid, r, dev.name(),
                        (dev.manufacturer() != null && !dev.manufacturer().isBlank())
                                ? dev.manufacturer() : dev.status());
            }
            deviceList.getChildren().add(wrapGrid(grid));
        });

        HBox splitPane = new HBox(0, rightPanel, deviceScroll);
        HBox.setHgrow(deviceScroll, Priority.ALWAYS);
        container.getChildren().addAll(searchField, splitPane);

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Others");
        tab.setContent(scroll);
        return tab;
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static GridPane createInfoGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(0);
        grid.setVgap(0);
        grid.setPadding(new Insets(0));

        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setPrefWidth(160);
        labelCol.setMinWidth(120);
        labelCol.setHgrow(Priority.NEVER);

        ColumnConstraints valueCol = new ColumnConstraints();
        valueCol.setMinWidth(200);
        valueCol.setHgrow(Priority.ALWAYS);

        grid.getColumnConstraints().addAll(labelCol, valueCol);
        return grid;
    }

    private static int addRow(GridPane grid, int row, String label, String value) {
        if (value == null || value.isBlank()) return row;

        Label keyLabel = new Label(label);
        keyLabel.getStyleClass().addAll("label", "sysinfo-label");
        keyLabel.setMaxWidth(Double.MAX_VALUE);

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().addAll("label", "sysinfo-value");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        RowConstraints rc = new RowConstraints();
        rc.setMinHeight(20);
        grid.getRowConstraints().add(rc);

        grid.add(keyLabel, 0, row);
        grid.add(valueLabel, 1, row);

        String bgClass = row % 2 == 0 ? "sysinfo-row-even" : "sysinfo-row-odd";
        keyLabel.getStyleClass().add(bgClass);
        valueLabel.getStyleClass().add(bgClass);

        return row + 1;
    }

    private static int addUsageRow(GridPane grid, int row, double usagePercent) {
        if (usagePercent <= 0) return row;

        Label keyLabel = new Label("Usage");
        keyLabel.getStyleClass().addAll("label", "sysinfo-label");
        keyLabel.setMaxWidth(Double.MAX_VALUE);

        HBox usageBox = new HBox(8);
        usageBox.setAlignment(Pos.CENTER_LEFT);
        ProgressBar usageBar = new ProgressBar(usagePercent / 100.0);
        usageBar.getStyleClass().add("sysinfo-usage-bar");
        if (usagePercent > 90) {
            usageBar.getStyleClass().add("sysinfo-usage-danger");
        } else if (usagePercent > 75) {
            usageBar.getStyleClass().add("sysinfo-usage-warning");
        }
        Label pctLabel = new Label(String.format("%.1f%%", usagePercent));
        pctLabel.getStyleClass().addAll("label", "sysinfo-value");
        usageBox.getChildren().addAll(usageBar, pctLabel);

        RowConstraints rc = new RowConstraints();
        rc.setMinHeight(20);
        grid.getRowConstraints().add(rc);

        grid.add(keyLabel, 0, row);
        grid.add(usageBox, 1, row);

        String bgClass = row % 2 == 0 ? "sysinfo-row-even" : "sysinfo-row-odd";
        keyLabel.getStyleClass().add(bgClass);
        usageBox.getStyleClass().add(bgClass);

        return row + 1;
    }

    private static VBox wrapGrid(GridPane grid) {
        VBox wrapper = new VBox(grid);
        wrapper.getStyleClass().add("sysinfo-card");
        return wrapper;
    }

    private static class ScrollableContainer extends javafx.scene.control.ScrollPane {
        ScrollableContainer(javafx.scene.Node content) {
            super(content);
            setFitToWidth(true);
            setFitToHeight(true);
            setVbarPolicy(ScrollBarPolicy.ALWAYS);
            setHbarPolicy(ScrollBarPolicy.NEVER);
            setStyle("-fx-background-color: transparent; -fx-background: transparent; -fx-border-color: transparent;");
            setContent(content);
        }
    }
}
