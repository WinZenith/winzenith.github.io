package com.sbtools.drivers;

import com.sbtools.util.VersionCompare;

/**
 * Pure post-install version verdict shared by single and batch install paths.
 */
public final class DriverVersionVerifier {

    public enum Verdict {
        VERIFIED,
        NEEDS_REBOOT,
        UNCHANGED,
        BELOW_EXPECTED,
        INCONCLUSIVE
    }

    private DriverVersionVerifier() {
    }

    public static boolean isPlausibleVersionNumber(String v) {
        if (v == null || v.isBlank() || v.length() > 64) {
            return false;
        }
        return v.matches("(?i).*\\d+\\.\\d+.*");
    }

    public static Verdict evaluate(String expectedVersion,
                                   String previousCurrent,
                                   String freshVersion,
                                   boolean rebootPending) {
        if (rebootPending) {
            return Verdict.NEEDS_REBOOT;
        }
        if (!isPlausibleVersionNumber(freshVersion)) {
            return Verdict.INCONCLUSIVE;
        }
        String expected = expectedVersion == null ? "" : expectedVersion.trim();
        String previous = previousCurrent == null ? "" : previousCurrent.trim();
        String fresh = freshVersion.trim();
        if (!expected.isBlank() && VersionCompare.compare(expected, fresh) <= 0) {
            return Verdict.VERIFIED;
        }
        if (!previous.isBlank() && VersionCompare.compare(fresh, previous) == 0) {
            return Verdict.UNCHANGED;
        }
        if (!expected.isBlank() && VersionCompare.compare(fresh, expected) < 0) {
            return Verdict.BELOW_EXPECTED;
        }
        if (!previous.isBlank() && VersionCompare.compare(fresh, previous) != 0) {
            return Verdict.INCONCLUSIVE;
        }
        return Verdict.INCONCLUSIVE;
    }
}
