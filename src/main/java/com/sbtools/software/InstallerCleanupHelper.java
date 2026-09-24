package com.sbtools.software;

import com.sbtools.ui.I18n;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.util.Duration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Shared logic for prompting the user to clean up installer files
 * after a successful software update install.
 */
public final class InstallerCleanupHelper {

    private static final long CLEANUP_DIALOG_TIMEOUT_MS = 90_000L;

    private InstallerCleanupHelper() {
    }

    /**
     * Asynchronously prompts the user to delete installer files detected in the winget download cache.
     */
    public static CompletableFuture<Boolean> promptAndCleanupAsync(SoftwareUpdateService service,
                                                                   SoftwareUpdateEntry entry,
                                                                   Instant since) {
        List<Path> candidates = service.findCandidateInstallersForPackage(entry, since);
        if (candidates == null || candidates.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    for (Path p : candidates) sb.append(p.toString()).append("\n");
                    Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                            "The following file(s) were detected in the winget download cache:\n\n"
                                    + sb + "\nDelete these files?\n\n(Auto-declines after 90 seconds.)");
                    del.setHeaderText("Clean winget cache for " + (entry.getName() != null ? entry.getName() : entry.id()));
                    runCleanupDialog(del, result, () -> service.deleteInstallerFiles(candidates));
                } catch (Exception ex) {
                    com.sbtools.util.AppLogger.warning("promptAndCleanupAsync failed: " + ex.getMessage());
                    result.complete(false);
                }
            });
        } catch (Exception ex) {
            result.complete(false);
        }
        return result;
    }

    public static CompletableFuture<Boolean> promptAndCleanupBatchAsync(SoftwareUpdateService service,
                                                                        List<SoftwareUpdateEntry> packages,
                                                                        Instant since) {
        Map<SoftwareUpdateEntry, List<Path>> allCandidates =
                service.findCandidateInstallersForPackages(packages, since);
        if (allCandidates.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    StringBuilder sb = new StringBuilder();
                    int totalFiles = 0;
                    for (Map.Entry<SoftwareUpdateEntry, List<Path>> entry : allCandidates.entrySet()) {
                        String name = entry.getKey().getName() != null ? entry.getKey().getName() : entry.getKey().id();
                        sb.append(name).append(":\n");
                        for (Path p : entry.getValue()) {
                            sb.append("  ").append(p.toString()).append("\n");
                            totalFiles++;
                        }
                        sb.append("\n");
                    }
                    Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                            "The following file(s) (" + totalFiles + ") were detected in the winget download cache:\n\n"
                                    + sb + "Delete these files?\n\n(Auto-declines after 90 seconds.)");
                    del.setHeaderText(I18n.t("Clean winget download cache"));
                    runCleanupDialog(del, result, () -> {
                        for (List<Path> files : allCandidates.values()) {
                            service.deleteInstallerFiles(files);
                        }
                    });
                } catch (Exception ex) {
                    com.sbtools.util.AppLogger.warning("promptAndCleanupBatchAsync failed: " + ex.getMessage());
                    result.complete(false);
                }
            });
        } catch (Exception ex) {
            result.complete(false);
        }
        return result;
    }

    private static void runCleanupDialog(Alert del, CompletableFuture<Boolean> result, Runnable onConfirmDelete) {
        PauseTransition timeout = new PauseTransition(Duration.millis(CLEANUP_DIALOG_TIMEOUT_MS));
        timeout.setOnFinished(e -> {
            if (!result.isDone()) {
                com.sbtools.util.AppLogger.warning("Cleanup dialog auto-declined after timeout");
                try {
                    del.setResult(ButtonType.CANCEL);
                    del.hide();
                } catch (Exception ignored) {}
                result.complete(false);
            }
        });
        timeout.play();
        try {
            boolean confirmed = del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
            if (!result.isDone()) {
                if (confirmed && onConfirmDelete != null) {
                    onConfirmDelete.run();
                }
                result.complete(confirmed);
            }
        } finally {
            timeout.stop();
        }
    }
}
