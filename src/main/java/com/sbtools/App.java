package com.sbtools;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.ui.BackupRestoreTabView;
import com.sbtools.ui.DriversTabView;
import com.sbtools.ui.SoftwareUpdatesTabView;
import com.sbtools.ui.SystemInfoTabView;
import com.sbtools.ui.UninstallerTabView;
import com.sbtools.ui.StartupTabView;
import com.sbtools.ui.CleanerTabView;
import com.sbtools.ui.DiskToolsTabView;
import com.sbtools.ui.DuplicateFilesTabView;
import com.sbtools.ui.UIButton;
import com.sbtools.ui.BrowserExtensionsTabView;
import com.sbtools.ui.NetworkOptimizerTabView;

import atlantafx.base.theme.Dracula;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import com.sbtools.util.AdminCheck;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);


    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new Dracula().getUserAgentStylesheet());

        AppLogger.init();
        AppSettings settings = settingsStore.load();
        if (!settings.eulaAccepted()) {
            showEula(settings);
        }

        boolean admin = AdminCheck.isRunningAsAdmin();

        // If running on Windows and not elevated, request elevation at startup (no prompt)
        if (!admin && AppPaths.isWindows()) {
            try {
                AdminCheck.requestElevation();
            } catch (IOException e) {
                new Alert(Alert.AlertType.ERROR, "Could not request elevation: " + e.getMessage()).showAndWait();
            }
            Platform.exit();
            return;
        }

        // Modern left sidebar with theme toggle
        DriversTabView driversTab = new DriversTabView(busy, AdminCheck::isRunningAsAdmin);
        BackupRestoreTabView backupRestoreTab = new BackupRestoreTabView(busy, AdminCheck::isRunningAsAdmin);
        SoftwareUpdatesTabView softwareTab = new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);
        SystemInfoTabView systemInfoTab = new SystemInfoTabView(busy, AdminCheck::isRunningAsAdmin);
        UninstallerTabView uninstallerTab = new UninstallerTabView(busy, AdminCheck::isRunningAsAdmin);
        StartupTabView startupTab = new StartupTabView(busy, AdminCheck::isRunningAsAdmin);
        CleanerTabView cleanerTab = new CleanerTabView(busy, AdminCheck::isRunningAsAdmin);
        DuplicateFilesTabView duplicateFilesTab = new DuplicateFilesTabView(AdminCheck::isRunningAsAdmin);
        DiskToolsTabView diskToolsTab = new DiskToolsTabView(AdminCheck::isRunningAsAdmin);
        BrowserExtensionsTabView browserExtensionsTab = new BrowserExtensionsTabView(AdminCheck::isRunningAsAdmin);
        NetworkOptimizerTabView networkOptimizerTab = new NetworkOptimizerTabView(busy, AdminCheck::isRunningAsAdmin);

        BorderPane root = new BorderPane();

        // Load logo icon
        Image logoImage = new Image(getClass().getResourceAsStream("/logo-ico.png"));
        ImageView logoView = new ImageView(logoImage);
        logoView.setFitHeight(48);
        logoView.setFitWidth(48);
        logoView.setPreserveRatio(true);

        Label appTitle = new Label("SBTools");
        appTitle.getStyleClass().addAll("label", "large");
        appTitle.setStyle("-fx-text-fill: #2AE061; -fx-font-size: 20px; -fx-font-weight: bold; -fx-font-family: 'Segoe UI', 'Orbitron', sans-serif; -fx-letter-spacing: 1px; -fx-padding: 8 0 4 0;");

        VBox sidebar = new VBox(6);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);

        UIButton driversBtn = UIButton.primary("Drivers");
        UIButton backupRestoreBtn = UIButton.secondary("Backup/Rollback");
        UIButton softwareBtn = UIButton.secondary("Software update");
        UIButton systemInfoBtn = UIButton.secondary("System Information");
        UIButton uninstallerBtn = UIButton.secondary("Uninstaller");
        UIButton startupBtn = UIButton.secondary("Startup items/services");
        UIButton cleanerBtn = UIButton.secondary("System cleanup");
        UIButton duplicateFilesBtn = UIButton.secondary("Duplicate Files");
        UIButton diskToolsBtn = UIButton.secondary("Disk Tools");
        UIButton browserExtensionsBtn = UIButton.secondary("Browser Extensions");
        UIButton networkOptimizerBtn = UIButton.secondary("Network Optimizer");

        Separator sep = new Separator();
        sep.setStyle("-fx-padding: 4 0 4 0;");

        UIButton[] allButtons = new UIButton[]{driversBtn, backupRestoreBtn, softwareBtn, systemInfoBtn, uninstallerBtn, startupBtn, cleanerBtn, duplicateFilesBtn, diskToolsBtn, browserExtensionsBtn, networkOptimizerBtn};

        driversBtn.setOnAction(e -> { selectTab(driversBtn, allButtons); root.setCenter(driversTab); });
        backupRestoreBtn.setOnAction(e -> { selectTab(backupRestoreBtn, allButtons); root.setCenter(backupRestoreTab); });
        softwareBtn.setOnAction(e -> { selectTab(softwareBtn, allButtons); root.setCenter(softwareTab); });
        systemInfoBtn.setOnAction(e -> { selectTab(systemInfoBtn, allButtons); root.setCenter(systemInfoTab); });
        uninstallerBtn.setOnAction(e -> { selectTab(uninstallerBtn, allButtons); root.setCenter(uninstallerTab); });
        startupBtn.setOnAction(e -> { selectTab(startupBtn, allButtons); root.setCenter(startupTab); });
        cleanerBtn.setOnAction(e -> { selectTab(cleanerBtn, allButtons); root.setCenter(cleanerTab); });
        duplicateFilesBtn.setOnAction(e -> { selectTab(duplicateFilesBtn, allButtons); root.setCenter(duplicateFilesTab); });
        diskToolsBtn.setOnAction(e -> { selectTab(diskToolsBtn, allButtons); root.setCenter(diskToolsTab); });
        browserExtensionsBtn.setOnAction(e -> { selectTab(browserExtensionsBtn, allButtons); root.setCenter(browserExtensionsTab); });
        networkOptimizerBtn.setOnAction(e -> { selectTab(networkOptimizerBtn, allButtons); root.setCenter(networkOptimizerTab); });

        sidebar.getChildren().addAll(logoView, appTitle, sep, driversBtn, backupRestoreBtn, softwareBtn, systemInfoBtn, uninstallerBtn, startupBtn, cleanerBtn, duplicateFilesBtn, diskToolsBtn, browserExtensionsBtn, networkOptimizerBtn);

        root.setLeft(sidebar);
        root.setCenter(driversTab);

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
    }

    private void selectTab(UIButton selected, UIButton... all) {
        for (UIButton btn : all) {
            btn.setStyleType(UIButton.ButtonStyle.SECONDARY);
        }
        selected.setStyleType(UIButton.ButtonStyle.PRIMARY);
    }

    private void showEula(AppSettings settings) {
        Alert eula = new Alert(Alert.AlertType.CONFIRMATION);
        eula.setTitle("License agreement");
        eula.setHeaderText(AppInfo.DISPLAY_NAME);
        eula.setContentText(
                "This tool can modify device drivers and system updates. "
                        + "Use at your own risk. Incorrect drivers may cause hardware issues. "
                        + "Always create backups before updating.\n\nDo you accept?");
        if (eula.showAndWait().orElse(null) != javafx.scene.control.ButtonType.OK) {
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
                    settings.networkOptimizationPreset()
            ));
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
    }



    public static void main(String[] args) {
        launch(args);
    }
}
