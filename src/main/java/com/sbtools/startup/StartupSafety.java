package com.sbtools.startup;

import java.util.Locale;
import java.util.Set;

/**
 * Central safety policy for startup management.
 *
 * <p>Previously the critical-service list lived only in the UI layer
 * ({@code StartupTabView}), which meant any future caller of
 * {@link StartupService} could bypass the guard. This class is the single
 * source of truth used by both UI and service layers.</p>
 *
 * <p>Design notes:</p>
 * <ul>
 *   <li>Conservative: only services required for Windows boot/stability.
 *       Audio, print, update etc. are intentionally NOT critical — disabling
 *       them is annoying but not unbootable.</li>
 *   <li>Case-insensitive: all lookups normalize with {@link Locale#ROOT}.</li>
 *   <li>Read-only: no registry access here, pure logic, fully unit-testable.</li>
 * </ul>
 */
public final class StartupSafety {

    /**
     * Boot-critical Windows services. Disabling any of these may render the
     * system unbootable or unstable. Kept identical to the legacy UI list so
     * existing behavior is preserved; centralized here for reuse.
     */
    private static final Set<String> CRITICAL_SERVICE_NAMES = Set.of(
            "schedule", "eventlog", "rpcss", "rpceptmapper", "dcomlaunch",
            "plugplay", "power", "brokerinfrastructure", "coremessagingregistrar",
            "lsm", "samss", "winmgmt", "cryptsvc", "dhcp", "dnscache",
            "mpssvc", "trustedinstaller", "gpsvc", "wcmsvc", "lanmanserver",
            "lanmanworkstation", "profsvc", "sens", "themes", "windefend");

    private StartupSafety() {
    }

    /**
     * @return true if the given service name is boot-critical (case-insensitive).
     */
    public static boolean isCriticalService(String serviceName) {
        if (serviceName == null || serviceName.isBlank()) {
            return false;
        }
        return CRITICAL_SERVICE_NAMES.contains(serviceName.toLowerCase(Locale.ROOT).trim());
    }

    /**
     * @return true if the item is an enabled critical service that is about to be disabled.
     */
    public static boolean isCriticalDisable(StartupItem item) {
        if (item == null || item.getType() != StartupItemType.SERVICE) {
            return false;
        }
        return item.isEnabled() && isCriticalService(item.getName());
    }

    /**
     * Validates a toggle operation. Does not throw for normal items.
     *
     * @param item the item about to be toggled
     * @return a human-readable warning when the operation is risky, else null.
     */
    public static String describeRisk(StartupItem item) {
        if (isCriticalDisable(item)) {
            return "This service is required for Windows stability/boot. "
                    + "Disabling \"" + item.getName() + "\" may render the system unbootable or unstable.";
        }
        return null;
    }

    /**
     * @return an unmodifiable view of the critical-service set (lowercase names).
     */
    public static Set<String> criticalServiceNames() {
        return CRITICAL_SERVICE_NAMES;
    }

    /**
     * Determines whether the given registry/task location requires elevation.
     * Pure helper extracted from UI logic so it can be unit-tested and reused.
     */
    public static boolean requiresAdmin(StartupItem item) {
        if (item == null) {
            return false;
        }
        if (item.getType() == StartupItemType.SERVICE) {
            return true;
        }
        String loc = item.getLocation();
        if (loc != null && (loc.contains("HKLM") || loc.contains("Common"))) {
            return true;
        }
        return isSystemTask(item);
    }

    /**
     * @return true for tasks under \Microsoft\ or \Windows\ (system-owned).
     */
    public static boolean isSystemTask(StartupItem item) {
        if (item == null || item.getType() != StartupItemType.TASK) {
            return false;
        }
        String tp = item.getTaskPath();
        if (tp == null) {
            return false;
        }
        String lower = tp.toLowerCase(Locale.ROOT);
        return lower.startsWith("\\microsoft\\") || lower.startsWith("\\windows\\")
                || lower.contains("\\microsoft\\windows");
    }
}
