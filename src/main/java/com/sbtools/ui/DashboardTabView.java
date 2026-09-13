package com.sbtools.ui;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanupService;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.catalog.DriverCatalogAggregator;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.software.SoftwareUpdateEntry;
import com.sbtools.software.SoftwareUpdateScanCache;
import com.sbtools.software.SoftwareUpdateService;
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
import javafx.scene.input.KeyEvent;
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
    /** Local reentrancy guard. */
    private final AtomicBoolean scanning = new AtomicBoolean(false);
    /** Exactly-once acquire/release for ref-counted {@link com.sbtools.util.BusyProperty}. */
    private final AtomicBoolean busyHeld = new AtomicBoolean(false);
    private volatile PreScanUiState preScanUiState;

    private record PreScanUiState(
            int generation,
            List<IssueCategory> issues,
            Instant lastScanTime,
            boolean welcomeVisible,
            boolean resultsVisible,
            boolean healthyVisible,
            String snapshotNote) {}

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
    private volatile CancellationToken driverChildToken;
    private volatile CancellationToken softwareChildToken;
    private volatile CancellationToken cleanupChildToken;
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
        cancelChildTokens();
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
        releaseBusyOnce();
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
            cancelFuture(f);
        }
        driverTask = null;
        softwareTask = null;
        cleanupTask = null;
    }

    private static void cancelFuture(Future<?> f) {
        if (f != null && !f.isDone()) {
            try {
                f.cancel(true);
            } catch (Exception ignored) {}
        }
    }

    private static void cancelToken(CancellationToken t) {
        if (t != null) {
            try {
                t.cancel();
            } catch (Exception ignored) {}
        }
    }

    private void cancelChildTokens() {
        cancelToken(driverChildToken);
        cancelToken(softwareChildToken);
        cancelToken(cleanupChildToken);
        driverChildToken = null;
        softwareChildToken = null;
        cleanupChildToken = null;
    }

    /**
     * Teardown for a finished scan generation: cancels only this generation's
     * handles and clears a field only when it still references ours, so a slow
     * teardown can never kill a newer scan started via Stop -> Scan.
     */
    private void teardownGeneration(Future<?> driverScan, Future<?> softwareScan, Future<?> cleanupScan,
            CancellationToken driverChild, CancellationToken softwareChild, CancellationToken cleanupChild) {
        cancelFuture(driverScan);
        cancelFuture(softwareScan);
        cancelFuture(cleanupScan);
        cancelToken(driverChild);
        cancelToken(softwareChild);
        cancelToken(cleanupChild);
        if (driverTask == driverScan) driverTask = null;
        if (softwareTask == softwareScan) softwareTask = null;
        if (cleanupTask == cleanupScan) cleanupTask = null;
        if (driverChildToken == driverChild) driverChildToken = null;
        if (softwareChildToken == softwareChild) softwareChildToken = null;
        if (cleanupChildToken == cleanupChild) cleanupChildToken = null;
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
                        "Find winget apps and Windows Update items with pending updates (Store apps not checked)", 3),
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
            card.setFocusTraversable(true);
            card.setAccessibleText(title);
            Runnable navigate = () -> tabSwitchRequest.accept(tabIndex);
            card.setOnMouseClicked(e -> navigate.run());
            card.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                    navigate.run();
                    e.consume();
                }
            });
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
     * each of the ~40 categories finishes. Coalesced through a single runLater
     * per callback (bounded, cheap) to show "Scanning… 4/40" live.
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
        boolean driverRetry = driverFailed || progressCategoryNeedsRetry(0);
        boolean softwareRetry = softwareFailed || progressCategoryNeedsRetry(1);
        boolean cleanupRetry = cleanupFailed || progressCategoryNeedsRetry(2);
        if (!driverRetry && !softwareRetry && !cleanupRetry) return;
        progressRow.setVisible(true);
        progressRow.setManaged(true);
        if (driverRetry) updateCategoryProgress(0, "failed");
        if (softwareRetry) updateCategoryProgress(1, "failed");
        if (cleanupRetry) {
            boolean timeout = cleanupTimeout
                    || (cleanupItem != null && "Timed out".equals(cleanupItem.statusLabel().getText()));
            updateCategoryProgress(2, timeout ? "timeout" : "failed");
        }
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
            row.setOnMouseClicked(event -> openIssueRowTab(row.getItem()));
            row.addEventHandler(KeyEvent.KEY_PRESSED, e -> {
                if (e.getCode() != KeyCode.ENTER) return;
                if (row.isEmpty()) return;
                openIssueRowTab(row.getItem());
                e.consume();
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

    private void openIssueRowTab(IssueCategory item) {
        if (item == null || item.isError() || tabSwitchRequest == null) return;
        int tabIndex = tabIndexForSource(item.sourceProperty().get());
        if (tabIndex >= 0) tabSwitchRequest.accept(tabIndex);
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

    private void acquireDashboardBusy() {
        if (busyHeld.compareAndSet(false, true)) {
            busy.set(true);
        }
    }

    private void releaseBusyOnce() {
        if (busyHeld.compareAndSet(true, false)) {
            try {
                busy.set(false);
            } catch (Exception ignored) {
            }
        }
    }

    private void capturePreScanUi(int generation) {
        preScanUiState = new PreScanUiState(
                generation,
                new ArrayList<>(issues),
                lastScanTime,
                welcomeBox.isVisible(),
                resultsBox.isVisible(),
                healthyBox != null && healthyBox.isVisible(),
                snapshotLabel != null ? snapshotLabel.getText() : "");
    }

    private void restorePreScanUi(int generation) {
        PreScanUiState snap = preScanUiState;
        if (snap == null || snap.generation != generation) return;
        issues.setAll(snap.issues());
        lastScanTime = snap.lastScanTime();
        updateDetailsLabel(null);
        welcomeBox.setVisible(snap.welcomeVisible());
        welcomeBox.setManaged(snap.welcomeVisible());
        resultsBox.setVisible(snap.resultsVisible());
        resultsBox.setManaged(snap.resultsVisible());
        if (healthyBox != null) {
            healthyBox.setVisible(snap.healthyVisible());
            healthyBox.setManaged(snap.healthyVisible());
        }
        if (table != null) {
            table.setVisible(!snap.healthyVisible() && snap.resultsVisible());
            table.setManaged(table.isVisible());
        }
        updateSummaryCards();
        updateTimestamp();
        if (snapshotLabel != null) snapshotLabel.setText(snap.snapshotNote() == null ? "" : snap.snapshotNote());
        preScanUiState = null;
    }

    private void clearPreScanUiForGeneration(int generation) {
        PreScanUiState snap = preScanUiState;
        if (snap != null && snap.generation == generation) {
            preScanUiState = null;
        }
    }

    static int tabIndexForSource(String source) {
        if (source == null) return -1;
        return switch (source) {
            case "Drivers" -> 1;
            case "Software" -> 3;
            case "Cleanup" -> 7;
            default -> -1;
        };
    }

    static boolean shouldKeepProgressRowVisible(long errorRowCount, boolean driverProgressFailed,
            boolean softwareProgressFailed, boolean cleanupProgressFailed) {
        if (errorRowCount > 0) return true;
        return driverProgressFailed || softwareProgressFailed || cleanupProgressFailed;
    }

    private boolean progressCategoryNeedsRetry(int categoryIndex) {
        ProgressItem pi = switch (categoryIndex) {
            case 0 -> driverItem;
            case 1 -> softwareItem;
            default -> cleanupItem;
        };
        if (pi == null) return false;
        String t = pi.statusLabel().getText();
        return "Failed".equals(t) || "Timed out".equals(t);
    }

    private void finishSubScanProgress(int generation, AtomicInteger scansComplete, int totalScans) {
        int done = scansComplete.incrementAndGet();
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            progressBar.setProgress((double) done / totalScans);
        });
    }

    // ── Scan Logic ────────────────────────────────────────────────────────

    private void startScan() {
        if (disposed) {
            return;
        }
        if (!scanning.compareAndSet(false, true)) {
            statusLabel.setText("A Dashboard scan is already in progress — press Stop to cancel it.");
            return;
        }
        if (busy.get()) {
            scanning.set(false);
            statusLabel.setText("Another operation is in progress — please wait.");
            return;
        }
        acquireDashboardBusy();
        final int generation = ++scanGeneration;
        capturePreScanUi(generation);
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
                    return;
                }
                if (isScanStale(generation) || token.isCancelled() || disposed) {
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
                AtomicInteger scansComplete = new AtomicInteger();
                int totalScans = 3;
                // Per-category child tokens: a soft-budget timeout cancels only the
                // slow category (cooperative abort for token-polling inner services)
                // without poisoning its siblings' shared parent token.
                CancellationToken driverChild = new CancellationToken();
                CancellationToken softwareChild = new CancellationToken();
                CancellationToken cleanupChild = new CancellationToken();
                driverChildToken = driverChild;
                softwareChildToken = softwareChild;
                cleanupChildToken = cleanupChild;
                Future<?> driverScan = null;
                Future<?> softwareScan = null;
                Future<?> cleanupScan = null;
                try {
                    // Sub-scan workers run on the dedicated dashboardPool so the
                    // inner services can safely use ioPool (catalog providers)
                    // without self-starvation. Cleanup uses its own isolated pool
                    // per scan (see scanCleanup) so orphans never starve the next scan.
                    // dashboardPool.submit (not runAsync) is used so cancel(true)
                    // truly interrupts PowerShell/file-walk workers.
                    driverScan = dashboardPool.submit(
                            () -> scanDrivers(generation, token, driverChild, scansComplete, totalScans));
                    softwareScan = dashboardPool.submit(
                            () -> scanSoftware(generation, token, softwareChild, scansComplete, totalScans));
                    cleanupScan = dashboardPool.submit(
                            () -> scanCleanup(generation, token, cleanupChild, scansComplete, totalScans));
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
                                DASHBOARD_SCAN_TIMEOUT_SECONDS,
                                List.of(driverChild, softwareChild, cleanupChild));
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
                        boolean keepProgress = shouldKeepProgressRowVisible(
                                preErrorCount,
                                progressCategoryNeedsRetry(0),
                                progressCategoryNeedsRetry(1),
                                progressCategoryNeedsRetry(2));
                        if (!keepProgress) {
                            progressRow.setVisible(false);
                            progressRow.setManaged(false);
                        } else {
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
                        lastScanTime = Instant.now();
                        updateTimestamp(generation);
                        clearPreScanUiForGeneration(generation);
                        try {
                            DashboardSummaryStore.save(lastScanTime, new ArrayList<>(issues));
                            setSnapshotNote("Snapshot saved");
                        } catch (Exception ignored) {}
                    });
                } catch (CancellationException ex) {
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
                } catch (InterruptedException ex) {
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
                    teardownGeneration(driverScan, softwareScan, cleanupScan,
                            driverChild, softwareChild, cleanupChild);
                    scanning.set(false);
                    Platform.runLater(() -> {
                        if (!isScanStale(generation)) {
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                            revealRetryForErrors();
                        }
                        releaseBusyOnce();
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            AppLogger.error("Scan executor rejected task", ex);
            scanning.set(false);
            releaseBusyOnce();
            cancelChildTokens();
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

    /**
     * Parent + per-category child cancellation. The shared parent covers Stop /
     * new-scan / overall-timeout; the child covers this category's soft-budget
     * timeout in isolation so one slow category never poisons its siblings.
     */
    private boolean isCancelledAny(int generation, CancellationToken parent, CancellationToken child) {
        if (disposed || isScanStale(generation) || Thread.currentThread().isInterrupted()) return true;
        if (parent != null && parent.isCancelled()) return true;
        return child != null && child.isCancelled();
    }

    private void scanDrivers(int generation, CancellationToken parent, CancellationToken child,
            AtomicInteger scansComplete, int totalScans) {
        try {
        if (isCancelledAny(generation, parent, child)) return;
        updateCategoryProgress(0, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for outdated drivers\u2026");
        });
        IssueCategory success = null;
        IssueCategory failure = null;
        CancellationToken effectiveChild = child != null ? child : parent;
        try {
            List<InstalledDriver> installed = driverScanServices().scanInstalled();
            if (isCancelledAny(generation, parent, child)) return;
            // Purge reboot-pending completed by a reboot so Dashboard does not
            // report stale REBOOT entries forever (same logic as Drivers tab).
            try {
                var rebootStore = new com.sbtools.drivers.RebootPendingStore();
                rebootStore.purgeAfterReboot();
                if (installed != null) {
                    Set<String> installedIds = new HashSet<>();
                    for (InstalledDriver d : installed) {
                        if (d != null && d.deviceId() != null && !d.deviceId().isBlank()) {
                            installedIds.add(d.deviceId());
                        }
                    }
                    rebootStore.purgeMissingDevices(installedIds);
                }
            } catch (Exception purgeEx) {
                AppLogger.warning("Dashboard reboot purge failed: " + purgeEx.getMessage());
            }
            List<DriverUpdateCandidate> candidates = catalogs().findUpdates(installed, effectiveChild);
            if (isCancelledAny(generation, parent, child)) return;
            // Filter ignored drivers so Dashboard count matches Drivers tab
            try {
                Set<String> excluded = loadExcludedDriverIdSet();
                if (!excluded.isEmpty()) {
                    candidates = candidates.stream()
                            .filter(c -> c.installed() == null || c.installed().deviceId() == null
                                    || !excluded.contains(DriverScanService.normalizeDeviceKey(
                                            c.installed().deviceId())))
                            .collect(java.util.stream.Collectors.toList());
                }
            } catch (Exception ex) {
                AppLogger.warning("Dashboard excluded filter failed: " + ex.getMessage());
            }
            // Reboot-pending: keep drivers awaiting restart in Outdated so the
            // Dashboard stays in sync with the Drivers tab (which badges them
            // REBOOT until reboot). Excluded ids still win over pending.
            try {
                Set<String> pendingIds = new com.sbtools.drivers.RebootPendingStore().loadPendingIds();
                if (pendingIds != null && !pendingIds.isEmpty() && installed != null) {
                    Set<String> excludedCheck = loadExcludedDriverIdSet();
                    Set<String> have = new HashSet<>();
                    for (DriverUpdateCandidate c : candidates) {
                        if (c != null && c.installed() != null && c.installed().deviceId() != null) {
                            have.add(DriverScanService.normalizeDeviceKey(c.installed().deviceId()));
                        }
                    }
                    Set<String> installedIds = new HashSet<>();
                    java.util.Map<String, String> names = new java.util.HashMap<>();
                    for (InstalledDriver d : installed) {
                        if (d != null && d.deviceId() != null) {
                            String key = DriverScanService.normalizeDeviceKey(d.deviceId());
                            installedIds.add(key);
                            if (!names.containsKey(key)) {
                                names.put(key, d.friendlyName() != null && !d.friendlyName().isBlank()
                                        ? d.friendlyName() : d.deviceId());
                            }
                        }
                    }
                    List<String> pendingDetails = new ArrayList<>();
                    for (String pid : pendingIds) {
                        if (pid == null || pid.isBlank()) continue;
                        String pidKey = DriverScanService.normalizeDeviceKey(pid);
                        if (have.contains(pidKey)) continue;
                        if (!excludedCheck.isEmpty() && excludedCheck.contains(pidKey)) continue;
                        if (!installedIds.contains(pidKey)) continue;
                        pendingDetails.add(names.getOrDefault(pidKey, pid) + " — reboot pending");
                    }
                    if (!pendingDetails.isEmpty()) {
                        List<String> combined = new ArrayList<>(topDriverDetails(candidates));
                        combined.addAll(pendingDetails);
                        success = new IssueCategory(
                                "Outdated Drivers", candidates.size() + pendingDetails.size(), 0, "Drivers",
                                combined.stream().limit(MAX_DETAIL_LINES).toList());
                    }
                }
            } catch (Exception ex) {
                AppLogger.warning("Dashboard reboot-pending filter failed: " + ex.getMessage());
            }
            if (isCancelledAny(generation, parent, child)) return;
            if (success == null && !candidates.isEmpty()) {
                success = new IssueCategory(
                        "Outdated Drivers", candidates.size(), 0, "Drivers",
                        topDriverDetails(candidates));
            }
            updateCategoryProgress(0, "done", generation);
        } catch (CancellationException ex) {
            AppLogger.info("Dashboard driver scan cancelled");
            updateCategoryProgress(0, "failed", generation);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            AppLogger.info("Dashboard driver scan cancelled");
            updateCategoryProgress(0, "failed", generation);
        } catch (Exception ex) {
            if (isCancelledAny(generation, parent, child)) {
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
        } finally {
            finishSubScanProgress(generation, scansComplete, totalScans);
        }
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
            if (settings == null || settings.excludedDriverIds() == null) return Set.of();
            Set<String> ids = new HashSet<>();
            for (String e : settings.excludedDriverIds()) {
                if (e == null || e.isBlank()) continue;
                int t = e.lastIndexOf('\t');
                if (t < 0) t = e.lastIndexOf('\u001F');
                String id = t >= 0 ? e.substring(t + 1).trim() : e.trim();
                if (!id.isBlank()) {
                    ids.add(DriverScanService.normalizeDeviceKey(id));
                }
            }
            return ids;
        } catch (Exception ex) {
            AppLogger.warning("Failed to load excluded drivers: " + ex.getMessage());
            return Set.of();
        }
    }

    private void scanSoftware(int generation, CancellationToken parent, CancellationToken child,
            AtomicInteger scansComplete, int totalScans) {
        try {
        if (isCancelledAny(generation, parent, child)) return;
        updateCategoryProgress(1, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for software updates\u2026");
        });
        IssueCategory toAdd = null;
        try {
            // Honour Stop (parent), per-category timeout (child) and
            // generation-staleness so Stop truly aborts winget/WU.
            List<SoftwareUpdateEntry> updates = softwareServices().scanAllConcurrent(
                    () -> isScanStale(generation)
                            || (parent != null && parent.isCancelled())
                            || (child != null && child.isCancelled()),
                    w -> {}, wu -> {}, DashboardScanCoordinator.SOFTWARE_TIMEOUT_SECONDS);
            if (isCancelledAny(generation, parent, child)) return;
            List<SoftwareUpdateEntry> filteredUpdates = filterSoftwareLikeViewModel(updates);
            if (isCancelledAny(generation, parent, child)) return;
            String wingetError = softwareServices().getLastWingetError();
            String wuError = softwareServices().getLastWindowsUpdateError();
            boolean wuFailed = wuError != null && !wuError.isBlank();
            boolean wingetFailed = wingetError != null && !wingetError.isBlank();
            List<String> softwareDetails = new ArrayList<>(topSoftwareDetails(filteredUpdates));
            if (filteredUpdates.isEmpty() && (wuFailed || wingetFailed)) {
                try {
                    var cachedOpt = SoftwareUpdateScanCache.getIfFresh();
                    if (cachedOpt.isPresent() && cachedOpt.get().entries() != null
                            && !cachedOpt.get().entries().isEmpty()) {
                        List<SoftwareUpdateEntry> cachedFiltered =
                                filterSoftwareLikeViewModel(cachedOpt.get().entries());
                        if (!cachedFiltered.isEmpty()) {
                            filteredUpdates = cachedFiltered;
                            java.time.Instant cachedAt = cachedOpt.get().cachedAt();
                            long mins = cachedAt == null ? -1
                                    : java.time.Duration.between(cachedAt, Instant.now()).toMinutes();
                            String age = mins < 0 ? "" : mins < 1 ? "just now" : mins + " min ago";
                            softwareDetails = new ArrayList<>(topSoftwareDetails(filteredUpdates));
                            softwareDetails.add("Live scan had warnings — showing cached results"
                                    + (age.isEmpty() ? "" : " (" + age + ")"));
                        }
                    }
                } catch (Exception ignored) {
                }
            }
            SoftwareScanDashboardBuild built = resolveSoftwareScanCategory(
                    filteredUpdates, wingetError, wuError, softwareDetails);
            if (built != null && built.category != null) {
                toAdd = built.category;
            }
            updateCategoryProgress(1, built != null && built.categoryFailed ? "failed" : "done", generation);
        } catch (CancellationException ex) {
            AppLogger.info("Dashboard software scan cancelled");
            updateCategoryProgress(1, "failed", generation);
        } catch (Exception ex) {
            if (isCancelledAny(generation, parent, child)) {
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
        } finally {
            finishSubScanProgress(generation, scansComplete, totalScans);
        }
    }

    /**
     * Mirrors {@code SoftwareUpdateViewModel} filtering so Dashboard counts match
     * the Software tab: drops null/blank ids, drops Windows Update rows without
     * an actionable updateId, applies the skipped-ids filter, then dedupes by id
     * case-insensitively (winget first, so winget wins ties).
     */
    private List<SoftwareUpdateEntry> filterSoftwareLikeViewModel(List<SoftwareUpdateEntry> updates) {
        try {
            if (updates == null || updates.isEmpty()) return List.of();
            java.util.Set<String> skippedSet;
            try {
                com.sbtools.settings.AppSettings settings =
                        new com.sbtools.settings.SettingsStore().load();
                List<String> skipped = settings == null ? null : settings.skippedSoftwareIds();
                if (skipped == null || skipped.isEmpty()) {
                    skippedSet = Set.of();
                } else {
                    skippedSet = new HashSet<>();
                    for (String s : skipped) {
                        if (s == null || s.isBlank()) continue;
                        int t = s.lastIndexOf('	');
                        String id = t >= 0 ? s.substring(t + 1) : s;
                        if (id != null && !id.isBlank()) skippedSet.add(id.trim().toLowerCase(java.util.Locale.ROOT));
                    }
                }
            } catch (Exception ex) {
                AppLogger.warning("Dashboard skipped filter failed: " + ex.getMessage());
                skippedSet = Set.of();
            }
            final java.util.Set<String> skip = skippedSet;
            List<SoftwareUpdateEntry> filtered = updates.stream()
                    .filter(e -> {
                        if (e == null) return false;
                        if (e.id() == null || e.id().isBlank()) return false;
                        String key = e.id().trim().toLowerCase(java.util.Locale.ROOT);
                        if ("WindowsUpdate".equals(e.source())) {
                            if (e.updateId() == null || e.updateId().isBlank()) return false;
                            return !skip.contains(key);
                        }
                        return !skip.contains(key);
                    })
                    .collect(java.util.stream.Collectors.toList());
            return dedupeSoftwareById(filtered);
        } catch (Exception ex) {
            AppLogger.warning("Dashboard software filter failed: " + ex.getMessage());
            return List.of();
        }
    }

    private static List<SoftwareUpdateEntry> dedupeSoftwareById(List<SoftwareUpdateEntry> entries) {
        if (entries == null || entries.size() < 2) return entries == null ? List.of() : entries;
        java.util.LinkedHashMap<String, SoftwareUpdateEntry> byId = new java.util.LinkedHashMap<>();
        for (SoftwareUpdateEntry e : entries) {
            if (e == null || e.id() == null) continue;
            String key = e.id().toLowerCase(java.util.Locale.ROOT);
            byId.putIfAbsent(key, e);
        }
        return new ArrayList<>(byId.values());
    }

    static record SoftwareScanDashboardBuild(IssueCategory category, boolean categoryFailed) {}

    /**
     * Package-visible for unit tests — mirrors Software tab partial-failure semantics.
     */
    static SoftwareScanDashboardBuild resolveSoftwareScanCategory(
            List<SoftwareUpdateEntry> filteredUpdates,
            String wingetError,
            String wuError,
            List<String> softwareDetails) {
        boolean wuFailed = wuError != null && !wuError.isBlank();
        boolean wingetFailed = wingetError != null && !wingetError.isBlank();
        boolean sourceFailed = wuFailed || wingetFailed;
        List<SoftwareUpdateEntry> filtered = filteredUpdates == null ? List.of() : filteredUpdates;
        if (filtered.isEmpty()) {
            if (!sourceFailed) {
                return new SoftwareScanDashboardBuild(null, false);
            }
            String err = wuFailed && wingetFailed
                    ? "winget: " + wingetError + "; Windows Update: " + wuError
                    : (wuFailed ? wuError : wingetError);
            if (err.length() > 200) err = err.substring(0, 200) + "...";
            return new SoftwareScanDashboardBuild(
                    IssueCategory.error("Outdated Software", err, "", "Software", 0), true);
        }
        long totalSize = filtered.stream().mapToLong(SoftwareUpdateEntry::sizeBytes).sum();
        List<String> details = softwareDetails == null ? List.of() : new ArrayList<>(softwareDetails);
        if (sourceFailed) {
            String err = wuFailed && wingetFailed
                    ? "Partial scan (winget and Windows Update had errors)"
                    : (wuFailed ? "Partial scan (Windows Update error)" : "Partial scan (winget error)");
            details.add(err);
            return new SoftwareScanDashboardBuild(
                    new IssueCategory("Outdated Software", filtered.size(), totalSize, "Software", List.copyOf(details)),
                    true);
        }
        return new SoftwareScanDashboardBuild(
                new IssueCategory("Outdated Software", filtered.size(), totalSize, "Software", List.copyOf(details)),
                false);
    }

    private List<String> topSoftwareDetails(List<SoftwareUpdateEntry> updates) {
        try {
            List<String> rows = updates.stream()
                    .limit(Math.max(1, MAX_DETAIL_LINES - 1))
                    .map(e -> {
                        String n = e.getName() != null && !e.getName().isBlank() ? e.getName() : e.id();
                        String cur = e.getCurrentVersion() != null ? e.getCurrentVersion() : "?";
                        String avail = e.getAvailableVersion() != null ? e.getAvailableVersion() : "?";
                        return n + " " + cur + " → " + avail;
                    })
                    .collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
            // Scope disclosure (mirrors Software tab): this scan covers winget +
            // Windows Update only, so a clean bill here never implies Store apps
            // are current. Kept inside details (not the category key) so existing
            // "Outdated Software" navigation/equality checks keep working.
            rows.add("(winget + Windows Update only; Store apps not checked)");
            return List.copyOf(rows);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void scanCleanup(int generation, CancellationToken parent, CancellationToken child,
            AtomicInteger scansComplete, int totalScans) {
        try {
        if (isCancelledAny(generation, parent, child)) return;
        updateCategoryProgress(2, "scanning", generation);
        Platform.runLater(() -> {
            if (isScanStale(generation)) return;
            statusLabel.setText("Scanning for system cleanup opportunities\u2026");
        });
        List<IssueCategory> batch = new ArrayList<>();
        // Isolated pool: a lingering walk after Stop/timeout must never occupy the
        // shared cleanPool and starve the next scan. Shut down in finally below.
        ExecutorService cleanupExec = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "dashboard-cleanup");
            t.setDaemon(true);
            return t;
        });
        CancellationToken effectiveCleanupToken = child != null ? child : parent;
        try {
            java.util.List<CleanupCategory> activeCategories = CleanupService.categoriesExcluding(
                    settingsStore.load().ignoredCleanupCategories());
            int totalCategories = activeCategories.size();
            AtomicInteger cleanupDone = new AtomicInteger();
            List<CleanupRow> results = cleanupServices().scan(
                    activeCategories,
                    () -> updateCleanupProgress(cleanupDone.incrementAndGet(), totalCategories, generation),
                    cleanupExec, effectiveCleanupToken);
            if (isCancelledAny(generation, parent, child)) return;
            for (CleanupRow row : results) {
                if (isCancelledAny(generation, parent, child)) return;
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
            if (isCancelledAny(generation, parent, child)) {
                AppLogger.info("Dashboard cleanup scan cancelled");
                updateCategoryProgress(2, "failed", generation);
                return;
            }
            AppLogger.warning("Dashboard cleanup scan failed: " + ex.getMessage());
            updateCategoryProgress(2, "failed", generation);
            batch.add(IssueCategory.error("System Cleanup", "Error: " + ex.getMessage(), "", "Cleanup", 0));
        } finally {
            try {
                cleanupExec.shutdownNow();
            } catch (Exception ignored) {}
        }
        // Single batched FX mutation for all cleanup rows (P1).
        if (!batch.isEmpty() && !isCancelledAny(generation, parent, child)) {
            final List<IssueCategory> toAdd = List.copyOf(batch);
            Platform.runLater(() -> {
                if (isScanStale(generation)) return;
                issues.addAll(toAdd);
            });
        }
        } finally {
            finishSubScanProgress(generation, scansComplete, totalScans);
        }
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
        acquireDashboardBusy();
        final int generation = ++scanGeneration;
        capturePreScanUi(generation);
        final CancellationToken token = new CancellationToken();
        scanCancellationToken = token;
        cancelChildTokens();
        statusLabel.setText("Checking privileges\u2026");
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
        progressBar.setVisible(true);
        stopButton.setVisible(true);
        stopButton.setDisable(false);
        scanButton.setDisable(true);
        hideRetryButtons();
        final int retryIndex = Math.min(2, Math.max(0, categoryIndex));
        try {
            scanFuture = dashboardPool.submit(() -> {
                boolean isAdmin;
                try {
                    // Off the FX thread: AdminCheck spawns PowerShell (up to ~5s).
                    isAdmin = adminCheck.getAsBoolean();
                } catch (Exception ex) {
                    isAdmin = false;
                }
                if (!isAdmin) {
                    if (!isScanStale(generation)) {
                        Platform.runLater(() -> {
                            if (isScanStale(generation)) return;
                            statusLabel.setText("Run as Administrator to scan for issues.");
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                        });
                    }
                    return;
                }
                if (isScanStale(generation) || token.isCancelled() || disposed) {
                    return;
                }
                final CancellationToken retryChild = new CancellationToken();
                if (retryIndex == 0) driverChildToken = retryChild;
                else if (retryIndex == 1) softwareChildToken = retryChild;
                else cleanupChildToken = retryChild;
                Platform.runLater(() -> {
                    if (isScanStale(generation)) return;
                    progressRow.setVisible(true);
                    progressRow.setManaged(true);
                    updateCategoryProgress(retryIndex, "scanning", generation);
                    String retryName = switch (retryIndex) {
                        case 0 -> "Outdated Drivers";
                        case 1 -> "Outdated Software";
                        default -> "System Cleanup";
                    };
                    statusLabel.setText("Retrying " + retryName + "\u2026");
                    // Remove prior rows for this category only after admin is
                    // confirmed so a non-admin retry never wipes existing data.
                    if (retryIndex == 0) {
                        issues.removeIf(ic -> "Outdated Drivers".equals(ic.categoryProperty().get()));
                    } else if (retryIndex == 1) {
                        issues.removeIf(ic -> "Outdated Software".equals(ic.categoryProperty().get()));
                    } else {
                        issues.removeIf(ic -> "Cleanup".equals(ic.sourceProperty().get()));
                    }
                    updateDetailsLabel(null);
                    if (issues.isEmpty()) {
                        hideHealthyState();
                        showResultsView();
                    }
                });
                runRetryScan(retryIndex, generation, token, retryChild);
            });
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            scanning.set(false);
            releaseBusyOnce();
            cancelChildTokens();
            cancelSubScans();
            progressBar.setVisible(false);
            stopButton.setVisible(false);
            stopButton.setDisable(true);
            scanButton.setDisable(busy.get());
            statusLabel.setText("Scan unavailable \u2014 try again later.");
        }
    }

    /**
     * Retry worker body: runs the single failed category with its own child
     * token (isolated soft-budget timeout) on the dashboard pool.
     */
    private void runRetryScan(int categoryIndex, int generation, CancellationToken token,
            CancellationToken retryChild) {
        AtomicInteger done = new AtomicInteger();
        Future<?> single = null;
        long budget;
        if (categoryIndex == 0) {
            budget = DashboardScanCoordinator.DRIVER_TIMEOUT_SECONDS;
        } else if (categoryIndex == 1) {
            budget = DashboardScanCoordinator.SOFTWARE_TIMEOUT_SECONDS;
        } else {
            budget = DashboardScanCoordinator.CLEANUP_TIMEOUT_SECONDS;
        }
        try {
            if (categoryIndex == 0) {
                single = dashboardPool.submit(() -> scanDrivers(generation, token, retryChild, done, 1));
                driverTask = single;
            } else if (categoryIndex == 1) {
                single = dashboardPool.submit(() -> scanSoftware(generation, token, retryChild, done, 1));
                softwareTask = single;
            } else {
                single = dashboardPool.submit(() -> scanCleanup(generation, token, retryChild, done, 1));
                cleanupTask = single;
            }
                try {
                    Set<Integer> timedOut = DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(single),
                            new long[]{budget},
                            () -> isScanStale(generation),
                            token,
                            () -> disposed,
                            Math.max(60, budget + 30),
                            List.of(retryChild));
                    if (!timedOut.isEmpty()) {
                        handlePerTaskTimeouts(Set.of(categoryIndex), generation, token);
                        Platform.runLater(() -> {
                            if (isScanStale(generation)) return;
                            statusLabel.setText("Retry timed out — partial results kept.");
                        });
                        return;
                    }
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
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
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
                        lastScanTime = Instant.now();
                        updateTimestamp(generation);
                        clearPreScanUiForGeneration(generation);
                        try {
                            DashboardSummaryStore.save(lastScanTime, new ArrayList<>(issues));
                        } catch (Exception ignored) {}
                    });
                } catch (CancellationException ex) {
                    Platform.runLater(() -> {
                        if (isScanStale(generation)) return;
                        statusLabel.setText("Scan stopped.");
                    });
                    updateCategoryProgress(categoryIndex, "failed", generation);
                } catch (InterruptedException ex) {
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
                    teardownGeneration(single, null, null,
                            categoryIndex == 0 ? retryChild : null,
                            categoryIndex == 1 ? retryChild : null,
                            categoryIndex == 2 ? retryChild : null);
                    scanning.set(false);
                    Platform.runLater(() -> {
                        if (!isScanStale(generation)) {
                            progressBar.setVisible(false);
                            stopButton.setVisible(false);
                            stopButton.setDisable(true);
                            scanButton.setDisable(busy.get());
                            revealRetryForErrors();
                        }
                        releaseBusyOnce();
                    });
                }
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            cancelToken(retryChild);
            cancelFuture(single);
            scanning.set(false);
            releaseBusyOnce();
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                stopButton.setVisible(false);
                stopButton.setDisable(true);
                scanButton.setDisable(busy.get());
                statusLabel.setText("Scan unavailable \u2014 try again later.");
            });
        }
    }

    private void stopScan() {
        if (!scanning.get()) {
            return;
        }
        final int runningGen = scanGeneration;
        scanGeneration++;
        CancellationToken token = scanCancellationToken;
        if (token != null) token.cancel();
        // Cancel per-category children too so token-polling inner services
        // (winget/WU, catalog providers, cleanup walks) abort promptly.
        cancelChildTokens();
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
        restorePreScanUi(runningGen);
        if (issues.isEmpty()) {
            statusLabel.setText("Scan stopped.");
            showWelcomeView();
        } else {
            statusLabel.setText("Scan stopped — previous results restored.");
        }
        progressRow.setVisible(false);
        progressRow.setManaged(false);
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
