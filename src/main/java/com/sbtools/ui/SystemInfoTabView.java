package com.sbtools.ui;

import com.sbtools.systeminfo.AudioDeviceInfo;
import com.sbtools.systeminfo.BatteryInfo;
import com.sbtools.systeminfo.BiosInfo;
import com.sbtools.systeminfo.CpuInfo;
import com.sbtools.systeminfo.GpuInfo;
import com.sbtools.systeminfo.MotherboardInfo;
import com.sbtools.systeminfo.NetworkAdapterInfo;
import com.sbtools.systeminfo.OtherDevice;
import com.sbtools.systeminfo.OsInfo;
import com.sbtools.systeminfo.RamInfo;
import com.sbtools.systeminfo.StorageInfo;
import com.sbtools.systeminfo.SystemInfoData;
import com.sbtools.systeminfo.SystemInfoService;
import com.sbtools.systeminfo.TemperatureInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.DataSizeFormatter;
import com.sbtools.util.JsonMapper;
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
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
    private final Button exportButton = new Button("Export...");
    private final Button copyButton = new Button("Copy All");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TabPane tabPane = new TabPane();

    private SystemInfoData currentData;

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
        exportButton.setDisable(true);
        exportButton.setOnAction(e -> exportToFile());
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyToClipboard());

        HBox top = new HBox(12, loadButton, refreshButton, exportButton, copyButton, spinner, progressBar, statusLabel);
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
        exportButton.setDisable(true);
        copyButton.setDisable(true);
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
                    currentData = data;
                    buildTabs(data);
                    statusLabel.setText("System information loaded.");
                    progressBar.setProgress(1);
                    refreshButton.setDisable(false);
                    exportButton.setDisable(false);
                    copyButton.setDisable(false);
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

        if (data.cpu() != null || data.os() != null) {
            tabPane.getTabs().add(buildOverviewTab(data));
        }
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
        if (data.networkAdapters() != null && !data.networkAdapters().isEmpty()) {
            tabPane.getTabs().add(buildNetworkTab(data.networkAdapters()));
        }
        if (data.audioDevices() != null && !data.audioDevices().isEmpty()) {
            tabPane.getTabs().add(buildAudioTab(data.audioDevices()));
        }
        if (data.battery() != null) {
            tabPane.getTabs().add(buildBatteryTab(data.battery()));
        }
        if (data.temperatures() != null && !data.temperatures().isEmpty()) {
            tabPane.getTabs().add(buildTemperaturesTab(data.temperatures()));
        }
        if (data.others() != null && !data.others().isEmpty()) {
            tabPane.getTabs().add(buildOthersTab(data.others()));
        }

        if (data.warnings() != null && !data.warnings().isEmpty()) {
            tabPane.getTabs().add(buildWarningsTab(data.warnings()));
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
                row = addRow(grid, row, "Media Type", disk.mediaType());
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

    // ── Overview ────────────────────────────────────────────────────────────

    private Tab buildOverviewTab(SystemInfoData data) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        // Summary cards
        HBox cards = new HBox(12);
        cards.setAlignment(Pos.CENTER_LEFT);
        if (data.os() != null) {
            cards.getChildren().add(buildSmallInfoCard("OS",
                    data.os().name() != null ? data.os().name() : "",
                    data.os().buildNumber() != null ? "Build " + data.os().buildNumber() : ""));
        }
        if (data.cpu() != null) {
            cards.getChildren().add(buildSmallInfoCard("CPU",
                    data.cpu().name() != null ? data.cpu().name() : "",
                    data.cpu().cores() + "C / " + data.cpu().logicalCpus() + "T"));
        }
        if (data.ram() != null) {
            cards.getChildren().add(buildSmallInfoCard("RAM",
                    data.ram().formatTotal(),
                    data.ram().channel()));
        }
        if (data.gpu() != null && !data.gpu().isEmpty()) {
            GpuInfo primaryGpu = data.gpu().get(0);
            cards.getChildren().add(buildSmallInfoCard("GPU",
                    primaryGpu.name() != null ? primaryGpu.name() : "",
                    primaryGpu.formatVram()));
        }
        if (data.storage() != null && data.storage().disks() != null) {
            long totalBytes = data.storage().disks().stream()
                    .mapToLong(StorageInfo.Disk::sizeBytes)
                    .sum();
            cards.getChildren().add(buildSmallInfoCard("Storage",
                    DataSizeFormatter.formatBytes(totalBytes),
                    data.storage().disks().size() + " disk(s)"));
        }
        container.getChildren().add(cards);

        // Key specs grid
        GridPane grid = createInfoGrid();
        int row = 0;
        if (data.os() != null) {
            row = addRow(grid, row, "OS", data.os().name());
            row = addRow(grid, row, "Version", data.os().version());
            row = addRow(grid, row, "Build", data.os().buildNumber());
            row = addRow(grid, row, "Architecture", data.os().architecture());
        }
        if (data.cpu() != null) {
            row = addRow(grid, row, "CPU", data.cpu().name());
            row = addRow(grid, row, "Cores / Threads", data.cpu().cores() + " / " + data.cpu().logicalCpus());
            row = addRow(grid, row, "Base Clock", data.cpu().formatBaseClock());
            row = addRow(grid, row, "Socket", data.cpu().socket());
        }
        if (data.ram() != null) {
            row = addRow(grid, row, "Total RAM", data.ram().formatTotal());
            row = addRow(grid, row, "Channel", data.ram().channel());
            if (data.ram().sticks() != null && !data.ram().sticks().isEmpty()) {
                RamInfo.RamStick firstStick = data.ram().sticks().get(0);
                row = addRow(grid, row, "RAM Type", firstStick.memoryType());
                row = addRow(grid, row, "RAM Speed", firstStick.formatSpeed());
            }
        }
        if (data.gpu() != null && !data.gpu().isEmpty()) {
            GpuInfo primaryGpu = data.gpu().get(0);
            row = addRow(grid, row, "GPU", primaryGpu.name());
            row = addRow(grid, row, "VRAM", primaryGpu.formatVram());
            row = addRow(grid, row, "Driver", primaryGpu.driverVersion());
        }
        if (data.storage() != null && data.storage().disks() != null && !data.storage().disks().isEmpty()) {
            StorageInfo.Disk primaryDisk = data.storage().disks().get(0);
            row = addRow(grid, row, "Primary Disk", primaryDisk.model());
            row = addRow(grid, row, "Disk Size", primaryDisk.formatSize());
            row = addRow(grid, row, "Interface", primaryDisk.interfaceType());
        }
        if (data.motherboard() != null) {
            row = addRow(grid, row, "Motherboard", data.motherboard().manufacturer() + " " + data.motherboard().model());
            row = addRow(grid, row, "Chipset", data.motherboard().chipset());
        }
        if (data.bios() != null) {
            row = addRow(grid, row, "BIOS", data.bios().manufacturer() + " " + data.bios().version());
            row = addRow(grid, row, "BIOS Date", data.bios().releaseDate());
        }
        container.getChildren().add(wrapGrid(grid));

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Overview");
        tab.setContent(scroll);
        return tab;
    }

    private VBox buildSmallInfoCard(String title, String value, String subtitle) {
        VBox card = new VBox(4);
        card.getStyleClass().add("sysinfo-overview-card");
        card.setPadding(new Insets(12));
        card.setMinWidth(160);

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().addAll("label", "text-muted");
        titleLabel.setStyle("-fx-font-size: 11px;");

        Label valueLabel = new Label(value != null ? value : "");
        valueLabel.getStyleClass().addAll("label", "large");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(180);

        Label subLabel = new Label(subtitle != null ? subtitle : "");
        subLabel.getStyleClass().addAll("label", "text-muted");
        subLabel.setStyle("-fx-font-size: 11px;");

        card.getChildren().addAll(titleLabel, valueLabel, subLabel);
        return card;
    }

    // ── Network ─────────────────────────────────────────────────────────────

    private Tab buildNetworkTab(List<NetworkAdapterInfo> adapters) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        for (int i = 0; i < adapters.size(); i++) {
            NetworkAdapterInfo adapter = adapters.get(i);
            container.getChildren().add(UILabel.sectionTitle("Adapter " + (i + 1) + ": " + adapter.name()));
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", adapter.name());
            row = addRow(grid, row, "Manufacturer", adapter.manufacturer());
            row = addRow(grid, row, "Type", adapter.adapterType());
            row = addRow(grid, row, "Speed", adapter.speed());
            row = addRow(grid, row, "MAC Address", adapter.macAddress());
            row = addRow(grid, row, "IP Addresses", adapter.formatIpAddresses());
            row = addRow(grid, row, "DHCP", adapter.dhcpEnabled() ? "Enabled" : "Disabled");
            row = addRow(grid, row, "Status", adapter.status());
            container.getChildren().add(wrapGrid(grid));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Network");
        tab.setContent(scroll);
        return tab;
    }

    // ── Audio ───────────────────────────────────────────────────────────────

    private Tab buildAudioTab(List<AudioDeviceInfo> devices) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        for (int i = 0; i < devices.size(); i++) {
            AudioDeviceInfo device = devices.get(i);
            container.getChildren().add(UILabel.sectionTitle(device.name()));
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", device.name());
            row = addRow(grid, row, "Manufacturer", device.manufacturer());
            row = addRow(grid, row, "Status", device.status());
            container.getChildren().add(wrapGrid(grid));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Audio");
        tab.setContent(scroll);
        return tab;
    }

    // ── Battery ─────────────────────────────────────────────────────────────

    private Tab buildBatteryTab(BatteryInfo battery) {
        GridPane grid = createInfoGrid();
        int row = 0;
        row = addRow(grid, row, "Name", battery.name());
        row = addRow(grid, row, "Charge Level", battery.formatChargeLevel());
        row = addRow(grid, row, "Status", battery.status());
        row = addRow(grid, row, "Chemistry", battery.chemistry());
        row = addRow(grid, row, "Remaining Capacity", battery.formatRemainingCapacity());
        row = addRow(grid, row, "Charge Rate", battery.formatChargeRate());

        if (battery.chargeLevel() > 0) {
            row = addUsageRow(grid, row, battery.chargeLevel());
        }

        return new Tab("Battery", wrapGrid(grid));
    }

    // ── Temperatures ────────────────────────────────────────────────────────

    private Tab buildTemperaturesTab(List<TemperatureInfo> temperatures) {
        GridPane grid = createInfoGrid();
        int row = 0;
        for (TemperatureInfo temp : temperatures) {
            String level = temp.temperatureLevel();
            String cssClass = switch (level) {
                case "hot" -> "sysinfo-temp-hot";
                case "warm" -> "sysinfo-temp-warm";
                default -> "sysinfo-temp-cool";
            };
            row = addTemperatureRow(grid, row, temp.zoneName(), temp.formatTemperature(), cssClass);
        }
        return new Tab("Temperatures", wrapGrid(grid));
    }

    private static int addTemperatureRow(GridPane grid, int row, String zoneName, String value, String cssClass) {
        if (value == null || value.isBlank()) return row;

        Label keyLabel = new Label(zoneName);
        keyLabel.getStyleClass().addAll("label", "sysinfo-label");
        keyLabel.setMaxWidth(Double.MAX_VALUE);

        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().addAll("label", "sysinfo-value", cssClass);
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

    // ── Warnings ────────────────────────────────────────────────────────────

    private Tab buildWarningsTab(List<String> warnings) {
        VBox container = new VBox(8);
        container.setPadding(new Insets(12));

        Label header = UILabel.sectionTitle("Warnings");
        container.getChildren().add(header);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < warnings.size(); i++) {
            sb.append(i + 1).append(". ").append(warnings.get(i));
            if (i < warnings.size() - 1) sb.append("\n");
        }

        Label warningsLabel = new Label(sb.toString());
        warningsLabel.getStyleClass().addAll("label", "warning");
        warningsLabel.setWrapText(true);
        warningsLabel.setMaxWidth(Double.MAX_VALUE);
        warningsLabel.setStyle("-fx-padding: 8; -fx-background-color: #3d2e1a; -fx-background-radius: 4; -fx-border-color: #ffb86c; -fx-border-radius: 4;");
        container.getChildren().add(warningsLabel);

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Warnings");
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
        }
    }

    // ── Export / Copy ──────────────────────────────────────────────────────

    private void copyToClipboard() {
        if (currentData == null) return;
        String text = generatePlainTextReport(currentData);
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("System information copied to clipboard.");
    }

    private void exportToFile() {
        if (currentData == null) return;

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export System Information");
        fileChooser.setInitialFileName("system-info");

        FileChooser.ExtensionFilter txtFilter = new FileChooser.ExtensionFilter("Plain Text (*.txt)", "*.txt");
        FileChooser.ExtensionFilter jsonFilter = new FileChooser.ExtensionFilter("JSON (*.json)", "*.json");
        FileChooser.ExtensionFilter htmlFilter = new FileChooser.ExtensionFilter("HTML Report (*.html)", "*.html");
        fileChooser.getExtensionFilters().addAll(txtFilter, jsonFilter, htmlFilter);
        fileChooser.setSelectedExtensionFilter(txtFilter);

        File file = fileChooser.showSaveDialog(getScene().getWindow());
        if (file == null) return;

        try {
            String content;
            FileChooser.ExtensionFilter selectedFilter = fileChooser.getSelectedExtensionFilter();
            String ext = "";
            if (selectedFilter != null) {
                List<String> extensions = selectedFilter.getExtensions();
                if (!extensions.isEmpty()) {
                    ext = extensions.get(0).replace("*", "");
                }
            }
            if (ext.isEmpty()) {
                String name = file.getName().toLowerCase();
                if (name.endsWith(".json")) ext = ".json";
                else if (name.endsWith(".html")) ext = ".html";
                else ext = ".txt";
            }

            if (".json".equals(ext)) {
                content = JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(currentData);
            } else if (".html".equals(ext)) {
                content = generateHtmlReport(currentData);
            } else {
                content = generatePlainTextReport(currentData);
                ext = ".txt";
            }

            String fileName = file.getName();
            if (!fileName.toLowerCase().endsWith(ext)) {
                file = new File(file.getAbsolutePath() + ext);
            }

            Files.writeString(file.toPath(), content, StandardCharsets.UTF_8);
            statusLabel.setText("Exported to: " + file.getName());
        } catch (IOException ex) {
            AppLogger.error("Failed to export system info", ex);
            new Alert(Alert.AlertType.ERROR, "Failed to export: " + ex.getMessage()).showAndWait();
        }
    }

    private static String generatePlainTextReport(SystemInfoData data) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== System Information ===\n");
        sb.append("Generated by WinZenith\n\n");

        if (data.os() != null) {
            sb.append("--- Operating System ---\n");
            appendField(sb, "OS", data.os().name());
            appendField(sb, "Version", data.os().version());
            appendField(sb, "Build", data.os().buildNumber());
            appendField(sb, "Architecture", data.os().architecture());
            appendField(sb, "Computer Name", data.os().computerName());
            appendField(sb, "Install Date", data.os().installDate());
            appendField(sb, "Last Boot", data.os().lastBoot());
            appendField(sb, "Serial Number", data.os().serialNumber());
            sb.append("\n");
        }

        if (data.cpu() != null) {
            sb.append("--- CPU ---\n");
            appendField(sb, "Name", data.cpu().name());
            appendField(sb, "Manufacturer", data.cpu().manufacturer());
            appendField(sb, "Architecture", data.cpu().architecture());
            appendField(sb, "Socket", data.cpu().socket());
            sb.append("Cores: ").append(data.cpu().cores()).append("\n");
            sb.append("Threads: ").append(data.cpu().logicalCpus()).append("\n");
            appendField(sb, "Base Clock", data.cpu().formatBaseClock());
            appendField(sb, "Current Clock", data.cpu().formatCurrentClock());
            appendField(sb, "L2 Cache", data.cpu().formatL2Cache());
            appendField(sb, "L3 Cache", data.cpu().formatL3Cache());
            appendField(sb, "Voltage", data.cpu().voltage());
            sb.append("\n");
        }

        if (data.gpu() != null) {
            for (int i = 0; i < data.gpu().size(); i++) {
                GpuInfo gpu = data.gpu().get(i);
                sb.append("--- GPU").append(data.gpu().size() > 1 ? " " + (i + 1) : "").append(" ---\n");
                appendField(sb, "Name", gpu.name());
                appendField(sb, "Manufacturer", gpu.manufacturer());
                appendField(sb, "VRAM", gpu.formatVram());
                appendField(sb, "Memory Type", gpu.memoryType());
                appendField(sb, "Driver Version", gpu.driverVersion());
                appendField(sb, "Driver Date", gpu.driverDate());
                appendField(sb, "Resolution", gpu.resolution());
                sb.append("\n");
            }
        }

        if (data.ram() != null) {
            sb.append("--- RAM ---\n");
            appendField(sb, "Total", data.ram().formatTotal());
            appendField(sb, "Channel", data.ram().channel());
            if (data.ram().sticks() != null) {
                for (int i = 0; i < data.ram().sticks().size(); i++) {
                    RamInfo.RamStick stick = data.ram().sticks().get(i);
                    sb.append("  Slot ").append(i + 1).append(": ").append(stick.formatCapacity())
                            .append(" ").append(nvl(stick.memoryType())).append(" ").append(stick.formatSpeed())
                            .append(" ").append(nvl(stick.manufacturer())).append("\n");
                }
            }
            sb.append("\n");
        }

        if (data.storage() != null) {
            sb.append("--- Storage ---\n");
            if (data.storage().disks() != null) {
                for (int i = 0; i < data.storage().disks().size(); i++) {
                    StorageInfo.Disk disk = data.storage().disks().get(i);
                    sb.append("Disk ").append(i + 1).append(": ").append(nvl(disk.model()))
                            .append(" (").append(disk.formatSize()).append(") ").append(nvl(disk.interfaceType()))
                            .append(" [").append(nvl(disk.mediaType())).append("]\n");
                    if (data.storage().partitions() != null) {
                        final int diskIdx = i;
                        data.storage().partitions().stream()
                                .filter(p -> p.diskIndex() == diskIdx)
                                .forEach(part -> sb.append("  ").append(nvl(part.deviceID())).append(": ").append(nvl(part.volumeName()))
                                        .append(" ").append(nvl(part.fsType()))
                                        .append(" ").append(part.formatSize())
                                        .append(" (").append(String.format("%.1f%% used", part.usagePercent())).append(")\n"));
                    }
                }
            }
            if (data.storage().nvmes() != null && !data.storage().nvmes().isEmpty()) {
                sb.append("NVMe Drives:\n");
                for (StorageInfo.Nvme nvme : data.storage().nvmes()) {
                    sb.append("  Serial: ").append(nvme.serialNumber())
                            .append(" | Media: ").append(nvme.mediaType())
                            .append(" | Bus: ").append(nvme.busType()).append("\n");
                }
            }
            sb.append("\n");
        }

        if (data.motherboard() != null) {
            sb.append("--- Motherboard ---\n");
            appendField(sb, "Manufacturer", data.motherboard().manufacturer());
            appendField(sb, "Model", data.motherboard().model());
            appendField(sb, "Version", data.motherboard().version());
            appendField(sb, "Chipset", data.motherboard().chipset());
            sb.append("\n");
        }

        if (data.bios() != null) {
            sb.append("--- BIOS ---\n");
            appendField(sb, "Manufacturer", data.bios().manufacturer());
            appendField(sb, "Version", data.bios().version());
            appendField(sb, "Release Date", data.bios().releaseDate());
            appendField(sb, "SMBIOS", data.bios().formatSmbios());
            sb.append("\n");
        }

        if (data.networkAdapters() != null && !data.networkAdapters().isEmpty()) {
            sb.append("--- Network Adapters ---\n");
            for (NetworkAdapterInfo na : data.networkAdapters()) {
                sb.append(nvl(na.name())).append(" (").append(nvl(na.status())).append(")\n");
                appendField(sb, "  Speed", na.speed());
                appendField(sb, "  MAC", na.macAddress());
                sb.append("  IP: ").append(na.formatIpAddresses()).append("\n");
                sb.append("  DHCP: ").append(na.dhcpEnabled() ? "Yes" : "No").append("\n\n");
            }
        }

        if (data.audioDevices() != null && !data.audioDevices().isEmpty()) {
            sb.append("--- Audio Devices ---\n");
            for (AudioDeviceInfo audio : data.audioDevices()) {
                sb.append(nvl(audio.name())).append(" - ").append(nvl(audio.manufacturer()))
                        .append(" (").append(nvl(audio.status())).append(")\n");
            }
            sb.append("\n");
        }

        if (data.battery() != null) {
            BatteryInfo batt = data.battery();
            sb.append("--- Battery ---\n");
            appendField(sb, "Name", batt.name());
            sb.append("Charge: ").append(batt.formatChargeLevel()).append("\n");
            appendField(sb, "Status", batt.status());
            appendField(sb, "Chemistry", batt.chemistry());
            sb.append("Remaining: ").append(batt.formatRemainingCapacity()).append("\n\n");
        }

        if (data.temperatures() != null && !data.temperatures().isEmpty()) {
            sb.append("--- Temperatures ---\n");
            for (TemperatureInfo temp : data.temperatures()) {
                sb.append(temp.zoneName()).append(": ").append(temp.formatTemperature()).append("\n");
            }
            sb.append("\n");
        }

        if (data.others() != null && !data.others().isEmpty()) {
            sb.append("--- Other Devices ---\n");
            for (OtherDevice dev : data.others()) {
                sb.append(nvl(dev.name())).append(" [").append(nvl(dev.deviceClass())).append("]\n");
            }
            sb.append("\n");
        }

        if (data.warnings() != null && !data.warnings().isEmpty()) {
            sb.append("--- Warnings ---\n");
            for (String w : data.warnings()) {
                sb.append("! ").append(w).append("\n");
            }
        }

        return sb.toString();
    }

    private static String generateHtmlReport(SystemInfoData data) {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">");
        html.append("<title>System Information - WinZenith</title>");
        html.append("<style>");
        html.append("body{font-family:'Segoe UI',sans-serif;background:#1e1f29;color:#f8f8f2;margin:24px;}");
        html.append("h1{color:#50fa7b;border-bottom:2px solid #44475a;padding-bottom:8px;}");
        html.append("h2{color:#8be9fd;margin-top:24px;}");
        html.append("table{border-collapse:collapse;width:100%;margin:8px 0 20px 0;}");
        html.append("td{padding:6px 12px;border-bottom:1px solid #44475a;}");
        html.append("td:first-child{color:#6272a4;font-weight:bold;width:180px;}");
        html.append("tr:nth-child(even){background:#21222c;}");
        html.append(".warning{color:#ffb86c;background:#3d2e1a;padding:8px;border-radius:4px;margin:8px 0;}");
        html.append("</style></head><body>");
        html.append("<h1>System Information</h1>");

        if (data.os() != null) {
            html.append("<h2>Operating System</h2><table>");
            html.append(row("OS", data.os().name()));
            html.append(row("Version", data.os().version()));
            html.append(row("Build", data.os().buildNumber()));
            html.append(row("Architecture", data.os().architecture()));
            html.append(row("Computer Name", data.os().computerName()));
            html.append(row("Install Date", data.os().installDate()));
            html.append(row("Last Boot", data.os().lastBoot()));
            html.append(row("Serial Number", data.os().serialNumber()));
            html.append("</table>");
        }

        if (data.cpu() != null) {
            html.append("<h2>CPU</h2><table>");
            html.append(row("Name", data.cpu().name()));
            html.append(row("Manufacturer", data.cpu().manufacturer()));
            html.append(row("Architecture", data.cpu().architecture()));
            html.append(row("Socket", data.cpu().socket()));
            html.append(row("Cores", String.valueOf(data.cpu().cores())));
            html.append(row("Threads", String.valueOf(data.cpu().logicalCpus())));
            html.append(row("Base Clock", data.cpu().formatBaseClock()));
            html.append(row("Current Clock", data.cpu().formatCurrentClock()));
            html.append(row("L2 Cache", data.cpu().formatL2Cache()));
            html.append(row("L3 Cache", data.cpu().formatL3Cache()));
            html.append(row("Voltage", data.cpu().voltage()));
            html.append("</table>");
        }

        if (data.gpu() != null) {
            for (int i = 0; i < data.gpu().size(); i++) {
                GpuInfo gpu = data.gpu().get(i);
                html.append("<h2>GPU").append(data.gpu().size() > 1 ? " " + (i + 1) : "").append("</h2><table>");
                html.append(row("Name", gpu.name()));
                html.append(row("Manufacturer", gpu.manufacturer()));
                html.append(row("VRAM", gpu.formatVram()));
                html.append(row("Memory Type", gpu.memoryType()));
                html.append(row("Driver Version", gpu.driverVersion()));
                html.append(row("Driver Date", gpu.driverDate()));
                html.append(row("Resolution", gpu.resolution()));
                html.append("</table>");
            }
        }

        if (data.ram() != null) {
            html.append("<h2>RAM</h2><table>");
            html.append(row("Total", data.ram().formatTotal()));
            html.append(row("Channel", data.ram().channel()));
            html.append("</table>");
            if (data.ram().sticks() != null) {
                for (int i = 0; i < data.ram().sticks().size(); i++) {
                    RamInfo.RamStick stick = data.ram().sticks().get(i);
                    html.append("<h3>Slot ").append(i + 1).append("</h3><table>");
                    html.append(row("Capacity", stick.formatCapacity()));
                    html.append(row("Type", stick.memoryType()));
                    html.append(row("Speed", stick.formatSpeed()));
                    html.append(row("Manufacturer", stick.manufacturer()));
                    html.append(row("Part Number", stick.partNumber()));
                    html.append("</table>");
                }
            }
        }

        if (data.storage() != null && data.storage().disks() != null) {
            html.append("<h2>Storage</h2>");
            for (int i = 0; i < data.storage().disks().size(); i++) {
                StorageInfo.Disk disk = data.storage().disks().get(i);
                html.append("<h3>Disk ").append(i + 1).append("</h3><table>");
                html.append(row("Model", disk.model()));
                html.append(row("Manufacturer", disk.manufacturer()));
                html.append(row("Size", disk.formatSize()));
                html.append(row("Media Type", disk.mediaType()));
                html.append(row("Interface", disk.interfaceType()));
                html.append(row("Serial", disk.serialNumber()));
                html.append(row("Partitions", String.valueOf(disk.partitions())));
                html.append("</table>");

                if (data.storage().partitions() != null) {
                    final int diskIdx = i;
                    List<StorageInfo.Partition> diskParts = data.storage().partitions().stream()
                            .filter(p -> p.diskIndex() == diskIdx)
                            .toList();
                    if (!diskParts.isEmpty()) {
                        html.append("<h4>Partitions on Disk ").append(i + 1).append("</h4><table>");
                        for (StorageInfo.Partition part : diskParts) {
                            html.append("<tr><td>").append(escapeHtml(part.deviceID())).append("</td><td>")
                                    .append(escapeHtml(part.volumeName())).append(" | ")
                                    .append(escapeHtml(part.fsType())).append(" | ")
                                    .append(part.formatSize()).append(" | ")
                                    .append(String.format("%.1f%% used", part.usagePercent()))
                                    .append("</td></tr>");
                        }
                        html.append("</table>");
                    }
                }
            }

            List<StorageInfo.Partition> unassigned = data.storage().partitions() != null
                    ? data.storage().partitions().stream().filter(p -> p.diskIndex() < 0).toList()
                    : List.of();
            if (!unassigned.isEmpty()) {
                html.append("<h3>Other Partitions</h3><table>");
                for (StorageInfo.Partition part : unassigned) {
                    html.append("<tr><td>").append(escapeHtml(part.deviceID())).append("</td><td>")
                            .append(escapeHtml(part.volumeName())).append(" | ")
                            .append(escapeHtml(part.fsType())).append(" | ")
                            .append(part.formatSize()).append(" | ")
                            .append(String.format("%.1f%% used", part.usagePercent()))
                            .append("</td></tr>");
                }
                html.append("</table>");
            }
        }

        if (data.storage() != null && data.storage().nvmes() != null && !data.storage().nvmes().isEmpty()) {
            html.append("<h2>NVMe Drives</h2><table>");
            for (StorageInfo.Nvme nvme : data.storage().nvmes()) {
                html.append(row("Serial Number", nvme.serialNumber()));
                html.append(row("Media Type", nvme.mediaType()));
                html.append(row("Bus Type", nvme.busType()));
            }
            html.append("</table>");
        }

        if (data.motherboard() != null) {
            html.append("<h2>Motherboard</h2><table>");
            html.append(row("Manufacturer", data.motherboard().manufacturer()));
            html.append(row("Model", data.motherboard().model()));
            html.append(row("Version", data.motherboard().version()));
            html.append(row("Chipset", data.motherboard().chipset()));
            html.append("</table>");
        }

        if (data.bios() != null) {
            html.append("<h2>BIOS</h2><table>");
            html.append(row("Manufacturer", data.bios().manufacturer()));
            html.append(row("Version", data.bios().version()));
            html.append(row("Release Date", data.bios().releaseDate()));
            html.append(row("SMBIOS", data.bios().formatSmbios()));
            html.append("</table>");
        }

        if (data.networkAdapters() != null && !data.networkAdapters().isEmpty()) {
            html.append("<h2>Network Adapters</h2>");
            for (NetworkAdapterInfo na : data.networkAdapters()) {
                html.append("<h3>").append(escapeHtml(na.name())).append("</h3><table>");
                html.append(row("Name", na.name()));
                html.append(row("Manufacturer", na.manufacturer()));
                html.append(row("Type", na.adapterType()));
                html.append(row("Speed", na.speed()));
                html.append(row("MAC Address", na.macAddress()));
                html.append(row("IP Addresses", na.formatIpAddresses()));
                html.append(row("DHCP", na.dhcpEnabled() ? "Enabled" : "Disabled"));
                html.append(row("Status", na.status()));
                html.append("</table>");
            }
        }

        if (data.audioDevices() != null && !data.audioDevices().isEmpty()) {
            html.append("<h2>Audio Devices</h2><table>");
            for (AudioDeviceInfo audio : data.audioDevices()) {
                html.append(row("Name", audio.name()));
                html.append(row("Manufacturer", audio.manufacturer()));
                html.append(row("Status", audio.status()));
            }
            html.append("</table>");
        }

        if (data.battery() != null) {
            html.append("<h2>Battery</h2><table>");
            html.append(row("Name", data.battery().name()));
            html.append(row("Charge Level", data.battery().formatChargeLevel()));
            html.append(row("Status", data.battery().status()));
            html.append(row("Chemistry", data.battery().chemistry()));
            html.append(row("Remaining", data.battery().formatRemainingCapacity()));
            html.append("</table>");
        }

        if (data.temperatures() != null && !data.temperatures().isEmpty()) {
            html.append("<h2>Temperatures</h2><table>");
            for (TemperatureInfo temp : data.temperatures()) {
                html.append(row(temp.zoneName(), temp.formatTemperature()));
            }
            html.append("</table>");
        }

        if (data.warnings() != null && !data.warnings().isEmpty()) {
            html.append("<h2>Warnings</h2>");
            for (String w : data.warnings()) {
                html.append("<div class=\"warning\">").append(escapeHtml(w)).append("</div>");
            }
        }

        html.append("</body></html>");
        return html.toString();
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    private static void appendField(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(label).append(": ").append(value).append("\n");
        }
    }

    private static String row(String label, String value) {
        return "<tr><td>" + escapeHtml(label) + "</td><td>" + escapeHtml(value != null ? value : "") + "</td></tr>";
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public void dispose() {
        executor.shutdownNow();
    }
}
