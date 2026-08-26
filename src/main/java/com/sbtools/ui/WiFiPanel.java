package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.WiFiInfo;
import com.sbtools.util.AppExecutors;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

class WiFiPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final Label statusLabel = new Label("Ready.");
    private final Label ssidLabel = new Label("SSID: -");
    private final Label stateLabel = new Label("State: -");
    private final Label signalLabel = new Label("Signal: -");
    private final Label radioLabel = new Label("Radio: -");
    private final Label channelLabel = new Label("Channel: -");
    private final Label rateLabel = new Label("Rates: -");
    private final ComboBox<String> profileCombo = new ComboBox<>();
    private volatile Future<?> currentTask;
    // Separate loading flag for profiles so it doesn't collide with currentInfo busy
    private final java.util.concurrent.atomic.AtomicBoolean profileLoading = new java.util.concurrent.atomic.AtomicBoolean(false);

    WiFiPanel(NetworkOptimizerService service, BooleanProperty busy, BooleanSupplier adminCheck) {
        this.service = service;
        this.busy = busy;
        this.adminCheck = adminCheck != null ? adminCheck : () -> false;
        getChildren().addAll(buildContent());
        setPadding(new Insets(12, 16, 12, 16));
    }

    WiFiPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this(service, busy, () -> false);
    }

    private boolean requireAdmin() {
        if (!adminCheck.getAsBoolean()) {
            new Alert(Alert.AlertType.WARNING, "Administrator privileges required.\n\nRight-click WinZenith.exe → Run as administrator.").showAndWait();
            return false;
        }
        return true;
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
    }

    private VBox buildContent() {
        VBox content = new VBox(12);

        Label header = new Label("Wi-Fi");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        content.getChildren().add(buildCurrentConnectionSection());
        content.getChildren().add(buildSavedProfilesSection());

        return content;
    }

    private VBox buildCurrentConnectionSection() {
        VBox section = new VBox(4);
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1; -fx-padding: 10; -fx-background-color: #282a36;");

        Label sectionHeader = new Label("Current Connection");
        sectionHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #bd93f9; -fx-font-size: 13px;");
        section.getChildren().add(sectionHeader);

        for (Label lbl : new Label[]{ssidLabel, stateLabel, signalLabel, radioLabel, channelLabel, rateLabel}) {
            lbl.setStyle("-fx-text-fill: #f8f8f2;");
            section.getChildren().add(lbl);
        }

        Button refreshInfoBtn = UIButton.secondary("Refresh");
        refreshInfoBtn.setOnAction(e -> loadCurrentInfo());
        HBox btnRow = new HBox(8, refreshInfoBtn);
        btnRow.setPadding(new Insets(6, 0, 0, 0));
        section.getChildren().add(btnRow);

        return section;
    }

    private VBox buildSavedProfilesSection() {
        VBox section = new VBox(4);
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1; -fx-padding: 10; -fx-background-color: #282a36;");

        Label sectionHeader = new Label("Saved Profiles");
        sectionHeader.setStyle("-fx-font-weight: bold; -fx-text-fill: #bd93f9; -fx-font-size: 13px;");
        section.getChildren().add(sectionHeader);

        profileCombo.setPrefWidth(250);

        Button refreshProfilesBtn = UIButton.secondary("Refresh");
        refreshProfilesBtn.setOnAction(e -> loadProfiles());

        Button disconnectBtn = UIButton.secondary("Disconnect");
        disconnectBtn.setOnAction(e -> disconnectWifi());

        Button forgetBtn = UIButton.danger("Forget");
        forgetBtn.setOnAction(e -> forgetProfile());

        Button enableBtn = UIButton.success("Enable Wi-Fi");
        enableBtn.setOnAction(e -> setWifiAdapterState(true));

        Button disableBtn = UIButton.secondary("Disable Wi-Fi");
        disableBtn.setOnAction(e -> setWifiAdapterState(false));

        HBox row = new HBox(8, profileCombo, refreshProfilesBtn, disconnectBtn, forgetBtn, enableBtn, disableBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(row);

        return section;
    }

    void loadCurrentInfo() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        statusLabel.setText("Loading Wi-Fi info...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                WiFiInfo info = service.getCurrentWifiInfo();
                Platform.runLater(() -> {
                    if (info == null) {
                        ssidLabel.setText("SSID: -");
                        stateLabel.setText("State: Not connected");
                        signalLabel.setText("Signal: -");
                        radioLabel.setText("Radio: -");
                        channelLabel.setText("Channel: -");
                        rateLabel.setText("Rates: -");
                        statusLabel.setText("Wi-Fi: No adapter/info found.");
                    } else {
                        ssidLabel.setText("SSID: " + (info.ssid() != null && !info.ssid().isEmpty() ? info.ssid() : "-"));
                        stateLabel.setText("State: " + (info.state() != null ? info.state() : "-"));
                        signalLabel.setText("Signal: " + info.signalPercent() + "%");
                        radioLabel.setText("Radio: " + (info.radioType() != null ? info.radioType() : "-"));
                        channelLabel.setText("Channel: " + (info.channel() != null ? info.channel() : "-"));
                        String rates = "";
                        if (info.receiveRate() != null && !info.receiveRate().isEmpty()) rates += "Rx: " + info.receiveRate();
                        if (info.transmitRate() != null && !info.transmitRate().isEmpty()) {
                            if (!rates.isEmpty()) rates += "  ";
                            rates += "Tx: " + info.transmitRate();
                        }
                        rateLabel.setText("Rates: " + (rates.isEmpty() ? "-" : rates));
                        statusLabel.setText("Wi-Fi info loaded.");
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load Wi-Fi info: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    void loadProfiles() {
        if (!profileLoading.compareAndSet(false, true)) return;
        AppExecutors.ioPool().submit(() -> {
            try {
                List<String> profiles = service.getWifiProfiles();
                Platform.runLater(() -> {
                    String prev = profileCombo.getSelectionModel().getSelectedItem();
                    profileCombo.getItems().clear();
                    profileCombo.getItems().addAll(profiles);
                    if (prev != null && profiles.contains(prev)) {
                        profileCombo.getSelectionModel().select(prev);
                    } else if (!profiles.isEmpty()) {
                        profileCombo.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load profiles: " + e.getMessage()));
            } finally {
                profileLoading.set(false);
            }
        });
    }

    void disconnectWifi() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        statusLabel.setText("Disconnecting Wi-Fi...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.disconnectWifi();
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "Disconnected." : "Disconnect failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Disconnect failed.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    void forgetProfile() {
        String profile = profileCombo.getSelectionModel().getSelectedItem();
        if (profile == null) {
            new Alert(Alert.AlertType.WARNING, "Please select a profile.").showAndWait();
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Forget Wi-Fi profile '" + profile + "'?", ButtonType.YES, ButtonType.NO);
        confirm.setTitle("Forget Profile");
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;

        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        busy.set(true);
        statusLabel.setText("Forgetting profile: " + profile + "...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.forgetWifiProfile(profile);
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? result.message() : "Failed to forget profile.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                    if (result.success()) loadProfiles();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Failed to forget profile.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void setWifiAdapterState(boolean enable) {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        busy.set(true);
        String action = enable ? "Enabling" : "Disabling";
        statusLabel.setText(action + " Wi-Fi adapter...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                List<NetworkAdapterRow> adapters = service.listAdapters();
                String wifiAdapterName = null;
                for (NetworkAdapterRow a : adapters) {
                    if (a.getDescription() != null && a.getDescription().matches("(?i).*(Wireless|Wi-Fi|802\\.11).*")) {
                        wifiAdapterName = a.getName();
                        break;
                    }
                }
                if (wifiAdapterName == null) {
                    Platform.runLater(() -> {
                        statusLabel.setText("No Wi-Fi adapter found.");
                        new Alert(Alert.AlertType.WARNING, "No Wi-Fi adapter found on this system.").showAndWait();
                    });
                    return;
                }
                var result = service.setAdapterState(wifiAdapterName, enable);
                Platform.runLater(() -> {
                    statusLabel.setText(result.success()
                            ? "Wi-Fi adapter " + (enable ? "enabled" : "disabled") + "."
                            : "Failed to " + (enable ? "enable" : "disable") + " Wi-Fi adapter.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to set Wi-Fi adapter state: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }
}
