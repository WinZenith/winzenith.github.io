package com.sbtools.ui;

import com.sbtools.backup.SystemRestoreService;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.NetworkSnapshot;
import com.sbtools.netoptimizer.OptimizationPreset;
import com.sbtools.netoptimizer.PresetExpectations;
import com.sbtools.netoptimizer.TcpSettings;
import com.sbtools.settings.AppSettings;
import com.sbtools.settings.SettingsStore;
import com.sbtools.util.AppExecutors;
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
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

class OptimizationPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final SettingsStore settingsStore;
    private AppSettings currentSettings;
    private final Label statusLabel;
    private final Consumer<AppSettings> onSettingsSaved;
    private ToggleGroup presetGroup;
    private Label descLabel;
    private Label snapshotLabel;
    private javafx.scene.control.CheckBox restorePointCheck;
    private volatile Future<?> currentTask;

    OptimizationPanel(NetworkOptimizerService service, BooleanProperty busy,
                      SettingsStore settingsStore, AppSettings currentSettings,
                      Label statusLabel, Consumer<AppSettings> onSettingsSaved) {
        this(service, busy, settingsStore, currentSettings, statusLabel, onSettingsSaved, () -> false);
    }

    OptimizationPanel(NetworkOptimizerService service, BooleanProperty busy,
                      SettingsStore settingsStore, AppSettings currentSettings,
                      Label statusLabel, Consumer<AppSettings> onSettingsSaved, BooleanSupplier adminCheck) {
        this.service = service;
        this.busy = busy;
        this.adminCheck = adminCheck != null ? adminCheck : () -> false;
        this.settingsStore = settingsStore;
        this.currentSettings = currentSettings;
        this.statusLabel = statusLabel;
        this.onSettingsSaved = onSettingsSaved;
        getChildren().addAll(buildContent());
    }

    private VBox buildContent() {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12, 16, 12, 16));

        Label header = new Label("Select Optimization Preset:");
        header.getStyleClass().addAll("label", "large");
        box.getChildren().add(header);

        ToggleGroup group = new ToggleGroup();
        this.presetGroup = group;

        this.descLabel = new Label("Choose a preset and click Apply.");
        descLabel.setWrapText(true);
        descLabel.setPrefWidth(500);
        Label descLabel = this.descLabel;

        OptimizationPreset savedPreset = OptimizationPreset.DEFAULT;
        try {
            String raw = currentSettings != null ? currentSettings.networkOptimizationPreset() : null;
            if (raw != null) savedPreset = OptimizationPreset.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException | NullPointerException ignored) {
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

        boolean restoreDefault = currentSettings != null && currentSettings.createSystemRestorePoint();
        restorePointCheck = new javafx.scene.control.CheckBox("Create system restore point before applying");
        restorePointCheck.setSelected(restoreDefault);
        restorePointCheck.setWrapText(true);
        box.getChildren().add(restorePointCheck);

        snapshotLabel = new Label("No snapshot yet — one is captured automatically before each Apply.");
        snapshotLabel.setWrapText(true);
        snapshotLabel.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        snapshotLabel.setPrefWidth(520);
        box.getChildren().add(snapshotLabel);
        refreshSnapshotLabel();

        Button currentSettingsBtn = UIButton.secondary("Show Current TCP/IP Settings");
        currentSettingsBtn.setOnAction(e -> showCurrentSettings());
        Button previewBtn = UIButton.secondary("Preview Changes");
        previewBtn.setOnAction(e -> previewSelected(group));
        Button snapshotsBtn = UIButton.secondary("Snapshots…");
        snapshotsBtn.setOnAction(e -> showSnapshots());
        HBox infoRow = new HBox(8, currentSettingsBtn, previewBtn, snapshotsBtn);
        infoRow.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(infoRow);

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

        resetBtn.setOnAction(e -> applyOptimization(OptimizationPreset.DEFAULT, progressBar));

        HBox btnBox = new HBox(12, applyBtn, resetBtn, progressBar);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        btnBox.setPadding(new Insets(12, 16, 12, 16));

        return new VBox(box, btnBox);
    }

    private boolean requireAdmin() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Administrator privileges required.\n\nRight-click WinZenith.exe → Run as administrator.\n\nOptimization changes TCP/IP and registry settings.").showAndWait();
            return false;
        }
        return true;
    }

    private void applyOptimization(OptimizationPreset preset, ProgressBar progressBar) {
        if (busy.get()) return;
        if (!requireAdmin()) return;

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Apply " + preset.getDisplayName() + "?\n\n" + preset.getDescription()
                        + "\n\nA snapshot of current TCP settings is captured first for guided restore.");
        confirm.setTitle("Confirm Optimization");
        confirm.setHeaderText("Apply Optimization Preset");
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;

        boolean wantRestorePoint = restorePointCheck != null && restorePointCheck.isSelected();
        busy.set(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("Capturing snapshot before " + preset.getDisplayName() + "...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            NetworkSnapshot preSnap = null;
            try {
                preSnap = service.captureSnapshot("before " + preset.name());
            } catch (Exception e) {
                AppLogger.warning("Pre-apply snapshot failed: " + e.getMessage());
            }
            if (wantRestorePoint) {
                try {
                    Platform.runLater(() -> statusLabel.setText("Creating system restore point..."));
                    var rp = new SystemRestoreService().createRestorePoint("WinZenith network " + preset.name());
                    if (!rp.success()) {
                        final String err = rp.error() != null ? rp.error() : "unknown error";
                        Platform.runLater(() -> new Alert(Alert.AlertType.WARNING,
                                "Restore point could not be created:\n" + err
                                        + "\n\nContinue with optimization anyway?").showAndWait());
                    }
                } catch (Exception e) {
                    AppLogger.warning("Restore point before optimization failed: " + e.getMessage());
                }
            }
            Platform.runLater(() -> statusLabel.setText("Applying " + preset.getDisplayName() + "..."));
            try {
                var result = service.applyOptimization(preset);
                String saveError = null;
                if (result.success()) {
                    try {
                        AppSettings newSettings = currentSettings.toBuilder()
                                .networkOptimizationPreset(preset.name())
                                .build();
                        settingsStore.save(newSettings);
                        currentSettings = newSettings;
                        if (onSettingsSaved != null) {
                            onSettingsSaved.accept(newSettings);
                        }
                    } catch (IOException e) {
                        AppLogger.warning("Failed to save optimization preset: " + e.getMessage());
                        saveError = e.getMessage();
                    }
                }
                final String finalSaveError = saveError;
                final boolean wasSuccess = result.success();
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    busy.set(false);
                    if (wasSuccess) {
                        // update toggle to reflect actual applied preset
                        for (javafx.scene.control.Toggle t : presetGroup.getToggles()) {
                            if (t instanceof RadioButton rb && rb.getUserData() == preset) {
                                rb.setSelected(true);
                                if (descLabel != null) descLabel.setText(preset.getDescription());
                                break;
                            }
                        }
                    }
                    statusLabel.setText(wasSuccess
                            ? "Optimization applied: " + preset.getDisplayName()
                            : "Optimization failed.");
                    Alert a = new Alert(wasSuccess ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : ""));
                    a.showAndWait();
                    if (finalSaveError != null) {
                        new Alert(Alert.AlertType.WARNING,
                                "Preset applied successfully, but failed to save preference:\n" + finalSaveError
                                        + "\n\nThe preset will revert on next launch.").showAndWait();
                    }
                    refreshSnapshotLabel();
                    if (wasSuccess) {
                        // defer so busy is already false
                        Platform.runLater(this::showCurrentSettings);
                    }
                });
                return;
            } catch (Exception e) {
                Platform.runLater(() -> {
                    progressBar.setVisible(false);
                    busy.set(false);
                    statusLabel.setText("Optimization failed.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            }
        });
    }

    void refreshPresetSelection() {
        if (presetGroup == null) return;
        OptimizationPreset savedPreset = OptimizationPreset.DEFAULT;
        try {
            String raw = currentSettings.networkOptimizationPreset();
            if (raw != null) savedPreset = OptimizationPreset.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
        }
        for (javafx.scene.control.Toggle toggle : presetGroup.getToggles()) {
            if (toggle instanceof RadioButton rb && rb.getUserData() instanceof OptimizationPreset p && p == savedPreset) {
                rb.setSelected(true);
                if (descLabel != null) descLabel.setText(p.getDescription());
                break;
            }
        }
    }

    private void showCurrentSettings() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Loading TCP/IP settings...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                TcpSettings settings = service.getCurrentTcpSettings();
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder();
                    if (settings.settings().isEmpty()) {
                        sb.append("No TCP global settings returned.\n\nPossible causes:\n- Not running on Windows\n- netsh output localized or permission denied (run as Administrator)\n");
                    } else {
                        settings.settings().forEach((k, v) -> sb.append(k).append(": ").append(v).append("\n"));
                    }
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
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load TCP/IP settings."));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void previewSelected(ToggleGroup group) {
        RadioButton selected = (RadioButton) group.getSelectedToggle();
        if (selected == null) return;
        OptimizationPreset preset = (OptimizationPreset) selected.getUserData();
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        statusLabel.setText("Building preview for " + preset.getDisplayName() + "...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var rows = service.buildPreview(preset);
                Platform.runLater(() -> {
                    javafx.scene.control.TableView<PresetExpectations.PreviewRow> table =
                            new javafx.scene.control.TableView<>();
                    table.setItems(javafx.collections.FXCollections.observableArrayList(rows));
                    javafx.scene.control.TableColumn<PresetExpectations.PreviewRow, String> c1 =
                            new javafx.scene.control.TableColumn<>("Setting");
                    c1.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().setting()));
                    c1.setPrefWidth(160);
                    javafx.scene.control.TableColumn<PresetExpectations.PreviewRow, String> c2 =
                            new javafx.scene.control.TableColumn<>("Current");
                    c2.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().current()));
                    c2.setPrefWidth(220);
                    javafx.scene.control.TableColumn<PresetExpectations.PreviewRow, String> c3 =
                            new javafx.scene.control.TableColumn<>("Will Set");
                    c3.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().willSet()));
                    c3.setPrefWidth(220);
                    table.getColumns().addAll(c1, c2, c3);
                    table.setPrefHeight(280);
                    long changed = rows.stream().filter(PresetExpectations.PreviewRow::changes).count();
                    Alert alert = new Alert(Alert.AlertType.INFORMATION);
                    alert.setTitle("Preview — " + preset.getDisplayName());
                    alert.setHeaderText(changed + " of " + rows.size() + " settings differ from current state."
                            + " No changes applied.");
                    alert.getDialogPane().setContent(table);
                    alert.getDialogPane().setPrefWidth(640);
                    alert.showAndWait();
                    statusLabel.setText("Ready.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Preview failed: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void refreshSnapshotLabel() {
        if (snapshotLabel == null) return;
        AppExecutors.ioPool().submit(() -> {
            try {
                var latest = service.latestSnapshot();
                Platform.runLater(() -> {
                    if (latest.isPresent()) {
                        snapshotLabel.setText("Last snapshot: " + latest.get().summary());
                    } else {
                        snapshotLabel.setText("No snapshot yet — one is captured automatically before each Apply.");
                    }
                });
            } catch (Exception ignored) {}
        });
    }

    private void showSnapshots() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        statusLabel.setText("Loading snapshots...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var snaps = service.listSnapshots();
                Platform.runLater(() -> {
                    if (snaps.isEmpty()) {
                        new Alert(Alert.AlertType.INFORMATION,
                                "No snapshots yet. Apply a preset (or it will be captured automatically).").showAndWait();
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append("Snapshots (newest first, max 20). Read-only+ mode: restore = guided 'Reset to Defaults' + manual check.\n\n");
                        for (NetworkSnapshot s : snaps) {
                            sb.append("• ").append(s.summary()).append("\n");
                        }
                        var first = snaps.get(0);
                        var info = service.describeRestore(first);
                        if (info.details() != null) sb.append("\n--- Newest snapshot detail ---\n").append(info.details());
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("Network Snapshots");
                        alert.setHeaderText(snaps.size() + " snapshot(s) stored (portable .winzenith/network-snapshots.json)");
                        javafx.scene.control.TextArea area = new javafx.scene.control.TextArea(sb.toString());
                        area.setEditable(false);
                        area.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
                        area.setPrefRowCount(20);
                        area.setPrefColumnCount(70);
                        alert.getDialogPane().setContent(area);
                        alert.showAndWait();
                    }
                    statusLabel.setText("Ready.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load snapshots."));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
    }
}
