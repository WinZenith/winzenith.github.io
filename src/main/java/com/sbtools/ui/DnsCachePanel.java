package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;

class DnsCachePanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final Label statusLabel;
    private final ComboBox<String> adapterCombo = new ComboBox<>();
    private final TextField primaryDnsField = new TextField();
    private final TextField secondaryDnsField = new TextField();
    private final Label currentDnsLabel = new Label("Current DNS: -");

    DnsCachePanel(NetworkOptimizerService service, BooleanProperty busy, Label statusLabel) {
        this.service = service;
        this.busy = busy;
        this.statusLabel = statusLabel;
        getChildren().addAll(buildContent());
    }

    void refreshAdapters() {
        new Thread(() -> {
            List<NetworkAdapterRow> adapters = service.listAdapters();
            Platform.runLater(() -> {
                adapterCombo.getItems().clear();
                for (NetworkAdapterRow a : adapters) {
                    adapterCombo.getItems().add(a.getName());
                }
                if (!adapterCombo.getItems().isEmpty()) {
                    adapterCombo.getSelectionModel().selectFirst();
                }
            });
        }, "net-dns-load-adapters").start();
    }

    private VBox buildContent() {
        VBox content = new VBox(12);
        content.setPadding(new Insets(12, 16, 12, 16));

        Label header = new Label("DNS & Network Utilities");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Button flushDnsBtn = UIButton.primary("Flush DNS Cache");
        Button resetStackBtn = UIButton.secondary("Reset Network Stack");
        Button resetWinsockBtn = UIButton.secondary("Reset Winsock");

        flushDnsBtn.setOnAction(e -> flushDns());
        resetStackBtn.setOnAction(e -> resetNetworkStack());
        resetWinsockBtn.setOnAction(e -> resetWinsock());

        content.getChildren().addAll(flushDnsBtn, resetStackBtn, resetWinsockBtn);

        Label dnsHeader = new Label("DNS Server Configuration");
        dnsHeader.getStyleClass().addAll("label", "large");
        dnsHeader.setPadding(new Insets(12, 0, 0, 0));
        content.getChildren().add(dnsHeader);

        adapterCombo.setPrefWidth(250);
        adapterCombo.setOnAction(e -> loadCurrentDns());

        Button refreshAdaptersBtn = UIButton.secondary("Refresh");
        refreshAdaptersBtn.setOnAction(e -> refreshAdapters());

        HBox adapterRow = new HBox(8, new Label("Adapter:"), adapterCombo, refreshAdaptersBtn);
        adapterRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(adapterRow);

        currentDnsLabel.setStyle("-fx-text-fill: #8be9fd;");
        content.getChildren().add(currentDnsLabel);

        Label dnsPresetsLabel = new Label("DNS Provider Presets:");
        content.getChildren().add(dnsPresetsLabel);

        HBox presetRow = new HBox(8);
        presetRow.setAlignment(Pos.CENTER_LEFT);
        for (String[] preset : new String[][]{
                {"Google", "8.8.8.8", "8.8.4.4"},
                {"Cloudflare", "1.1.1.1", "1.0.0.1"},
                {"OpenDNS", "208.67.222.222", "208.67.220.220"},
                {"Quad9", "9.9.9.9", "149.112.112.112"}
        }) {
            Button btn = UIButton.small(preset[0]);
            btn.setOnAction(e -> {
                primaryDnsField.setText(preset[1]);
                secondaryDnsField.setText(preset[2]);
            });
            presetRow.getChildren().add(btn);
        }
        content.getChildren().add(presetRow);

        primaryDnsField.setPromptText("Primary DNS (e.g. 8.8.8.8)");
        primaryDnsField.setPrefWidth(200);
        secondaryDnsField.setPromptText("Secondary DNS (e.g. 8.8.4.4)");
        secondaryDnsField.setPrefWidth(200);

        HBox dnsRow = new HBox(8, primaryDnsField, secondaryDnsField);
        dnsRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(dnsRow);

        Button applyDnsBtn = UIButton.primary("Apply DNS");
        Button resetDnsBtn = UIButton.secondary("Reset to DHCP");

        applyDnsBtn.setOnAction(e -> applyDns());
        resetDnsBtn.setOnAction(e -> resetDns());

        HBox dnsBtnRow = new HBox(12, applyDnsBtn, resetDnsBtn);
        dnsBtnRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(dnsBtnRow);

        return content;
    }

    private void flushDns() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Flushing DNS...");
        new Thread(() -> {
            var result = service.flushDnsCache();
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "DNS cache flushed." : "Flush failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                busy.set(false);
            });
        }, "net-flush-dns").start();
    }

    private void resetNetworkStack() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Resetting network stack...");
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Resetting the network stack requires a system reboot. Continue?");
        if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            busy.set(false);
            return;
        }
        new Thread(() -> {
            var result = service.resetNetworkStack();
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "Network stack reset. Reboot required." : "Reset failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                busy.set(false);
            });
        }, "net-reset-stack").start();
    }

    private void resetWinsock() {
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Resetting Winsock...");
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Resetting Winsock may require a reboot. Continue?");
        if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            busy.set(false);
            return;
        }
        new Thread(() -> {
            var result = service.resetWinsock();
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "Winsock reset. Reboot recommended." : "Reset failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                busy.set(false);
            });
        }, "net-reset-winsock").start();
    }

    private void loadCurrentDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) return;
        new Thread(() -> {
            List<String> dns = service.getCurrentDnsServers(adapter);
            Platform.runLater(() -> {
                if (dns.isEmpty()) {
                    currentDnsLabel.setText("Current DNS: None (DHCP)");
                } else {
                    currentDnsLabel.setText("Current DNS: " + String.join(", ", dns));
                }
            });
        }, "net-dns-load").start();
    }

    private void applyDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) {
            new Alert(Alert.AlertType.WARNING, "Please select an adapter.").showAndWait();
            return;
        }
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Setting DNS servers...");

        String primary = primaryDnsField.getText().trim();
        String secondary = secondaryDnsField.getText().trim();

        new Thread(() -> {
            var result = service.setDnsServers(adapter, primary, secondary);
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "DNS updated." : "DNS update failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                if (result.success()) loadCurrentDns();
                busy.set(false);
            });
        }, "net-dns-set").start();
    }

    private void resetDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) {
            new Alert(Alert.AlertType.WARNING, "Please select an adapter.").showAndWait();
            return;
        }
        if (busy.get()) return;
        busy.set(true);
        statusLabel.setText("Resetting DNS to DHCP...");

        new Thread(() -> {
            var result = service.setDnsServers(adapter, null, null);
            Platform.runLater(() -> {
                statusLabel.setText(result.success() ? "DNS reset to DHCP." : "DNS reset failed.");
                new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                        result.message()).showAndWait();
                if (result.success()) {
                    primaryDnsField.clear();
                    secondaryDnsField.clear();
                    loadCurrentDns();
                }
                busy.set(false);
            });
        }, "net-dns-reset").start();
    }
}
