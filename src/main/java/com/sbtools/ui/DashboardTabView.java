package com.sbtools.ui;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanupService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateService;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.CancellationToken;
import com.sbtools.util.FormatUtils;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class DashboardTabView extends BorderPane {

    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final Consumer<Integer> tabSwitchRequest;
    // Lazy services (P1): avoid paying construction / catalog-cache cost at app
    // startup; created on first scan on a worker thread. Volatile + locked init.
    private volatile CleanupService cleanupService;
    private volatile DriverScanService driverScanService;
    private volatile DriverCatalogAggregator catalog;
    private volatile SoftwareUpdateService softwareUpdateService;
    private final Object servicesLock = new Object();
    private final com.sbtools.settings.SettingsStore settingsStore = new com.sbtools.settings.SettingsStore();
    /**
     * Dedicated pool for Dashboard sub-scans. Sub-scan workers must NEVER run
     * on ioPool/cleanPool directly because the inner services
     * (DriverCatalogAggregator on ioPool, CleanupService on cleanPool) submit
     * to those same pools and block — nesting would starve the fixed pools.
     * Cached (unbounded) so a lingering worker after Stop (e.g. a long cleanup
     * walk whose inner join ignores interrupts) never blocks a fresh scan.
     */
    private final ExecutorService dashboardPool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "dashboard-scan");
        t.setDaemon(true);
        return t;
    });
    /** Overall Dashboard scan budget (outer coordinator). Delegates to coordinator. */
    private static final long DASHBOARD_SCAN_TIMEOUT_SECONDS =
            DashboardScanCoordinator.OVERALL_TIMEOUT_SECONDS;
    private static final long[] PER_TASK_BUDGETS = {
            DashboardScanCoordinator.DRIVER_TIMEOUT_SECONDS,
            DashboardScanCoordinator.SOFTWARE_TIMEOUT_SECONDS,
            DashboardScanCoordinator.CLEANUP_TIMEOUT_SECONDS
    };
    private static final int MAX_DETAIL_LINES = 5;
    /** Local reentrancy guard. Dashboard is read-only: it must NOT hold the global busy. */
    private final AtomicBoolean scanning = new AtomicBoolean(false);

    private final ObservableList<IssueCategory> issues = FXCollections.observableArrayList();
    private final Label statusLabel = new Label("Check your PC health by pressing the Scan for issues button.");
    private final ProgressBar progressBar = new ProgressBar(0);
    private final Button scanButton = new Button("Scan for issues");
    private final Button stopButton = new Button("Stop");
    private TableView<IssueCategory> table;
    private Label detailsLabel;
    private volatile Future<?> scanFuture;
    // Interruptible worker handles: submitted via dashboardPool.submit so
    // cancel(true) truly interrupts the worker thread (CompletableFuture.cancel
    // would NOT interrupt, leaving PowerShell/file walks stuck until timeout).
    private volatile Future<?> driverTask;
    private volatile Future<?> softwareTask;
    private volatile Future<?> cleanupTask;
    private volatile int scanGeneration;
    private volatile CancellationToken scanCancellationToken;
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
    private ProgressItem driverItem;
    private ProgressItem softwareItem;
    private ProgressItem cleanupItem;

    // Status bar
    private Label timestampLabel;
    private Label snapshotLabel;
    private Label summaryLabel;

    // Periodic timestamp refresher (60s). Stopped on dispose.
    private Timeline timestampTimeline;

    public DashboardTabView(BooleanProperty busy, BooleanSupplier adminCheck, Consumer<Integer> tabSwitchRequest) {
        this.busy = busy;
        this.adminCheck = adminCheck;
        this.tabSwitchRequest = tabSwitchRequest;

        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);
        stopButton.setDisable(true);
        stopButton.setVisible(false);
        scanButton.setDefaultButton(true);

        scanButton.setOnAction(e -> startScan());
        stopButton.setOnAction(e -> stopScan());
        setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE && scanning.get()) {
                stopScan();
                e.consume();
            }
        });

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
            // Dashboard never acquires global busy (read-only); only reflect
            // other tabs' activity + local scanning state.
            scanButton.setDisable(newVal || scanning.get());
            if (table != null) table.refresh();
        });

        if (!AppPaths.isWindows()) {
            statusLabel.setText("This application requires Windows.");
            scanButton.setDisable(true);
        }

        restoreSnapshot();
        startTimestampTicker();
    }

    // ── Lazy services (P1) ────────────────────────────────────────────────

    private CleanupService cleanupServices() {
        CleanupService s = cleanupService;
        if (s == null) {
            synchronized (servicesLock) {
                s = cleanupService;
                if (s == null) {
                    s = new CleanupService();
                    cleanupService = s;
                }
            }
        }
        return s;
    }

    private DriverScanService driverScanServices() {
        DriverScanService s = driverScanService;
        if (s == null) {
            synchronized (servicesLock) {
                s = driverScanService;
                if (s == null) {
                    s = new DriverScanService();
                    driverScanService = s;
                }
            }
        }
        return s;
    }

    private DriverCatalogAggregator catalogs() {
        DriverCatalogAggregator c = catalog;
        if (c == null) {
            synchronized (servicesLock) {
                c = catalog;
                if (c == null) {
                    c = DriverCatalogAggregator.createDefault();
                    catalog = c;
                }
            }
        }
        return c;
    }

    private SoftwareUpdateService softwareServices() {
        SoftwareUpdateService s = softwareUpdateService;
        if (s == null) {
            synchronized (servicesLock) {
                s = softwareUpdateService;
                if (s == null) {
                    s = new SoftwareUpdateService();
                    softwareUpdateService = s;
                }
            }
        }
        return s;
    }

    public void dispose() {
        disposed = true;
        scanGeneration++;
        CancellationToken token = scanCancellationToken;
        if (token != null) token.cancel();
        cancelSubScans();
        Future<?> f = scanFuture;
        if (f != null) {
            f.cancel(true);
            scanFuture = null;
        }
        // Dashboard uses local `scanning`, never global busy — do NOT touch
        // global busy here (would steal another tab's reference-counted hold).
        scanning.set(false);
        stopTimestampTicker();
        try {
            SoftwareUpdateService s = softwareUpdateService;
            if (s != null) s.shutdown();
        } catch (Exception ignored) {}
        try {
            dashboardPool.shutdownNow();
        } catch (Exception ignored) {}
        try {
            if (Platform.isFxApplicationThread()) {
                progressBar.setVisible(false);
                stopButton.setVisible(false);
                stopButton.setDisable(true);
                scanButton.setDisable(false);
                if (progressRow != null) {
                    progressRow.setVisible(false);
                    progressRow.setManaged(false);
                }
            } else {
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    stopButton.setVisible(false);
                    stopButton.setDisable(true);
                    scanButton.setDisable(false);
                    if (progressRow != null) {
                        progressRow.setVisible(false);
                        progressRow.setManaged(false);
                    }
                });
            }
        } catch (Exception ignored) {}
    }

    private void cancelSubScans() {
        for (Future<?> f : new Future<?>[]{driverTask, softwareTask, cleanupTask}) {
            if (f != null && !f.isDone()) {
                try {
                    f.cancel(true);
                } catch (Exception ignored) {}
            }
        }
        driverTask = null;
        softwareTask = null;
        cleanupTask = null;
    }

    // ── Welcome Screen ────────────────────────────────────────────────────

    private void buildWelcomeScreen() {
        javafx.scene.Node logoNode;
        try (java.io.InputStream logoStream = getClass().getResourceAsStream("/logo-ico.png")) {
            if (logoStream != null) {
                ImageView logoView = new ImageView(new Image(logoStream));
                logoView.setFitHeight(64);
                logoView.setFitWidth(64);
                logoView.setPreserveRatio(true);
                logoNode = logoView;
            } else {
                Label fallback = new Label("\u2699");
                fallback.setStyle("-fx-font-size: 48px;");
                logoNode = fallback;
            }
        } catch (java.io.IOException e) {
            Label fallback = new Label("\u2699");
            fallback.setStyle("-fx-font-size: 48px;");
            logoNode = fallback;
        }

        Label title = new Label("WinZenith Dashboard");
        title.getStyleClass().add("dashboard-welcome-title");

        Label desc = new Label("Get a quick overview of your system health.\nPress \"Scan for issues\" to check drivers, software updates, and cleanup opportunities.");
        desc.getStyleClass().add("dashboard-welcome-desc");
        desc.setWrapText(true);

        HBox cards = new HBox(16,
                createInfoCard("\uD83D\uDD0C", "Outdated Drivers",
                        "Detect drivers that have newer versions available from OEM catalogs", 1),
                createInfoCard("\uD83D\uDD14", "Software Updates",
                        "Find installed applications with pending updates via winget", 3),
                createInfoCard("\uD83E\uDDF9", "System Cleanup",
                        "Identify temporary files, caches, and junk that waste disk space", 7)
        );
        cards.getStyleClass().add("dashboard-welcome-cards");
        cards.setAlignment(Pos.CENTER);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);

        welcomeBox = new VBox(12, logoNode, title, desc, cards, spacer);
        welcomeBox.getStyleClass().add("dashboard-welcome");
    }

    private VBox createInfoCard(String icon, String title, String description, int tabIndex) {
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

        if (tabSwitchRequest != null) {
            card.getStyleClass().add("dashboard-clickable");
            card.setOnMouseClicked(e -> tabSwitchRequest.accept(tabIndex));
        }

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

        detailsLabel = new Label();
        detailsLabel.getStyleClass().add("dashboard-details");
        detailsLabel.setWrapText(true);
        detailsLabel.setVisible(false);
        detailsLabel.setManaged(false);

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

        resultsBox = new VBox(8, summaryRow, progressRow, table, detailsLabel, healthyBox, summaryLabel);
        resultsBox.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(table, Priority.ALWAYS);
    }

    // ── Summary Cards ─────────────────────────────────────────────────────

    private HBox buildSummaryCards() {
        issuesValueLabel = new Label("\u2014");
        issuesValueLabel.getStyleClass().add("dashboard-summary-value");
        issuesDescLabel = new Label("Outdated Drivers/Software");
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
        categoriesDescLabel = new Label("Cleanup Categories");
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
            issuesDescLabel.setText("Outdated Drivers/Software");
            spaceValueLabel.setText("0 B");
            spaceDescLabel.setText("can be freed");
            categoriesValueLabel.setText("0");
            categoriesDescLabel.setText("cleanup categories");
            return;
        }

        int driverCount = 0;
        int softwareCount = 0;
        long totalSize = 0;
        int cleanupCategoryCount = 0;

        for (IssueCategory ic : issues) {
            if (ic.isError()) continue;
            String name = ic.categoryProperty().get();
            if ("Outdated Drivers".equals(name)) {
                driverCount = ic.getCount();
            } else if ("Outdated Software".equals(name)) {
                softwareCount = ic.getCount();
            } else if ("Cleanup".equals(ic.sourceProperty().get())) {
                cleanupCategoryCount++;
                totalSize += ic.getSizeBytes();
            }
        }

        int totalDriverSoftware = driverCount + softwareCount;
        issuesValueLabel.setText(String.valueOf(totalDriverSoftware));
        issuesDescLabel.setText("Outdated Drivers/Software");

        spaceValueLabel.setText(formatBytes(totalSize));
        spaceDescLabel.setText("can be freed");

        categoriesValueLabel.setText(String.valueOf(cleanupCategoryCount));
        categoriesDescLabel.setText("cleanup categories");
    }

    // ── Per-Category Progress (with per-category Retry) ───────────────────

    private record ProgressItem(HBox box, ProgressBar bar, Label statusLabel, Button retryButton) {}

    private HBox buildProgressRow() {
        driverItem = createProgressItem("Outdated Drivers", 0);
        softwareItem = createProgressItem("Software Updates", 1);
        cleanupItem = createProgressItem("System Cleanup", 2);

        HBox row = new HBox(12, driverItem.box(), softwareItem.box(), cleanupItem.box());
        row.getStyleClass().add("dashboard-progress-row");
        return row;
    }

    private ProgressItem createProgressItem(String label, int categoryIndex) {
        Label nameLabel = new Label(label);
        nameLabel.getStyleClass().add("dashboard-progress-label");

        ProgressBar pbar = new ProgressBar(0);
        pbar.setPrefWidth(100);
        pbar.setPrefHeight(6);

        Label statusLbl = new Label("Pending");
        statusLbl.getStyleClass().add("dashboard-progress-status");

        Button retry = new Button("Retry");
        retry.getStyleClass().addAll("button-outlined", "small", "dashboard-retry");
        retry.setVisible(false);
        retry.setManaged(false);
        retry.setOnAction(e -> retryCategory(categoryIndex));

        HBox item = new HBox(8, nameLabel, pbar, statusLbl, retry);
        item.setAlignment(Pos.CENTER_LEFT);
        item.getStyleClass().add("dashboard-progress-item");
        return new ProgressItem(item, pbar, statusLbl, retry);
    }

    private void updateCategoryProgress(int categoryIndex, String state) {
        // Legacy overload — no generation check (used from non-scan contexts)
        updateCategoryProgress(categoryIndex, state, -1);
    }

    private void updateCategoryProgress(int categoryIndex, String state, int generation) {
        ProgressItem pi = switch (categoryIndex) {
            case 0 -> driverItem;
            case 1 -> softwareItem;
            default -> cleanupItem;
        };
        if (pi == null) return;
        Platform.runLater(() -> {
            if (generation >= 0 && isScanStale(generation)) return;
            pi.box().getStyleClass().removeAll("active", "done", "failed");
            pi.statusLabel().getStyleClass().removeAll("active", "done", "failed");
            switch (state) {
                case "scanning" -> {
                    pi.bar().setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                    pi.statusLabel().setText("Scanning\u2026");
                    pi.box().getStyleClass().add("active");
                    pi.statusLabel().getStyleClass().add("active");
                    pi.retryButton().setVisible(false);
                    pi.retryButton().setManaged(false);
                }
                case "done" -> {
                    pi.bar().setProgress(1);
                    pi.statusLabel().setText("Done");
                    pi.box().getStyleClass().add("done");
                    pi.statusLabel().getStyleClass().add("done");
                    pi.retryButton().setVisible(false);
                    pi.retryButton().setManaged(false);
                }
                case "failed" -> {
                    pi.bar().setProgress(0);
                    pi.statusLabel().setText("Failed");
                    pi.box().getStyleClass().add("failed");
                    pi.statusLabel().getStyleClass().add("failed");
                    // Retry is only meaningful when idle (not mid-scan, not disposed).
                    boolean idle = !scanning.get() || isScanStale(generation);
                    pi.retryButton().setVisible(!disposed && (generation < 0 || idle || !scanning.get()));
                    pi.retryButton().setManaged(pi.retryButton().isVisible());
                }
                case "timeout" -> {
                    pi.bar().setProgress(0);
                    pi.statusLabel().setText("Timed out");
                    pi.box().getStyleClass().add("failed");
                    pi.statusLabel().getStyleClass().add("failed");
                    pi.retryButton().setVisible(!disposed);
                    pi.retryButton().setManaged(pi.retryButton().isVisible());
                }
                default -> {
                    pi.bar().setProgress(0);
                    pi.statusLabel().setText("Pending");
                    pi.retryButton().setVisible(false);
                    pi.retryButton().setManaged(false);
                }
            }
        });
    }

    /**
     * Granular cleanup progress (P1): called from the cleanup worker threads as
     * each of the ~21 categories finishes. Coalesced through a single runLater
     * per callback (bounded, cheap) to show "Scanning… 4/21" live.
     */
    private void updateCleanupProgress(int done, int total, int generation) {
        if (cleanupItem == null) return;
        Platform.runLater(() -> {
            if (generation >= 0 && isScanStale(generation)) return;
            if (cleanupItem.bar().getProgress() == 1) return; // already done
            cleanupItem.statusLabel().setText("Scanning\u2026 " + Math.min(done, total) + "/" + total);
        });
    }

    private void resetProgressItems() {
        for (ProgressItem pi : new ProgressItem[]{driverItem, softwareItem, cleanupItem}) {
            if (pi == null) continue;
            pi.bar().setProgress(0);
            pi.statusLabel().setText("Pending");
            pi.box().getStyleClass().removeAll("active", "done", "failed");
            pi.statusLabel().getStyleClass().removeAll("active", "done", "failed");
            pi.retryButton().setVisible(false);
            pi.retryButton().setManaged(false);
        }
    }

    private void hideRetryButtons() {
        for (ProgressItem pi : new ProgressItem[]{driverItem, softwareItem, cleanupItem}) {
            if (pi == null || pi.retryButton() == null) continue;
            ProgressItem p = pi;
            if (Platform.isFxApplicationThread()) {
                p.retryButton().setVisible(false);
                p.retryButton().setManaged(false);
            } else {
                Platform.runLater(() -> {
                    p.retryButton().setVisible(false);
                    p.retryButton().setManaged(false);
                });
            }
        }
    }

    /**
     * After a scan finishes (idle), keep the progress row visible when any
     * category errored so its inline Retry stays clickable. Must run on the FX
     * thread after {@code scanning} was cleared.
     */
    private void revealRetryForErrors() {
        if (disposed || progressRow == null) return;
        boolean driverFailed = false;
        boolean softwareFailed = false;
        boolean cleanupFailed = false;
        boolean cleanupTimeout = false;
        try {
            for (IssueCategory ic : issues) {
                if (ic == null || !ic.isError()) continue;
                String cat = ic.categoryProperty().get();
                String src = ic.sourceProperty().get();
                if ("Outdated Drivers".equals(cat) || "Drivers".equals(src)) {
                    driverFailed = true;
                } else if ("Outdated Software".equals(cat) || "Software".equals(src)) {
                    softwareFailed = true;
                } else if ("Cleanup".equals(src) || "System Cleanup".equals(cat)) {
                    cleanupFailed = true;
                    String detail = ic.countTextProperty().get();
                    if (detail != null && detail.contains("Timed out")) cleanupTimeout = true;
                }
            }
        } catch (Exception ignored) {}
        if (!driverFailed && !softwareFailed && !cleanupFailed) return;
        progressRow.setVisible(true);
        progressRow.setManaged(true);
        // Legacy overload (generation -1) always shows Retry when idle.
        if (driverFailed) updateCategoryProgress(0, "failed");
        if (softwareFailed) updateCategoryProgress(1, "failed");
        if (cleanupFailed) updateCategoryProgress(2, cleanupTimeout ? "timeout" : "failed");
    }

    // ── Status Bar + timestamp ticker ─────────────────────────────────────

    private HBox createStatusBar() {
        snapshotLabel = new Label();
        snapshotLabel.getStyleClass().add("dashboard-snapshot");

        timestampLabel = new Label();
        timestampLabel.getStyleClass().add("dashboard-timestamp");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(8, snapshotLabel, spacer, timestampLabel);
        bar.getStyleClass().add("dashboard-status-bar");
        return bar;
    }

    private void startTimestampTicker() {
        try {
            stopTimestampTicker();
            timestampTimeline = new Timeline(
                    new KeyFrame(javafx.util.Duration.seconds(60), e -> {
                        if (!disposed) updateTimestamp();
                    }));
            timestampTimeline.setCycleCount(Timeline.INDEFINITE);
            timestampTimeline.play();
        } catch (Exception ignored) {}
    }

    private void stopTimestampTicker() {
        try {
            if (timestampTimeline != null) {
                timestampTimeline.stop();
                timestampTimeline = null;
            }
        } catch (Exception ignored) {}
    }

    private void updateTimestamp() {
        updateTimestamp(-1);
    }

    private void updateTimestamp(int generation) {
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
            Platform.runLater(() -> {
                if (generation >= 0 && isScanStale(generation)) return;
                timestampLabel.setText(text);
            });
        }
    }

    private void setSnapshotNote(String text) {
        Platform.runLater(() -> {
            if (snapshotLabel != null) snapshotLabel.setText(text == null ? "" : text);
        });
    }

    // ── Snapshot restore (P3) ─────────────────────────────────────────────

    private void restoreSnapshot() {
        try {
            DashboardSummaryStore.Snapshot snap = DashboardSummaryStore.load();
            if (snap == null || disposed) return;
            List<DashboardSummaryStore.IssueSnapshot> rows = snap.issues();
            if (rows == null) return;
            List<IssueCategory> restored = new ArrayList<>();
            for (DashboardSummaryStore.IssueSnapshot s : rows) {
                if (s == null || s.category() == null || s.category().isBlank()) continue;
                if (s.error()) {
                    restored.add(IssueCategory.error(
                            s.category(), s.countText(), s.sizeText(), s.source(), s.sizeBytes()));
                } else if (s.details() != null && !s.details().isEmpty()) {
                    restored.add(new IssueCategory(
                            s.category(), s.countText(), s.sizeText(), s.source(), s.sizeBytes(), s.details()));
                } else if (s.source() != null
                        && ("Drivers".equals(s.source()) || "Software".equals(s.source()))) {
                    restored.add(new IssueCategory(s.category(), s.count(), s.sizeBytes(), s.source()));
                } else {
                    restored.add(new IssueCategory(
                            s.category(), s.countText(), s.sizeText(), s.source(), s.sizeBytes()));
                }
            }
            lastScanTime = Instant.ofEpochMilli(snap.scannedEpochMilli());
            issues.setAll(restored);
            showResultsView();
            if (restored.isEmpty()) {
                showHealthyState();
            } else {
                hideHealthyState();
            }
            updateSummaryCards();
            updateTimestamp();
            statusLabel.setText("Restored last scan — press \"Scan for issues\" for fresh results.");
            setSnapshotNote("Restored snapshot");
        } catch (Exception e) {
            AppLogger.warning("Dashboard snapshot restore failed: " + e.getMessage());
        }
    }

    /**
     * Called by {@code App} after the main window is shown. Honors the existing
     * {@code scanOnStartup} setting without adding new settings keys.
     */
    public void maybeAutoScan() {
        try {
            if (disposed || !AppPaths.isWindows()) return;
            if (scanning.get() || busy.get()) return;
            boolean auto = false;
            try {
                auto = settingsStore.load().scanOnStartup();
            } catch (Exception ignored) {}
            if (!auto) return;
            Platform.runLater(this::startScan);
        } catch (Exception ignored) {}
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

    // ── Table (with severity pills + details) ─────────────────────────────

    private TableView<IssueCategory> buildTable() {
        TableView<IssueCategory> t = new TableView<>(issues);
        t.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<IssueCategory, String> categoryCol = new TableColumn<>("Category");
        categoryCol.setCellValueFactory(c -> c.getValue().categoryProperty());
        categoryCol.setPrefWidth(220);

        TableColumn<IssueCategory, String> countCol = new TableColumn<>("Issues Found");
        countCol.setCellValueFactory(c -> c.getValue().countTextProperty());
        countCol.setPrefWidth(160);

        TableColumn<IssueCategory, String> sizeCol = new TableColumn<>("Size");
        sizeCol.setCellValueFactory(c -> c.getValue().sizeTextProperty());
        sizeCol.setPrefWidth(110);

        TableColumn<IssueCategory, String> sourceCol = new TableColumn<>("Source");
        sourceCol.setCellValueFactory(c -> c.getValue().sourceProperty());
        sourceCol.setPrefWidth(90);

        TableColumn<IssueCategory, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().severity()));
        statusCol.setPrefWidth(130);
        statusCol.setCellFactory(col -> new TableCell<>() {
            private final Label pill = new Label();
            {
                pill.getStyleClass().add("severity-pill");
                setGraphic(pill);
                setText(null);
            }
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    pill.setVisible(false);
                    return;
                }
                IssueCategory ic = getTableRow() != null ? getTableRow().getItem() : null;
                pill.setVisible(true);
                pill.setText(item);
                pill.getStyleClass().removeAll(
                        "severity-error", "severity-warn", "severity-ok", "severity-info");
                if (ic != null && ic.isError()) {
                    pill.getStyleClass().add("severity-error");
                } else if ("Updates".equals(item)) {
                    pill.getStyleClass().add("severity-warn");
                } else if ("Reclaimable".equals(item)) {
                    pill.getStyleClass().add("severity-info");
                } else {
                    pill.getStyleClass().add("severity-ok");
                }
            }
        });

        t.getColumns().addAll(categoryCol, countCol, sizeCol, sourceCol, statusCol);

        t.setRowFactory(tv -> {
            TableRow<IssueCategory> row = new TableRow<>();
            row.setOnMouseClicked(event -> {
                if (row.isEmpty() || row.getItem() == null || row.getItem().isError()) return;
                if (tabSwitchRequest == null) return;
                String source = row.getItem().sourceProperty().get();
                int tabIndex = switch (source) {
                    case "Drivers" -> 1;
                    case "Software" -> 3;
                    case "Cleanup" -> 7;
                    default -> -1;
                };
                if (tabIndex >= 0) tabSwitchRequest.accept(tabIndex);
            });
            row.setOnMouseEntered(e -> {
                if (!row.isEmpty() && row.getItem() != null && !row.getItem().isError()) {
                    row.getStyleClass().add("dashboard-clickable");
                }
            });
            row.setOnMouseExited(e -> row.getStyleClass().remove("dashboard-clickable"));
            // Per-row tooltip with top details (read-only, no extra scans).
            row.itemProperty().addListener((obs, oldV, newV) -> {
                if (newV != null && newV.getDetails() != null && !newV.getDetails().isEmpty()) {
                    String tip = String.join("\n", newV.getDetails().stream().limit(MAX_DETAIL_LINES).toList());
                    if (!newV.isError() && tabSwitchRequest != null) {
                        tip += "\n\nClick to open details →";
                    }
                    row.setTooltip(new Tooltip(tip));
                } else if (newV != null && !newV.isError() && tabSwitchRequest != null) {
                    row.setTooltip(new Tooltip("Click to open details →"));
                } else {
                    row.setTooltip(null);
                }
            });
            return row;
        });

        t.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> updateDetailsLabel(newV));

        return t;
    }

    private void updateDetailsLabel(IssueCategory selected) {
        if (detailsLabel == null) return;
        if (selected == null || selected.getDetails() == null || selected.getDetails().isEmpty()) {
            detailsLabel.setVisible(false);
            detailsLabel.setManaged(false);
            detailsLabel.setText("");
            return;
        }
        List<String> lines = selected.getDetails().stream().limit(MAX_DETAIL_LINES).toList();
        String header = selected.categoryProperty().get() + " — top " + lines.size() + ": ";
        detailsLabel.setText(header + String.join(" · ", lines));
        detailsLabel.setVisible(true);
        detailsLabel.setManaged(true);
    }

    // ── Scan Logic ────────────────────────────────────────────────────────

    private void startScan() {
        if (disposed) {
            return;
        }
        // Local guard first: Dashboard is read-only and never holds global busy,
        // so concurrent Dashboard scans are prevented locally.
        if (!scanning.compareAndSet(false, true)) {
            statusLabel.setText("A Dashboard scan is already in progress — press Stop to cancel it.");
            return;
        }
        // Defer to mutating operations running elsewhere, but do NOT acquire
        // global busy — a read-only overview must not freeze all other tabs.
        if (busy.get()) {
            scanning.set(false);
            statusLabel.setText("Another operation is in progress — please wait.");
            return;
        }
        final int generation = ++scanGeneration;
        final CancellationToken token = new CancellationToken();
        scanCancellationToken = token;
        // Do NOT clear previous results yet: the admin check runs off the FX
        // thread and a non-admin result must preserve existing data (no wipe).
        progressBar.setProgress(0);
        progressBar.setVisible(true);
        stopButton.setVisible(true);
        stopButton.setDisable(false);
        scanButton.setDisable(true);
        statusLabel.setText("Checking privileges\u2026");
        summaryLabel.setVisible(false);
        setSnapshotNote("");
        hideRetryButtons();

        try {
            scanFuture = dashboardPool.submit(() -> {
                boolean isAdmin;
                try {
                    isAdmin = adminCheck.getAsBoolean();
                } catch (Exception ex) {
                    isAdmin = false;
                }
                if (!isAdmin) {
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        statusLabel.setText("Run as Administrator to scan for issues.");
                        progressRow.setVisible(false);
                        progressRow.setManaged(false);
                        progressBar.setVisible(false);
                        stopButton.setVisible(false);
                        stopButton.setDisable(true);
                        scanButton.setDisable(busy.get());
                        // Previous results (if any) are intentionally preserved.
                    });
                    scanning.set(false);
                    return;
                }
                if (isScanStale(generation) || token.isCancelled() || disposed) {
                    scanning.set(false);
                    return;
                }
                // Admin confirmed — now it is safe to reset the view.
                Platform.runLater(() -> {
                    if (isScanStale(generation)) return;
                    issues.clear();
                    updateDetailsLabel(null);
                    showResultsView();
                    hideHealthyState();
                    resetProgressItems();
                    progressRow.setVisible(true);
                    progressRow.setManaged(true);
                    statusLabel.setText("Scanning system for issues\u2026");
                });
                lastScanTime = Instant.now();
                AtomicInteger scansComplete = new AtomicInteger();
                int totalScans = 3;
                try {
                    // Sub-scan workers run on the dedicated dashboardPool so the
                    // inner services can safely use ioPool (catalog providers)
                    // and cleanPool (cleanup categories) without self-starvation.
                    // dashboardPool.submit (not runAsync) is used so cancel(true)
                    // truly interrupts PowerShell/file-walk workers.
                    Future<?> driverScan = dashboardPool.submit(
                            () -> scanDrivers(generation, token, scansComplete, totalScans));
                    Future<?> softwareScan = dashboardPool.submit(
                            () -> scanSoftware(generation, token, scansComplete, totalScans));
                    Future<?> cleanupScan = dashboardPool.submit(
                            () -> scanCleanup(generation, token, scansComplete, totalScans));
                    driverTask = driverScan;
                    softwareTask = softwareScan;
                    cleanupTask = cleanupScan;

                    Set<Integer> timedOut;
                    try {
                        timedOut = DashboardScanCoordinator.awaitAllInterruptible(
                                List.of(driverScan, softwareScan, cleanupScan),
                                PER_TASK_BUDGETS,
                                () -> isScanStale(generation),
                                token,
                                () -> disposed,
                                DASHBOARD_SCAN_TIMEOUT_SECONDS);
                    } catch (TimeoutException te) {
                        // Overall budget: partial results kept (same contract as before).
                        throw te;
                    }
                    if (!timedOut.isEmpty()) {
                        handlePerTaskTimeouts(timedOut, generation, token);
                    }

                    boolean cancelled = isScanStale(generation) || token.isCancelled() || disposed;
                    if (cancelled) {
                        Platform.runLater(() -> {
                            progressRow.setVisible(false);
                            progressRow.setManaged(false);
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                            if (!disposed) {
                                statusLabel.setText("Scan stopped.");
                            }
                        });
                        return;
                    }

                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;

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

                        long preErrorCount = issues.stream().filter(IssueCategory::isError).count();
                        if (preErrorCount == 0) {
                            progressRow.setVisible(false);
                            progressRow.setManaged(false);
                        } else {
                            // Keep progress row visible so failed categories keep Retry clickable.
                            progressRow.setVisible(true);
                            progressRow.setManaged(true);
                        }

                        if (issues.isEmpty()) {
                            showHealthyState();
                            statusLabel.setText("Scan complete \u2014 no issues found.");
                        } else {
                        long errorCount = issues.stream().filter(IssueCategory::isError).count();
                        int cleanupCategoryCount = (int) issues.stream()
                                .filter(ic -> !ic.isError() && "Cleanup".equals(ic.sourceProperty().get()))
                                .count();
                        int totalDriverSoftware = 0;
                        for (IssueCategory ic : issues) {
                            if (ic.isError()) continue;
                            String name = ic.categoryProperty().get();
                            if ("Outdated Drivers".equals(name) || "Outdated Software".equals(name)) {
                                totalDriverSoftware += ic.getCount();
                            }
                        }
                        long totalSize = issues.stream()
                                .filter(ic -> !ic.isError() && "Cleanup".equals(ic.sourceProperty().get()))
                                .mapToLong(IssueCategory::getSizeBytes).sum();
                        String errorNote = errorCount > 0
                                ? " (" + errorCount + " scan error" + (errorCount == 1 ? "" : "s") + ")"
                                : "";
                        statusLabel.setText("Scan complete \u2014 "
                                + totalDriverSoftware + " outdated driver" + (totalDriverSoftware == 1 ? "" : "s")
                                + "/software, " + cleanupCategoryCount
                                + " cleanup categor" + (cleanupCategoryCount == 1 ? "y" : "ies")
                                + " with reclaimable space." + errorNote);
                        summaryLabel.setText("Total: " + totalDriverSoftware + " outdated driver"
                                + (totalDriverSoftware == 1 ? "" : "s") + "/software, "
                                + cleanupCategoryCount + " cleanup categor"
                                + (cleanupCategoryCount == 1 ? "y" : "ies") + ". "
                                + formatBytes(totalSize) + " can be freed." + errorNote);
                            summaryLabel.setVisible(true);
                        }
                        updateSummaryCards();
                        updateTimestamp(generation);
                        // Persist snapshot for instant startup next time (silent on failure).
                        try {
                            DashboardSummaryStore.save(lastScanTime, new ArrayList<>(issues));
                            setSnapshotNote("Snapshot saved");
                        } catch (Exception ignored) {}
                    });
                } catch (CancellationException | InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    if (!isScanStale(generation)) {
                        AppLogger.info("Dashboard scan cancelled");
                        Platform.runLater(() -> {
                            progressRow.setVisible(false);
                            progressRow.setManaged(false);
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                            statusLabel.setText("Scan stopped.");
                        });
                    }
                } catch (Exception ex) {
                    if (!isScanStale(generation) && !token.isCancelled()) {
                        // Timeout surfaces as TimeoutException with a clear message.
                        AppLogger.error("Dashboard scan failed", ex);
                        Platform.runLater(() -> {
                            if (isScanStale(generation)) return;
                            progressRow.setVisible(false);
                            progressRow.setManaged(false);
                            // Non-modal: never block the FX thread with showAndWait
                            // while teardown is still queued behind it.
                            statusLabel.setText("Scan failed: " + ex.getMessage());
                        });
                    } else if (!isScanStale(generation)) {
                        Platform.runLater(() -> {
                            if (isScanStale(generation)) return;
                            statusLabel.setText("Scan stopped.");
                        });
                    }
                } finally {
                    if (!isScanStale(generation)) {
                        scanFuture = null;
                    }
                    cancelSubScans();
                    scanning.set(false);
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        progressBar.setVisible(false);
                        stopButton.setVisible(false);
                        stopButton.setDisable(true);
                        scanButton.setDisable(busy.get());
                        revealRetryForErrors();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            AppLogger.error("Scan executor rejected task", ex);
            scanning.set(false);
            cancelSubScans();
            progressBar.setVisible(false);
            stopButton.setVisible(false);
            stopButton.setDisable(true);
            scanButton.setDisable(busy.get());
            statusLabel.setText("Scan unavailable \u2014 try again later.");
        }
    }

    /**
     * Adds explicit timeout rows for per-task soft-budget expiries. The
     * cancelled worker itself also marks its progress as failed; this only
     * ensures a visible error row exists so Retry can target it.
     */
    private void handlePerTaskTimeouts(Set<Integer> timedOut, int generation, CancellationToken token) {
        if (timedOut == null || timedOut.isEmpty()) return;
        if (isScanStale(generation) || (token != null && token.isCancelled()) || disposed) return;
        String[] names = {"Outdated Drivers", "Outdated Software", "System Cleanup"};
        String[] sources = {"Drivers", "Software", "Cleanup"};
        for (int idx : timedOut) {
            if (idx < 0 || idx > 2) continue;
            updateCategoryProgress(idx, "timeout", generation);
            final String name = names[idx];
            final String source = sources[idx];
            boolean alreadyPresent = false;
            try {
                // issues is only mutated on FX thread; read a snapshot safely via copy.
                // Iterating directly off-FX risks ConcurrentModification — instead
                // check inside the runLater below. Optimistically add; dupes avoided
                // by the worker having been cancelled before it could add.
                alreadyPresent = false;
            } catch (Exception ignored) {}
            if (!alreadyPresent) {
                Platform.runLater(() -> {
                    if (isScanStale(generation)) return;
                    boolean exists = issues.stream().anyMatch(ic ->
                            name.equals(ic.categoryProperty().get())
                                    || ("System Cleanup".equals(name)
                                    && "Cleanup".equals(ic.sourceProperty().get())));
                    if (!exists) {
                        issues.add(IssueCategory.error(
                                name, "Timed out — press Retry to rescan", "", source, 0));
                    }
                });
            }
        }
    }

    private boolean isScanStale(int generation) {
        return generation != scanGeneration;
    }

    private boolean isCancelled(int generation, CancellationToken token) {
        return disposed || isScanStale(generation) || (token != null && token.isCancelled())
                || Thread.currentThread().isInterrupted();
    }

    private void scanDrivers(int generation, CancellationToken token, AtomicInteger scansComplete, int totalScans) {
        if (isCancelled(generation, token)) return;
        updateCategoryProgress(0, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for outdated drivers\u2026");
        });
        IssueCategory success = null;
        IssueCategory failure = null;
        try {
            List<InstalledDriver> installed = driverScanServices().scanInstalled();
            if (isCancelled(generation, token)) return;
            List<DriverUpdateCandidate> candidates = catalogs().findUpdates(installed, token);
            if (isCancelled(generation, token)) return;
            // Filter ignored drivers so Dashboard count matches Drivers tab
            try {
                Set<String> excluded = loadExcludedDriverIdSet();
                if (!excluded.isEmpty()) {
                    candidates = candidates.stream()
                            .filter(c -> c.installed() == null || !excluded.contains(c.installed().deviceId()))
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception ex) {
                AppLogger.warning("Dashboard excluded filter failed: " + ex.getMessage());
            }
            if (isCancelled(generation, token)) return;
            if (!candidates.isEmpty()) {
                success = new IssueCategory(
                        "Outdated Drivers", candidates.size(), 0, "Drivers",
                        topDriverDetails(candidates));
            }
            updateCategoryProgress(0, "done", generation);
        } catch (CancellationException | InterruptedException ex) {
            Thread.currentThread().interrupt();
            AppLogger.info("Dashboard driver scan cancelled");
            updateCategoryProgress(0, "failed", generation);
        } catch (Exception ex) {
            if (isCancelled(generation, token)) {
                AppLogger.info("Dashboard driver scan cancelled");
                updateCategoryProgress(0, "failed", generation);
                return;
            }
            AppLogger.warning("Dashboard driver scan failed: " + ex.getMessage());
            updateCategoryProgress(0, "failed", generation);
            failure = IssueCategory.error("Outdated Drivers", "Error: " + ex.getMessage(), "", "Drivers", 0);
        }
        // Single batched FX mutation (P1): one runLater per sub-scan, not per row.
        if (success != null || failure != null) {
            final IssueCategory toAdd = success != null ? success : failure;
            Platform.runLater(() -> {
                if (isScanStale(generation)) return;
                issues.add(toAdd);
            });
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            progressBar.setProgress((double) done / totalScans);
        });
    }

    private List<String> topDriverDetails(List<DriverUpdateCandidate> candidates) {
        try {
            return candidates.stream()
                    .limit(MAX_DETAIL_LINES)
                    .map(c -> {
                        String name = c.installed() != null && c.installed().friendlyName() != null
                                ? c.installed().friendlyName() : "Unknown device";
                        String from = c.installed() != null && c.installed().driverVersion() != null
                                ? c.installed().driverVersion() : "?";
                        String to = c.availableVersion() != null ? c.availableVersion() : "?";
                        return name + " " + from + " → " + to;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Set<String> loadExcludedDriverIdSet() {
        try {
            com.sbtools.settings.AppSettings settings = settingsStore.load();
            Set<String> ids = new HashSet<>();
            for (String e : settings.excludedDriverIds()) {
                int t = e.lastIndexOf('\t');
                if (t < 0) t = e.lastIndexOf('\u001F');
                ids.add(t >= 0 ? e.substring(t + 1) : e);
            }
            return ids;
        } catch (Exception ex) {
            AppLogger.warning("Failed to load excluded drivers: " + ex.getMessage());
            return Set.of();
        }
    }

    private void scanSoftware(int generation, CancellationToken token, AtomicInteger scansComplete, int totalScans) {
        if (isCancelled(generation, token)) return;
        updateCategoryProgress(1, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for software updates\u2026");
        });
        IssueCategory toAdd = null;
        try {
            // Honour both Stop (token) and generation-staleness so Stop truly aborts winget/WU.
            List<SoftwareUpdateEntry> updates = softwareServices().scanAllConcurrent(
                    () -> isScanStale(generation) || (token != null && token.isCancelled()),
                    w -> {}, wu -> {});
            if (isCancelled(generation, token)) return;
            // Filter ignored software ids (same logic as SoftwareUpdateViewModel) so dashboard count matches Software tab
            List<SoftwareUpdateEntry> filteredUpdates = updates;
            try {
                com.sbtools.settings.AppSettings settings = new com.sbtools.settings.SettingsStore().load();
                List<String> skipped = settings.skippedSoftwareIds();
                if (skipped != null && !skipped.isEmpty()) {
                    java.util.Set<String> skippedSet = skipped.stream()
                            .map(s -> { int t = s.lastIndexOf('\t'); return t >= 0 ? s.substring(t + 1) : s; })
                            .collect(java.util.stream.Collectors.toSet());
                    filteredUpdates = updates.stream()
                            .filter(e -> e.id() == null || !skippedSet.contains(e.id()))
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception ex) {
                AppLogger.warning("Dashboard skipped filter failed: " + ex.getMessage());
            }
            if (isCancelled(generation, token)) return;
            if (!filteredUpdates.isEmpty()) {
                long totalSize = filteredUpdates.stream().mapToLong(SoftwareUpdateEntry::sizeBytes).sum();
                toAdd = new IssueCategory(
                        "Outdated Software", filteredUpdates.size(), totalSize, "Software",
                        topSoftwareDetails(filteredUpdates));
            }
            updateCategoryProgress(1, "done", generation);
        } catch (CancellationException ex) {
            AppLogger.info("Dashboard software scan cancelled");
            updateCategoryProgress(1, "failed", generation);
        } catch (Exception ex) {
            if (isCancelled(generation, token)) {
                AppLogger.info("Dashboard software scan cancelled");
                updateCategoryProgress(1, "failed", generation);
                return;
            }
            AppLogger.warning("Dashboard software scan failed: " + ex.getMessage());
            updateCategoryProgress(1, "failed", generation);
            toAdd = IssueCategory.error("Outdated Software", "Error: " + ex.getMessage(), "", "Software", 0);
        }
        if (toAdd != null) {
            final IssueCategory finalAdd = toAdd;
            Platform.runLater(() -> {
                if (isScanStale(generation)) return;
                issues.add(finalAdd);
            });
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            progressBar.setProgress((double) done / totalScans);
        });
    }

    private List<String> topSoftwareDetails(List<SoftwareUpdateEntry> updates) {
        try {
            return updates.stream()
                    .limit(MAX_DETAIL_LINES)
                    .map(e -> {
                        String n = e.getName() != null && !e.getName().isBlank() ? e.getName() : e.id();
                        String cur = e.getCurrentVersion() != null ? e.getCurrentVersion() : "?";
                        String avail = e.getAvailableVersion() != null ? e.getAvailableVersion() : "?";
                        return n + " " + cur + " → " + avail;
                    })
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private void scanCleanup(int generation, CancellationToken token, AtomicInteger scansComplete, int totalScans) {
        if (isCancelled(generation, token)) return;
        updateCategoryProgress(2, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for system cleanup opportunities\u2026");
        });
        List<IssueCategory> batch = new ArrayList<>();
        try {
            int totalCategories = CleanupCategory.values().length;
            AtomicInteger cleanupDone = new AtomicInteger();
            List<CleanupRow> results = cleanupServices().scan(
                    () -> updateCleanupProgress(cleanupDone.incrementAndGet(), totalCategories, generation),
                    com.sbtools.util.AppExecutors.cleanPool(), token);
            if (isCancelled(generation, token)) return;
            for (CleanupRow row : results) {
                if (isCancelled(generation, token)) return;
                if (row.getScanStatus() == CleanupRow.ScanStatus.ERROR) {
                    String detailText = row.getErrorMessage() != null ? row.getErrorMessage() : "Scan error";
                    batch.add(IssueCategory.error(
                            row.getCategory().getDisplayName(),
                            detailText,
                            "",
                            "Cleanup",
                            0));
                    continue;
                }
                if (row.getTotalBytes() <= 0 && (row.getItemCount() <= 0)) {
                    continue;
                }
                // Build display text from volatile scan counters (thread-safe) instead
                // of reading the FX StringProperty off this worker thread.
                final long sizeBytes = row.getTotalBytes();
                final int itemCount = row.getItemCount();
                final String detailText = sizeBytes > 0 && itemCount > 0
                        ? formatBytes(sizeBytes) + " (" + itemCount + " files)"
                        : sizeBytes > 0 ? formatBytes(sizeBytes)
                        : itemCount + " item" + (itemCount == 1 ? "" : "s");
                String sizeText = row.getTotalBytes() > 0 ? formatBytes(row.getTotalBytes()) : "";
                batch.add(new IssueCategory(
                        row.getCategory().getDisplayName(),
                        detailText,
                        sizeText,
                        "Cleanup",
                        sizeBytes));
            }
            updateCategoryProgress(2, "done", generation);
        } catch (CancellationException ex) {
            AppLogger.info("Dashboard cleanup scan cancelled");
            updateCategoryProgress(2, "failed", generation);
        } catch (Exception ex) {
            if (isCancelled(generation, token)) {
                AppLogger.info("Dashboard cleanup scan cancelled");
                updateCategoryProgress(2, "failed", generation);
                return;
            }
            AppLogger.warning("Dashboard cleanup scan failed: " + ex.getMessage());
            updateCategoryProgress(2, "failed", generation);
            batch.add(IssueCategory.error("System Cleanup", "Error: " + ex.getMessage(), "", "Cleanup", 0));
        }
        // Single batched FX mutation for all cleanup rows (P1).
        if (!batch.isEmpty() && !isCancelled(generation, token)) {
            final List<IssueCategory> toAdd = List.copyOf(batch);
            Platform.runLater(() -> {
                if (isScanStale(generation)) return;
                issues.addAll(toAdd);
            });
        }
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            progressBar.setProgress((double) done / totalScans);
        });
    }

    /**
     * Re-runs a single failed category without wiping the other results.
     * Read-only: delegates to the same scanX worker used by full scans.
     */
    private void retryCategory(int categoryIndex) {
        if (disposed) return;
        if (!scanning.compareAndSet(false, true)) {
            statusLabel.setText("A scan is already in progress — press Stop to cancel it.");
            return;
        }
        if (busy.get()) {
            scanning.set(false);
            statusLabel.setText("Another operation is in progress — please wait.");
            return;
        }
        boolean isAdmin;
        try {
            isAdmin = adminCheck.getAsBoolean();
        } catch (Exception ex) {
            isAdmin = false;
        }
        if (!isAdmin) {
            scanning.set(false);
            statusLabel.setText("Run as Administrator to scan for issues.");
            return;
        }
        final int generation = ++scanGeneration;
        final CancellationToken token = new CancellationToken();
        scanCancellationToken = token;
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        stopButton.setVisible(true);
        stopButton.setDisable(false);
        scanButton.setDisable(true);
        progressRow.setVisible(true);
        progressRow.setManaged(true);
        updateCategoryProgress(categoryIndex, "scanning", generation);
        String retryName = switch (categoryIndex) {
            case 0 -> "Outdated Drivers";
            case 1 -> "Outdated Software";
            default -> "System Cleanup";
        };
        statusLabel.setText("Retrying " + retryName + "\u2026");

        // Remove prior rows for this category on the FX thread (we are on FX here).
        if (categoryIndex == 0) {
            issues.removeIf(ic -> "Outdated Drivers".equals(ic.categoryProperty().get()));
        } else if (categoryIndex == 1) {
            issues.removeIf(ic -> "Outdated Software".equals(ic.categoryProperty().get()));
        } else {
            issues.removeIf(ic -> "Cleanup".equals(ic.sourceProperty().get()));
        }
        updateDetailsLabel(null);
        if (issues.isEmpty()) {
            hideHealthyState();
            showResultsView();
        }

        AtomicInteger done = new AtomicInteger();
        try {
            scanFuture = dashboardPool.submit(() -> {
                Future<?> single;
                long budget;
                if (categoryIndex == 0) {
                    single = dashboardPool.submit(() -> scanDrivers(generation, token, done, 1));
                    driverTask = single;
                    budget = DashboardScanCoordinator.DRIVER_TIMEOUT_SECONDS;
                } else if (categoryIndex == 1) {
                    single = dashboardPool.submit(() -> scanSoftware(generation, token, done, 1));
                    softwareTask = single;
                    budget = DashboardScanCoordinator.SOFTWARE_TIMEOUT_SECONDS;
                } else {
                    single = dashboardPool.submit(() -> scanCleanup(generation, token, done, 1));
                    cleanupTask = single;
                    budget = DashboardScanCoordinator.CLEANUP_TIMEOUT_SECONDS;
                }
                try {
                    DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(single),
                            new long[]{budget},
                            () -> isScanStale(generation),
                            token,
                            () -> disposed,
                            Math.max(60, budget + 30));
                    if (isScanStale(generation) || token.isCancelled() || disposed) {
                        Platform.runLater(() -> {
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                            statusLabel.setText("Scan stopped.");
                        });
                        return;
                    }
                    lastScanTime = Instant.now();
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        // Re-sort drivers/software to top (same order as full scan).
                        IssueCategory d = null;
                        IssueCategory s = null;
                        for (IssueCategory ic : issues) {
                            if ("Outdated Drivers".equals(ic.categoryProperty().get())) d = ic;
                            else if ("Outdated Software".equals(ic.categoryProperty().get())) s = ic;
                        }
                        if (d != null) issues.remove(d);
                        if (s != null) issues.remove(s);
                        if (d != null) issues.add(0, d);
                        if (s != null) issues.add(d != null ? 1 : 0, s);
                        if (issues.isEmpty()) {
                            showHealthyState();
                            statusLabel.setText("Scan complete \u2014 no issues found.");
                        } else {
                            hideHealthyState();
                            statusLabel.setText("Retry complete.");
                        }
                        updateSummaryCards();
                        updateTimestamp(generation);
                        try {
                            DashboardSummaryStore.save(lastScanTime, new ArrayList<>(issues));
                        } catch (Exception ignored) {}
                    });
                } catch (CancellationException | InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        statusLabel.setText("Scan stopped.");
                    });
                    updateCategoryProgress(categoryIndex, "failed", generation);
                } catch (TimeoutException te) {
                    AppLogger.warning("Dashboard retry timed out: " + te.getMessage());
                    updateCategoryProgress(categoryIndex, "timeout", generation);
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        statusLabel.setText("Retry timed out — partial results kept.");
                    });
                } catch (Exception ex) {
                    AppLogger.error("Dashboard retry failed", ex);
                    updateCategoryProgress(categoryIndex, "failed", generation);
                } finally {
                    if (!isScanStale(generation)) scanFuture = null;
                    cancelSubScans();
                    scanning.set(false);
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        progressBar.setVisible(false);
                        stopButton.setVisible(false);
                        stopButton.setDisable(true);
                        scanButton.setDisable(busy.get());
                        revealRetryForErrors();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            scanning.set(false);
            cancelSubScans();
            progressBar.setVisible(false);
            stopButton.setVisible(false);
            stopButton.setDisable(true);
            scanButton.setDisable(busy.get());
            statusLabel.setText("Scan unavailable \u2014 try again later.");
        }
    }

    private void stopScan() {
        if (!scanning.get()) {
            return;
        }
        scanGeneration++;
        CancellationToken token = scanCancellationToken;
        if (token != null) token.cancel();
        // Cancel inner workers first so the interruptible outer wait unblocks;
        // cancelling only the outer Future never interrupted join().
        cancelSubScans();
        Future<?> f = scanFuture;
        if (f != null) {
            f.cancel(true);
            scanFuture = null;
        }
        // Local flag only — never decrement global busy we do not own.
        scanning.set(false);
        progressBar.setVisible(false);
        stopButton.setVisible(false);
        stopButton.setDisable(true);
        scanButton.setDisable(busy.get());
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
        if (detailsLabel != null) {
            detailsLabel.setVisible(false);
            detailsLabel.setManaged(false);
        }
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
        return FormatUtils.formatBytes(bytes);
    }

    public static class IssueCategory {
        private final SimpleStringProperty category;
        private final SimpleStringProperty countText;
        private final SimpleStringProperty sizeText;
        private final SimpleStringProperty source;
        private final int count;
        private final long sizeBytes;
        private final boolean error;
        private final List<String> details;

        public IssueCategory(String category, int count, long sizeBytes, String source) {
            this(category, count, sizeBytes, source, List.of());
        }

        public IssueCategory(String category, int count, long sizeBytes, String source, List<String> details) {
            this.category = new SimpleStringProperty(category);
            this.count = count;
            this.sizeBytes = sizeBytes;
            this.error = false;
            this.countText = new SimpleStringProperty(count + " issue" + (count == 1 ? "" : "s"));
            this.sizeText = new SimpleStringProperty(sizeBytes > 0 ? formatBytes(sizeBytes) : "");
            this.source = new SimpleStringProperty(source);
            this.details = details == null ? List.of() : List.copyOf(details);
        }

        public IssueCategory(String category, String detailText, String sizeText, String source, long sizeBytes) {
            this(category, detailText, sizeText, source, sizeBytes, List.of());
        }

        public IssueCategory(String category, String detailText, String sizeText, String source, long sizeBytes,
                List<String> details) {
            this.category = new SimpleStringProperty(category);
            this.count = 1;
            this.sizeBytes = sizeBytes;
            this.error = false;
            this.countText = new SimpleStringProperty(detailText);
            this.sizeText = new SimpleStringProperty(sizeText);
            this.source = new SimpleStringProperty(source);
            this.details = details == null ? List.of() : List.copyOf(details);
        }

        public static IssueCategory error(String category, String detailText, String sizeText, String source, long sizeBytes) {
            return new IssueCategory(category, detailText, sizeText, source, sizeBytes, true);
        }

        private IssueCategory(String category, String detailText, String sizeText, String source, long sizeBytes, boolean error) {
            this.category = new SimpleStringProperty(category);
            this.count = 1;
            this.sizeBytes = sizeBytes;
            this.error = error;
            this.countText = new SimpleStringProperty(detailText);
            this.sizeText = new SimpleStringProperty(sizeText);
            this.source = new SimpleStringProperty(source);
            this.details = List.of();
        }

        public SimpleStringProperty categoryProperty() { return category; }
        public SimpleStringProperty countTextProperty() { return countText; }
        public SimpleStringProperty sizeTextProperty() { return sizeText; }
        public SimpleStringProperty sourceProperty() { return source; }
        public int getCount() { return count; }
        public long getSizeBytes() { return sizeBytes; }
        public boolean isError() { return error; }
        public List<String> getDetails() { return details; }

        /**
         * Read-only severity for the Status pill: Error / Updates / Reclaimable / OK.
         */
        public String severity() {
            if (error) return "Error";
            String src = source.get();
            if ("Drivers".equals(src) || "Software".equals(src)) return "Updates";
            if ("Cleanup".equals(src)) return "Reclaimable";
            return "Info";
        }
    }
}
