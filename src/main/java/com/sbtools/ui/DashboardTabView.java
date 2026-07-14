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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class DashboardTabView extends BorderPane {

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final CleanupService cleanupService = new CleanupService();
    private final DriverScanService driverScanService = new DriverScanService();
    private final DriverCatalogAggregator catalog = DriverCatalogAggregator.createDefault();
    private final SoftwareUpdateService softwareUpdateService = new SoftwareUpdateService();
    private final ExecutorService executor = Executors.newFixedThreadPool(5,
            r -> { Thread t = new Thread(r, "dashboard-scan"); t.setDaemon(true); return t; });

    private final ObservableList<IssueCategory> issues = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Check your PC health by pressing the Scan for issues button.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button scanButton = new Button("Scan for issues");
    private final Button stopButton = new Button("Stop");
    private TableView<IssueCategory> table;
    private volatile Future<?> scanFuture;
    private volatile boolean scanCancelled;
    private volatile boolean disposed;
    private volatile Instant lastScanTime;

    // View state containers
    private final StackPane centerPane = new StackPane();
    private VBox welcomeBox;
    private VBox resultsBox;
    private VBox healthyBox;

    // Summary cards
    private Label issuesValueLabel;
    private Label issuesDescLabel;
    private Label spaceValueLabel;
    private Label spaceDescLabel;
    private Label categoriesValueLabel;
    private Label categoriesDescLabel;

    // Per-category progress
    private HBox progressRow;
    private ProgressBar driverProgress;
    private Label driverStatusLabel;
    private ProgressBar softwareProgress;
    private Label softwareStatusLabel;
    private ProgressBar cleanupProgress;
    private Label cleanupStatusLabel;
    private HBox progressItemDrivers;
    private HBox progressItemSoftware;
    private HBox progressItemCleanup;

    // Status bar
    private Label timestampLabel;
    private Label summaryLabel;

    public DashboardTabView(BooleanProperty busy, BooleanSupplier adminCheck) {
        this.busy = busy;
        this.adminCheck = adminCheck;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        stopButton.setDisable(true);
        stopButton.setVisible(false);

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> stopScan());

        HBox top = new HBox(12, scanButton, stopButton, progressBar, statusLabel);
        top.setAlignment(Pos.CENTER_LEFT);
        top.setPadding(new Insets(12, 16, 12, 16));
        top.getStyleClass().add("toolbar");

        buildWelcomeScreen();
        buildResultsBox();

        centerPane.getChildren().addAll(welcomeBox, resultsBox);
        resultsBox.setVisible(false);
        resultsBox.setManaged(false);

        setTop(top);
        setCenter(centerPane);
        setBottom(createStatusBar());

        busy.addListener((obs, oldVal, newVal) -> {
            scanButton.setDisable(newVal);
            table.refresh();
        });

        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }
    }

    public void dispose() {
        disposed = true;
        scanCancelled = true;
        if (scanFuture != null) {
            scanFuture.cancel(true);
            scanFuture = null;
        }
        executor.shutdownNow();
    }

    // ── Welcome Screen ────────────────────────────────────────────────────

    private void buildWelcomeScreen() {
        ImageView logoView = new ImageView(new Image(getClass().getResourceAsStream("/logo-ico.png")));
        logoView.setFitHeight(64);
        logoView.setFitWidth(64);
        logoView.setPreserveRatio(true);

        Label title = new Label("WinZenith Dashboard");
        title.getStyleClass().add("dashboard-welcome-title");

        Label desc = new Label("Get a quick overview of your system health.\nPress \"Scan for issues\" to check drivers, software updates, and cleanup opportunities.");
        desc.getStyleClass().add("dashboard-welcome-desc");
        desc.setWrapText(true);

        HBox cards = new HBox(16,
                createInfoCard("\uD83D\uDD0C", "Outdated Drivers",
                        "Detect drivers that have newer versions available from OEM catalogs"),
                createInfoCard("\uD83D\uDD14", "Software Updates",
                        "Find installed applications with pending updates via winget"),
                createInfoCard("\uD83E\uDDF9", "System Cleanup",
                        "Identify temporary files, caches, and junk that waste disk space")
        );
        cards.getStyleClass().add("dashboard-welcome-cards");
        cards.setAlignment(Pos.CENTER);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        welcomeBox = new VBox(12, logoView, title, desc, cards, spacer);
        welcomeBox.getStyleClass().add("dashboard-welcome");
    }

    private VBox createInfoCard(String icon, String title, String description) {
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("dashboard-info-card-icon");

        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("dashboard-info-card-title");

        Label descLabel = new Label(description);
        descLabel.getStyleClass().add("dashboard-info-card-desc");
        descLabel.setWrapText(true);

        VBox card = new VBox(8, iconLabel, titleLabel, descLabel);
        card.getStyleClass().add("dashboard-info-card");
        card.setAlignment(Pos.CENTER);
        return card;
    }

    // ── Results Box ───────────────────────────────────────────────────────

    private void buildResultsBox() {
        HBox summaryRow = buildSummaryCards();
        progressRow = buildProgressRow();
        progressRow.setVisible(false);
        progressRow.setManaged(false);

        table = buildTable();
        VBox.setVgrow(table, Priority.ALWAYS);

        summaryLabel = new Label();
        summaryLabel.setStyle("-fx-text-fill: #2AE061; -fx-font-size: 13px; -fx-padding: 12 0 12 0;");
        summaryLabel.setVisible(false);

        healthyBox = new VBox(12,
                new Label("\u2714"),
                new Label("Your system looks healthy!"),
                new Label("No issues found across drivers, software, or cleanup.")
        );
        healthyBox.getStyleClass().add("dashboard-healthy");
        healthyBox.setAlignment(Pos.CENTER);
        ((Label) healthyBox.getChildren().get(0)).getStyleClass().add("dashboard-healthy-icon");
        ((Label) healthyBox.getChildren().get(1)).getStyleClass().add("dashboard-healthy-title");
        ((Label) healthyBox.getChildren().get(2)).getStyleClass().add("dashboard-healthy-desc");
        healthyBox.setVisible(false);
        healthyBox.setManaged(false);

        resultsBox = new VBox(8, summaryRow, progressRow, table, healthyBox, summaryLabel);
        resultsBox.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    // ── Summary Cards ─────────────────────────────────────────────────────

    private HBox buildSummaryCards() {
        issuesValueLabel = new Label("\u2014");
        issuesValueLabel.getStyleClass().add("dashboard-summary-value");
        issuesDescLabel = new Label("Issues Found");
        issuesDescLabel.getStyleClass().add("dashboard-summary-label");
        VBox issuesCard = new VBox(4, issuesValueLabel, issuesDescLabel);
        issuesCard.getStyleClass().add("dashboard-summary-card");

        spaceValueLabel = new Label("\u2014");
        spaceValueLabel.getStyleClass().add("dashboard-summary-value");
        spaceDescLabel = new Label("Reclaimable Space");
        spaceDescLabel.getStyleClass().add("dashboard-summary-label");
        VBox spaceCard = new VBox(4, spaceValueLabel, spaceDescLabel);
        spaceCard.getStyleClass().add("dashboard-summary-card");

        categoriesValueLabel = new Label("\u2014");
        categoriesValueLabel.getStyleClass().add("dashboard-summary-value");
        categoriesDescLabel = new Label("Categories");
        categoriesDescLabel.getStyleClass().add("dashboard-summary-label");
        VBox categoriesCard = new VBox(4, categoriesValueLabel, categoriesDescLabel);
        categoriesCard.getStyleClass().add("dashboard-summary-card");

        HBox row = new HBox(12, issuesCard, spaceCard, categoriesCard);
        row.getStyleClass().add("dashboard-summary-row");
        return row;
    }

    private void updateSummaryCards() {
        if (issues.isEmpty()) {
            issuesValueLabel.setText("0");
            issuesDescLabel.setText("issues found");
            spaceValueLabel.setText("0 B");
            spaceDescLabel.setText("can be freed");
            categoriesValueLabel.setText("0");
            categoriesDescLabel.setText("categories");
            return;
        }
        int totalIssues = issues.stream().mapToInt(IssueCategory::getCount).sum();
        long totalSize = issues.stream().mapToLong(IssueCategory::getSizeBytes).sum();

        issuesValueLabel.setText(String.valueOf(totalIssues));
        issuesDescLabel.setText("issue" + (totalIssues == 1 ? "" : "s") + " found");

        spaceValueLabel.setText(formatBytes(totalSize));
        spaceDescLabel.setText("can be freed");

        categoriesValueLabel.setText(String.valueOf(issues.size()));
        categoriesDescLabel.setText("categories with issues");
    }

    // ── Per-Category Progress ─────────────────────────────────────────────

    private HBox buildProgressRow() {
        progressItemDrivers = createProgressItem("Outdated Drivers", "#8be9fd");
        driverProgress = (ProgressBar) progressItemDrivers.getChildren().get(1);
        driverStatusLabel = (Label) progressItemDrivers.getChildren().get(2);

        progressItemSoftware = createProgressItem("Software Updates", "#ffb86c");
        softwareProgress = (ProgressBar) progressItemSoftware.getChildren().get(1);
        softwareStatusLabel = (Label) progressItemSoftware.getChildren().get(2);

        progressItemCleanup = createProgressItem("System Cleanup", "#ff79c6");
        cleanupProgress = (ProgressBar) progressItemCleanup.getChildren().get(1);
        cleanupStatusLabel = (Label) progressItemCleanup.getChildren().get(2);

        HBox row = new HBox(12, progressItemDrivers, progressItemSoftware, progressItemCleanup);
        row.getStyleClass().add("dashboard-progress-row");
        return row;
    }

    private HBox createProgressItem(String label, String accentColor) {
        Label nameLabel = new Label(label);
        nameLabel.getStyleClass().add("dashboard-progress-label");

        ProgressBar pbar = new ProgressBar(0);
        pbar.setPrefWidth(120);
        pbar.setPrefHeight(6);

        Label statusLbl = new Label("Pending");
        statusLbl.getStyleClass().add("dashboard-progress-status");

        HBox item = new HBox(8, nameLabel, pbar, statusLbl);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("dashboard-progress-item");
        return item;
    }

    private void updateCategoryProgress(int categoryIndex, String state) {
        ProgressBar pbar;
        Label statusLbl;
        HBox item;
        switch (categoryIndex) {
            case 0 -> { pbar = driverProgress; statusLbl = driverStatusLabel; item = progressItemDrivers; }
            case 1 -> { pbar = softwareProgress; statusLbl = softwareStatusLabel; item = progressItemSoftware; }
            default -> { pbar = cleanupProgress; statusLbl = cleanupStatusLabel; item = progressItemCleanup; }
        }
        Platform.runLater(() -> {
            item.getStyleClass().removeAll("active", "done", "failed");
            statusLbl.getStyleClass().removeAll("active", "done", "failed");
            switch (state) {
                case "scanning" -> {
                    pbar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    statusLbl.setText("Scanning\u2026");
                    item.getStyleClass().add("active");
                    statusLbl.getStyleClass().add("active");
                }
                case "done" -> {
                    pbar.setProgress(1);
                    statusLbl.setText("Done");
                    item.getStyleClass().add("done");
                    statusLbl.getStyleClass().add("done");
                }
                case "failed" -> {
                    pbar.setProgress(0);
                    statusLbl.setText("Failed");
                    item.getStyleClass().add("failed");
                    statusLbl.getStyleClass().add("failed");
                }
                default -> {
                    pbar.setProgress(0);
                    statusLbl.setText("Pending");
                }
            }
        });
    }

    private void resetProgressItems() {
        for (HBox item : new HBox[]{progressItemDrivers, progressItemSoftware, progressItemCleanup}) {
            if (item == null) continue;
            ProgressBar pb = (ProgressBar) item.getChildren().get(1);
            Label sl = (Label) item.getChildren().get(2);
            pb.setProgress(0);
            sl.setText("Pending");
            item.getStyleClass().removeAll("active", "done", "failed");
            sl.getStyleClass().removeAll("active", "done", "failed");
        }
    }

    // ── Status Bar ────────────────────────────────────────────────────────

    private HBox createStatusBar() {
        timestampLabel = new Label();
        timestampLabel.getStyleClass().add("dashboard-timestamp");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(0, spacer, timestampLabel);
        bar.getStyleClass().add("dashboard-status-bar");
        return bar;
    }

    private void updateTimestamp() {
        if (lastScanTime != null) {
            Duration elapsed = Duration.between(lastScanTime, Instant.now());
            String text;
            if (elapsed.toSeconds() < 60) {
                text = "Last scanned: just now";
            } else if (elapsed.toMinutes() < 60) {
                long mins = elapsed.toMinutes();
                text = "Last scanned: " + mins + " min" + (mins == 1 ? "" : "s") + " ago";
            } else {
                long hrs = elapsed.toHours();
                text = "Last scanned: " + hrs + " hour" + (hrs == 1 ? "" : "s") + " ago";
            }
            Platform.runLater(() -> timestampLabel.setText(text));
        }
    }

    // ── View Switching ────────────────────────────────────────────────────

    private void showWelcomeView() {
        welcomeBox.setVisible(true);
        welcomeBox.setManaged(true);
        resultsBox.setVisible(false);
        resultsBox.setManaged(false);
    }

    private void showResultsView() {
        welcomeBox.setVisible(false);
        welcomeBox.setManaged(false);
        resultsBox.setVisible(true);
        resultsBox.setManaged(true);
    }

    // ── Table ─────────────────────────────────────────────────────────────

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

    // ── Scan Logic ────────────────────────────────────────────────────────

    private void startScan() {
        if (busy.get()) return;
        if (!adminCheck.getAsBoolean()) {
            statusLabel.setText("Run as Administrator to scan for issues.");
            return;
        }
        scanCancelled = false;
        busy.set(true);
        issues.clear();
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        stopButton.setVisible(true);
        stopButton.setDisable(false);
        statusLabel.setText("Scanning system for issues\u2026");

        showResultsView();
        hideHealthyState();
        resetProgressItems();
        progressRow.setVisible(true);
        progressRow.setManaged(true);

        summaryLabel.setVisible(false);

        scanFuture = executor.submit(() -> {
            lastScanTime = Instant.now();
            AtomicInteger scansComplete = new AtomicInteger();
            int totalScans = 3;
            try {
                CompletableFuture<Void> driverScan = CompletableFuture.runAsync(
                        () -> scanDrivers(scansComplete, totalScans), executor);
                CompletableFuture<Void> softwareScan = CompletableFuture.runAsync(
                        () -> scanSoftware(scansComplete, totalScans), executor);
                CompletableFuture<Void> cleanupScan = CompletableFuture.runAsync(
                        () -> scanCleanup(scansComplete, totalScans), executor);

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
                    if (driversEntry != null) issues.remove(driversEntry);
                    if (softwareEntry != null) issues.remove(softwareEntry);
                    if (driversEntry != null) issues.add(0, driversEntry);
                    if (softwareEntry != null) issues.add(driversEntry != null ? 1 : 0, softwareEntry);

                    progressRow.setVisible(false);
                    progressRow.setManaged(false);

                    if (issues.isEmpty()) {
                        showHealthyState();
                        statusLabel.setText("Scan complete \u2014 no issues found.");
                    } else {
                        int totalIssues = issues.stream().mapToInt(IssueCategory::getCount).sum();
                        long totalSize = issues.stream().mapToLong(IssueCategory::getSizeBytes).sum();
                        statusLabel.setText("Scan complete \u2014 " + issues.size() + " category" + (issues.size() == 1 ? "" : "ies") + " with issues.");
                        summaryLabel.setText("Total: " + totalIssues + " issue" + (totalIssues == 1 ? "" : "s") + " across " + issues.size()
                                + " categor" + (issues.size() == 1 ? "y" : "ies") + ". " + formatBytes(totalSize) + " can be freed.");
                        summaryLabel.setVisible(true);
                    }
                    updateSummaryCards();
                    updateTimestamp();
                });
            } catch (Exception ex) {
                if (!scanCancelled) {
                    AppLogger.error("Dashboard scan failed", ex);
                    Platform.runLater(() -> {
                        progressRow.setVisible(false);
                        progressRow.setManaged(false);
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
                    if (scanCancelled && issues.isEmpty()) {
                        showWelcomeView();
                    }
                });
            }
        });
    }

    private void scanDrivers(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        updateCategoryProgress(0, "scanning");
        Platform.runLater(() -> statusLabel.setText("Scanning for outdated drivers\u2026"));
        try {
            List<InstalledDriver> installed = driverScanService.scanInstalled();
            if (scanCancelled) return;
            List<DriverUpdateCandidate> candidates = catalog.findUpdates(installed);
            if (scanCancelled) return;
            if (!candidates.isEmpty()) {
                Platform.runLater(() -> issues.add(new IssueCategory(
                        "Outdated Drivers", candidates.size(), 0, "Drivers")));
            }
            updateCategoryProgress(0, "done");
        } catch (Exception ex) {
            AppLogger.warning("Dashboard driver scan failed: " + ex.getMessage());
            updateCategoryProgress(0, "failed");
            Platform.runLater(() -> issues.add(new IssueCategory(
                    "Outdated Drivers", "Error: " + ex.getMessage(), "", "Drivers", 0)));
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> progressBar.setProgress((double) done / totalScans));
    }

    private void scanSoftware(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        updateCategoryProgress(1, "scanning");
        Platform.runLater(() -> statusLabel.setText("Scanning for software updates\u2026"));
        try {
            List<SoftwareUpdateEntry> updates = softwareUpdateService.scanAllConcurrent(
                    () -> scanCancelled, w -> {}, wu -> {});
            if (scanCancelled) return;
            if (!updates.isEmpty()) {
                long totalSize = updates.stream().mapToLong(SoftwareUpdateEntry::sizeBytes).sum();
                Platform.runLater(() -> issues.add(new IssueCategory(
                        "Outdated Software", updates.size(), totalSize, "Software")));
            }
            updateCategoryProgress(1, "done");
        } catch (Exception ex) {
            AppLogger.warning("Dashboard software scan failed: " + ex.getMessage());
            updateCategoryProgress(1, "failed");
            Platform.runLater(() -> issues.add(new IssueCategory(
                    "Outdated Software", "Error: " + ex.getMessage(), "", "Software", 0)));
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> progressBar.setProgress((double) done / totalScans));
    }

    private void scanCleanup(AtomicInteger scansComplete, int totalScans) {
        if (scanCancelled) return;
        updateCategoryProgress(2, "scanning");
        Platform.runLater(() -> statusLabel.setText("Scanning for system cleanup opportunities\u2026"));
        try {
            List<CleanupRow> results = cleanupService.scan(() -> {});
            if (scanCancelled) return;
            for (CleanupRow row : results) {
                if (scanCancelled) return;
                if (row.getTotalBytes() <= 0 && row.getScanStatus() != CleanupRow.ScanStatus.ERROR) {
                    continue;
                }
                String detailText = row.sizeOrCountTextProperty().get();
                String sizeText = row.getTotalBytes() > 0 ? formatBytes(row.getTotalBytes()) : "";
                Platform.runLater(() -> issues.add(new IssueCategory(
                        row.getCategory().getDisplayName(),
                        detailText,
                        sizeText,
                        "Cleanup",
                        row.getTotalBytes())));
            }
            updateCategoryProgress(2, "done");
        } catch (Exception ex) {
            AppLogger.warning("Dashboard cleanup scan failed: " + ex.getMessage());
            updateCategoryProgress(2, "failed");
            Platform.runLater(() -> issues.add(new IssueCategory(
                    "System Cleanup", "Error: " + ex.getMessage(), "", "Cleanup", 0)));
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
        progressRow.setVisible(false);
        progressRow.setManaged(false);
        if (issues.isEmpty()) {
            showWelcomeView();
        }
    }

    // ── Healthy State ─────────────────────────────────────────────────────

    private void showHealthyState() {
        table.setVisible(false);
        table.setManaged(false);
        healthyBox.setVisible(true);
        healthyBox.setManaged(true);
    }

    private void hideHealthyState() {
        table.setVisible(true);
        table.setManaged(true);
        healthyBox.setVisible(false);
        healthyBox.setManaged(false);
    }

    // ── Utilities ─────────────────────────────────────────────────────────

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
            this.count = 1;
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
