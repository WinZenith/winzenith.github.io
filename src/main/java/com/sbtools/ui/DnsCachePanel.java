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
    // PR3: benchmark / MTU / speed-test state (local flags, never global busy)
    private final java.util.concurrent.atomic.AtomicBoolean benchRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> benchTask;
    private final javafx.collections.ObservableList<NetworkOptimizerService.DnsBenchmarkRow> benchRows =
            javafx.collections.FXCollections.observableArrayList();
    private final java.util.concurrent.atomic.AtomicBoolean mtuRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> mtuTask;
    private final java.util.concurrent.atomic.AtomicBoolean speedRunning = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean speedCancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Future<?> speedTask;
    private final javafx.scene.chart.XYChart.Series<Number, Number> pingSeries = new javafx.scene.chart.XYChart.Series<>();
    private int pingSampleIndex = 0;
    private javafx.scene.chart.LineChart<Number, Number> pingChart;

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
        Future<?> b = benchTask;
        if (b != null) b.cancel(true);
        benchRunning.set(false);
        Future<?> m = mtuTask;
        if (m != null) m.cancel(true);
        mtuRunning.set(false);
        Future<?> s = speedTask;
        if (s != null) s.cancel(true);
        speedRunning.set(false);
        speedCancelled.set(true);
    }

    // ---------------- PR3 sections (opt-in, local flags, stdlib only) ----------------

    private VBox buildPingHistorySection() {
        VBox box = new VBox(6);
        Label h = new Label("Ping History (avg ms per run, last 50)");
        h.getStyleClass().addAll("label", "large");
        box.getChildren().add(h);
        javafx.scene.chart.NumberAxis x = new javafx.scene.chart.NumberAxis();
        x.setLabel("Run");
        x.setForceZeroInRange(false);
        javafx.scene.chart.NumberAxis y = new javafx.scene.chart.NumberAxis();
        y.setLabel("Avg ms");
        pingChart = new javafx.scene.chart.LineChart<>(x, y);
        pingChart.setPrefHeight(160);
        pingChart.setCreateSymbols(false);
        pingChart.setLegendVisible(false);
        pingChart.setAnimated(false);
        pingSeries.setName("ping avg");
        pingChart.getData().add(pingSeries);
        box.getChildren().add(pingChart);
        return box;
    }

    private void recordPingHistory(double avgMs) {
        try {
            Platform.runLater(() -> {
                pingSeries.getData().add(
                        new javafx.scene.chart.XYChart.Data<>(pingSampleIndex++, avgMs));
                while (pingSeries.getData().size() > 50) {
                    pingSeries.getData().remove(0);
                }
            });
        } catch (Exception ignored) {}
    }

    private VBox buildBenchmarkSection() {
        VBox box = new VBox(6);
        Label h = new Label("DNS Benchmark (opt-in internet: resolves google.com via each provider)");
        h.getStyleClass().addAll("label", "large");
        h.setPadding(new Insets(12, 0, 0, 0));
        box.getChildren().add(h);
        Label hint = new Label("Runs only when you click Benchmark. No background traffic.");
        hint.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        box.getChildren().add(hint);

        javafx.scene.control.TableView<NetworkOptimizerService.DnsBenchmarkRow> table =
                new javafx.scene.control.TableView<>(benchRows);
        table.setPrefHeight(150);
        javafx.scene.control.TableColumn<NetworkOptimizerService.DnsBenchmarkRow, String> pCol =
                new javafx.scene.control.TableColumn<>("Provider");
        pCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().provider()));
        pCol.setPrefWidth(130);
        javafx.scene.control.TableColumn<NetworkOptimizerService.DnsBenchmarkRow, String> sCol =
                new javafx.scene.control.TableColumn<>("Server");
        sCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(c.getValue().servers()));
        sCol.setPrefWidth(140);
        javafx.scene.control.TableColumn<NetworkOptimizerService.DnsBenchmarkRow, String> aCol =
                new javafx.scene.control.TableColumn<>("Avg ms");
        aCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                        c.getValue().avgMs() < 0 ? "-" : String.format("%.1f", c.getValue().avgMs())));
        aCol.setPrefWidth(80);
        javafx.scene.control.TableColumn<NetworkOptimizerService.DnsBenchmarkRow, String> nCol =
                new javafx.scene.control.TableColumn<>("Status");
        nCol.setCellValueFactory(c -> new javafx.beans.property.SimpleStringProperty(
                c.getValue().ok() ? "OK" : ("FAIL" + (c.getValue().note() != null && !c.getValue().note().isBlank()
                        ? ": " + c.getValue().note() : ""))));
        nCol.setPrefWidth(260);
        table.getColumns().addAll(pCol, sCol, aCol, nCol);
        box.getChildren().add(table);

        Button benchBtn = UIButton.secondary("Benchmark DNS Now");
        Label benchStatus = new Label("Not run yet.");
        benchStatus.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        benchBtn.setOnAction(e -> {
            if (!benchRunning.compareAndSet(false, true)) {
                benchStatus.setText("Benchmark already running...");
                return;
            }
            benchStatus.setText("Benchmarking (resolves google.com 3x per provider)...");
            benchTask = AppExecutors.ioPool().submit(() -> {
                try {
                    var rows = service.benchmarkDnsServers();
                    Platform.runLater(() -> {
                        benchRows.setAll(rows);
                        rows.stream().filter(NetworkOptimizerService.DnsBenchmarkRow::ok)
                                .min(java.util.Comparator.comparingDouble(NetworkOptimizerService.DnsBenchmarkRow::avgMs))
                                .ifPresentOrElse(
                                        best -> benchStatus.setText("Fastest: " + best.provider()
                                                + " (" + best.servers() + ") " + String.format("%.1f ms", best.avgMs())
                                                + ". Use Apply DNS above to switch (admin required)."),
                                        () -> benchStatus.setText("All providers failed — check connectivity."));
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> benchStatus.setText("Benchmark failed: " + ex.getMessage()));
                } finally {
                    benchRunning.set(false);
                }
            });
        });
        HBox row = new HBox(8, benchBtn, benchStatus);
        row.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().add(row);
        return box;
    }

    private VBox buildMtuSection() {
        VBox box = new VBox(6);
        Label h = new Label("MTU Discovery (read-only ping -f sweep, suggestion only)");
        h.getStyleClass().addAll("label", "large");
        h.setPadding(new Insets(12, 0, 0, 0));
        box.getChildren().add(h);
        TextField targetField = new TextField("8.8.8.8");
        targetField.setPromptText("Target (gateway IP or 1.1.1.1)");
        targetField.setPrefWidth(180);
        Button mtuBtn = UIButton.secondary("Probe MTU");
        Button mtuCancel = UIButton.secondary("Cancel");
        mtuCancel.setDisable(true);
        TextArea mtuOut = new TextArea();
        mtuOut.setEditable(false);
        mtuOut.setPrefRowCount(6);
        mtuOut.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        mtuOut.setPromptText("MTU probe output (no changes applied)...");
        mtuBtn.setOnAction(e -> {
            String target = targetField.getText() != null ? targetField.getText().trim() : "";
            if (!NetworkOptimizerService.isValidHost(target)) {
                new Alert(Alert.AlertType.WARNING, "Invalid target host.").showAndWait();
                return;
            }
            if (!mtuRunning.compareAndSet(false, true)) return;
            mtuBtn.setDisable(true);
            mtuCancel.setDisable(false);
            mtuOut.setText("Probing MTU to " + target + " ...\n");
            statusLabel.setText("Probing MTU...");
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean(false);
            mtuTask = AppExecutors.ioPool().submit(() -> {
                try {
                    var res = service.probeMtu(target, cancelled);
                    Platform.runLater(() -> {
                        mtuOut.setText(res.details() != null ? res.details() : "");
                        statusLabel.setText(res.success() ? "MTU probe: " + res.optimalMtu() : "MTU probe failed.");
                    });
                } catch (java.util.concurrent.CancellationException ce) {
                    Platform.runLater(() -> {
                        mtuOut.setText("MTU probe cancelled.");
                        statusLabel.setText("MTU probe cancelled.");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> mtuOut.setText("MTU probe error: " + ex.getMessage()));
                } finally {
                    mtuRunning.set(false);
                    Platform.runLater(() -> {
                        mtuBtn.setDisable(false);
                        mtuCancel.setDisable(true);
                    });
                }
            });
            mtuCancel.setOnAction(ev -> {
                cancelled.set(true);
                Future<?> m = mtuTask;
                if (m != null) m.cancel(true);
            });
        });
        HBox r = new HBox(8, new Label("Target:"), targetField, mtuBtn, mtuCancel);
        r.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(r, mtuOut);
        return box;
    }

    private VBox buildSpeedSection() {
        VBox box = new VBox(6);
        Label h = new Label("Download Speed Test (opt-in internet, stdlib HttpClient)");
        h.getStyleClass().addAll("label", "large");
        h.setPadding(new Insets(12, 0, 0, 0));
        box.getChildren().add(h);
        Label hint = new Label("Downloads ~10 MB from the URL below. Runs only on click.");
        hint.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        box.getChildren().add(hint);
        TextField urlField = new TextField("https://speed.cloudflare.com/__down?bytes=10000000");
        urlField.setPrefWidth(340);
        javafx.scene.control.ProgressBar bar = new javafx.scene.control.ProgressBar(0);
        bar.setPrefWidth(220);
        bar.setVisible(false);
        Label out = new Label("Not run yet.");
        out.setStyle("-fx-text-fill: #8be9fd;");
        Button go = UIButton.primary("Run Speed Test");
        Button cancel = UIButton.secondary("Cancel");
        cancel.setDisable(true);
        com.sbtools.netoptimizer.SpeedTestService speedSvc = new com.sbtools.netoptimizer.SpeedTestService();
        go.setOnAction(e -> {
            if (!speedRunning.compareAndSet(false, true)) return;
            speedCancelled.set(false);
            String url = urlField.getText();
            go.setDisable(true);
            cancel.setDisable(false);
            bar.setVisible(true);
            bar.setProgress(-1);
            out.setText("Testing...");
            speedTask = AppExecutors.ioPool().submit(() -> {
                try {
                    var res = speedSvc.runDownload(url, speedCancelled, p ->
                            Platform.runLater(() -> {
                                bar.setVisible(true);
                                if (p >= 0) bar.setProgress(p);
                            }));
                    Platform.runLater(() -> out.setText(res.success()
                            ? String.format("Download: %.1f Mbps (%s)", res.mbps(), res.note())
                            : "Speed test failed: " + res.note()));
                } catch (Exception ex) {
                    Platform.runLater(() -> out.setText("Speed test error: " + ex.getMessage()));
                } finally {
                    speedRunning.set(false);
                    Platform.runLater(() -> {
                        go.setDisable(false);
                        cancel.setDisable(true);
                        bar.setVisible(false);
                    });
                }
            });
        });
        cancel.setOnAction(e -> {
            speedCancelled.set(true);
            Future<?> s = speedTask;
            if (s != null) s.cancel(true);
        });
        HBox r = new HBox(8, urlField, go, cancel, bar);
        r.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(r, out);
        return box;
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

        HBox presetRowV6 = new HBox(8);
        presetRowV6.setAlignment(Pos.CENTER_LEFT);
        Label v6Label = new Label("IPv6:");
        v6Label.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        presetRowV6.getChildren().add(v6Label);
        for (String[] preset : new String[][]{
                {"Google v6", "2001:4860:4860::8888", "2001:4860:4860::8844"},
                {"Cloudflare v6", "2606:4700:4700::1111", "2606:4700:4700::1001"},
                {"Quad9 v6", "2620:fe::fe", "2620:fe::9"}
        }) {
            Button btn = UIButton.small(preset[0]);
            btn.setOnAction(e -> {
                primaryDnsField.setText(preset[1]);
                secondaryDnsField.setText(preset[2]);
            });
            presetRowV6.getChildren().add(btn);
        }
        content.getChildren().add(presetRowV6);

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
        diagnosticOutput.setPrefRowCount(10);
        diagnosticOutput.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px; -fx-control-inner-background: #1e1f29;");
        diagnosticOutput.setPromptText("Ping/Traceroute results will appear here...");
        VBox.setVgrow(diagnosticOutput, Priority.ALWAYS);
        content.getChildren().add(diagnosticOutput);

        content.getChildren().add(buildPingHistorySection());
        content.getChildren().add(buildBenchmarkSection());
        content.getChildren().add(buildMtuSection());
        content.getChildren().add(buildSpeedSection());

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
                try { service.captureSnapshot("before reset-network-stack"); } catch (Exception ignored) {}
                var result = service.resetNetworkStack();
                if (result.success()) {
                    try { service.markRebootRequired("Network stack reset"); } catch (Exception ignored) {}
                }
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
                try { service.captureSnapshot("before reset-winsock"); } catch (Exception ignored) {}
                var result = service.resetWinsock();
                if (result.success()) {
                    try { service.markRebootRequired("Winsock reset"); } catch (Exception ignored) {}
                }
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
                    if (result.packetsReceived() > 0) {
                        recordPingHistory(result.avgMs());
                    }
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
