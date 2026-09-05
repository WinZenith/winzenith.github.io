package com.sbtools;

import com.sbtools.license.EulaDialog;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.ui.*;
import com.sbtools.update.AppUpdateDialog;
import com.sbtools.update.UpdateChecker;

import atlantafx.base.theme.Dracula;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import com.sbtools.util.*;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import com.sbtools.util.ProcessManager;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final UpdateChecker updateChecker = new UpdateChecker();
    private final BooleanProperty busy = new com.sbtools.util.BusyProperty();

    private static final String[] TAB_NAMES = {
            "Dashboard", "Drivers", "Backup/Rollback", "Software Update",
            "System Information", "Uninstaller", "Startup items/services",
            "System cleanup", "Duplicate Files", "Disk Tools",
            "Browser Extensions", "Network Optimizer"
    };

    private BorderPane root;
    private VBox sidebar;
    private Node[] tabViews;
    private UIButton[] tabButtons;
    private UIButton helpBtn;
    private UIButton updateBtn;
    private HelpTabView helpTab;
    private Image logoImage;
    private int selectedTab = 0;
    private AppSettings appSettings;
    private volatile boolean checkingForUpdate = false;
    private Stage primaryStage;
    /**
     * Guards the close path so window-close, OS shutdown and {@link #stop()}
     * all funnel through one cleanup. {@code shutdownRequested} is set once the
     * user confirmed exit (or no confirmation was needed); {@code cleanupDone}
     * ensures dispose/shutdown logic runs exactly once.
     */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean(false);
    private final AtomicBoolean cleanupDone = new AtomicBoolean(false);
    /** Watchdog delay before forcing the JVM out (ms). Bounds Task Manager linger. */
    private static final long FORCE_EXIT_DELAY_MS = 4000;

    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;
        SingleInstance.onFocusRequested(() -> Platform.runLater(() -> {
            try {
                if (primaryStage != null) {
                    primaryStage.setIconified(false);
                    primaryStage.toFront();
                    primaryStage.requestFocus();
                }
            } catch (Exception ignored) {
            }
        }));
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        AppLogger.init();
        DataMigration.migrateIfNeeded();
        AppSettings settings = settingsStore.load();

        if (!settings.eulaAccepted()) {
            if (!showEula(settings)) {
                // User declined: exit quietly without building the main window.
                // Platform.exit() alone would still run stop(); force the JVM out
                // so no process lingers in Task Manager.
                shutdownRequested.set(true);
                try {
                    SingleInstance.release();
                } catch (Throwable ignored) {
                }
                Platform.exit();
                armForceExitWatchdog();
                return;
            }
        }

        // Async elevation check to avoid FX freeze (B1). UI is built immediately;
        // elevation is requested off FX thread and exits if successful.
        // NOTE: elevation is handled pre-launch by ElevationGate (see main()).
        // No in-UI relaunch here: the window must never flash before the UAC prompt.
        logoImage = new Image(getClass().getResourceAsStream("/logo-ico.png"));

        appSettings = settings;
        tabViews = new Node[TAB_NAMES.length];

        root = new BorderPane();
        sidebar = new VBox(6);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);

        helpTab = new HelpTabView();

        buildSidebar();

        root.setLeft(sidebar);
        root.setCenter(createTab(0));

        if (!AppPaths.isWindows()) {
            new Alert(Alert.AlertType.WARNING,
                    AppInfo.DISPLAY_NAME + " is designed for Windows only.").showAndWait();
        }

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        stage.setTitle(AppInfo.DISPLAY_NAME);
        stage.getIcons().add(logoImage);
        stage.setScene(scene);
        stage.setMaximized(true);
        // Explicit close path: confirm when busy, then guarantee the JVM dies.
        // Without this, Platform.exit() alone leaves the process in Task Manager
        // whenever a non-daemon worker or hung child process is still alive.
        Platform.setImplicitExit(true);
        stage.setOnCloseRequest(evt -> {
            evt.consume();
            requestShutdown();
        });
        stage.show();
        AppLogger.info("Main window shown (version " + AppInfo.getVersion() + ")");

        // Dashboard honors the existing scanOnStartup setting (read-only auto-scan).
        try {
            if (tabViews != null && tabViews.length > 0 && tabViews[0] instanceof DashboardTabView dtv) {
                dtv.maybeAutoScan();
            }
        } catch (Exception ignored) {}

        if (settings.autoCheckForUpdates() && com.sbtools.util.AppInfo.isPackaged()) {
            updateChecker.checkForUpdateAsync(this::promptForUpdate);
        }
    }

    /**
     * Shows the "new version available" confirmation and, on YES, starts the
     * non-blocking download popup (auto-closes + auto-opens folder on success).
     */
    private void promptForUpdate(UpdateChecker.UpdateResult result) {
        if (result == null || !result.isUpdateAvailable()) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "A new version v" + result.latestVersion() + " is available.\nDo you want to download it?",
                ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Update Available");
        confirm.setHeaderText("Update Available");
        if (primaryStage != null) {
            confirm.initOwner(primaryStage);
        }

        if (confirm.showAndWait().orElse(ButtonType.NO) == ButtonType.YES) {
            AppUpdateDialog.showAndDownload(primaryStage, result,
                    settingsStore.load().downloadDirectory());
        }
    }

    private void buildSidebar() {
        sidebar.getChildren().clear();

        ImageView logoView = new ImageView(logoImage);
        logoView.setFitHeight(48);
        logoView.setFitWidth(48);
        logoView.setPreserveRatio(true);

        Label appTitle = new Label("WinZenith");
        appTitle.getStyleClass().addAll("label", "large");
        appTitle.setStyle("-fx-text-fill: #2AE061; -fx-font-size: 20px; -fx-font-weight: bold; -fx-font-family: 'Segoe UI', 'Orbitron', sans-serif; -fx-letter-spacing: 1px; -fx-padding: 8 0 4 0;");

        Separator sep = new Separator();
        sep.setStyle("-fx-padding: 4 0 4 0;");

        tabButtons = new UIButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = createTabButton(TAB_NAMES[i]);
            final int idx = i;
            tabButtons[i].setOnAction(e -> {
                selectedTab = idx;
                selectTab(tabButtons[idx]);
                root.setCenter(createTab(idx));
            });
        }

        helpBtn = UIButton.secondary("\u2753 Help");
        helpBtn.setOnAction(e -> {
            selectTab(helpBtn);
            root.setCenter(helpTab);
        });

        sidebar.getChildren().addAll(
                logoView, appTitle, sep
        );
        sidebar.getChildren().addAll(tabButtons);
        Label versionLabel = new Label("v" + AppInfo.getVersion());
        versionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #6272a4; -fx-padding: 4 0 0 0;");

        updateBtn = UIButton.secondary("\u2B50 Check for Updates");
        updateBtn.setOnAction(e -> {
            if (checkingForUpdate) return;
            checkingForUpdate = true;
            updateBtn.setDisable(true);
            updateBtn.setText("Checking...");

            updateChecker.checkForUpdateAsync(result -> {
                try {
                    if (result.isUpdateAvailable()) {
                        promptForUpdate(result);
                    } else if (result.isUnknown()) {
                        Alert warn = new Alert(Alert.AlertType.WARNING,
                                "Could not check for updates.\nPlease check your internet connection and try again.");
                        warn.setTitle("Update Check Failed");
                        warn.setHeaderText(null);
                        warn.showAndWait();
                    } else {
                        Alert info = new Alert(Alert.AlertType.INFORMATION,
                                "Your version is up to date.");
                        info.setTitle("No Updates");
                        info.setHeaderText(null);
                        info.showAndWait();
                    }
                } finally {
                    checkingForUpdate = false;
                    updateBtn.setDisable(false);
                    updateBtn.setText("\u2B50 Check for Updates");
                }
            });
        });

        sidebar.getChildren().addAll(
                new Separator(),
                helpBtn,
                updateBtn,
                versionLabel
        );

        if (tabButtons.length > 0) {
            selectTab(tabButtons[selectedTab]);
        }
    }

    private UIButton createTabButton(String name) {
        if ("Dashboard".equals(name)) {
            return UIButton.primary(name);
        }
        return UIButton.secondary(name);
    }

    private Node createTab(int index) {
        if (tabViews[index] != null) {
            return tabViews[index];
        }
        tabViews[index] = switch (index) {
            case 0 -> new DashboardTabView(busy, AdminCheck::isRunningAsAdmin,
                    idx -> {
                        selectedTab = idx;
                        selectTab(tabButtons[idx]);
                        root.setCenter(createTab(idx));
                    });
            case 1 -> new DriversTabView(busy, AdminCheck::isRunningAsAdmin);
            case 2 -> new BackupRestoreTabView(busy, AdminCheck::isRunningAsAdminFresh);
            case 3 -> new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);
            case 4 -> new SystemInfoTabView(busy, AdminCheck::isRunningAsAdmin);
            case 5 -> new UninstallerTabView(busy, AdminCheck::isRunningAsAdmin);
            case 6 -> new StartupTabView(busy, AdminCheck::isRunningAsAdmin);
            case 7 -> new CleanerTabView(busy, AdminCheck::isRunningAsAdminFresh, settingsStore);
            case 8 -> new DuplicateFilesTabView(busy, AdminCheck::isRunningAsAdmin);
            case 9 -> new DiskToolsTabView(AdminCheck::isRunningAsAdmin);
            case 10 -> new BrowserExtensionsTabView(AdminCheck::isRunningAsAdmin, settingsStore);
            case 11 -> new NetworkOptimizerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore, appSettings,
                    updatedSettings -> this.appSettings = updatedSettings);
            default -> throw new IllegalArgumentException("Unknown tab index: " + index);
        };
        return tabViews[index];
    }

    private void selectTab(UIButton selected) {
        for (UIButton btn : tabButtons) {
            btn.setStyleType(UIButton.ButtonStyle.SECONDARY);
        }
        helpBtn.setStyleType(UIButton.ButtonStyle.SECONDARY);
        selected.setStyleType(UIButton.ButtonStyle.PRIMARY);
    }

    // NOTE: update download is handled by AppUpdateDialog (non-blocking Task).
    // See com.sbtools.update.AppUpdateDialog.showAndDownload.

    /**
     * @return true when accepted (startup continues), false when declined
     *         (caller must exit without building UI).
     */
    private boolean showEula(AppSettings settings) {
        EulaDialog eula = new EulaDialog();
        if (eula.showAndWait().orElse(null) != EulaDialog.ACCEPT) {
            return false;
        }
        try {
            settingsStore.save(settings.toBuilder().eulaAccepted(true).build());
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
        return true;
    }

    /**
     * Window-close entry point (FX thread). Asks for confirmation when work is
     * in progress, otherwise exits immediately. Always arms the force-exit
     * watchdog so the process can never linger in Task Manager.
     */
    private void requestShutdown() {
        if (shutdownRequested.get()) {
            return;
        }
        if (isBusy()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "An operation is still running.\nExiting now will cancel it.\n\nDo you want to exit anyway?",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Operation in progress");
            confirm.setHeaderText("Operation in progress");
            if (primaryStage != null) {
                confirm.initOwner(primaryStage);
            }
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) {
                return;
            }
        }
        shutdownRequested.set(true);
        armForceExitWatchdog();
        try {
            Platform.exit();
        } catch (Throwable ignored) {
            System.exit(0);
        }
    }

    private boolean isBusy() {
        try {
            if (busy != null && busy.get()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (com.sbtools.util.AppExecutors.globalBusyProperty().get()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Guarantees the JVM terminates even if a worker thread ignores interrupts
     * or a child process hangs. Daemon by design: it only fires while the JVM
     * is otherwise kept alive, and {@code System.exit} is a no-op if we already
     * exited cleanly.
     */
    private static void armForceExitWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(FORCE_EXIT_DELAY_MS);
            } catch (InterruptedException ignored) {
                return;
            }
            try {
                AppLogger.warning("Force-exit watchdog firing; forcing JVM termination.");
            } catch (Throwable ignored) {
            }
            System.exit(0);
        }, "force-exit-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void disposeAllViews() {
        if (tabViews == null) {
            return;
        }
        for (Node view : tabViews) {
            if (view == null) continue;
            try {
                if (view instanceof DashboardTabView dtv) {
                    dtv.dispose();
                } else if (view instanceof DriversTabView dtv2) {
                    dtv2.dispose();
                } else if (view instanceof SoftwareUpdatesTabView suv) {
                    suv.dispose();
                } else if (view instanceof UninstallerTabView utv) {
                    utv.dispose();
                } else if (view instanceof StartupTabView stv) {
                    stv.dispose();
                } else if (view instanceof SystemInfoTabView sitv) {
                    sitv.dispose();
                } else if (view instanceof BrowserExtensionsTabView betv) {
                    betv.dispose();
                } else if (view instanceof NetworkOptimizerTabView notv) {
                    notv.dispose();
                } else if (view instanceof DuplicateFilesTabView dftv) {
                    dftv.dispose();
                } else if (view instanceof CleanerTabView ctv) {
                    ctv.dispose();
                } else if (view instanceof DiskToolsTabView dttv) {
                    dttv.dispose();
                }
                // Note: BackupRestoreTabView uses only the shared daemon ioPool
                // plus ProcessManager-tracked children, so no per-view dispose
                // is needed; its children are reaped by shutdownAll() below.
            } catch (Throwable t) {
                try {
                    AppLogger.error("Error disposing view on shutdown", t);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static void main(String[] args) {
        // Single instance first: a second launch only brings the running window
        // forward and exits quietly — it must never trigger a second UAC prompt.
        // Elevation consent happens BEFORE JavaFX starts: no window is ever shown
        // before the user answers, so there is no load -> UAC -> close -> reopen flash.
        // handlePreLaunch returns true only when an elevated child was accepted and
        // is starting; this process must then exit quietly without launching.
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                reportFatalStartup("Uncaught exception on " + t.getName(), e));
        com.sbtools.util.SingleInstance.Role role;
        try {
            role = com.sbtools.util.SingleInstance.acquire(
                    com.sbtools.util.ElevationGate.isElevatedChild(args));
        } catch (Throwable t) {
            System.err.println("[App] Single-instance check failed, continuing: " + t.getMessage());
            role = com.sbtools.util.SingleInstance.Role.PRIMARY_UNLOCKED;
        }
        if (role == com.sbtools.util.SingleInstance.Role.SECONDARY) {
            com.sbtools.util.SingleInstance.signalPrimary();
            return;
        }
        try {
            if (com.sbtools.util.ElevationGate.handlePreLaunch(args)) {
                com.sbtools.util.SingleInstance.release();
                return;
            }
        } catch (Throwable t) {
            System.err.println("[App] Elevation gate failed, starting normally: " + t.getMessage());
        }
        try {
            launch(args);
        } catch (Throwable t) {
            // An exception out of start() would otherwise kill the app silently
            // (no console on the packaged .exe). Persist it for diagnosis.
            reportFatalStartup("Application failed to start", t);
            throw t;
        }
    }

    /**
     * Last-resort startup diagnostics: log + stderr + a crash file next to the
     * executable (or in the user home when the exe dir is not writable).
     */
    private static void reportFatalStartup(String message, Throwable t) {
        try {
            AppLogger.error("[App] " + message, t);
        } catch (Throwable ignored) {
        }
        try {
            System.err.println("[App] FATAL: " + message);
            if (t != null) {
                t.printStackTrace(System.err);
            }
        } catch (Throwable ignored) {
        }
        try {
            String detail = message + System.lineSeparator()
                    + (t != null ? stackTraceOf(t) : "(no throwable)");
            java.nio.file.Path dir = null;
            try {
                dir = com.sbtools.util.AppPaths.portableBaseDir();
            } catch (Throwable ignored) {
            }
            if (dir == null) {
                dir = java.nio.file.Path.of(System.getProperty("user.home"));
            }
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path crash = dir.resolve("startup-error.log");
            String entry = "[" + java.time.Instant.now() + "] " + detail + System.lineSeparator();
            java.nio.file.Files.writeString(crash, entry,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
        }
    }

    private static String stackTraceOf(Throwable t) {
        try {
            java.io.StringWriter sw = new java.io.StringWriter();
            t.printStackTrace(new java.io.PrintWriter(sw));
            return sw.toString();
        } catch (Throwable ignored) {
            return t.toString();
        }
    }

    @Override
    public void stop() throws Exception {
        // Direct toolkit shutdown (e.g. OS session end) may reach here without
        // going through requestShutdown(): make sure the watchdog is armed so a
        // hung worker can never leave the process in Task Manager.
        boolean firstRequest = shutdownRequested.compareAndSet(false, true);
        if (firstRequest) {
            armForceExitWatchdog();
        }
        if (!cleanupDone.compareAndSet(false, true)) {
            try {
                super.stop();
            } catch (Throwable ignored) {
            }
            return;
        }
        // Ensure any tracked child processes are terminated when the app stops
        try {
            AppLogger.info("Application stopping; shutting down tracked processes...");
        } catch (Throwable ignored) {}
        disposeAllViews();
        try {
            ProcessManager.shutdownAll();
        } catch (Throwable ignored) {
        }
        try {
            AppExecutors.shutdown();
        } catch (Throwable ignored) {
        }
        try {
            SingleInstance.release();
        } catch (Throwable ignored) {}
        try {
            super.stop();
        } catch (Throwable ignored) {
        }
        // Platform.exit() does not kill non-daemon workers or hung children.
        // Cleanup above is best-effort and bounded; force the JVM out so the
        // process never lingers in Task Manager after the window closes.
        System.exit(0);
    }
}
