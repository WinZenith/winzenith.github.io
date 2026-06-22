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
            "Dashboard", "Drivers", "Backup/Rollback", "Software update",
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

        tabViews = new Node[]{
                new DashboardTabView(busy, AdminCheck::isRunningAsAdmin),
                new DriversTabView(busy, AdminCheck::isRunningAsAdmin),
                new BackupRestoreTabView(busy, AdminCheck::isRunningAsAdmin),
                new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin),
                new SystemInfoTabView(busy, AdminCheck::isRunningAsAdmin),
                new UninstallerTabView(busy, AdminCheck::isRunningAsAdmin),
                new StartupTabView(busy, AdminCheck::isRunningAsAdmin),
                new CleanerTabView(busy, AdminCheck::isRunningAsAdmin),
                new DuplicateFilesTabView(AdminCheck::isRunningAsAdmin),
                new DiskToolsTabView(AdminCheck::isRunningAsAdmin),
                new BrowserExtensionsTabView(AdminCheck::isRunningAsAdmin),
                new NetworkOptimizerTabView(busy, AdminCheck::isRunningAsAdmin, settingsStore, settings)
        };

        root = new BorderPane();
        sidebar = new VBox(6);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);

        helpTab = new HelpTabView();

        buildSidebar();

        root.setLeft(sidebar);
        root.setCenter(tabViews[0]);

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
                root.setCenter(tabViews[idx]);
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
        ProcessManager.shutdownAll();
        super.stop();
    }
}
