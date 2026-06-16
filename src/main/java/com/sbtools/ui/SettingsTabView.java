package com.sbtools.ui;

import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.io.File;

public class SettingsTabView extends VBox {

    private final SettingsStore settingsStore;

    private CheckBox minimizeToTrayBox;
    private CheckBox startMinimizedBox;
    private CheckBox scanOnStartupBox;
    private CheckBox notifyDriverUpdateBox;
    private CheckBox autoBackupBox;
    private CheckBox createRestorePointBox;
    private CheckBox rememberWindowSizeBox;
    private CheckBox autoCheckUpdatesBox;
    private TextField downloadDirField;
    private TextField backupDirField;
    private TextField powerShellField;

    public SettingsTabView(SettingsStore settingsStore) {
        this.settingsStore = settingsStore;
        setPadding(new Insets(24));
        setSpacing(16);
        getStyleClass().add("settings-view");

        AppSettings settings = settingsStore.load();

        Label header = new Label("Settings");
        header.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #f8f8f2;");
        getChildren().add(header);

        getChildren().add(createSection("General", createGeneralSection(settings)));
        getChildren().add(createSection("Paths", createPathsSection(settings)));
        getChildren().add(createSection("Driver Safety", createDriverSafetySection(settings)));
        getChildren().add(createSection("Window", createWindowSection(settings)));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        getChildren().add(spacer);

        HBox buttons = new HBox(12);
        buttons.setAlignment(Pos.CENTER_RIGHT);
        Button resetBtn = new Button("Reset to Defaults");
        resetBtn.getStyleClass().addAll("button", "button-outlined");
        resetBtn.setOnAction(e -> resetToDefaults());
        Button saveBtn = new Button("Save");
        saveBtn.getStyleClass().addAll("button", "accent");
        saveBtn.setOnAction(e -> saveSettings());
        buttons.getChildren().addAll(resetBtn, saveBtn);
        getChildren().add(buttons);
    }

    private VBox createSection(String title, VBox content) {
        Label sectionTitle = new Label(title);
        sectionTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #8be9fd; -fx-padding: 0 0 6 0;");
        VBox section = new VBox(4);
        section.getChildren().addAll(sectionTitle, content);
        section.setPadding(new Insets(12));
        section.setStyle("-fx-background-color: #282a36; -fx-background-radius: 6; -fx-border-color: #44475a; -fx-border-radius: 6;");
        return section;
    }

    private VBox createGeneralSection(AppSettings s) {
        minimizeToTrayBox = new CheckBox("Minimize to system tray");
        minimizeToTrayBox.setSelected(s.minimizeToTray());
        startMinimizedBox = new CheckBox("Start minimized");
        startMinimizedBox.setSelected(s.startMinimized());
        scanOnStartupBox = new CheckBox("Auto-scan on startup");
        scanOnStartupBox.setSelected(s.scanOnStartup());
        notifyDriverUpdateBox = new CheckBox("Show notifications for driver updates");
        notifyDriverUpdateBox.setSelected(s.notifyOnDriverUpdate());
        autoCheckUpdatesBox = new CheckBox("Auto-check for updates on startup");
        autoCheckUpdatesBox.setSelected(s.autoCheckForUpdates());

        VBox box = new VBox(8);
        box.getChildren().addAll(minimizeToTrayBox, startMinimizedBox, scanOnStartupBox, notifyDriverUpdateBox, autoCheckUpdatesBox);
        return box;
    }

    private VBox createPathsSection(AppSettings s) {
        downloadDirField = new TextField(s.downloadDirectory());
        downloadDirField.setPrefWidth(350);
        Button downloadBrowse = createBrowseButton(downloadDirField, true);

        backupDirField = new TextField(s.backupDirectory().isEmpty() ? "Auto" : s.backupDirectory());
        backupDirField.setPrefWidth(350);
        Button backupBrowse = createBrowseButton(backupDirField, true);
        Button backupReset = new Button("Reset");
        backupReset.getStyleClass().addAll("button", "small");
        backupReset.setOnAction(e -> backupDirField.setText("Auto"));

        powerShellField = new TextField(s.powerShellPath());
        powerShellField.setPrefWidth(350);
        Button psBrowse = createBrowseButton(powerShellField, false);

        VBox box = new VBox(10);
        box.getChildren().addAll(
                createPathRow("Download dir:", downloadDirField, downloadBrowse),
                createPathRow("Backup dir:", backupDirField, backupBrowse, backupReset),
                createPathRow("PowerShell:", powerShellField, psBrowse)
        );
        return box;
    }

