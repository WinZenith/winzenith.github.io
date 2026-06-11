package com.sbtools.software;

import com.sbtools.util.AppLogger;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared logic for prompting the user to clean up installer files
 * after a successful software update install.
 */
public final class InstallerCleanupHelper {

    private InstallerCleanupHelper() {
    }

    /**
     * Prompts the user to delete installer files detected in the Downloads folder.
     * Runs the dialog on the JavaFX thread and blocks until the user responds.
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
        CountDownLatch latch = new CountDownLatch(1);

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
