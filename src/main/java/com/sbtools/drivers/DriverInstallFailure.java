package com.sbtools.drivers;

import com.sbtools.drivers.model.InstallStatus;

import java.nio.channels.ClosedByInterruptException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

/** Null-safe user diagnostics for driver install failures. */
public final class DriverInstallFailure {

    public static final String CANCELLED_MESSAGE = "Installation cancelled by user.";

    public static final String UNKNOWN_DIALOG_MESSAGE =
            "Driver installation failed for an unknown reason. See app.log for technical details.";

    private DriverInstallFailure() {
    }

    /**
     * First non-blank message walking the cause chain; otherwise the leaf exception simple name.
     */
    public static String deepestMessage(Throwable t) {
        if (t == null) {
            return "Unknown error";
        }
        String found = null;
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            String m = cur.getMessage();
            if (m != null && !m.isBlank()) {
                found = m.trim();
            }
        }
        if (found != null) {
            return found;
        }
        Throwable leaf = t;
        while (leaf.getCause() != null) {
            leaf = leaf.getCause();
        }
        String name = leaf.getClass().getSimpleName();
        return name == null || name.isBlank() ? "Unknown error" : name;
    }

    public static boolean isInterruptionThrowable(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof InterruptedException
                    || cur instanceof CancellationException
                    || cur instanceof ClosedByInterruptException) {
                return true;
            }
        }
        return false;
    }

    public static void restoreInterruptFlag(Throwable t) {
        if (isInterruptionThrowable(t)) {
            Thread.interrupted();
        }
    }

    public static String userMessage(String stage, Throwable t, boolean cancelRequested) {
        if (cancelRequested && (isInterruptionThrowable(t) || t == null)) {
            return CANCELLED_MESSAGE;
        }
        if (isInterruptionThrowable(t)) {
            return interruptedMessage(stage);
        }
        return stageFailureMessage(stage, deepestMessage(t));
    }

    public static String stageFailureMessage(String stage, String detail) {
        String d = detail == null || detail.isBlank() ? "Unknown error" : detail.trim();
        if (stage == null || stage.isBlank()) {
            return "Driver installation failed: " + d + ".";
        }
        return "Driver " + stage + " failed: " + d + ".";
    }

    public static String interruptedMessage(String stage) {
        if (stage == null || stage.isBlank()) {
            return "Driver installation was interrupted. Retry the update.";
        }
        return "Driver installation was interrupted during " + stage + ". Retry the update.";
    }

    public static String intelInstallerLaunchFailure(String source, Path driverFile, String[] args, Throwable t) {
        String vendor = source == null || source.isBlank() ? "vendor" : source;
        String file = driverFile == null || driverFile.getFileName() == null
                ? "installer"
                : driverFile.getFileName().toString();
        String argList = args == null || args.length == 0 ? "" : " " + String.join(" ", args);
        return "Could not start " + vendor + " installer (" + file + argList + "): "
                + deepestMessage(t) + ".";
    }

    public static String intelInstallerWaitInterrupted(String source) {
        String vendor = source == null || source.isBlank() ? "vendor" : source;
        return "Driver installation was interrupted while waiting for the " + vendor + " installer."
                + " Retry the update.";
    }

    public static InstallStatus failureStatus() {
        return InstallStatus.INSTALL_FAILED;
    }

    public static boolean isInvalidUserMessage(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String trimmed = message.trim();
        if ("null".equals(trimmed)) {
            return true;
        }
        if ("Error: null".equals(trimmed)) {
            return true;
        }
        if ("Install failed:\nnull".equals(trimmed) || "Install failed:null".equals(trimmed)) {
            return true;
        }
        return trimmed.endsWith(": null") && trimmed.length() <= 80;
    }

    public static String sanitizeUserMessage(String message, String friendlyDeviceName) {
        if (!isInvalidUserMessage(message)) {
            return message.trim();
        }
        if (friendlyDeviceName != null && !friendlyDeviceName.isBlank()) {
            return "Driver installation failed for " + friendlyDeviceName.trim() + ". "
                    + UNKNOWN_DIALOG_MESSAGE;
        }
        return UNKNOWN_DIALOG_MESSAGE;
    }

    public static String sanitizeHistoryDetail(String detail, String friendlyDeviceName) {
        return sanitizeUserMessage(detail, friendlyDeviceName);
    }
}
