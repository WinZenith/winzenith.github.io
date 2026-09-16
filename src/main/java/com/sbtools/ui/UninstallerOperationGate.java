package com.sbtools.ui;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the active Uninstaller tab phase and whether Cancel should abort work
 * immediately or only stop a batch queue after the current app.
 */
final class UninstallerOperationGate {

    enum Phase {
        IDLE,
        LIST_SCAN,
        SIZE_ENRICHMENT,
        RESTORE_POINT,
        VENDOR_UNINSTALL,
        LEFTOVER_SCAN,
        CLEANUP,
        FORCE_UNINSTALL
    }

    enum WorkflowOutcome {
        COMPLETED,
        CANCELLED,
        SKIPPED,
        FAILED
    }

    record RestorePointErrorPresentation(String headerText, String tipSuffix) {}

    private volatile Phase phase = Phase.IDLE;
    private final AtomicLong generation = new AtomicLong(0);
    private volatile long activeGeneration;
    private volatile AtomicBoolean cancelFlag = new AtomicBoolean(false);

    synchronized void begin(Phase newPhase) {
        phase = newPhase;
        activeGeneration = generation.incrementAndGet();
        cancelFlag = new AtomicBoolean(false);
    }

    synchronized void end(Phase expected) {
        if (phase == expected) {
            phase = Phase.IDLE;
        }
    }

    Phase phase() {
        return phase;
    }

    long activeGeneration() {
        return activeGeneration;
    }

    boolean isCurrent(long gen) {
        return gen == activeGeneration && phase != Phase.IDLE;
    }

    AtomicBoolean cancelFlag() {
        return cancelFlag;
    }

    void requestCancel() {
        cancelFlag.set(true);
    }

    boolean isCancelRequested() {
        return cancelFlag.get();
    }

    /** Cancel stops in-flight cooperative work (scan, restore, leftover scan, sizing, cleanup). */
    boolean cancelStopsWorkImmediately() {
        return switch (phase) {
            case LIST_SCAN, SIZE_ENRICHMENT, RESTORE_POINT, LEFTOVER_SCAN, CLEANUP, FORCE_UNINSTALL -> true;
            case VENDOR_UNINSTALL, IDLE -> false;
        };
    }

    String cancelButtonLabel() {
        return phase == Phase.IDLE ? "Cancel" : "Cancel";
    }

    static RestorePointErrorPresentation classifyRestorePointError(String err) {
        String e = err == null ? "" : err.trim();
        String lower = e.toLowerCase();
        if (e.contains("FREQUENCY_LIMIT")) {
            return new RestorePointErrorPresentation(
                    "Restore point skipped (Windows 24h limit)",
                    "\n\nWindows allows one restore point per 24h by default. You can still continue safely.");
        }
        if (e.contains("PROTECTION_DISABLED")) {
            return new RestorePointErrorPresentation(
                    "System Restore is disabled",
                    "\n\nTip: Enable System Protection for the system drive to use restore points.");
        }
        if (lower.contains("vss_error") || lower.contains("0x80042316")) {
            return new RestorePointErrorPresentation(
                    "Could not create restore point",
                    "");
        }
        if (lower.contains("access") && lower.contains("denied") || lower.contains("0x80070005")) {
            return new RestorePointErrorPresentation(
                    "Could not create restore point",
                    "\n\nTry running as administrator.");
        }
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return new RestorePointErrorPresentation(
                    "Could not create restore point",
                    "\n\nCreating a restore point can take several minutes on a busy disk.");
        }
        if (e.isBlank()) {
            return new RestorePointErrorPresentation("Could not create restore point", "");
        }
        return new RestorePointErrorPresentation("Could not create restore point", "");
    }

    static String stripRestoreErrorPrefixes(String err) {
        if (err == null) return "";
        String t = err.trim();
        t = t.replace("FREQUENCY_LIMIT:", "").replace("PROTECTION_DISABLED:", "")
                .replace("VSS_ERROR:", "").trim();
        return t.isBlank() ? "(no details)" : t;
    }
}
