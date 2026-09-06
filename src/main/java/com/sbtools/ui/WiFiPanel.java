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
    private final javafx.collections.ObservableList<NetworkOptimizerService.WifiNetwork> scanRows =
            javafx.collections.FXCollections.observableArrayList();
    private final java.util.concurrent.atomic.AtomicBoolean scanRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> scanTask;
    private final javafx.scene.chart.XYChart.Series<Number, Number> signalSeries = new javafx.scene.chart.XYChart.Series<>();
    private int signalIndex = 0;
    private final Label scanStatus = new Label("Not scanned yet.");

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
        Future<?> s = scanTask;
        if (s != null) s.cancel(true);
        scanRunning.set(false);
    }

    private VBox buildContent() {
        VBox content = new VBox(12);

        Label header = new Label("Wi-Fi");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);
        Label sub = new Label("Survey is read-only (netsh scan). Forget/disable still require Admin.");
        sub.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        content.getChildren().add(sub);

        content.getChildren().add(buildCurrentConnectionSection());
        content.getChildren().add(buildSignalHistorySection());
        content.getChildren().add(buildSurveySection());
        content.getChildren().add(buildSavedProfilesSection());
        content.getChildren().add(statusLabel);

        return content;
    }

    private VBox buildSignalHistorySection() {
        VBox section = new VBox(4);
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1; -fx-padding: 10; -fx-background-color: #282a36;");
        Label h = new Label("Signal History (%, sampled on each Refresh)");
        h.setStyle("-fx-font-weight: bold; -fx-text-fill: #bd93f9; -fx-font-size: 13px;");
        section.getChildren().add(h);
        javafx.scene.chart.NumberAxis x = new javafx.scene.chart.NumberAxis();
        x.setLabel("Sample");
        x.setForceZeroInRange(false);
        javafx.scene.chart.NumberAxis y = new javafx.scene.chart.NumberAxis(0, 100, 10);
        y.setLabel("Signal %");
        javafx.scene.chart.LineChart<Number, Number> chart = new javafx.scene.chart.LineChart<>(x, y);
        chart.setPrefHeight(150);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setAnimated(false);
        signalSeries.setName("signal");
        chart.getData().add(signalSeries);
        section.getChildren().add(chart);
        return section;
    }

    private VBox buildSurveySection() {
        VBox section = new VBox(4);
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1; -fx-padding: 10; -fx-background-color: #282a36;");
        Label h = new Label("Nearby Networks (read-only survey)");
        h.setStyle("-fx-font-weight: bold; -fx-text-fill: #bd93f9; -fx-font-size: 13px;");
        section.getChildren().add(h);

        javafx.scene.control.TableView<NetworkOptimizerService.WifiNetwork> table =
                new javafx.scene.control.TableView<>(scanRows);
        table.setPrefHeight(160);
        javafx.scene.control.TableColumn<NetworkOptimizerService.WifiNetwork, String> ssidCol =
                new javafx.scene.control.TableColumn<>("SSID");
        ssidCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().ssid()));
        ssidCol.setPrefWidth(160);
        javafx.scene.control.TableColumn<NetworkOptimizerService.WifiNetwork, String> sigCol =
                new javafx.scene.control.TableColumn<>("Signal");
        sigCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().signalPercent() + "%"));
        sigCol.setPrefWidth(70);
        javafx.scene.control.TableColumn<NetworkOptimizerService.WifiNetwork, String> authCol =
                new javafx.scene.control.TableColumn<>("Auth");
        authCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().auth()));
        authCol.setPrefWidth(140);
        javafx.scene.control.TableColumn<NetworkOptimizerService.WifiNetwork, String> chCol =
                new javafx.scene.control.TableColumn<>("Ch");
        chCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().channel()));
        chCol.setPrefWidth(50);
        javafx.scene.control.TableColumn<NetworkOptimizerService.WifiNetwork, String> bssidCol =
                new javafx.scene.control.TableColumn<>("BSSID");
        bssidCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().bssid()));
        bssidCol.setPrefWidth(150);
        table.getColumns().addAll(ssidCol, sigCol, authCol, chCol, bssidCol);
        section.getChildren().add(table);

        scanStatus.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        Button scanBtn = UIButton.secondary("Scan Nearby Networks");
        scanBtn.setOnAction(e -> scanNearby());
        HBox row = new HBox(8, scanBtn, scanStatus);
        row.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(row);
        return section;
    }

    void scanNearby() {
        if (!scanRunning.compareAndSet(false, true)) return;
        scanStatus.setText("Scanning (netsh, read-only)...");
        scanTask = AppExecutors.ioPool().submit(() -> {
            try {
                var nets = service.scanWifiNetworks();
                Platform.runLater(() -> {
                    scanRows.setAll(nets);
                    scanStatus.setText(nets.isEmpty()
                            ? "No networks found. Is Wi-Fi enabled?"
                            : "Found " + nets.size() + " network(s). Strongest first not guaranteed — sort by Signal.");
                    scanRows.sort((a, b) -> Integer.compare(b.signalPercent(), a.signalPercent()));
                });
            } catch (Exception e) {
                Platform.runLater(() -> scanStatus.setText("Scan failed: " + e.getMessage()));
            } finally {
                scanRunning.set(false);
            }
        });
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
                        try {
                            if (info.signalPercent() > 0) {
                                signalSeries.getData().add(
                                        new javafx.scene.chart.XYChart.Data<>(signalIndex++, info.signalPercent()));
                                while (signalSeries.getData().size() > 50) {
                                    signalSeries.getData().remove(0);
                                }
                            }
                        } catch (Exception ignored) {}
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
        if (!enable) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Disable the Wi-Fi adapter?\n\nYou will lose wireless connectivity until it is re-enabled.",
                    ButtonType.YES, ButtonType.NO);
            confirm.setTitle("Confirm Disable");
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.NO) != ButtonType.YES) return;
        }
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
