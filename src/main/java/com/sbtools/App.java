package com.sbtools;

import com.sbtools.license.EulaDialog;
import com.sbtools.license.LicenseDialog;
import com.sbtools.license.LicenseValidator;
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
import javafx.beans.property.SimpleBooleanProperty;

import java.io.IOException;
import java.util.Set;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final LicenseValidator licenseValidator = new LicenseValidator();
    private final UpdateChecker updateChecker = new UpdateChecker();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);

    private static final Set<String> FREE_TABS = Set.of(
            "Dashboard",
            "System Information",
            "Disk Tools",
            "Browser Extensions",
            "Network Optimizer"
    );

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
    private UIButton activateBtn;
    private UIButton settingsBtn;
    private SettingsTabView settingsTab;
    private Image logoImage;
    private int selectedTab = 0;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        AppLogger.init();
        DataMigration.migrateIfNeeded();
        AppSettings settings = settingsStore.load();

        licenseValidator.check();

        if (!settings.eulaAccepted()) {
            showEula(settings);
        }

        boolean admin = AdminCheck.isRunningAsAdmin();
        if (!admin && AppPaths.isWindows()) {
            try {
                AdminCheck.requestElevation();
            } catch (IOException e) {
                new Alert(Alert.AlertType.ERROR, "Could not request elevation: " + e.getMessage()).showAndWait();
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

        settingsTab = new SettingsTabView(settingsStore, licenseValidator);

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

        if (settings.autoCheckForUpdates()) {
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

        boolean licenseActive = licenseValidator.isLicenseActive();

        tabButtons = new UIButton[TAB_NAMES.length];
        for (int i = 0; i < TAB_NAMES.length; i++) {
            tabButtons[i] = createTabButton(TAB_NAMES[i], licenseActive);
            final int idx = i;
            tabButtons[i].setOnAction(e -> {
                if (isLocked(tabButtons[idx]) && !licenseValidator.isLicenseActive()) {
                    showLicenseDialog();
                    return;
                }
                selectedTab = idx;
                selectTab(tabButtons[idx]);
                root.setCenter(tabViews[idx]);
            });
        }

        activateBtn = UIButton.secondary("Activate Pro");
        activateBtn.setStyle("-fx-text-fill: #ffb86c; -fx-border-color: #ffb86c;");
        activateBtn.setOnAction(e -> showLicenseDialog());

        settingsBtn = UIButton.secondary("\u2699 Settings");
        settingsBtn.setOnAction(e -> {
            selectTab(settingsBtn);
            root.setCenter(settingsTab);
        });

        sidebar.getChildren().addAll(
                logoView, appTitle, sep
        );
        sidebar.getChildren().addAll(tabButtons);
        sidebar.getChildren().addAll(
                new Separator(),
                activateBtn,
                settingsBtn
        );

        if (tabButtons.length > 0) {
            selectTab(tabButtons[selectedTab]);
        }
    }

    private UIButton createTabButton(String name, boolean licenseActive) {
        if (FREE_TABS.contains(name) || licenseActive) {
            if ("Dashboard".equals(name)) {
                return UIButton.primary(name);
            }
            return UIButton.secondary(name);
        }
        return new UIButton("\uD83D\uDD12 " + name, UIButton.ButtonStyle.LOCKED);
    }

    private boolean isLocked(UIButton button) {
        return button.getStyleClass().contains("button-locked");
    }

    private void showLicenseDialog() {
        LicenseDialog dialog = new LicenseDialog();
        boolean activated = dialog.show(licenseValidator);
        AppLogger.info("License dialog closed. activated=" + activated + ", licenseActive=" + licenseValidator.isLicenseActive());

        if (activated || licenseValidator.isLicenseActive()) {
            AppLogger.info("Rebuilding sidebar after license activation");
            licenseValidator.check();
            buildSidebar();
            root.setCenter(tabViews[0]);
            selectedTab = 0;
        }
    }

    private void selectTab(UIButton selected) {
        for (UIButton btn : tabButtons) {
            if (!isLocked(btn)) {
                btn.setStyleType(UIButton.ButtonStyle.SECONDARY);
            }
        }
        if (!isLocked(selected)) {
            selected.setStyleType(UIButton.ButtonStyle.PRIMARY);
        }
    }

    private void showEula(AppSettings settings) {
        EulaDialog eula = new EulaDialog();
        if (eula.showAndWait().orElse(null) != ButtonType.OK) {
            Platform.exit();
            return;
        }
        try {
            settingsStore.save(new AppSettings(
                    settings.autoBackupDrivers(),
                    settings.createSystemRestorePoint(),
                    true,
                    settings.excludedDriverIds(),
                    settings.skippedSoftwareIds(),
                    settings.networkOptimizationPreset(),
                    settings.downloadDirectory(),
                    settings.licenseKey(),
                    settings.minimizeToTray(),
                    settings.startMinimized(),
                    settings.scanOnStartup(),
                    settings.notifyOnDriverUpdate(),
                    settings.backupDirectory(),
                    settings.powerShellPath(),
                    settings.windowWidth(),
                    settings.windowHeight(),
                    settings.windowMaximized(),
                    settings.autoCheckForUpdates()
            ));
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
