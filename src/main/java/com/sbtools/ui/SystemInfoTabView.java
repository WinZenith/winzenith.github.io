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
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.RowConstraints;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

public class SystemInfoTabView extends BorderPane {

    private final SystemInfoService service = new SystemInfoService();
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;

    private final Label statusLabel = new Label("Click Load to query system information.");
    private final Button loadButton = new Button("Load System Info");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final TabPane tabPane = new TabPane();

    public SystemInfoTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        spinner.setPrefSize(24, 24);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setVisible(false);

        loadButton.setOnAction(e -> loadInfo());

        HBox top = new HBox(12, loadButton, spinner, statusLabel);
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
        spinner.setVisible(true);
        statusLabel.setText("Querying system information\u2026");

        new Thread(() -> {
            try {
                SystemInfoData data = service.gatherSystemInfo();
                Platform.runLater(() -> {
                    buildTabs(data);
                    statusLabel.setText("System information loaded.");
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
                });
            }
        }, "system-info").start();
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
                UILabel header = UILabel.sectionTitle("GPU " + (i + 1));
                container.getChildren().add(header);
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
                UILabel slotHeader = UILabel.sectionTitle("Slot " + (i + 1));
                container.getChildren().add(slotHeader);
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
                UILabel header = UILabel.sectionTitle("Disk " + (i + 1));
                container.getChildren().add(header);
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
            }
        }

        if (storage.partitions() != null && !storage.partitions().isEmpty()) {
            UILabel partHeader = UILabel.sectionTitle("Logical Partitions");
            container.getChildren().add(partHeader);
            for (StorageInfo.Partition part : storage.partitions()) {
                GridPane grid = createInfoGrid();
                int row = 0;
                row = addRow(grid, row, "Drive", part.deviceID());
                row = addRow(grid, row, "Volume Name", part.volumeName());
                row = addRow(grid, row, "File System", part.fsType());
                row = addRow(grid, row, "Total Size", part.formatSize());
                row = addRow(grid, row, "Used", part.formatUsed());
                row = addRow(grid, row, "Free", part.formatFree());
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
            UILabel mbHeader = UILabel.sectionTitle("Motherboard");
            container.getChildren().add(mbHeader);
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
            UILabel biosHeader = UILabel.sectionTitle("BIOS");
            container.getChildren().add(biosHeader);
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

        // Group devices by deviceClass, sorted alphabetically
        java.util.TreeMap<String, List<OtherDevice>> grouped = new java.util.TreeMap<>();
        for (OtherDevice dev : devices) {
            String cls = (dev.deviceClass() != null && !dev.deviceClass().isBlank())
                    ? dev.deviceClass() : "Other";
            grouped.computeIfAbsent(cls, k -> new ArrayList<>()).add(dev);
        }

        for (var entry : grouped.entrySet()) {
            String category = entry.getKey();
            List<OtherDevice> devs = entry.getValue();

            UILabel header = UILabel.sectionTitle(category + " (" + devs.size() + ")");
            container.getChildren().add(header);

            GridPane grid = new GridPane();
            grid.setHgap(0);
            grid.setVgap(0);
            grid.setPadding(new Insets(0));
            ColumnConstraints nameCol = new ColumnConstraints();
            nameCol.setPrefWidth(360);
            nameCol.setMinWidth(200);
            nameCol.setHgrow(Priority.ALWAYS);
            ColumnConstraints mfrCol = new ColumnConstraints();
            mfrCol.setMinWidth(150);
            mfrCol.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().addAll(nameCol, mfrCol);
            int row = 0;
            for (OtherDevice dev : devs) {
                row = addRow(grid, row, dev.name(),
                        (dev.manufacturer() != null && !dev.manufacturer().isBlank())
                                ? dev.manufacturer() : dev.status());
            }
            container.getChildren().add(wrapGrid(grid));
        }

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
        keyLabel.setStyle("-fx-text-fill: #6272a4; -fx-font-weight: bold; -fx-padding: 8 12 8 16;");
        keyLabel.setMaxWidth(Double.MAX_VALUE);

        Label valueLabel = new Label(value);
        valueLabel.setStyle("-fx-text-fill: #f8f8f2; -fx-padding: 8 12 8 16;");
        valueLabel.setWrapText(true);
        valueLabel.setMaxWidth(Double.MAX_VALUE);

        RowConstraints rc = new RowConstraints();
        rc.setMinHeight(20);
        grid.getRowConstraints().add(rc);

        grid.add(keyLabel, 0, row);
        grid.add(valueLabel, 1, row);

        // Alternate row background
        if (row % 2 == 0) {
            keyLabel.setStyle(keyLabel.getStyle() + " -fx-background-color: #21222c;");
            valueLabel.setStyle(valueLabel.getStyle() + " -fx-background-color: #21222c;");
        }

        return row + 1;
    }

    private static VBox wrapGrid(GridPane grid) {
        VBox wrapper = new VBox(grid);
        wrapper.setStyle("-fx-background-color: #1e1f29; -fx-border-color: #44475a; -fx-border-width: 1; -fx-background-radius: 4; -fx-border-radius: 4;");
        return wrapper;
    }

    /**
     * Simple wrapper that puts a VBox inside a ScrollPane for overflow.
     */
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
