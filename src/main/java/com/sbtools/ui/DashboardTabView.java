package com.sbtools.ui;

import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanupService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateService;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DashboardTabView extends BorderPane {

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final CleanupService cleanupService = new CleanupService();
    private final DriverScanService driverScanService = new DriverScanService();
    private final DriverCatalogAggregator catalog = DriverCatalogAggregator.createDefault();
    private final SoftwareUpdateService softwareUpdateService = new SoftwareUpdateService();
    private final ExecutorService executor = Executors.newFixedThreadPool(3,
            r -> { Thread t = new Thread(r, "dashboard-scan"); t.setDaemon(true); return t; });

    private final ObservableList<IssueCategory> issues = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Check your PC health by pressing the Scan for issues button.");
    private final Label introLabel = new Label("Check your PC health by pressing the Scan for issues button.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button scanButton = new Button("Scan for issues");
    private final Button stopButton = new Button("Stop");
    private final Label summaryLabel = new Label();
    private TableView<IssueCategory> table;
    private volatile Future<?> scanFuture;
    private volatile boolean scanCancelled;

    public DashboardTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        stopButton.setDisable(true);
        stopButton.setVisible(false);

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> stopScan());

        introLabel.setStyle("-fx-text-fill: #f8f8f2; -fx-font-size: 14px; -fx-padding: 8 0 0 0;");

        HBox top = new HBox(12, scanButton, stopButton, progressBar, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        summaryLabel.setStyle("-fx-text-fill: #2AE061; -fx-font-size: 13px; -fx-padding: 12 0 12 0;");
        summaryLabel.setVisible(false);

        VBox center = new VBox(8, introLabel, table, summaryLabel);
        center.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);

        setTop(top);
        setCenter(center);

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            table.refresh();
        });

        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    private TableView<IssueCategory> buildTable() {
        TableView<IssueCategory> t = new TableView<>(issues);
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<IssueCategory, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        categoryCol.setPrefWidth(250);

        TableColumn<IssueCategory, String> countCol = new TableColumn<>("Issues Found");
        countCol.setCellValueFactory(c -> c.getValue().countTextProperty());
        countCol.setPrefWidth(120);

        TableColumn<IssueCategory, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeTextProperty());
        sizeCol.setPrefWidth(150);

        TableColumn<IssueCategory, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(120);

        t.getColumns().addAll(categoryCol, countCol, sizeCol, sourceCol);
        return t;
    }

    private void startScan() {
        if (busy.get()) return;
        scanCancelled = false;
        busy.set(true);
        issues.clear();
        summaryLabel.setVisible(false);
        introLabel.setVisible(false);
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        stopButton.setVisible(true);
        stopButton.setDisable(false);
        statusLabel.setText("Scanning system for issues...");

        scanFuture = executor.submit(() -> {
            AtomicInteger scansComplete = new AtomicInteger();
            int totalScans = 3;
            try {
                CompletableFuture<Void> driverScan = CompletableFuture.runAsync(
                        () -> scanDrivers(scansComplete, totalScans));
                CompletableFuture<Void> softwareScan = CompletableFuture.runAsync(
                        () -> scanSoftware(scansComplete, totalScans));
                CompletableFuture<Void> cleanupScan = CompletableFuture.runAsync(
                        () -> scanCleanup(scansComplete, totalScans));

                CompletableFuture.allOf(driverScan, softwareScan, cleanupScan).join();

                Platform.runLater(() -> {
                    if (scanCancelled) return;

                    IssueCategory driversEntry = null;
                    IssueCategory softwareEntry = null;
                    for (IssueCategory ic : issues) {
                        if ("Outdated Drivers".equals(ic.categoryProperty().get())) {
                            driversEntry = ic;
                        } else if ("Outdated Software".equals(ic.categoryProperty().get())) {
                            softwareEntry = ic;
                        }
                    }
                    issues.remove(driversEntry);
                    issues.remove(softwareEntry);
                    if (driversEntry != null) issues.add(0, driversEntry);
                    if (softwareEntry != null) issues.add(driversEntry != null ? 1 : 0, softwareEntry);

                    if (issues.isEmpty()) {
                        statusLabel.setText("No issues found. Your system looks healthy!");
                    } else {
                        int totalIssues = issues.stream().mapToInt(IssueCategory::getCount).sum();
                        long totalSize = issues.stream().mapToLong(IssueCategory::getSizeBytes).sum();
                        statusLabel.setText("Scan complete \u2014 " + issues.size() + " issue(s) found.");
                        summaryLabel.setText("Total: " + totalIssues + " issues across " + issues.size()
                                + " categories. " + formatBytes(totalSize) + " can be freed.");
                        summaryLabel.setVisible(true);
                    }
                });
            } catch (Exception ex) {
                if (!scanCancelled) {
                    AppLogger.error("Dashboard scan failed", ex);
                    Platform.runLater(() -> {
                        statusLabel.setText("Scan failed: " + ex.getMessage());
                        new Alert(Alert.AlertType.ERROR, "Scan failed:\n" + ex.getMessage()).showAndWait();
                    });
                }
            } finally {
                scanFuture = null;
                Platform.runLater(() -> {
                    busy.set(false);
                    progressBar.setVisible(false);
                    stopButton.setVisible(false);
                    stopButton.setDisable(true);
                    if (issues.isEmpty() && !scanCancelled) {
                        introLabel.setVisible(true);
                    }
                });
            }
        });
    }

    private void scanDrivers(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        Platform.runLater(() -> statusLabel.setText("Scanning for outdated drivers..."));
        try {
            List<InstalledDriver> installed = driverScanService.scanInstalled();
            if (scanCancelled) return;
            List<DriverUpdateCandidate> candidates = catalog.findUpdates(installed);
            if (scanCancelled) return;
            if (!candidates.isEmpty()) {
                Platform.runLater(() -> issues.add(new IssueCategory(
                        "Outdated Drivers", candidates.size(), 0, "Drivers")));
            }
        } catch (Exception ex) {
            AppLogger.warning("Dashboard driver scan failed: " + ex.getMessage());
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> progressBar.setProgress((double) done / totalScans));
    }

    private void scanSoftware(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        Platform.runLater(() -> statusLabel.setText("Scanning for software updates..."));
        try {
            AtomicBoolean cancelled = new AtomicBoolean(scanCancelled);
            List<SoftwareUpdateEntry> updates = softwareUpdateService.scanAllConcurrent(cancelled, w -> {}, wu -> {});
            if (scanCancelled) return;
            if (!updates.isEmpty()) {
                long totalSize = updates.stream().mapToLong(SoftwareUpdateEntry::sizeBytes).sum();
                Platform.runLater(() -> issues.add(new IssueCategory(
                        "Outdated Software", updates.size(), totalSize, "Software")));
            }
        } catch (Exception ex) {
            AppLogger.warning("Dashboard software scan failed: " + ex.getMessage());
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> progressBar.setProgress((double) done / totalScans));
    }

    private void scanCleanup(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        Platform.runLater(() -> statusLabel.setText("Scanning for system cleanup opportunities..."));
        try {
            List<CleanupRow> results = cleanupService.scan(() -> {});
            if (scanCancelled) return;
            for (CleanupRow row : results) {
                if (scanCancelled) return;
                String detailText = row.sizeOrCountTextProperty().get();
                String sizeText = row.getTotalBytes() > 0 ? formatBytes(row.getTotalBytes()) : "";
                Platform.runLater(() -> issues.add(new IssueCategory(
                        row.getCategory().getDisplayName(),
                        detailText,
                        sizeText,
                        "Cleanup",
                        row.getTotalBytes())));
            }
        } catch (Exception ex) {
            AppLogger.warning("Dashboard cleanup scan failed: " + ex.getMessage());
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> progressBar.setProgress((double) done / totalScans));
    }

    private void stopScan() {
        scanCancelled = true;
        if (scanFuture != null) {
            scanFuture.cancel(true);
            scanFuture = null;
        }
        busy.set(false);
        progressBar.setVisible(false);
        stopButton.setVisible(false);
        stopButton.setDisable(true);
        statusLabel.setText("Scan stopped.");
        if (issues.isEmpty()) {
            introLabel.setVisible(true);
        }
    }

    private static String formatBytes(long bytes) {
        if (bytes <= 0) return "0 B";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static class IssueCategory {
        private final SimpleStringProperty category;
        private final SimpleStringProperty countText;
        private final SimpleStringProperty sizeText;
        private final SimpleStringProperty source;
        private final int count;
        private final long sizeBytes;

        public IssueCategory(String category, int count, long sizeBytes, String source) {
            this.category = new SimpleStringProperty(category);
            this.count = count;
            this.sizeBytes = sizeBytes;
            this.countText = new SimpleStringProperty(count + " issue" + (count == 1 ? "" : "s"));
            this.sizeText = new SimpleStringProperty(sizeBytes > 0 ? formatBytes(sizeBytes) : "");
            this.source = new SimpleStringProperty(source);
        }

        public IssueCategory(String category, String detailText, String sizeText, String source, long sizeBytes) {
            this.category = new SimpleStringProperty(category);
            this.count = 0;
            this.sizeBytes = sizeBytes;
            this.countText = new SimpleStringProperty(detailText);
            this.sizeText = new SimpleStringProperty(sizeText);
            this.source = new SimpleStringProperty(source);
        }

        public SimpleStringProperty categoryProperty() { return category; }
        public SimpleStringProperty countTextProperty() { return countText; }
        public SimpleStringProperty sizeTextProperty() { return sizeText; }
        public SimpleStringProperty sourceProperty() { return source; }
        public int getCount() { return count; }
        public long getSizeBytes() { return sizeBytes; }
    }
}
