package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.WiFiInfo;
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

class WiFiPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final Label statusLabel = new Label("Ready.");
    private final Label ssidLabel = new Label("SSID: -");
    private final Label stateLabel = new Label("State: -");
    private final Label signalLabel = new Label("Signal: -");
    private final Label radioLabel = new Label("Radio: -");
    private final Label channelLabel = new Label("Channel: -");
    private final Label rateLabel = new Label("Rates: -");
    private final ComboBox<String> profileCombo = new ComboBox<>();

    WiFiPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        getChildren().addAll(buildContent());
        setPadding(new Insets(12, 16, 12, 16));
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

        HBox row = new HBox(8, profileCombo, refreshProfilesBtn, disconnectBtn, forgetBtn);
        row.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(row);

        return section;
    }

    void loadCurrentInfo() {
        if (busy.get()) return;
        busy.set(true);
        new Thread(() -> {
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
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load Wi-Fi info."));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        }, "net-wifi-info").start();
    }

    void loadProfiles() {
        new Thread(() -> {
            try {
                List<String> profiles = service.getWifiProfiles();
                Platform.runLater(() -> {
                    profileCombo.getItems().clear();
                    profileCombo.getItems().addAll(profiles);
                    if (!profiles.isEmpty()) profileCombo.getSelectionModel().selectFirst();
                });
            } catch (Exception e) {
                Platform.runLater(() -> statusLabel.setText("Failed to load profiles."));
            }
        }, "net-wifi-profiles").start();
    }

    void disconnectWifi() {
        if (busy.get()) return;
        busy.set(true);
        new Thread(() -> {
            var result = service.disconnectWifi();
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "Disconnected." : "Disconnect failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                busy.set(false);
            });
        }, "net-wifi-disconnect").start();
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

        if (busy.get()) return;
        busy.set(true);
        new Thread(() -> {
            var result = service.forgetWifiProfile(profile);
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? result.message() : "Failed to forget profile.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                if (result.success()) loadProfiles();
                busy.set(false);
            });
        }, "net-wifi-forget").start();
    }
}
