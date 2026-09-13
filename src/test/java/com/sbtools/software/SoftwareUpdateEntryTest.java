package com.sbtools.software;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SoftwareUpdateEntryTest {

    @Test
    void terminalFailureIncludesFailedAndManualRepair() {
        assertTrue(SoftwareUpdateEntry.isTerminalFailureStatus(SoftwareUpdateEntry.STATUS_FAILED));
        assertTrue(SoftwareUpdateEntry.isTerminalFailureStatus(SoftwareUpdateEntry.STATUS_MANUAL_REPAIR));
        assertFalse(SoftwareUpdateEntry.isTerminalFailureStatus("Installing..."));
        assertFalse(SoftwareUpdateEntry.isTerminalFailureStatus(""));
        assertFalse(SoftwareUpdateEntry.isTerminalFailureStatus(null));
    }

    @Test
    void requiresManualRepairOnlyForManualStatus() {
        assertTrue(SoftwareUpdateEntry.requiresManualRepair(SoftwareUpdateEntry.STATUS_MANUAL_REPAIR));
        assertFalse(SoftwareUpdateEntry.requiresManualRepair(SoftwareUpdateEntry.STATUS_FAILED));
    }
}
