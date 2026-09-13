package com.sbtools.ui;

import com.sbtools.drivers.DriverVersionVerifier;
import com.sbtools.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

class DriverInstallReconciliationTest {

    @Test
    void historySuccessOnlyForVerifiedOrReboot() {
        assertTrue(DriverInstallReconciliation.historySuccess(DriverVersionVerifier.Verdict.VERIFIED));
        assertTrue(DriverInstallReconciliation.historySuccess(DriverVersionVerifier.Verdict.NEEDS_REBOOT));
        assertFalse(DriverInstallReconciliation.historySuccess(DriverVersionVerifier.Verdict.UNCHANGED));
        assertFalse(DriverInstallReconciliation.historySuccess(DriverVersionVerifier.Verdict.INCONCLUSIVE));
    }

    @Test
    void verdictUsesInstalledDriverVersion() {
        InstalledDriver d = new InstalledDriver("x", "GPU", "", "Nvidia", "581.57", "", "", "", LocalDate.now(), true);
        assertEquals(DriverVersionVerifier.Verdict.VERIFIED,
                DriverInstallReconciliation.verdict("581.57", "32.0.15.8157", d, false));
    }

    @Test
    void exhaustedMessageMentionsMultipleChecks() {
        String msg = DriverInstallReconciliation.userStatusLine(
                "Intel BT", DriverVersionVerifier.Verdict.UNCHANGED, "24.70.0.3", "24.40.0.3", 4, "deadline");
        assertTrue(msg.contains("4 check"));
        assertTrue(msg.contains("24.40.0.3"));
        assertTrue(msg.contains("24.70.0.3"));
    }
}
