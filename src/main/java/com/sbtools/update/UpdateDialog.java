package com.sbtools.update;

import com.sbtools.util.AppInfo;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.awt.Desktop;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public class UpdateDialog extends Dialog<ButtonType> {

    public UpdateDialog(UpdateChecker.UpdateResult result) {
        setTitle(AppInfo.DISPLAY_NAME + " - Update Available");
        setHeaderText("A new version is available");

        VBox content = new VBox(12);
        content.setPadding(new Insets(20));
        content.setMinWidth(400);

        Label currentLabel = new Label("Current version: " + AppInfo.getVersion());
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
            downloadButton.setDisable(true);

            ProgressBar progressBar = new ProgressBar(0);
            progressBar.setPrefWidth(360);
            Label statusLabel = new Label("Preparing download...");

            VBox downloadBox = new VBox(10, currentLabel, newVersionLabel, descLabel, progressBar, statusLabel);
            downloadBox.setPadding(new Insets(20));
            getDialogPane().setContent(downloadBox);

            Thread t = new Thread(() -> {
                try {
                    String urlStr = result.downloadUrl();
                    if (urlStr == null || urlStr.isBlank()) {
                        Platform.runLater(() -> {
                            statusLabel.setText("Download URL unavailable. Opening release page in browser.");
                            openBrowser("https://github.com/" + AppInfo.GITHUB_REPO + "/releases/latest");
                        });
                        return;
                    }

                    URL url = new URL(urlStr);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestProperty("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion());
                    conn.setInstanceFollowRedirects(true);
                    conn.connect();

                    int status = conn.getResponseCode();
                    if (status >= 300 && status < 400) {
                        String loc = conn.getHeaderField("Location");
                        if (loc != null && !loc.isBlank()) {
                            conn = (HttpURLConnection) new URL(loc).openConnection();
                            conn.setRequestProperty("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion());
                            conn.connect();
                        }
                    }

                    int contentLength = conn.getContentLength();
                    String raw = conn.getURL().getFile();
                    String filename = raw.substring(raw.lastIndexOf('/') + 1);
                    if (filename == null || filename.isBlank()) filename = "update.zip";

                    Path tempDir = Files.createTempDirectory("WinZenith-update-");
                    Path target = tempDir.resolve(filename);

                    try (InputStream in = conn.getInputStream();
                         OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        byte[] buffer = new byte[8192];
                        int read;
                        long total = 0;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                            total += read;
                            if (contentLength > 0) {
                                double prog = Math.min(1.0, (double) total / contentLength);
                                final double p = prog;
                                Platform.runLater(() -> progressBar.setProgress(p));
                            } else {
                                Platform.runLater(() -> progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS));
                            }
                        }
                    }

                    Platform.runLater(() -> {
                        statusLabel.setText("Downloaded to: " + target.toString());
                        try {
                            if (Desktop.isDesktopSupported()) {
                                Desktop.getDesktop().open(tempDir.toFile());
                            }
                        } catch (Exception ex) {
                            // ignore
                        }
                        downloadButton.setDisable(false);
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        statusLabel.setText("Download failed: " + ex.getMessage());
                        downloadButton.setDisable(false);
                    });
                }
            }, "UpdateDownloader");
            t.setDaemon(true);
            t.start();
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
