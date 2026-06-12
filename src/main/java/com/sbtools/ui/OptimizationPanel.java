package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.OptimizationPreset;
import com.sbtools.netoptimizer.TcpSettings;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.io.IOException;

class OptimizationPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final SettingsStore settingsStore;
    private final AppSettings currentSettings;
    private final Label statusLabel;

    OptimizationPanel(NetworkOptimizerService service, BooleanProperty busy,
                      SettingsStore settingsStore, AppSettings currentSettings, Label statusLabel) {
        this.service = service;
        this.busy = busy;
        this.settingsStore = settingsStore;
        this.currentSettings = currentSettings;
        this.statusLabel = statusLabel;
        getChildren().addAll(buildContent());
    }

    private VBox buildContent() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12, 16, 12, 16));

        Label header = new Label("Select Optimization Preset:");
        header.getStyleClass().addAll("label", "large");
        box.getChildren().add(header);

        ToggleGroup group = new ToggleGroup();

        Label descLabel = new Label("Choose a preset and click Apply.");
        descLabel.setWrapText(true);
        descLabel.setPrefWidth(500);

        OptimizationPreset savedPreset = OptimizationPreset.DEFAULT;
        try {
            savedPreset = OptimizationPreset.valueOf(currentSettings.networkOptimizationPreset());
        } catch (IllegalArgumentException ignored) {
        }

        for (OptimizationPreset preset : OptimizationPreset.values()) {
            RadioButton rb = new RadioButton(preset.getDisplayName());
            rb.setToggleGroup(group);
            rb.setUserData(preset);
            if (preset == savedPreset) {
                rb.setSelected(true);
                descLabel.setText(preset.getDescription());
            }
            rb.setOnAction(e -> descLabel.setText(preset.getDescription()));
            box.getChildren().add(rb);
        }

        box.getChildren().add(descLabel);

        Button currentSettingsBtn = UIButton.secondary("Show Current TCP/IP Settings");
        currentSettingsBtn.setOnAction(e -> showCurrentSettings());
        box.getChildren().add(currentSettingsBtn);

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(300);

        Button applyBtn = UIButton.primary("Apply");
        Button resetBtn = UIButton.secondary("Reset to Defaults");

        applyBtn.setOnAction(e -> {
            RadioButton selected = (RadioButton) group.getSelectedToggle();
            if (selected == null) return;
            OptimizationPreset preset = (OptimizationPreset) selected.getUserData();
            applyOptimization(preset, progressBar);
        });

        resetBtn.setOnAction(e -> {
            applyOptimization(OptimizationPreset.DEFAULT, progressBar);
            group.selectToggle(group.getToggles().get(0));
        });

        HBox btnBox = new HBox(12, applyBtn, resetBtn, progressBar);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        btnBox.setPadding(new Insets(12, 16, 12, 16));

        return new VBox(box, btnBox);
    }

    private void applyOptimization(OptimizationPreset preset, ProgressBar progressBar) {
        if (busy.get()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Apply " + preset.getDisplayName() + "?\n\n" + preset.getDescription());
        confirm.setTitle("Confirm Optimization");
        confirm.setHeaderText("Apply Optimization Preset");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        busy.set(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("Applying " + preset.getDisplayName() + "...");

        new Thread(() -> {
            var result = service.applyOptimization(preset);
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                statusLabel.setText(result.success()
                        ? "Optimization applied: " + preset.getDisplayName()
                        : "Optimization failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                if (result.success()) savePreset(preset);
                busy.set(false);
            });
        }, "net-optimize-apply").start();
    }

    private void savePreset(OptimizationPreset preset) {
        try {
            settingsStore.save(new AppSettings(
                    currentSettings.autoBackupDrivers(),
                    currentSettings.createSystemRestorePoint(),
                    currentSettings.eulaAccepted(),
                    currentSettings.excludedDriverIds(),
                    currentSettings.skippedSoftwareIds(),
                    preset.name()
            ));
        } catch (IOException e) {
            AppLogger.warning("Failed to save optimization preset: " + e.getMessage());
        }
    }

    private void showCurrentSettings() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading TCP/IP settings...");

        new Thread(() -> {
            TcpSettings settings = service.getCurrentTcpSettings();
            Platform.runLater(() -> {
                StringBuilder sb = new StringBuilder();
                settings.settings().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Current TCP/IP Settings");
                alert.setHeaderText("Active TCP Global Settings");
                javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(sb.toString());
                area.setEditable(false);
                area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
                area.setPrefRowCount(20);
                area.setPrefColumnCount(60);
                alert.getDialogPane().setContent(area);
                alert.showAndWait();
                statusLabel.setText("Ready.");
                busy.set(false);
            });
        }, "net-tcp-settings").start();
    }
}
