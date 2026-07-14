package com.sbtools;

import com.sbtools.license.EulaDialog;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.ui.*;
import com.sbtools.update.UpdateChecker;
import com.sbtools.update.UpdateDialog;

import atlantafx.base.theme.Dracula;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
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
import javafx.beans.property.SimpleBooleanProperty;

import java.io.IOException;
import com.sbtools.util.ProcessManager;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final UpdateChecker updateChecker = new UpdateChecker();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

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
    private HelpTabView helpTab;
    private Image logoImage;
    private int selectedTab = 0;
    private AppSettings appSettings;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        AppLogger.init();
        DataMigration.migrateIfNeeded();
        AppSettings settings = settingsStore.load();

        if (!settings.eulaAccepted()) {
            showEula(settings);
        }

        boolean admin = AdminCheck.isRunningAsAdmin();
        if (!admin && AppPaths.isWindows()) {
            AppLogger.info("Requesting administrator privileges...");
            try {
                AdminCheck.requestElevation();
            } catch (IOException ex) {
                AppLogger.warning("Failed to request elevation: " + ex.getMessage());
            }
            Platform.exit();
            return;
        }

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
        stage.show();

        if (settings.autoCheckForUpdates() && com.sbtools.util.AppInfo.isPackaged()) {
            updateChecker.checkForUpdateAsync(() -> {
                UpdateChecker.UpdateResult result = updateChecker.getCachedResult();
                if (result.isUpdateAvailable()) {
                    UpdateDialog dialog = new UpdateDialog(result);
                    dialog.showAndWait();
                }
            });
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
        sidebar.getChildren().addAll(
                new Separator(),
                helpBtn
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
            case 0 -> new DashboardTabView(busy, AdminCheck::isRunningAsAdmin);
            case 1 -> new DriversTabView(busy, AdminCheck::isRunningAsAdmin);
            case 2 -> new BackupRestoreTabView(busy, AdminCheck::isRunningAsAdmin);
            case 3 -> new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);
            case 4 -> new SystemInfoTabView(busy, AdminCheck::isRunningAsAdmin);
            case 5 -> new UninstallerTabView(busy, AdminCheck::isRunningAsAdmin);
            case 6 -> new StartupTabView(busy, AdminCheck::isRunningAsAdmin);
            case 7 -> new CleanerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore);
            case 8 -> new DuplicateFilesTabView(AdminCheck::isRunningAsAdmin);
            case 9 -> new DiskToolsTabView(AdminCheck::isRunningAsAdmin);
            case 10 -> new BrowserExtensionsTabView(AdminCheck::isRunningAsAdmin);
            case 11 -> new NetworkOptimizerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore, appSettings);
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

    private void showEula(AppSettings settings) {
        EulaDialog eula = new EulaDialog();
        if (eula.showAndWait().orElse(null) != EulaDialog.ACCEPT) {
            Platform.exit();
            return;
        }
        try {
            settingsStore.save(settings.toBuilder().eulaAccepted(true).build());
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void stop() throws Exception {
        // Ensure any tracked child processes are terminated when the app stops
        try {
            AppLogger.info("Application stopping; shutting down tracked processes...");
        } catch (Throwable ignored) {}
        if (tabViews != null) {
            for (Node view : tabViews) {
                if (view == null) continue;
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
                }
            }
        }
        ProcessManager.shutdownAll();
        super.stop();
    }
}
