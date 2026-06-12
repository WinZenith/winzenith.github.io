package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
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
        return content;
    }
}