    private HBox createPathRow(String label, TextField field, Button... buttons) {
        Label l = new Label(label);
        l.setMinWidth(90);
        l.setStyle("-fx-text-fill: #f8f8f2;");
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(field, Priority.ALWAYS);
        row.getChildren().add(l);
        row.getChildren().add(field);
        row.getChildren().addAll(buttons);
        return row;
    }

    private Button createBrowseButton(TextField target, boolean isDir) {
        Button btn = new Button("Browse");
        btn.getStyleClass().addAll("button", "small");
        btn.setOnAction(e -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle("Select Directory");
            File selected = chooser.showDialog(getScene().getWindow());
            if (selected != null) {
                target.setText(selected.getAbsolutePath());
            }
        });
        return btn;
    }

    private VBox createDriverSafetySection(AppSettings s) {
        autoBackupBox = new CheckBox("Auto-backup drivers before updating");
        autoBackupBox.setSelected(s.autoBackupDrivers());
        createRestorePointBox = new CheckBox("Create system restore point before updating");
        createRestorePointBox.setSelected(s.createSystemRestorePoint());

        VBox box = new VBox(8);
        box.getChildren().addAll(autoBackupBox, createRestorePointBox);
        return box;
    }

    private VBox createWindowSection(AppSettings s) {
        rememberWindowSizeBox = new CheckBox("Remember window size and position");
        rememberWindowSizeBox.setSelected(s.windowMaximized());

        VBox box = new VBox(8);
        box.getChildren().add(rememberWindowSizeBox);
        return box;
    }

    private void saveSettings() {
        try {
            AppSettings current = settingsStore.load();
            String backupDir = backupDirField.getText().trim();
            AppSettings updated = new AppSettings(
                    autoBackupBox.isSelected(),
                    createRestorePointBox.isSelected(),
                    current.eulaAccepted(),
                    current.excludedDriverIds(),
                    current.skippedSoftwareIds(),
                    current.networkOptimizationPreset(),
                    downloadDirField.getText().trim(),
                    minimizeToTrayBox.isSelected(),
                    startMinimizedBox.isSelected(),
                    scanOnStartupBox.isSelected(),
                    notifyDriverUpdateBox.isSelected(),
                    backupDir.equals("Auto") ? "" : backupDir,
                    powerShellField.getText().trim(),
                    current.windowWidth(),
                    current.windowHeight(),
                    rememberWindowSizeBox.isSelected(),
                    autoCheckUpdatesBox.isSelected()
            );
            settingsStore.save(updated);
            showAlert(Alert.AlertType.INFORMATION, "Settings saved successfully.");
        } catch (Exception ex) {
            AppLogger.error("Failed to save settings", ex);
            showAlert(Alert.AlertType.ERROR, "Failed to save settings: " + ex.getMessage());
        }
    }

    private void resetToDefaults() {
        AppSettings d = AppSettings.defaults();
        minimizeToTrayBox.setSelected(d.minimizeToTray());
        startMinimizedBox.setSelected(d.startMinimized());
        scanOnStartupBox.setSelected(d.scanOnStartup());
        notifyDriverUpdateBox.setSelected(d.notifyOnDriverUpdate());
        autoCheckUpdatesBox.setSelected(d.autoCheckForUpdates());
        downloadDirField.setText(d.downloadDirectory());
        backupDirField.setText("Auto");
        powerShellField.setText(d.powerShellPath());
        autoBackupBox.setSelected(d.autoBackupDrivers());
        createRestorePointBox.setSelected(d.createSystemRestorePoint());
        rememberWindowSizeBox.setSelected(d.windowMaximized());
    }

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.setTitle(AppInfo.DISPLAY_NAME);
        alert.showAndWait();
    }
}
