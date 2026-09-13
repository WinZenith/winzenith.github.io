package com.sbtools.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DriversOperationGateTest {

    @Test
    void staleReleaseIsRejected() {
        DriversOperationGate gate = new DriversOperationGate();
        DriversOperationGate.Lease first = gate.tryAcquire(DriversOperationGate.Owner.SCAN);
        assertNotNull(first);
        DriversOperationGate.Lease second = gate.tryAcquire(DriversOperationGate.Owner.INSTALL);
        assertNull(second);
        assertTrue(gate.release(first));
        DriversOperationGate.Lease third = gate.tryAcquire(DriversOperationGate.Owner.SCAN);
        assertNotNull(third);
        assertFalse(gate.release(first));
        assertTrue(gate.isCurrent(third));
        assertTrue(gate.release(third));
    }

    @Test
    void generationsIncrease() {
        DriversOperationGate gate = new DriversOperationGate();
        DriversOperationGate.Lease a = gate.tryAcquire(DriversOperationGate.Owner.SCAN);
        gate.release(a);
        DriversOperationGate.Lease b = gate.tryAcquire(DriversOperationGate.Owner.SCAN);
        assertTrue(b.generation > a.generation);
    }
}
