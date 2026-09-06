package com.sbtools.ui;

import com.sbtools.netoptimizer.NetworkOptimizerService;
import com.sbtools.util.AppExecutors;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Future;

class ConnectionOverviewPanel extends VBox {

    private final NetworkOptimizerService service;
    private final BooleanProperty busy;
    private final TextArea outputArea;
    private final ComboBox<String> sectionCombo = new ComboBox<>();
    private volatile Future<?> currentTask;

    ConnectionOverviewPanel(NetworkOptimizerService service, BooleanProperty busy) {
        this.service = service;
        this.busy = busy;
        this.outputArea = new TextArea();
        getChildren().addAll(buildContent());
    }

    void loadOverview() {
        // Preserve legacy entry point: load currently selected section (default ipconfig).
        loadSection();
    }

    private void loadSection() {
        String section = sectionCombo.getSelectionModel().getSelectedItem();
        if (section == null) section = "IP Configuration (ipconfig /all)";
        final String sel = section;
        if (busy.get()) {
            outputArea.setText("Please wait, another operation is in progress...");
            return;
        }
        busy.set(true);
        outputArea.setText("Loading " + sel + "...");
        currentTask = AppExecutors.ioPool().submit(() -> {
            try {
                String info = switch (sel) {
                    case "Routing Table (route print)" -> service.getRouteTable();
                    case "ARP Table (arp -a)" -> service.getArpTable();
                    case "Connections (netstat -ano)" -> service.getNetstatSummary();
                    default -> service.getIpConfigAll();
                };
                Platform.runLater(() -> outputArea.setText(info));
            } catch (Exception e) {
                Platform.runLater(() -> outputArea.setText("Failed to load network information: " + e.getMessage()));
            } finally {
                Platform.runLater(() -> busy.set(false));
            }
        });
    }

    void dispose() {
        Future<?> t = currentTask;
        if (t != null) t.cancel(true);
    }

    private VBox buildContent() {
        VBox content = new VBox(8);
        content.setPadding(new Insets(12, 16, 12, 16));
        VBox.setVgrow(content, Priority.ALWAYS);

        Label header = new Label("Connection Overview");
        header.getStyleClass().addAll("label", "large");
        content.getChildren().add(header);

        Label sub = new Label("Read-only views. Nothing here changes system settings.");
        sub.setStyle("-fx-text-fill: #6272a4; -fx-font-size: 11px;");
        content.getChildren().add(sub);

        outputArea.setEditable(false);
        outputArea.setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 11px;");
        VBox.setVgrow(outputArea, Priority.ALWAYS);

        sectionCombo.getItems().addAll(
                "IP Configuration (ipconfig /all)",
                "Routing Table (route print)",
                "ARP Table (arp -a)",
                "Connections (netstat -ano)");
        sectionCombo.getSelectionModel().selectFirst();
        sectionCombo.setOnAction(e -> loadSection());

        Button refreshBtn = UIButton.primary("Refresh");
        refreshBtn.setOnAction(e -> loadSection());

        Button copyBtn = UIButton.secondary("Copy to Clipboard");
        copyBtn.setOnAction(e -> {
            String text = outputArea.getText();
            if (text != null && !text.isBlank()) {
                ClipboardContent clipboard = new ClipboardContent();
                clipboard.putString(text);
                Clipboard.getSystemClipboard().setContent(clipboard);
            }
        });

        Button saveBtn = UIButton.secondary("Save to File…");
        saveBtn.setOnAction(e -> saveToFile());

        HBox btnBox = new HBox(12, sectionCombo, refreshBtn, copyBtn, saveBtn);
        btnBox.setPadding(new Insets(0, 0, 8, 0));

        content.getChildren().addAll(btnBox, outputArea);
        return content;
    }

    private void saveToFile() {
        String text = outputArea.getText();
        if (text == null || text.isBlank()) return;
        String section = sectionCombo.getSelectionModel().getSelectedItem();
        AppExecutors.ioPool().submit(() -> {
            try {
                Path base = AppPaths.portableBaseDir();
                Path dir;
                if (base != null) {
                    dir = base.resolve(".winzenith").resolve("exports");
                } else {
                    dir = Path.of(System.getProperty("user.home"), ".winzenith", "exports");
                }
                Files.createDirectories(dir);
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                String safe = section != null
                        ? section.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "")
                        : "network";
                Path out = dir.resolve("network-" + safe + "-" + stamp + ".txt");
                Files.writeString(out, text);
                Platform.runLater(() -> new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.INFORMATION,
                        "Saved to:\n" + out).showAndWait());
            } catch (Exception e) {
                AppLogger.warning("Failed to save network overview: " + e.getMessage());
                Platform.runLater(() -> new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR,
                        "Save failed: " + e.getMessage()).showAndWait());
            }
        });
    }
}
