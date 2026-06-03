package com.sbtools;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.ui.DriversTabView;
import com.sbtools.ui.RestoreTabView;
import com.sbtools.ui.SoftwareUpdatesTabView;
import com.sbtools.ui.UIButton;

import atlantafx.base.theme.Dracula;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
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
        RestoreTabView restoreTab = new RestoreTabView(busy, AdminCheck::isRunningAsAdmin);
        SoftwareUpdatesTabView softwareTab = new SoftwareUpdatesTabView(busy, AdminCheck::isRunningAsAdmin);

        BorderPane root = new BorderPane();

        Label appTitle = new Label("SB Tools");
        appTitle.getStyleClass().addAll("label", "large");
        appTitle.setStyle("-fx-text-fill: #50fa7b; -fx-font-size: 18px; -fx-font-weight: bold; -fx-padding: 0 0 4 0;");

        VBox sidebar = new VBox(6);
        sidebar.setPadding(new Insets(16));
        sidebar.getStyleClass().add("sidebar");
        sidebar.setAlignment(Pos.TOP_LEFT);

        UIButton driversBtn = UIButton.primary("Drivers");
        UIButton restoreBtn = UIButton.secondary("Rollback drivers");
        UIButton softwareBtn = UIButton.secondary("Software update");

        Separator sep = new Separator();
        sep.setStyle("-fx-padding: 4 0 4 0;");

        driversBtn.setOnAction(e -> { selectTab(driversBtn, driversBtn, restoreBtn, softwareBtn); root.setCenter(driversTab); });
        restoreBtn.setOnAction(e -> { selectTab(restoreBtn, driversBtn, restoreBtn, softwareBtn); root.setCenter(restoreTab); restoreTab.refresh(); });
        softwareBtn.setOnAction(e -> { selectTab(softwareBtn, driversBtn, restoreBtn, softwareBtn); root.setCenter(softwareTab); });

        sidebar.getChildren().addAll(appTitle, sep, driversBtn, restoreBtn, softwareBtn);

        root.setLeft(sidebar);
        root.setCenter(driversTab);

        if (!AppPaths.isWindows()) {
            new Alert(Alert.AlertType.WARNING,
                    AppInfo.DISPLAY_NAME + " is designed for Windows only.").showAndWait();
        }

        Scene scene = new Scene(root, 960, 600);
        scene.getStylesheets().add(getClass().getResource("/custom.css").toExternalForm());
        stage.setTitle(AppInfo.DISPLAY_NAME);
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
