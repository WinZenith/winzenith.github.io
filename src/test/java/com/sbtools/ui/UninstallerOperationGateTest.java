package com.sbtools.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UninstallerOperationGateTest {

    @Test
    void cancelSemanticsByPhase() {
        UninstallerOperationGate gate = new UninstallerOperationGate();
        gate.begin(UninstallerOperationGate.Phase.LIST_SCAN);
        assertTrue(gate.cancelStopsWorkImmediately());
        gate.end(UninstallerOperationGate.Phase.LIST_SCAN);

        gate.begin(UninstallerOperationGate.Phase.VENDOR_UNINSTALL);
        assertFalse(gate.cancelStopsWorkImmediately());
        gate.end(UninstallerOperationGate.Phase.VENDOR_UNINSTALL);
    }

    @Test
    void generationInvalidatesStaleWork() {
        UninstallerOperationGate gate = new UninstallerOperationGate();
        gate.begin(UninstallerOperationGate.Phase.LIST_SCAN);
        long gen = gate.activeGeneration();
        assertTrue(gate.isCurrent(gen));
        gate.begin(UninstallerOperationGate.Phase.SIZE_ENRICHMENT);
        assertFalse(gate.isCurrent(gen));
        assertTrue(gate.isCurrent(gate.activeGeneration()));
    }

    @Test
    void restoreErrorClassification() {
        var freq = UninstallerOperationGate.classifyRestorePointError("FREQUENCY_LIMIT: already");
        assertTrue(freq.headerText().contains("24h"));

        var disabled = UninstallerOperationGate.classifyRestorePointError("PROTECTION_DISABLED: off");
        assertTrue(disabled.headerText().contains("disabled"));

        var generic = UninstallerOperationGate.classifyRestorePointError("Failed to create restore point: access denied");
        assertEquals("Could not create restore point", generic.headerText());
        assertFalse(generic.headerText().toLowerCase().contains("disabled"));
    }
}
