package com.basicsdriverupdate;

import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.settings.SettingsStore;
import com.basicsdriverupdate.ui.DriversTabView;
import com.basicsdriverupdate.ui.RestoreTabView;
import com.basicsdriverupdate.ui.WindowsUpdateTabView;
import com.basicsdriverupdate.ui.SoftwareUpdatesTabView;
import com.basicsdriverupdate.ui.UILabel;
import com.basicsdriverupdate.ui.UIButton;

import javafx.scene.layout.VBox;
import com.basicsdriverupdate.util.AdminCheck;
import com.basicsdriverupdate.util.AppInfo;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.AppPaths;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import java.io.IOException;

public class App extends Application {

    private final SettingsStore settingsStore = new SettingsStore();
    private final BooleanProperty busy = new SimpleBooleanProperty(false);


    @Override
    public void start(Stage stage) {
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
        RestoreTabView restoreTab = new RestoreTabView(busy, AdminCheck::isRunningAsAdmin);
        WindowsUpdateTabView wuTab = new WindowsUpdateTabView(busy, AdminCheck::isRunningAsAdmin);
        SoftwareUpdatesTabView softwareTab = new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);

        BorderPane root = new BorderPane();

        VBox sidebar = new VBox(12);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");

        UIButton driversBtn = UIButton.primary("Drivers");
        UIButton restoreBtn = UIButton.secondary("Rollback driver");
        UIButton wuBtn = UIButton.secondary("Windows Update");
        UIButton softwareBtn = UIButton.secondary("Software updates");

        driversBtn.setOnAction(e -> { selectTab(driversBtn, driversBtn, restoreBtn, wuBtn, softwareBtn); root.setCenter(driversTab); });
        restoreBtn.setOnAction(e -> { selectTab(restoreBtn, driversBtn, restoreBtn, wuBtn, softwareBtn); root.setCenter(restoreTab); restoreTab.refresh(); });
        wuBtn.setOnAction(e -> { selectTab(wuBtn, driversBtn, restoreBtn, wuBtn, softwareBtn); root.setCenter(wuTab); });
        softwareBtn.setOnAction(e -> { selectTab(softwareBtn, driversBtn, restoreBtn, wuBtn, softwareBtn); root.setCenter(softwareTab); });

        sidebar.getChildren().addAll(driversBtn, restoreBtn, wuBtn, softwareBtn);

        root.setLeft(sidebar);
        root.setCenter(driversTab);

        if (!AppPaths.isWindows()) {
            new Alert(Alert.AlertType.WARNING,
                    AppInfo.DISPLAY_NAME + " is designed for Windows only.").showAndWait();
        }

        Scene scene = new Scene(root, 920, 560);
        // Load modern theme stylesheet
        String stylesheet = getClass().getResource("/styles-modern.css").toExternalForm();
        scene.getStylesheets().add(stylesheet);
        stage.setTitle(AppInfo.DISPLAY_NAME);
        stage.setScene(scene);
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
                    true
            ));
        } catch (IOException e) {
            AppLogger.error("Failed to save EULA acceptance", e);
        }
    }



    public static void main(String[] args) {
        launch(args);
    }
}
