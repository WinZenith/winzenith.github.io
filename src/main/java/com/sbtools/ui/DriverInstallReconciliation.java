package com.sbtools.ui;

import com.sbtools.drivers.DriverVersionVerifier;
import com.sbtools.drivers.model.InstalledDriver;

/** Maps post-install version verdicts to history and user-facing text. */
final class DriverInstallReconciliation {

    private DriverInstallReconciliation() {
    }

    static String freshVersion(InstalledDriver driver) {
        if (driver == null || driver.driverVersion() == null) {
            return "";
        }
        return driver.driverVersion().trim();
    }

    static DriverVersionVerifier.Verdict verdict(String expected,
                                                 String previousCurrent,
                                                 InstalledDriver fresh,
                                                 boolean rebootPending) {
        return DriverVersionVerifier.evaluate(
                expected,
                previousCurrent,
                freshVersion(fresh),
                rebootPending);
    }

    static boolean historySuccess(DriverVersionVerifier.Verdict verdict) {
        return verdict == DriverVersionVerifier.Verdict.VERIFIED
                || verdict == DriverVersionVerifier.Verdict.NEEDS_REBOOT;
    }

    static String historyDetail(DriverVersionVerifier.Verdict verdict, String freshVersion) {
        return historyDetail(verdict, freshVersion, 1, "");
    }

    static String historyDetail(DriverVersionVerifier.Verdict verdict,
                                String freshVersion,
                                int readAttempts,
                                String diagnostic) {
        String v = freshVersion == null || freshVersion.isBlank() ? "?" : freshVersion;
        String base = switch (verdict) {
            case VERIFIED -> "verified " + v;
            case NEEDS_REBOOT -> "reboot-required; verified reports " + v;
            case UNCHANGED -> "post-install verify: version unchanged (" + v + ")";
            case BELOW_EXPECTED -> "post-install verify: below expected (" + v + ")";
            case INCONCLUSIVE -> "post-install verify: inconclusive (" + v + ")";
        };
        if (readAttempts > 1) {
            base += "; " + readAttempts + " reads";
        }
        if (diagnostic != null && !diagnostic.isBlank()) {
            base += "; " + diagnostic;
        }
        return base;
    }

    static String userStatusLine(String friendlyName,
                                 DriverVersionVerifier.Verdict verdict,
                                 String expected,
                                 String freshVersion) {
        return userStatusLine(friendlyName, verdict, expected, freshVersion, 1, "");
    }

    static String userStatusLine(String friendlyName,
                                 DriverVersionVerifier.Verdict verdict,
                                 String expected,
                                 String freshVersion,
                                 int readAttempts,
                                 String diagnostic) {
        String name = friendlyName == null ? "Driver" : friendlyName;
        String fresh = freshVersion == null || freshVersion.isBlank() ? "unknown" : freshVersion;
        String exp = expected == null || expected.isBlank() ? "?" : expected;
        return switch (verdict) {
            case VERIFIED -> "Update verified for " + name + " (" + fresh + ").";
            case NEEDS_REBOOT -> "Update installed for " + name
                    + " — restart pending (reports " + fresh + ").";
            case UNCHANGED -> exhaustedVerifyMessage(name, fresh, exp, readAttempts);
            case BELOW_EXPECTED -> exhaustedVerifyMessage(name, fresh, exp, readAttempts)
                    + " (below expected).";
            case INCONCLUSIVE -> "Installer finished but could not verify " + name
                    + " after " + Math.max(1, readAttempts) + " check(s) (read " + fresh + "). Scan again to confirm.";
        };
    }

    private static String exhaustedVerifyMessage(String name, String fresh, String exp, int readAttempts) {
        String base = "Installer finished but Windows did not bind the expected version for " + name
                + " after " + Math.max(1, readAttempts) + " check(s) (still " + fresh
                + ", expected " + exp + "). Scan again or try manual install.";
        if (com.sbtools.util.WindowsServicingSafety.isServicingPending()) {
            base += " Windows reports a pending reboot — restart, then scan again.";
        }
        return base;
    }
}
