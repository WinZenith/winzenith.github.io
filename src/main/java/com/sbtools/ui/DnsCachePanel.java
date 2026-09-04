package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkAdapterRow;
import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.PingResult;
import com.sbtools.netoptimizer.TracerouteHop;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.concurrent.Future;
import java.util.function.BooleanSupplier;

class DnsCachePanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final BooleanSupplier adminCheck;
    private final Label statusLabel;
    private final ComboBox<String> adapterCombo = new ComboBox<>();
    private final TextField primaryDnsField = new TextField();
    private final TextField secondaryDnsField = new TextField();
    private final Label currentDnsLabel = new Label("Current DNS: -");
    private final TextField pingHostField = new TextField();
    private final TextField pingCountField = new TextField("4");
    private final TextArea diagnosticOutput = new TextArea();
    private volatile Future<?> currentTask;
    private volatile Future<?> dnsQueryTask;
    // Adapter refresh is read-only and frequent (tab selects, Refresh button): coalesce
    // concurrent runs and track them separately so the handle of an in-flight mutating
    // op (flush/DNS) stored in currentTask is never lost.
    private final java.util.concurrent.atomic.AtomicBoolean refreshingAdapters = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> refreshTask;
    // Diagnostics (ping/traceroute) run on a local flag, NOT the shared app-wide busy flag,
    // so a multi-minute traceroute cannot freeze every other tab. Cancellable via cancelDiagBtn.
    private final java.util.concurrent.atomic.AtomicBoolean diagRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> diagTask;
    private Button pingBtn;
    private Button tracerouteBtn;
    private Button cancelDiagBtn;

    DnsCachePanel(NetworkOptimizerService service, BooleanProperty busy, Label statusLabel, BooleanSupplier adminCheck) {
        this.service = service;
        this.busy = busy;
        this.adminCheck = adminCheck != null ? adminCheck : () -> false;
        this.statusLabel = statusLabel;
        getChildren().addAll(buildContent());
    }

    DnsCachePanel(NetworkOptimizerService service, BooleanProperty busy, Label statusLabel) {
        this(service, busy, statusLabel, () -> false);
    }

    void refreshAdapters() {
        if (!refreshingAdapters.compareAndSet(false, true)) return;
        refreshTask = AppExecutors.ioPool().submit(() -> {
            try {
                List<NetworkAdapterRow> adapters = service.listAdapters();
                Platform.runLater(() -> {
                    String selected = adapterCombo.getSelectionModel().getSelectedItem();
                    adapterCombo.getItems().clear();
                    for (NetworkAdapterRow a : adapters) {
                        adapterCombo.getItems().add(a.getName());
                    }
                    if (!adapterCombo.getItems().isEmpty()) {
                        if (selected != null && adapterCombo.getItems().contains(selected)) {
                            adapterCombo.getSelectionModel().select(selected);
                        } else {
                            adapterCombo.getSelectionModel().selectFirst();
                        }
                    }
                });
            } finally {
                refreshingAdapters.set(false);
            }
        });
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
        Future<?> d = dnsQueryTask;
        if (d != null) d.cancel(true);
        Future<?> r = refreshTask;
        if (r != null) r.cancel(true);
        refreshingAdapters.set(false);
        Future<?> g = diagTask;
        if (g != null) g.cancel(true);
        diagRunning.set(false);
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

        Label diagHeader = new Label("Diagnostics");
        diagHeader.getStyleClass().addAll("label", "large");
        diagHeader.setPadding(new Insets(12, 0, 0, 0));
        content.getChildren().add(diagHeader);

        pingHostField.setPromptText("Host (e.g. 8.8.8.8 or google.com)");
        pingHostField.setPrefWidth(250);
        pingCountField.setPrefWidth(60);

        Button pingBtn = UIButton.primary("Ping");
        Button tracerouteBtn = UIButton.secondary("Traceroute");
        Button cancelDiagBtn = UIButton.secondary("Cancel");
        cancelDiagBtn.setDisable(true);
        this.pingBtn = pingBtn;
        this.tracerouteBtn = tracerouteBtn;
        this.cancelDiagBtn = cancelDiagBtn;

        pingBtn.setOnAction(e -> runPing());
        tracerouteBtn.setOnAction(e -> runTraceroute());
        cancelDiagBtn.setOnAction(e -> {
            Future<?> g = diagTask;
            if (g != null) g.cancel(true);
        });

        HBox pingRow = new HBox(8, new Label("Host:"), pingHostField, new Label("Count:"), pingCountField, pingBtn, tracerouteBtn, cancelDiagBtn);
        pingRow.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().add(pingRow);

        diagnosticOutput.setEditable(false);
        diagnosticOutput.setPrefRowCount(12);
        diagnosticOutput.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-control-inner-background: #1e1f29;");
        diagnosticOutput.setPromptText("Ping/Traceroute results will appear here...");
        VBox.setVgrow(diagnosticOutput, Priority.ALWAYS);
        content.getChildren().add(diagnosticOutput);

        return content;
    }

    private void flushDns() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        busy.set(true);
        statusLabel.setText("Flushing DNS...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.flushDnsCache();
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "DNS cache flushed." : "Flush failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Flush DNS failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to flush DNS: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void resetNetworkStack() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Resetting the network stack requires a system reboot. Continue?");
        warn.setTitle("Confirm Reset");
        warn.setHeaderText("Reset Network Stack");
        if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            statusLabel.setText("Ready.");
            return;
        }
        busy.set(true);
        statusLabel.setText("Resetting network stack...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.resetNetworkStack();
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "Network stack reset. Reboot required." : "Reset failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Reset network stack failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to reset network stack: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void resetWinsock() {
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        Alert warn = new Alert(Alert.AlertType.WARNING,
                "Resetting Winsock may require a reboot. Continue?");
        warn.setTitle("Confirm Reset");
        warn.setHeaderText("Reset Winsock");
        if (warn.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            statusLabel.setText("Ready.");
            return;
        }
        busy.set(true);
        statusLabel.setText("Resetting Winsock...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.resetWinsock();
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "Winsock reset. Reboot recommended." : "Reset failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("Reset Winsock failed.");
                    new Alert(Alert.AlertType.ERROR, "Failed to reset Winsock: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void loadCurrentDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) return;
        final String requestedAdapter = adapter;
        currentDnsLabel.setText("Current DNS: loading...");
        Future<?> prev = dnsQueryTask;
        if (prev != null) prev.cancel(true);
        dnsQueryTask = AppExecutors.ioPool().submit(() -> {
            List<String> dns = service.getCurrentDnsServers(requestedAdapter);
            Platform.runLater(() -> {
                // Avoid race: only update if selection hasn't changed since request
                String current = adapterCombo.getSelectionModel().getSelectedItem();
                if (!requestedAdapter.equals(current)) return;
                if (dns.isEmpty()) {
                    currentDnsLabel.setText("Current DNS: None (DHCP)");
                } else {
                    currentDnsLabel.setText("Current DNS: " + String.join(", ", dns));
                }
            });
        });
    }

    private void applyDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) {
            new Alert(Alert.AlertType.WARNING, "Please select an adapter.").showAndWait();
            return;
        }
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;

        String primary = primaryDnsField.getText().trim();
        String secondary = secondaryDnsField.getText().trim();

        if (!primary.isEmpty() && !NetworkOptimizerService.isValidIpAddress(primary)) {
            new Alert(Alert.AlertType.WARNING, "Invalid primary DNS address. Must be valid IPv4 or IPv6.").showAndWait();
            return;
        }
        if (!secondary.isEmpty() && !NetworkOptimizerService.isValidIpAddress(secondary)) {
            new Alert(Alert.AlertType.WARNING, "Invalid secondary DNS address. Must be valid IPv4 or IPv6.").showAndWait();
            return;
        }
        if (primary.isEmpty() && !secondary.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Primary DNS must be set if secondary is set.").showAndWait();
            return;
        }
        if (primary.isEmpty() && secondary.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Enter at least one DNS server or use 'Reset to DHCP'.").showAndWait();
            return;
        }

        busy.set(true);
        statusLabel.setText("Setting DNS servers...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.setDnsServers(adapter, primary, secondary);
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "DNS updated." : "DNS update failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                    if (result.success()) loadCurrentDns();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("DNS update failed.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private void resetDns() {
        String adapter = adapterCombo.getSelectionModel().getSelectedItem();
        if (adapter == null) {
            new Alert(Alert.AlertType.WARNING, "Please select an adapter.").showAndWait();
            return;
        }
        if (busy.get()) {
            statusLabel.setText("Please wait, another operation is in progress...");
            return;
        }
        if (!requireAdmin()) return;
        busy.set(true);
        statusLabel.setText("Resetting DNS to DHCP...");

        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                var result = service.setDnsServers(adapter, null, null);
                Platform.runLater(() -> {
                    statusLabel.setText(result.success() ? "DNS reset to DHCP." : "DNS reset failed.");
                    new Alert(result.success() ? Alert.AlertType.INFORMATION : Alert.AlertType.ERROR,
                            result.message() + (result.details() != null ? "\n\n" + result.details() : "")).showAndWait();
                    if (result.success()) {
                        primaryDnsField.clear();
                        secondaryDnsField.clear();
                        loadCurrentDns();
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusLabel.setText("DNS reset failed.");
                    new Alert(Alert.AlertType.ERROR, "Error: " + e.getMessage()).showAndWait();
                });
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    private boolean isValidIpAddress(String ip) {
        return NetworkOptimizerService.isValidIpAddress(ip);
    }

    private void setDiagRunning(boolean running) {
        diagRunning.set(running);
        Platform.runLater(() -> {
            if (pingBtn != null) pingBtn.setDisable(running);
            if (tracerouteBtn != null) tracerouteBtn.setDisable(running);
            if (cancelDiagBtn != null) cancelDiagBtn.setDisable(!running);
        });
    }

    private void runPing() {
        String host = pingHostField.getText().trim();
        if (host.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter a host to ping.").showAndWait();
            return;
        }
        if (!NetworkOptimizerService.isValidHost(host)) {
            new Alert(Alert.AlertType.WARNING, "Invalid host: " + host + "\nAllowed: letters, digits, dot, hyphen, underscore, colon (IPv6).").showAndWait();
            return;
        }
        int count;
        try {
            count = Integer.parseInt(pingCountField.getText().trim());
            if (count < 1 || count > 50) {
                new Alert(Alert.AlertType.WARNING, "Count must be between 1 and 50.").showAndWait();
                return;
            }
        } catch (NumberFormatException e) {
            new Alert(Alert.AlertType.WARNING, "Invalid count value.").showAndWait();
            return;
        }

        if (!diagRunning.compareAndSet(false, true)) {
            statusLabel.setText("A diagnostic is already running — Cancel it first.");
            return;
        }
        setDiagRunning(true);
        statusLabel.setText("Pinging " + host + "...");
        diagnosticOutput.setText("Pinging " + host + " (" + count + " packets)...\n");

        diagTask = AppExecutors.ioPool().submit(() -> {
            try {
                PingResult result = service.ping(host, count);
                Platform.runLater(() -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Ping Results for ").append(host).append(":\n");
                    sb.append("  Packets Sent:     ").append(result.packetsSent()).append("\n");
                    sb.append("  Packets Received: ").append(result.packetsReceived()).append("\n");
                    sb.append("  Packet Loss:      ").append(result.packetLossPercent()).append("%\n");
                    if (result.packetsReceived() > 0) {
                        sb.append("  Min Latency:      ").append(String.format("%.1f", result.minMs())).append(" ms\n");
                        sb.append("  Max Latency:      ").append(String.format("%.1f", result.maxMs())).append(" ms\n");
                        sb.append("  Avg Latency:      ").append(String.format("%.1f", result.avgMs())).append(" ms\n");
                    }
                    sb.append("\n--- Raw Output ---\n");
                    sb.append(result.rawOutput() != null ? result.rawOutput() : "");
                    diagnosticOutput.setText(sb.toString());
                    // detect error string in raw for user hint
                    if (result.rawOutput() != null && result.rawOutput().toLowerCase().contains("error")) {
                        statusLabel.setText("Ping failed: " + result.rawOutput().substring(0, Math.min(80, result.rawOutput().length())));
                    } else {
                        statusLabel.setText(result.packetsReceived() > 0
                                ? "Ping complete: " + result.avgMs() + "ms avg"
                                : "Ping failed: no reply from " + host);
                    }
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (e instanceof java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted()) {
                        diagnosticOutput.setText("Ping cancelled by user.");
                        statusLabel.setText("Ping cancelled.");
                    } else {
                        diagnosticOutput.setText("Ping error: " + e.getMessage());
                        statusLabel.setText("Ping failed.");
                    }
                });
            } finally {
                setDiagRunning(false);
            }
        });
    }

    private void runTraceroute() {
        String host = pingHostField.getText().trim();
        if (host.isEmpty()) {
            new Alert(Alert.AlertType.WARNING, "Please enter a host for traceroute.").showAndWait();
            return;
        }
        if (!NetworkOptimizerService.isValidHost(host)) {
            new Alert(Alert.AlertType.WARNING, "Invalid host: " + host + "\nAllowed: letters, digits, dot, hyphen, underscore, colon (IPv6).").showAndWait();
            return;
        }

        if (!diagRunning.compareAndSet(false, true)) {
            statusLabel.setText("A diagnostic is already running — Cancel it first.");
            return;
        }
        setDiagRunning(true);
        statusLabel.setText("Traceroute to " + host + "...");
        diagnosticOutput.setText("Traceroute to " + host + " (max 30 hops)...\n");

        diagTask = AppExecutors.ioPool().submit(() -> {
            try {
                List<TracerouteHop> hops = service.traceroute(host, 30);
                Platform.runLater(() -> {
                    if (hops.isEmpty()) {
                        diagnosticOutput.setText("Traceroute failed. No hops returned for " + host + ".\nCheck host name and network connectivity.");
                        statusLabel.setText("Traceroute failed.");
                    } else {
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format("%-5s %-18s %-12s %-12s %-12s\n", "Hop", "Address", "Latency 1", "Latency 2", "Latency 3"));
                        sb.append("-".repeat(60)).append("\n");
                        for (TracerouteHop hop : hops) {
                            sb.append(String.format("%-5d %-18s %-12s %-12s %-12s\n",
                                    hop.hopNumber(), hop.address(), hop.latency1(), hop.latency2(), hop.latency3()));
                        }
                        diagnosticOutput.setText(sb.toString());
                        statusLabel.setText("Traceroute complete: " + hops.size() + " hops.");
                    }
                });
            } catch (IllegalArgumentException iae) {
                Platform.runLater(() -> {
                    diagnosticOutput.setText("Traceroute validation failed: " + iae.getMessage());
                    statusLabel.setText("Traceroute failed: invalid host.");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (e instanceof java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted()) {
                        diagnosticOutput.setText("Traceroute cancelled by user.");
                        statusLabel.setText("Traceroute cancelled.");
                    } else {
                        diagnosticOutput.setText("Traceroute error: " + e.getMessage());
                        statusLabel.setText("Traceroute failed.");
                    }
                });
            } finally {
                setDiagRunning(false);
            }
        });
    }
}
