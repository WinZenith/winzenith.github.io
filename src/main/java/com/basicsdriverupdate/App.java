package com.basicsdriverupdate;

import com.basicsdriverupdate.settings.AppSettings;
import com.basicsdriverupdate.settings.SettingsStore;
import com.basicsdriverupdate.ui.DriversTabView;
import com.basicsdriverupdate.ui.RestoreTabView;
import com.basicsdriverupdate.ui.WindowsUpdateTabView;
import com.basicsdriverupdate.ui.SoftwareUpdatesTabView;
import com.basicsdriverupdate.ui.UILabel;
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

        // Modern header with improved styling
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        UILabel titleLabel = UILabel.header(AppInfo.DISPLAY_NAME);
        HBox header = new HBox(16, titleLabel, spacer);
        header.setPadding(new Insets(14, 16, 14, 16));
        header.setStyle("-fx-background-color: #FFFFFF; -fx-border-color: #E5E7EB; -fx-border-width: 0 0 1 0;");

        DriversTabView driversTab = new DriversTabView(busy, AdminCheck::isRunningAsAdmin);
        RestoreTabView restoreTab = new RestoreTabView(busy, AdminCheck::isRunningAsAdmin);
        WindowsUpdateTabView wuTab = new WindowsUpdateTabView(busy, AdminCheck::isRunningAsAdmin);
        SoftwareUpdatesTabView softwareTab = new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);

        Tab restoreTabPane = new Tab("Restore", restoreTab);
        TabPane tabs = new TabPane(
                new Tab("Drivers", driversTab),
                restoreTabPane,
                new Tab("Windows Update", wuTab),
                new Tab("Software updates", softwareTab)
        );
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == restoreTabPane) {
                restoreTab.refresh();
            }
        });
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(tabs);

        if (!AppPaths.isWindows()) {
            new Alert(Alert.AlertType.WARNING,
                    AppInfo.DISPLAY_NAME + " is designed for Windows only.").showAndWait();
        }

        Scene scene = new Scene(root, 920, 560);
        // Load light theme stylesheet
        String stylesheet = getClass().getResource("/styles.css").toExternalForm();
        scene.getStylesheets().add(stylesheet);
        stage.setTitle(AppInfo.DISPLAY_NAME);
        stage.setScene(scene);
        stage.show();
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
