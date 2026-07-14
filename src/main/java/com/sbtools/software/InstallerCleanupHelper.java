package com.sbtools.software;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared logic for prompting the user to clean up installer files
 * after a successful software update install.
 */
public final class InstallerCleanupHelper {

    private InstallerCleanupHelper() {
    }

    /**
     * Asynchronously prompts the user to delete installer files detected in the Downloads folder.
     * Runs the dialog on the JavaFX thread and returns a CompletableFuture with the result.
     *
     * @param service   the update service (for finding/deleting files)
     * @param entry     the update entry that was installed
     * @param since     timestamp to search for candidate files (typically install start time)
     * @return CompletableFuture that completes with true if files were deleted
     */
    public static CompletableFuture<Boolean> promptAndCleanupAsync(SoftwareUpdateService service,
                                                                   SoftwareUpdateEntry entry,
                                                                   Instant since) {
        List<Path> candidates = service.findCandidateInstallersForPackage(entry, since);
        if (candidates == null || candidates.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder();
            for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
            Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                    "The following installer files were detected in your Downloads folder:\n\n"
                            + sb + "\nDelete these files?");
            del.setHeaderText("Delete installer files for " + (entry.getName() != null ? entry.getName() : entry.id()));
            boolean confirmed = del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
            if (confirmed) {
                service.deleteInstallerFiles(candidates);
            }
            result.complete(confirmed);
        });
        return result;
    }

    /**
     * Synchronously prompts the user to delete installer files detected in the Downloads folder.
     * Blocks until the user responds. Safe to call only from background threads.
     *
     * @param service   the update service (for finding/deleting files)
     * @param entry     the update entry that was installed
     * @param since     timestamp to search for candidate files (typically install start time)
     * @return true if the user confirmed deletion and files were deleted
     */
    public static boolean promptAndCleanup(SoftwareUpdateService service,
                                           SoftwareUpdateEntry entry,
                                           Instant since) {
        List<Path> candidates = service.findCandidateInstallersForPackage(entry, since);
        if (candidates == null || candidates.isEmpty()) return false;

        AtomicBoolean userConfirmed = new AtomicBoolean(false);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        Platform.runLater(() -> {
            StringBuilder sb = new StringBuilder();
            for (Path p : candidates) sb.append(p.getFileName().toString()).append("\n");
            Alert del = new Alert(Alert.AlertType.CONFIRMATION,
                    "The following installer files were detected in your Downloads folder:\n\n"
                            + sb + "\nDelete these files?");
            del.setHeaderText("Delete installer files for " + (entry.getName() != null ? entry.getName() : entry.id()));
            if (del.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                userConfirmed.set(true);
            }
            latch.countDown();
        });

        try {
            latch.await();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }

        if (userConfirmed.get()) {
            service.deleteInstallerFiles(candidates);
        }
        return userConfirmed.get();
    }
}
