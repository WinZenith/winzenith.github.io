package com.sbtools.update;

import com.sbtools.util.AppInfo;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.net.URI;

public class UpdateDialog extends Dialog<ButtonType> {

    public UpdateDialog(UpdateChecker.UpdateResult result) {
        setTitle(AppInfo.DISPLAY_NAME + " - Update Available");
        setHeaderText("A new version is available");

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setMinWidth(400);

        Label currentLabel = new Label("Current version: " + AppInfo.VERSION);
        currentLabel.setStyle("-fx-font-size: 13px; -fx-text-fill: #f8f8f2;");

        Label newVersionLabel = new Label("New version: " + result.latestVersion());
        newVersionLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #50fa7b;");

        Label descLabel = new Label("Downloading the latest version is recommended for the best experience and latest features.");
        descLabel.setWrapText(true);
        descLabel.setStyle("-fx-font-size: 12px; -fx-text-fill: #6272a4;");

        content.getChildren().addAll(currentLabel, newVersionLabel, descLabel);

        ButtonType downloadBtn = new ButtonType("Download Update", ButtonBar.ButtonData.OK_DONE);
        ButtonType laterBtn = new ButtonType("Later", ButtonBar.ButtonData.CANCEL_CLOSE);
        getDialogPane().getButtonTypes().addAll(downloadBtn, laterBtn);

        getDialogPane().setContent(content);

        Button downloadButton = (Button) getDialogPane().lookupButton(downloadBtn);
        downloadButton.setOnAction(e -> {
            openBrowser(result.downloadUrl());
        });
    }

    private void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI(url));
            }
        } catch (Exception ex) {
            // fallback - just close
        }
    }
}
