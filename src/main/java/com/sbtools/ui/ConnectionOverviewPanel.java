package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.netoptimizer.SpeedTestResult;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

class ConnectionOverviewPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final TextArea outputArea;
    private final Label downloadLabel = new Label("Download: -");
    private final Label uploadLabel = new Label("Upload: -");
    private final Label latencyLabel = new Label("Latency: -");
    private final Label serverLabel = new Label("Server: -");

    ConnectionOverviewPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        this.outputArea = new TextArea();
        getChildren().addAll(buildContent());
    }

    void loadOverview() {
        if (busy.get()) return;
        busy.set(true);
        outputArea.setText("Loading network information...");
        new Thread(() -> {
            String info = service.getIpConfigAll();
            Platform.runLater(() -> {
                outputArea.setText(info);
                busy.set(false);
            });
        }, "net-overview").start();
    }

    private VBox buildContent() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(content, Priority.ALWAYS);

        Label header = new Label("Connection Overview");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        outputArea.setEditable(false);
        outputArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> loadOverview());

        Button copyBtn = UIButton.secondary("Copy to Clipboard");
        copyBtn.setOnAction(e -> {
            String text = outputArea.getText();
            if (text != null && !text.isBlank()) {
                ClipboardContent clipboard = new ClipboardContent();
                clipboard.putString(text);
                Clipboard.getSystemClipboard().setContent(clipboard);
            }
        });

        HBox btnBox = new HBox(12, refreshBtn, copyBtn);
        btnBox.setPadding(new Insets(0, 0, 8, 0));

        content.getChildren().addAll(btnBox, outputArea);
        content.getChildren().add(buildSpeedTestSection());
        return content;
    }

    private VBox buildSpeedTestSection() {
        VBox section = new VBox(6);
        section.setPadding(new Insets(12, 0, 0, 0));
        section.setStyle("-fx-border-color: #44475a; -fx-border-width: 1 0 0 0; -fx-padding: 12 0 0 0;");

        Label header = new Label("Network Speed Test");
        header.getStyleClass().addAll("label", "large");
        section.getChildren().add(header);

        Button runTestBtn = UIButton.primary("Run Speed Test");

        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setVisible(false);
        progressBar.setPrefWidth(200);

        runTestBtn.setOnAction(e -> runSpeedTest(runTestBtn, progressBar));

        HBox btnRow = new HBox(8, runTestBtn, progressBar);
        btnRow.setAlignment(Pos.CENTER_LEFT);
        section.getChildren().add(btnRow);

        for (Label lbl : new Label[]{downloadLabel, uploadLabel, latencyLabel, serverLabel}) {
            lbl.setStyle("-fx-text-fill: #8be9fd; -fx-font-family: 'Consolas', monospace;");
            section.getChildren().add(lbl);
        }

        return section;
    }

    private void runSpeedTest(Button runTestBtn, ProgressBar progressBar) {
        if (busy.get()) return;
        busy.set(true);
        runTestBtn.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        downloadLabel.setText("Download: Testing...");
        uploadLabel.setText("Upload: Testing...");
        latencyLabel.setText("Latency: Testing...");
        serverLabel.setText("Server: Testing...");

        new Thread(() -> {
            SpeedTestResult result = service.runSpeedTest();
            Platform.runLater(() -> {
                progressBar.setVisible(false);
                runTestBtn.setDisable(false);
                if (result.downloadMbps() > 0) {
                    downloadLabel.setText(String.format("Download: %.2f Mbps", result.downloadMbps()));
                    uploadLabel.setText(String.format("Upload: %.2f Mbps", result.uploadMbps()));
                    latencyLabel.setText(String.format("Latency: %d ms", result.latencyMs()));
                    serverLabel.setText("Server: " + (result.serverInfo() != null && !result.serverInfo().isEmpty() ? result.serverInfo() : "N/A"));
                } else {
                    downloadLabel.setText("Download: Failed");
                    uploadLabel.setText("Upload: -");
                    latencyLabel.setText("Latency: -");
                    serverLabel.setText("Server: -");
                    new Alert(Alert.AlertType.WARNING,
                            "Speed test failed.\n\n" + (result.rawOutput() != null ? result.rawOutput() : "No details available.")).showAndWait();
                }
                busy.set(false);
            });
        }, "net-speed-test").start();
    }
}
