package com.sbtools.util;

/**
 * Pre-launch elevation gate.
 *
 * <p>Must be called as the very first statement of {@code App.main()}, BEFORE
 * {@code Application.launch()}. This guarantees the standard Windows UAC prompt
 * appears before any JavaFX window is created, so the user never sees the app
 * load, close and reopen.
 *
 * <p>Flow (Windows only, single dialog — the standard UAC prompt only):
 * <ol>
 *   <li>If already running as admin &rarr; proceed to launch.</li>
 *   <li>If not admin &rarr; trigger the standard Windows UAC prompt directly.
 *       No custom pre-dialog is shown.</li>
 *   <li>UAC accepted &rarr; the elevated child starts and this process exits
 *       quietly without launching.</li>
 *   <li>UAC cancelled/failed &rarr; launch unelevated (limited features).</li>
 * </ol>
 *
 * <p>Loop protection: relaunched elevated children carry
 * {@link #ARG_ELEVATED_RELAUNCH}. Such instances never prompt again, even if they
 * are still non-admin (e.g. UAC disabled by policy).
 */
public final class ElevationGate {

    /** Marker appended when relaunching elevated; suppresses re-prompting. */
    public static final String ARG_ELEVATED_RELAUNCH = "--elevated-relaunch";
    /** Escape hatch for scripts/tests: never prompt, always launch directly. */
    public static final String ARG_NO_ELEVATE_PROMPT = "--no-elevate-prompt";

    private ElevationGate() {
    }

    /**
     * Runs the pre-launch gate.
     *
     * @param args raw {@code main()} args
     * @return {@code true} if the caller must exit immediately WITHOUT calling
     *         {@code launch()} (an elevated child was started and accepted UAC);
     *         {@code false} to proceed with a normal (possibly unelevated) launch.
     */
    public static boolean handlePreLaunch(String[] args) {
        if (!AppPaths.isWindows()) {
            return false;
        }
        if (hasArg(args, ARG_NO_ELEVATE_PROMPT) || hasArg(args, ARG_ELEVATED_RELAUNCH)) {
            return false;
        }
        boolean admin;
        try {
            admin = AdminCheck.isRunningAsAdmin();
        } catch (Throwable t) {
            // If the check itself fails, do not block startup.
            System.err.println("[ElevationGate] Admin check failed, starting unelevated: " + t.getMessage());
            return false;
        }
        if (admin) {
            return false;
        }

        // Not admin -> trigger the single standard Windows UAC prompt synchronously
        // so we know the outcome (accepted vs cancelled).
        try {
            AdminCheck.ElevationResult result = AdminCheck.requestElevation(args);
            if (result == AdminCheck.ElevationResult.ELEVATED_CHILD_STARTED) {
                log("Elevation accepted. Exiting non-elevated instance.");
                return true;
            }
            if (result == AdminCheck.ElevationResult.DENIED_BY_USER) {
                log("Elevation cancelled by user. Starting without administrator privileges.");
            } else {
                log("Elevation failed. Starting without administrator privileges.");
            }
        } catch (Throwable t) {
            System.err.println("[ElevationGate] Failed to request elevation, starting unelevated: " + t.getMessage());
        }
        return false;
    }

    private static boolean hasArg(String[] args, String flag) {
        if (args == null) {
            return false;
        }
        for (String a : args) {
            if (flag.equalsIgnoreCase(a)) {
                return true;
            }
        }
        return false;
    }

    private static void log(String msg) {
        try {
            AppLogger.info("[ElevationGate] " + msg);
        } catch (Throwable ignored) {
            System.err.println("[ElevationGate] " + msg);
        }
    }
}
