package com.sbtools.update;

import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.awt.Desktop;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Non-blocking download popup for application updates.
 *
 * <p>Uses {@link AppUpdateService} (a {@code Task}) with property bindings so the
 * JavaFX Application Thread is never blocked: the dialog is shown with
 * {@code show()}, never {@code showAndWait()}, and the download runs on a daemon
 * thread. On success the dialog closes itself and the containing folder is
 * opened (no second popup). The dialog always stays closable via Cancel / X.</p>
 */
public final class AppUpdateDialog {

    private static final java.util.concurrent.atomic.AtomicBoolean DOWNLOAD_IN_PROGRESS =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private AppUpdateDialog() {
    }

    /**
     * Shows the progress dialog and starts the download.
     * Must be called on the FX thread; otherwise the call is deferred via
     * {@code Platform.runLater}.
     *
     * @param owner                 owner window (may be null)
     * @param result                update info from {@link UpdateChecker}
     * @param configuredDownloadDir value of {@code Settings.downloadDirectory},
     *                              may be null/blank (falls back to ~/Downloads)
     */
    public static void showAndDownload(Window owner,
                                       UpdateChecker.UpdateResult result,
                                       String configuredDownloadDir) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> showAndDownload(owner, result, configuredDownloadDir));
            return;
        }

        // Non-blocking dialog now: guard against stacking multiple downloads
        // (previously showAndWait() implicitly serialized them).
        if (!DOWNLOAD_IN_PROGRESS.compareAndSet(false, true)) {
            AppLogger.info("Update download already in progress, ignoring duplicate request.");
            return;
        }

        String version = result != null ? result.latestVersion() : null;
        String url = result != null ? result.downloadUrl() : null;
        Path targetDir = resolveTargetDir(configuredDownloadDir);

        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle("Downloading Update");
        dialog.setHeaderText("Downloading v" + (version != null ? version : "") + "...");
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        dialog.setResultConverter(bt -> null);
        if (owner != null) {
            dialog.initOwner(owner);
            dialog.initModality(Modality.WINDOW_MODAL);
        }

        ProgressBar progressBar = new ProgressBar(-1);
        progressBar.setPrefWidth(360);
        progressBar.setMaxWidth(Double.MAX_VALUE);

        Label statusLabel = new Label("Connecting...");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(360);

        VBox box = new VBox(12, progressBar, statusLabel);
        box.setPadding(new Insets(20));
        box.setMinWidth(400);
        dialog.getDialogPane().setContent(box);

        AppUpdateService task = new AppUpdateService(version, url, targetDir);
        progressBar.progressProperty().bind(task.progressProperty());
        statusLabel.textProperty().bind(task.messageProperty());

        task.setOnSucceeded(e -> {
            DOWNLOAD_IN_PROGRESS.set(false);
            unbindQuietly(progressBar, statusLabel);
            Path downloaded = task.getValue();
            try {
                dialog.close();
            } catch (Exception ignored) {
            }
            if (downloaded != null) {
                openFolderInBackground(downloaded);
            }
        });

        task.setOnFailed(e -> {
            unbindQuietly(progressBar, statusLabel);
            Throwable ex = task.getException();
            String msg = ex != null && ex.getMessage() != null && !ex.getMessage().isBlank()
                    ? ex.getMessage()
                    : "Download failed.";
            // Keep full message in logs, show a short one in the popup.
            AppLogger.warning("App update download failed: " + (ex != null ? ex.toString() : msg));
            progressBar.setProgress(0);
            statusLabel.setText("Failed: " + msg);
            // Swap Cancel -> OK so the failure is clearly dismissible.
            // The dialog stays open (no second popup) and X still works.
            // Flag is released on dialog hidden so the user can retry after dismissing.
            dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK);
        });

        task.setOnCancelled(e -> {
            DOWNLOAD_IN_PROGRESS.set(false);
            unbindQuietly(progressBar, statusLabel);
            try {
                if (dialog.isShowing()) {
                    dialog.close();
                }
            } catch (Exception ignored) {
            }
        });

        // Cancel the background task when the user presses Cancel or X.
        // Use an event filter (not setOnAction) so the dialog's built-in
        // close behaviour is preserved — the dialog always closes.
        Button cancelBtn = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        if (cancelBtn != null) {
            cancelBtn.addEventFilter(ActionEvent.ACTION, evt -> task.cancel(true));
        }
        dialog.setOnCloseRequest(evt -> task.cancel(true));
        // Release the single-download guard whenever the popup actually closes
        // (covers success, cancel, X, and dismissal of the error state).
        dialog.setOnHidden(evt -> DOWNLOAD_IN_PROGRESS.set(false));

        dialog.show();

        Thread worker = new Thread(task, "UpdateDownloader");
        worker.setDaemon(true);
        worker.start();
    }

    static Path resolveTargetDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            try {
                return Path.of(configured.trim());
            } catch (Exception e) {
                AppLogger.warning("Invalid download directory '" + configured
                        + "', falling back to Downloads: " + e.getMessage());
            }
        }
        return Path.of(System.getProperty("user.home"), "Downloads");
    }

    private static void unbindQuietly(ProgressBar bar, Label label) {
        try {
            bar.progressProperty().unbind();
        } catch (Exception ignored) {
        }
        try {
            label.textProperty().unbind();
        } catch (Exception ignored) {
        }
    }

    /**
     * Opens the folder containing the downloaded file without blocking the FX
     * thread. Uses {@code explorer.exe} first (reliable from elevated processes
     * where AWT Desktop can hang), falling back to {@code Desktop.open} off the
     * FX thread.
     */
    private static void openFolderInBackground(Path downloadedFile) {
        Thread t = new Thread(() -> {
            try {
                Path abs = downloadedFile.toAbsolutePath();
                Path folder = abs.getParent();
                if (folder == null) {
                    folder = abs;
                }
                if (!Files.exists(folder)) {
                    AppLogger.warning("Download folder no longer exists: " + folder);
                    return;
                }
                // Preferred: select the file in Explorer.
                try {
                    new ProcessBuilder("explorer.exe", "/select,", abs.toString()).start();
                    return;
                } catch (Exception e) {
                    AppLogger.debug("explorer /select failed: " + e.getMessage());
                }
                // Fallback: open the folder itself.
                try {
                    new ProcessBuilder("explorer.exe", folder.toString()).start();
                    return;
                } catch (Exception e) {
                    AppLogger.debug("explorer folder open failed: " + e.getMessage());
                }
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().open(folder.toFile());
                    }
                } catch (Exception e) {
                    AppLogger.warning("Could not open download folder: " + e.getMessage());
                }
            } catch (Exception e) {
                AppLogger.warning("Could not open download folder: " + e.getMessage());
            }
        }, "OpenDownloadFolder");
        t.setDaemon(true);
        t.start();
    }
}
