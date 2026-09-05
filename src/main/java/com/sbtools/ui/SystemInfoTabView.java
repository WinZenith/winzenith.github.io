package com.sbtools.ui;

import com.sbtools.systeminfo.AudioDeviceInfo;
import com.sbtools.systeminfo.BatteryInfo;
import com.sbtools.systeminfo.BiosInfo;
import com.sbtools.systeminfo.CpuInfo;
import com.sbtools.systeminfo.GpuInfo;
import com.sbtools.systeminfo.MonitorInfo;
import com.sbtools.systeminfo.MotherboardInfo;
import com.sbtools.systeminfo.NetworkAdapterInfo;
import com.sbtools.systeminfo.OtherDevice;
import com.sbtools.systeminfo.OsInfo;
import com.sbtools.systeminfo.PrinterInfo;
import com.sbtools.systeminfo.RamInfo;
import com.sbtools.systeminfo.StorageInfo;
import com.sbtools.systeminfo.SystemInfoData;
import com.sbtools.systeminfo.SystemInfoReportGenerator;
import com.sbtools.systeminfo.SystemInfoService;
import com.sbtools.systeminfo.TemperatureInfo;
import com.sbtools.systeminfo.UsbDeviceInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.DataSizeFormatter;
import com.sbtools.util.JsonMapper;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
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
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
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
import java.util.Map;
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
    private final Label adminWarningLabel = new Label("Not running as admin. Some data (temperatures, NVMe) may be unavailable.");
    private final Button loadButton = new Button("Load System Info");
    private final Button refreshButton = new Button("Refresh");
    private final Button cancelButton = new Button("Cancel");
    private final Button exportButton = new Button("Export...");
    private final Button copyButton = new Button("Copy All");
    private final Button copyTabButton = new Button("Copy Tab");
    private final ProgressIndicator spinner = new ProgressIndicator();
    private final ProgressBar progressBar = new ProgressBar(0);
    private final TabPane tabPane = new TabPane();

    private SystemInfoData currentData;
    private final java.util.concurrent.atomic.AtomicBoolean isLoading = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile java.util.concurrent.Future<?> currentTask;
    private final java.util.concurrent.atomic.AtomicBoolean cancellationToken = new java.util.concurrent.atomic.AtomicBoolean(false);
    // Exactly-once guard for the ref-counted global busy flag: loadInfo() acquires
    // once, and both the worker-thread finally and the FX-thread cleanup must not
    // each decrement (previously 1 acquire / 2 releases corrupted BusyProperty's
    // counter and could clear another tab's still-running busy state).
    private final java.util.concurrent.atomic.AtomicBoolean busyHeld = new java.util.concurrent.atomic.AtomicBoolean(false);

    public SystemInfoTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        spinner.setPrefSize(24, 24);
        spinner.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
        spinner.setVisible(false);

        progressBar.setPrefWidth(200);
        progressBar.setVisible(false);

        loadButton.setOnAction(e -> loadInfo());
        refreshButton.setOnAction(e -> { service.invalidateCache(); loadInfo(false); });
        refreshButton.setDisable(true);
        cancelButton.setDisable(true);
        cancelButton.setOnAction(e -> cancelLoading());
        exportButton.setDisable(true);
        exportButton.setOnAction(e -> exportToFile());
        copyButton.setDisable(true);
        copyButton.setOnAction(e -> copyToClipboard());
        copyTabButton.setDisable(true);
        copyTabButton.setTooltip(new javafx.scene.control.Tooltip("Copy the currently visible tab as plain text"));
        copyTabButton.setOnAction(e -> copyVisibleTab());

        HBox top = new HBox(12, loadButton, refreshButton, cancelButton, exportButton, copyButton, copyTabButton, spinner, progressBar, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        adminWarningLabel.getStyleClass().addAll("label", "text-muted");
        adminWarningLabel.setStyle("-fx-padding: 6 16; -fx-background-color: #3d2e1a; -fx-text-fill: #ffb86c; -fx-font-size: 11px;");
        adminWarningLabel.setWrapText(true);
        adminWarningLabel.setMaxWidth(Double.MAX_VALUE);
        adminWarningLabel.setVisible(false);
        adminWarningLabel.setManaged(false);

        VBox topContainer = new VBox(top, adminWarningLabel);
        topContainer.setSpacing(0);

        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabPane.setPadding(new Insets(8, 0, 0, 0));

        setTop(topContainer);
        setCenter(tabPane);

        if (!AppPaths.isWindows()) {
            statusLabel.setText("System information is only available on Windows.");
            loadButton.setDisable(true);
        } else {
            // AdminCheck spawns powershell.exe (up to ~5s) â€” never block the FX
            // thread for it. Resolve off-FX and publish the banner on FX.
            refreshAdminWarningAsync();
            // Stale-while-revalidate (v3.1): render last snapshot instantly from
            // portable disk cache so the tab is never blank; explicit Load/Refresh
            // still performs a fresh parallel query. Snapshot model preserved.
            tryStaleWhileRevalidate();
        }
    }

    private void tryStaleWhileRevalidate() {
        try {
            SystemInfoData cached = service.tryLoadCachedSnapshot();
            if (cached != null) {
                currentData = cached;
                buildTabs(cached);
                String when = cached.collectedAt() != null && !cached.collectedAt().isBlank()
                        ? " (collected " + cached.collectedAt() + ")" : "";
                statusLabel.setText("Loaded cached snapshot" + when + ". Click Refresh for latest.");
                refreshButton.setDisable(false);
                exportButton.setDisable(false);
                copyButton.setDisable(false);
                copyTabButton.setDisable(false);
            }
        } catch (Exception ignored) {}
    }

    private void loadInfo() {
        loadInfo(false);
    }

    private void loadInfo(boolean forceRefresh) {
        if (!isLoading.compareAndSet(false, true)) return;
        // Decouple from global busy but also set it for outer UI dimming
        busy.set(true);
        busyHeld.set(true);
        refreshAdminWarningAsync();
        cancellationToken.set(false);
        loadButton.setDisable(true);
        refreshButton.setDisable(true);
        cancelButton.setDisable(false);
        exportButton.setDisable(true);
        copyButton.setDisable(true);
        copyTabButton.setDisable(true);
        spinner.setVisible(true);
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        statusLabel.setText("Querying system information\u2026");

        currentTask = executor.submit(() -> {
            try {
                if (cancellationToken.get()) throw new InterruptedException("Cancelled");
                SystemInfoData data = service.gatherSystemInfo(
                        (section, progress) -> Platform.runLater(() -> {
                            if ("cached".equals(section)) {
                                statusLabel.setText("Loaded from cache.");
                            } else {
                                statusLabel.setText("Loading " + section + "\u2026");
                            }
                            progressBar.setProgress(progress);
                        }),
                        forceRefresh,
                        cancellationToken
                );
                if (cancellationToken.get()) throw new InterruptedException("Cancelled");
                Platform.runLater(() -> {
                    try {
                        currentData = data;
                        buildTabs(data);
                    // B3 fix: surface empty-state clearly instead of silent blank
                    // Note: buildTabs now inserts placeholder when data is empty, so tabPane is never empty after.
                    // Check data content directly for correct status message.
                    if (isDataMostlyEmpty(data)) {
                        statusLabel.setText("No system information available. Check Warnings tab or try Refresh as Administrator.");
                    } else if (tabPane.getTabs().isEmpty()) {
                        statusLabel.setText("No system information available. Check Warnings tab or try Refresh as Administrator.");
                    } else if (hasAnyWarningsOrPartial(data)) {
                        statusLabel.setText("System information loaded (partial â€” some data unavailable).");
                    } else {
                        statusLabel.setText("System information loaded.");
                    }
                    progressBar.setProgress(1);
                    refreshButton.setDisable(false);
                    exportButton.setDisable(false);
                    copyButton.setDisable(false);
                    copyTabButton.setDisable(false);
                    } catch (Exception renderEx) {
                        // buildTabs runs on the FX thread, outside the worker try/catch:
                        // never let a single bad section kill the FX update silently.
                        AppLogger.error("Failed to render system info", renderEx);
                        statusLabel.setText("Failed to display system information: " + renderEx.getMessage());
                    }
                });
            } catch (InterruptedException ce) {
                Thread.currentThread().interrupt();
                Platform.runLater(() -> statusLabel.setText("Cancelled."));
            } catch (Exception ex) {
                if (cancellationToken.get()) {
                    Platform.runLater(() -> statusLabel.setText("Cancelled."));
                } else {
                    AppLogger.error("Failed to load system info", ex);
                    Platform.runLater(() -> {
                        statusLabel.setText("Failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Failed to load system information:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                // Ensure loading flag is cleared even if FX toolkit is shutting down and runLater never executes.
                // Busy is released exactly once via busyHeld (ref-counted global flag).
                isLoading.set(false);
                releaseBusyOnce();
                Platform.runLater(() -> {
                    releaseBusyOnce();
                    isLoading.set(false);
                    loadButton.setDisable(false);
                    cancelButton.setDisable(true);
                    spinner.setVisible(false);
                    progressBar.setVisible(false);
                    if (currentData != null) {
                        refreshButton.setDisable(false);
                        exportButton.setDisable(false);
                        copyButton.setDisable(false);
                        copyTabButton.setDisable(false);
                    }
                });
            }
        });
    }

    private void cancelLoading() {
        cancellationToken.set(true);
        java.util.concurrent.Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        statusLabel.setText("Cancelling\u2026");
        cancelButton.setDisable(true);
    }

    private void releaseBusyOnce() {
        if (busyHeld.compareAndSet(true, false)) {
            try { busy.set(false); } catch (Exception ignored) {}
        }
    }

    /**
     * Queries elevation off the FX thread (AdminCheck spawns powershell.exe and
     * can block for seconds) and publishes the banner back on the FX thread.
     */
    private void refreshAdminWarningAsync() {
        if (!AppPaths.isWindows()) return;
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> {
                    try { return adminCheck.getAsBoolean(); } catch (Exception ignored) { return false; }
                })
                .thenAcceptAsync(isAdmin -> {
                    boolean show = !isAdmin;
                    adminWarningLabel.setVisible(show);
                    adminWarningLabel.setManaged(show);
                }, Platform::runLater);
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
        if (data.usbDevices() != null && !data.usbDevices().isEmpty()) {
            tabPane.getTabs().add(buildUsbTab(data.usbDevices()));
        }
        if (data.monitors() != null && !data.monitors().isEmpty()) {
            tabPane.getTabs().add(buildMonitorTab(data.monitors()));
        }
        if (data.printers() != null && !data.printers().isEmpty()) {
            tabPane.getTabs().add(buildPrinterTab(data.printers()));
        }

        if (data.warnings() != null && !data.warnings().isEmpty()) {
            tabPane.getTabs().add(buildWarningsTab(data));
        } else if (data.timings() != null && !data.timings().isEmpty()) {
            // v3.1: timings alone are worth a Diagnostics tab even without warnings
            tabPane.getTabs().add(buildWarningsTab(data));
        }

        // B3 fix: never leave tabPane empty â€” show placeholder so user understands failure vs. blank
        if (tabPane.getTabs().isEmpty()) {
            tabPane.getTabs().add(buildEmptyStateTab(data));
        }
    }

    private boolean isDataMostlyEmpty(SystemInfoData data) {
        if (data == null) return true;
        boolean hasCpu = data.cpu() != null && !isBlank(data.cpu().name());
        boolean hasOs = data.os() != null && !isBlank(data.os().name());
        boolean hasRam = data.ram() != null && data.ram().totalBytes() > 0;
        boolean hasStorage = data.storage() != null && data.storage().disks() != null && !data.storage().disks().isEmpty();
        boolean hasGpu = data.gpu() != null && !data.gpu().isEmpty();
        // If none of the primary sections have real data, consider mostly empty
        return !hasCpu && !hasOs && !hasRam && !hasStorage && !hasGpu;
    }

    private boolean hasAnyWarningsOrPartial(SystemInfoData data) {
        if (data == null) return true;
        if (data.warnings() != null && !data.warnings().isEmpty()) return true;
        // Partial if any primary section is missing while at least one exists
        boolean hasCpu = data.cpu() != null && !isBlank(data.cpu().name());
        boolean hasOs = data.os() != null && !isBlank(data.os().name());
        boolean hasRam = data.ram() != null && data.ram().totalBytes() > 0;
        boolean hasStorage = data.storage() != null && data.storage().disks() != null && !data.storage().disks().isEmpty();
        // If we have OS but no CPU/RAM/Storage, or vice versa, consider partial
        int present = 0;
        if (hasCpu) present++;
        if (hasOs) present++;
        if (hasRam) present++;
        if (hasStorage) present++;
        return present > 0 && present < 4;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private Tab buildEmptyStateTab(SystemInfoData data) {
        VBox box = new VBox(12);
        box.setPadding(new Insets(24));
        box.setAlignment(Pos.CENTER_LEFT);
        Label title = new Label("No system information available");
        title.getStyleClass().addAll("label", "large");
        title.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label msg = new Label(
                "WinZenith queried WMI but received no usable data.\n" +
                "â€¢ Try Refresh or restart as Administrator (some data requires elevation).\n" +
                "â€¢ Check the Warnings tab for diagnostics.\n" +
                "â€¢ See logs/system-info-last.json for raw output.");
        msg.setWrapText(true);
        msg.getStyleClass().addAll("label", "text-muted");
        box.getChildren().addAll(title, msg);
        if (data != null && data.warnings() != null && !data.warnings().isEmpty()) {
            Label wTitle = UILabel.sectionTitle("Warnings");
            box.getChildren().add(wTitle);
            for (String w : data.warnings()) {
                Label wl = new Label("â€¢ " + w);
                wl.setWrapText(true);
                wl.getStyleClass().addAll("label", "warning");
                wl.setStyle("-fx-padding: 4 8; -fx-background-color: #3d2e1a; -fx-background-radius: 4; -fx-border-color: #ffb86c; -fx-border-radius: 4;");
                wl.setMaxWidth(Double.MAX_VALUE);
                box.getChildren().add(wl);
            }
        }
        Button retry = new Button("Refresh as Administrator");
        retry.setOnAction(e -> { service.invalidateCache(); loadInfo(true); });
        box.getChildren().add(retry);
        ScrollableContainer scroll = new ScrollableContainer(box);
        Tab tab = new Tab("Overview");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ CPU â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        if (row == 0) {
            return new Tab("CPU", placeholderCard("No CPU data available (WMI query returned empty). Try running as Administrator."));
        }
        return new Tab("CPU", wrapGrid(grid));
    }

    // â”€â”€ GPU â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            if (row == 0) {
                container.getChildren().add(placeholderCard("No GPU data available for this device."));
            } else {
                container.getChildren().add(wrapGrid(grid));
            }
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("GPU");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ RAM â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildRamTab(RamInfo ram) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        GridPane summary = createInfoGrid();
        int row = 0;
        row = addRow(summary, row, "Total Memory", ram.formatTotal());
        row = addRow(summary, row, "Channel", ram.channel());
        container.getChildren().add(wrapGridOrPlaceholder(summary, row, "No RAM summary available."));

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
                container.getChildren().add(wrapGridOrPlaceholder(stickGrid, r, "No data for this slot."));
            }
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("RAM");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ OS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
        row = addRow(grid, row, "BIOS Serial Number", os.serialNumber());
        if (row == 0) {
            return new Tab("OS", placeholderCard("No OS data available."));
        }
        return new Tab("OS", wrapGrid(grid));
    }

    // â”€â”€ Storage â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

    // â”€â”€ Motherboard / BIOS â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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
            container.getChildren().add(wrapGridOrPlaceholder(grid, row, "No motherboard data available."));
        }

        if (bios != null) {
            container.getChildren().add(UILabel.sectionTitle("BIOS"));
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Manufacturer", bios.manufacturer());
            row = addRow(grid, row, "Version", bios.version());
            row = addRow(grid, row, "Release Date", bios.releaseDate());
            row = addRow(grid, row, "SMBIOS Version", bios.formatSmbios());
            container.getChildren().add(wrapGridOrPlaceholder(grid, row, "No BIOS data available."));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Motherboard");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Others â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

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

        VBox leftPanel = new VBox(8);
        leftPanel.setPadding(new Insets(0, 0, 0, 0));
        Label selectLabel = new Label("Select a category:");
        selectLabel.getStyleClass().addAll("label", "text-muted");
        leftPanel.getChildren().add(selectLabel);
        leftPanel.getChildren().add(categoryList);
        VBox.setVgrow(categoryList, Priority.ALWAYS);
        categoryList.setPrefHeight(320);

        VBox deviceList = new VBox(8);
        deviceList.setPadding(new Insets(0, 8, 0, 8));

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
                // B4 fix: ensure every device renders even when manufacturer/status blank
                String devName = dev.name();
                if (devName == null || devName.isBlank()) {
                    devName = dev.deviceId() != null && !dev.deviceId().isBlank() ? dev.deviceId() : "Unknown Device";
                }
                String rawVal = (dev.manufacturer() != null && !dev.manufacturer().isBlank())
                        ? dev.manufacturer()
                        : (dev.status() != null && !dev.status().isBlank() ? dev.status() : "");
                String val = rawVal.isBlank() ? "â€”" : rawVal;
                r = addRow(grid, r, devName, val);
            }
            if (r == 0) {
                Label empty = new Label("No details available");
                empty.getStyleClass().addAll("label", "text-muted");
                deviceList.getChildren().add(empty);
            } else {
                javafx.scene.control.ScrollPane inner = new javafx.scene.control.ScrollPane(wrapGrid(grid));
                inner.setFitToWidth(true);
                inner.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
                deviceList.getChildren().add(inner);
                VBox.setVgrow(inner, Priority.ALWAYS);
            }
        });

        javafx.scene.control.SplitPane splitPane = new javafx.scene.control.SplitPane();
        splitPane.getItems().addAll(leftPanel, deviceList);
        splitPane.setDividerPositions(0.35);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        container.getChildren().addAll(searchField, splitPane);
        VBox.setVgrow(splitPane, Priority.ALWAYS);

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Others");
        tab.setContent(scroll);
        // auto-select first category if available
        if (!filteredCategories.isEmpty()) {
            categoryList.getSelectionModel().select(0);
        }
        return tab;
    }

    // â”€â”€ Overview â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildOverviewTab(SystemInfoData data) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        // Summary cards - FlowPane wrapping for responsive layout
        javafx.scene.layout.FlowPane cards = new javafx.scene.layout.FlowPane();
        cards.setHgap(12);
        cards.setVgap(12);
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

    // â”€â”€ Network â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // v3.1: virtualized TableView + search + detail pane. Previous per-adapter
    // VBox cards created N GridPanes and stalled on hosts with many virtual
    // adapters; TableView renders only visible rows and supports sorting.

    private Tab buildNetworkTab(List<NetworkAdapterInfo> adapters) {
        TextField search = new TextField();
        search.setPromptText("Search adapters\u2026");
        search.getStyleClass().add("sysinfo-search");

        ObservableList<NetworkAdapterInfo> base = FXCollections.observableArrayList(adapters);
        FilteredList<NetworkAdapterInfo> filtered = new FilteredList<>(base, p -> true);
        search.textProperty().addListener((obs, o, n) -> {
            String lower = n == null ? "" : n.toLowerCase();
            filtered.setPredicate(a -> lower.isEmpty()
                    || containsLower(a.name(), lower)
                    || containsLower(a.manufacturer(), lower)
                    || containsLower(a.macAddress(), lower)
                    || containsLower(a.formatIpAddresses(), lower)
                    || containsLower(a.status(), lower));
        });
        SortedList<NetworkAdapterInfo> sorted = new SortedList<>(filtered);

        TableView<NetworkAdapterInfo> table = new TableView<>(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(280);
        table.getColumns().addAll(
                tableColumn("Name", 220, a -> nvl(a.name())),
                tableColumn("Status", 110, a -> nvl(a.status())),
                tableColumn("Speed", 90, a -> nvl(a.speed())),
                tableColumn("MAC", 130, a -> nvl(a.macAddress())),
                tableColumn("IP", 180, NetworkAdapterInfo::formatIpAddresses));
        sorted.comparatorProperty().bind(table.comparatorProperty());

        VBox detailBox = new VBox(8);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            detailBox.getChildren().clear();
            if (sel == null) {
                return;
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", sel.name());
            row = addRow(grid, row, "Manufacturer", sel.manufacturer());
            row = addRow(grid, row, "Type", sel.adapterType());
            row = addRow(grid, row, "Speed", sel.speed());
            row = addRow(grid, row, "MAC Address", sel.macAddress());
            row = addRow(grid, row, "IP Addresses", sel.formatIpAddresses());
            row = addRow(grid, row, "DHCP", sel.dhcpEnabled() ? "Enabled" : "Disabled");
            row = addRow(grid, row, "Status", sel.status());
            detailBox.getChildren().add(wrapGridOrPlaceholder(grid, row, "No details available."));
        });
        if (!sorted.isEmpty()) {
            table.getSelectionModel().select(0);
        }

        VBox container = new VBox(8, search, table, detailBox);
        container.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Network");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Audio â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildAudioTab(List<AudioDeviceInfo> devices) {
        TextField search = new TextField();
        search.setPromptText("Search audio devices\u2026");
        search.getStyleClass().add("sysinfo-search");

        ObservableList<AudioDeviceInfo> base = FXCollections.observableArrayList(devices);
        FilteredList<AudioDeviceInfo> filtered = new FilteredList<>(base, p -> true);
        search.textProperty().addListener((obs, o, n) -> {
            String lower = n == null ? "" : n.toLowerCase();
            filtered.setPredicate(d -> lower.isEmpty()
                    || containsLower(d.name(), lower)
                    || containsLower(d.manufacturer(), lower)
                    || containsLower(d.status(), lower));
        });
        SortedList<AudioDeviceInfo> sorted = new SortedList<>(filtered);

        TableView<AudioDeviceInfo> table = new TableView<>(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(240);
        table.getColumns().addAll(
                tableColumn("Name", 260, a -> nvl(a.name())),
                tableColumn("Manufacturer", 180, a -> nvl(a.manufacturer())),
                tableColumn("Status", 120, a -> nvl(a.status())));
        sorted.comparatorProperty().bind(table.comparatorProperty());

        VBox detailBox = new VBox(8);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            detailBox.getChildren().clear();
            if (sel == null) {
                return;
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", sel.name());
            row = addRow(grid, row, "Manufacturer", sel.manufacturer());
            row = addRow(grid, row, "Status", sel.status());
            row = addRow(grid, row, "Device ID", sel.deviceId());
            detailBox.getChildren().add(wrapGridOrPlaceholder(grid, row, "No details available."));
        });
        if (!sorted.isEmpty()) {
            table.getSelectionModel().select(0);
        }

        VBox container = new VBox(8, search, table, detailBox);
        container.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Audio");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ USB Devices â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildUsbTab(List<UsbDeviceInfo> devices) {
        TextField search = new TextField();
        search.setPromptText("Search USB devices\u2026");
        search.getStyleClass().add("sysinfo-search");

        ObservableList<UsbDeviceInfo> base = FXCollections.observableArrayList(devices);
        FilteredList<UsbDeviceInfo> filtered = new FilteredList<>(base, p -> true);
        search.textProperty().addListener((obs, o, n) -> {
            String lower = n == null ? "" : n.toLowerCase();
            filtered.setPredicate(d -> lower.isEmpty()
                    || containsLower(d.name(), lower)
                    || containsLower(d.manufacturer(), lower)
                    || containsLower(d.deviceId(), lower)
                    || containsLower(d.status(), lower));
        });
        SortedList<UsbDeviceInfo> sorted = new SortedList<>(filtered);

        TableView<UsbDeviceInfo> table = new TableView<>(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(280);
        table.getColumns().addAll(
                tableColumn("Name", 240, a -> nvl(a.name())),
                tableColumn("Manufacturer", 160, a -> nvl(a.manufacturer())),
                tableColumn("Status", 100, a -> nvl(a.status())));
        sorted.comparatorProperty().bind(table.comparatorProperty());

        VBox detailBox = new VBox(8);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            detailBox.getChildren().clear();
            if (sel == null) {
                return;
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", sel.name());
            row = addRow(grid, row, "Manufacturer", sel.manufacturer());
            row = addRow(grid, row, "Device ID", sel.deviceId());
            row = addRow(grid, row, "Status", sel.status());
            detailBox.getChildren().add(wrapGridOrPlaceholder(grid, row, "No details available."));
        });
        if (!sorted.isEmpty()) {
            table.getSelectionModel().select(0);
        }

        VBox container = new VBox(8, search, table, detailBox);
        container.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("USB Devices");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Monitors â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildMonitorTab(List<MonitorInfo> monitors) {
        TextField search = new TextField();
        search.setPromptText("Search monitors\u2026");
        search.getStyleClass().add("sysinfo-search");

        ObservableList<MonitorInfo> base = FXCollections.observableArrayList(monitors);
        FilteredList<MonitorInfo> filtered = new FilteredList<>(base, p -> true);
        search.textProperty().addListener((obs, o, n) -> {
            String lower = n == null ? "" : n.toLowerCase();
            filtered.setPredicate(m -> lower.isEmpty()
                    || containsLower(m.name(), lower)
                    || containsLower(m.manufacturer(), lower)
                    || containsLower(m.resolution(), lower));
        });
        SortedList<MonitorInfo> sorted = new SortedList<>(filtered);

        TableView<MonitorInfo> table = new TableView<>(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);
        table.getColumns().addAll(
                tableColumn("Name", 220, a -> nvl(a.name())),
                tableColumn("Manufacturer", 160, a -> nvl(a.manufacturer())),
                tableColumn("Resolution", 120, a -> nvl(a.resolution())),
                tableColumn("Status", 100, a -> nvl(a.status())));
        sorted.comparatorProperty().bind(table.comparatorProperty());

        VBox detailBox = new VBox(8);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            detailBox.getChildren().clear();
            if (sel == null) {
                return;
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", sel.name());
            row = addRow(grid, row, "Manufacturer", sel.manufacturer());
            row = addRow(grid, row, "Screen Size", sel.screenSize());
            row = addRow(grid, row, "Resolution", sel.resolution());
            row = addRow(grid, row, "Status", sel.status());
            row = addRow(grid, row, "PNP Device ID", sel.pnpDeviceId());
            detailBox.getChildren().add(wrapGridOrPlaceholder(grid, row, "No details available."));
        });
        if (!sorted.isEmpty()) {
            table.getSelectionModel().select(0);
        }

        VBox container = new VBox(8, search, table, detailBox);
        container.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Monitors");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Printers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildPrinterTab(List<PrinterInfo> printers) {
        TextField search = new TextField();
        search.setPromptText("Search printers\u2026");
        search.getStyleClass().add("sysinfo-search");

        ObservableList<PrinterInfo> base = FXCollections.observableArrayList(printers);
        FilteredList<PrinterInfo> filtered = new FilteredList<>(base, p -> true);
        search.textProperty().addListener((obs, o, n) -> {
            String lower = n == null ? "" : n.toLowerCase();
            filtered.setPredicate(p -> lower.isEmpty()
                    || containsLower(p.name(), lower)
                    || containsLower(p.driver(), lower)
                    || containsLower(p.port(), lower)
                    || containsLower(p.status(), lower));
        });
        SortedList<PrinterInfo> sorted = new SortedList<>(filtered);

        TableView<PrinterInfo> table = new TableView<>(sorted);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefHeight(220);
        table.getColumns().addAll(
                tableColumn("Name", 200, a -> nvl(a.name())),
                tableColumn("Driver", 180, a -> nvl(a.driver())),
                tableColumn("Port", 120, a -> nvl(a.port())),
                tableColumn("Status", 100, a -> nvl(a.status())));
        sorted.comparatorProperty().bind(table.comparatorProperty());

        VBox detailBox = new VBox(8);
        table.getSelectionModel().selectedItemProperty().addListener((obs, o, sel) -> {
            detailBox.getChildren().clear();
            if (sel == null) {
                return;
            }
            GridPane grid = createInfoGrid();
            int row = 0;
            row = addRow(grid, row, "Name", sel.name());
            row = addRow(grid, row, "Driver", sel.driver());
            row = addRow(grid, row, "Port", sel.port());
            row = addRow(grid, row, "Status", sel.status());
            row = addRow(grid, row, "Shared", sel.shared() ? "Yes" : "No");
            row = addRow(grid, row, "Default", sel.isDefault() ? "Yes" : "No");
            detailBox.getChildren().add(wrapGrid(grid));
        });
        if (!sorted.isEmpty()) {
            table.getSelectionModel().select(0);
        }

        VBox container = new VBox(8, search, table, detailBox);
        container.setPadding(new Insets(12));
        VBox.setVgrow(table, Priority.ALWAYS);
        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Printers");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Battery â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildBatteryTab(BatteryInfo battery) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

        GridPane grid = createInfoGrid();
        int row = 0;
        row = addRow(grid, row, "Name", battery.name());
        row = addRow(grid, row, "Charge Level", battery.formatChargeLevel());
        row = addRow(grid, row, "Status", battery.status());
        row = addRow(grid, row, "Chemistry", battery.chemistry());
        row = addRow(grid, row, "Remaining Capacity", battery.formatRemainingCapacity());
        row = addRow(grid, row, "Charge Rate", battery.formatChargeRate());

        if (battery.chargeLevel() >= 0) {
            row = addUsageRow(grid, row, battery.chargeLevel());
        }

        container.getChildren().add(wrapGrid(grid));

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Battery");
        tab.setContent(scroll);
        return tab;
    }

    // â”€â”€ Temperatures â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildTemperaturesTab(List<TemperatureInfo> temperatures) {
        VBox container = new VBox(16);
        container.setPadding(new Insets(12));

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
        container.getChildren().add(wrapGrid(grid));

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Temperatures");
        tab.setContent(scroll);
        return tab;
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

    // â”€â”€ Warnings â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private Tab buildWarningsTab(SystemInfoData data) {
        List<String> warnings = data.warnings() != null ? data.warnings() : List.of();
        VBox container = new VBox(8);
        container.setPadding(new Insets(12));

        Label header = UILabel.sectionTitle("Warnings");
        container.getChildren().add(header);

        if (!warnings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < warnings.size(); i++) {
                sb.append(i + 1).append(". ").append(warnings.get(i));
                if (i < warnings.size() - 1) {
                    sb.append("\n");
                }
            }

            Label warningsLabel = new Label(sb.toString());
            warningsLabel.getStyleClass().addAll("label", "warning");
            warningsLabel.setWrapText(true);
            warningsLabel.setMaxWidth(Double.MAX_VALUE);
            warningsLabel.setStyle("-fx-padding: 8; -fx-background-color: #3d2e1a; -fx-background-radius: 4; -fx-border-color: #ffb86c; -fx-border-radius: 4;");
            container.getChildren().add(warningsLabel);
        }

        // v3.1: per-section timings aid perf diagnostics (parallel fan-out).
        if (data.timings() != null && !data.timings().isEmpty()) {
            container.getChildren().add(UILabel.sectionTitle("Collection timings (ms)"));
            GridPane grid = createInfoGrid();
            int row = 0;
            List<Map.Entry<String, Long>> entries = new ArrayList<>(data.timings().entrySet());
            entries.sort(Map.Entry.comparingByKey());
            for (Map.Entry<String, Long> e : entries) {
                row = addRow(grid, row, e.getKey(), String.valueOf(e.getValue() == null ? 0 : e.getValue()));
            }
            if (data.collectedAt() != null && !data.collectedAt().isBlank()) {
                row = addRow(grid, row, "Collected at", data.collectedAt());
            }
            if (data.version() != null && !data.version().isBlank()) {
                row = addRow(grid, row, "Payload version", data.version());
            }
            container.getChildren().add(wrapGridOrPlaceholder(grid, row, "No timing data."));
        } else if (warnings.isEmpty()) {
            container.getChildren().add(placeholderCard("No warnings or timing data."));
        }

        ScrollableContainer scroll = new ScrollableContainer(container);
        Tab tab = new Tab("Warnings");
        tab.setContent(scroll);
        return tab;
    }

    /** Legacy overload retained for tests/callers passing warnings only. */
    private Tab buildWarningsTab(List<String> warnings) {
        return buildWarningsTab(new SystemInfoData(null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null,
                warnings != null ? List.copyOf(warnings) : List.of(), null, null));
    }

    // â”€â”€ Helpers â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

    private static boolean containsLower(String value, String lower) {
        return value != null && !value.isBlank() && value.toLowerCase().contains(lower);
    }

    private static <T> TableColumn<T, String> tableColumn(String title, double prefWidth,
                                                          java.util.function.Function<T, String> extractor) {
        TableColumn<T, String> col = new TableColumn<>(title);
        col.setPrefWidth(prefWidth);
        col.setSortable(true);
        col.setCellValueFactory(cd -> {
            T item = cd.getValue();
            String v = "";
            try {
                v = extractor.apply(item);
            } catch (Exception ignored) {}
            return new SimpleStringProperty(v != null ? v : "");
        });
        return col;
    }

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
        if (usagePercent < 0 || Double.isNaN(usagePercent)) return row;

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

    private static VBox placeholderCard(String message) {
        Label label = new Label(message);
        label.getStyleClass().addAll("label", "text-muted");
        label.setWrapText(true);
        label.setStyle("-fx-padding: 12; -fx-font-style: italic;");
        VBox box = new VBox(label);
        box.getStyleClass().add("sysinfo-card");
        box.setPadding(new Insets(12));
        return box;
    }

    private static VBox wrapGridOrPlaceholder(GridPane grid, int row, String placeholder) {
        if (row == 0) {
            return placeholderCard(placeholder);
        }
        return wrapGrid(grid);
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

    // â”€â”€ Export / Copy â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    // v3.1: reports delegate to SystemInfoReportGenerator (single implementation,
    // header metadata, TOC). Export runs off-FX so large payloads never freeze UI.

    private Boolean adminHintFast() {
        try {
            if (!AppPaths.isWindows()) {
                return null;
            }
            // Instant, non-blocking: banner visibility mirrors last elevation check.
            // Visible == not admin; hidden == admin (or not yet resolved -> null).
            if (adminWarningLabel.isManaged()) {
                return !adminWarningLabel.isVisible();
            }
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void copyToClipboard() {
        if (currentData == null) {
            return;
        }
        String text = SystemInfoReportGenerator.generatePlainTextReport(currentData, adminHintFast());
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("System information copied to clipboard.");
    }

    private void copyVisibleTab() {
        if (currentData == null) {
            return;
        }
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        String tabName = selected != null ? selected.getText() : "Overview";
        String text = SystemInfoReportGenerator.generateSectionText(currentData, tabName);
        if (text == null || text.isBlank()) {
            text = SystemInfoReportGenerator.generatePlainTextReport(currentData, adminHintFast());
            tabName = "Overview";
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        statusLabel.setText("Tab '" + tabName + "' copied to clipboard.");
    }

    private void exportToFile() {
        if (currentData == null) {
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Export System Information");
        fileChooser.setInitialFileName("system-info");

        FileChooser.ExtensionFilter txtFilter = new FileChooser.ExtensionFilter("Plain Text (*.txt)", "*.txt");
        FileChooser.ExtensionFilter jsonFilter = new FileChooser.ExtensionFilter("JSON (*.json)", "*.json");
        FileChooser.ExtensionFilter htmlFilter = new FileChooser.ExtensionFilter("HTML Report (*.html)", "*.html");
        fileChooser.getExtensionFilters().addAll(txtFilter, jsonFilter, htmlFilter);
        fileChooser.setSelectedExtensionFilter(txtFilter);

        File file = fileChooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        if (file == null) {
            return;
        }

        // Resolve target path + format synchronously (cheap), render + write off-FX.
        FileChooser.ExtensionFilter selectedFilter = fileChooser.getSelectedExtensionFilter();
        String ext = "";
        if (selectedFilter != null) {
            List<String> extensions = selectedFilter.getExtensions();
            if (!extensions.isEmpty()) {
                ext = extensions.get(0).replace("*", "");
            }
        }
        // Determine extension from selected filter first, but respect user's typed extension
        String typedNameLower = file.getName().toLowerCase();
        boolean typedHasExt = typedNameLower.endsWith(".json") || typedNameLower.endsWith(".html") || typedNameLower.endsWith(".txt");
        if (!typedHasExt && ext.isEmpty()) {
            ext = ".txt";
        } else if (typedHasExt) {
            if (typedNameLower.endsWith(".json")) {
                ext = ".json";
            } else if (typedNameLower.endsWith(".html")) {
                ext = ".html";
            } else {
                ext = ".txt";
            }
        } else if (ext.isEmpty()) {
            ext = ".txt";
        }

        // Correctly strip existing extension using lastIndexOf to handle .html (5 chars) and .json (5 chars)
        String fileName = file.getName();
        String baseName = fileName;
        int dotIdx = fileName.lastIndexOf('.');
        if (dotIdx > 0) {
            String existingExt = fileName.substring(dotIdx).toLowerCase();
            if (existingExt.equals(".txt") || existingExt.equals(".json") || existingExt.equals(".html")) {
                baseName = fileName.substring(0, dotIdx);
            }
        }
        if (baseName.isBlank()) {
            baseName = "system-info";
        }
        final File target = new File(file.getParent(), baseName + ext);
        final String finalExt = ext;
        final SystemInfoData snapshot = currentData;
        final Boolean adminHint = adminHintFast();

        statusLabel.setText("Exporting to " + target.getName() + "\u2026");
        exportButton.setDisable(true);
        executor.submit(() -> {
            try {
                String content;
                if (".json".equals(finalExt)) {
                    content = JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(snapshot);
                } else if (".html".equals(finalExt)) {
                    content = SystemInfoReportGenerator.generateHtmlReport(snapshot, adminHint);
                } else {
                    content = SystemInfoReportGenerator.generatePlainTextReport(snapshot, adminHint);
                }
                Files.writeString(target.toPath(), content, StandardCharsets.UTF_8);
                Platform.runLater(() -> {
                    statusLabel.setText("Exported to: " + target.getName());
                    exportButton.setDisable(false);
                });
            } catch (IOException ex) {
                AppLogger.error("Failed to export system info", ex);
                Platform.runLater(() -> {
                    exportButton.setDisable(false);
                    new Alert(Alert.AlertType.ERROR, "Failed to export: " + ex.getMessage()).showAndWait();
                });
            }
        });
    }

    private static String generatePlainTextReport(SystemInfoData data) {
        return SystemInfoReportGenerator.generatePlainTextReport(data);
    }

    private static String generateHtmlReport(SystemInfoData data) {
        return SystemInfoReportGenerator.generateHtmlReport(data);
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    public void dispose() {
        executor.shutdownNow();
    }
}
