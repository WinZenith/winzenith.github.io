package com.sbtools.drivers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriverVersionVerifierTest {

    @Test
    void verifiedWhenFreshMatchesExpected() {
        assertEquals(DriverVersionVerifier.Verdict.VERIFIED,
                DriverVersionVerifier.evaluate("581.57", "32.0.15.8157", "581.57", false));
    }

    @Test
    void unchangedWhenEqualToPrevious() {
        assertEquals(DriverVersionVerifier.Verdict.UNCHANGED,
                DriverVersionVerifier.evaluate("99.0", "1.0", "1.0", false));
    }

    @Test
    void needsRebootWhenPending() {
        assertEquals(DriverVersionVerifier.Verdict.NEEDS_REBOOT,
                DriverVersionVerifier.evaluate("2.0", "1.0", "2.0", true));
    }

    @Test
    void belowExpectedWhenFreshOlderThanTarget() {
        assertEquals(DriverVersionVerifier.Verdict.BELOW_EXPECTED,
                DriverVersionVerifier.evaluate("10.0", "8.0", "9.0", false));
    }

    @Test
    void inconclusiveWhenVersionNotPlausible() {
        assertEquals(DriverVersionVerifier.Verdict.INCONCLUSIVE,
                DriverVersionVerifier.evaluate("2.0", "1.0", "n/a", false));
    }

    @Test
    void verifiedWhenFreshNewerThanExpected() {
        assertEquals(DriverVersionVerifier.Verdict.VERIFIED,
                DriverVersionVerifier.evaluate("10.0", "8.0", "11.0", false));
    }
}
