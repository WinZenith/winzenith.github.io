package com.sbtools.ui;

/**
 * Generation-stamped operation lease for Drivers-tab scan/install/backup/catalog work.
 * Stale async completions cannot release or reset a newer operation.
 */
final class DriversOperationGate {

    enum Owner {
        SCAN, INSTALL, BACKUP, CATALOG
    }

    static final class Lease {
        final Owner owner;
        final long generation;

        Lease(Owner owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }
    }

    private Lease current;
    private long sequence;

    synchronized Lease tryAcquire(Owner owner) {
        if (current != null) {
            return null;
        }
        long gen = ++sequence;
        current = new Lease(owner, gen);
        return current;
    }

    synchronized boolean release(Lease lease) {
        if (lease == null || current == null) {
            return false;
        }
        if (current.owner != lease.owner || current.generation != lease.generation) {
            return false;
        }
        current = null;
        return true;
    }

    synchronized boolean isCurrent(Lease lease) {
        if (lease == null || current == null) {
            return false;
        }
        return current.owner == lease.owner && current.generation == lease.generation;
    }

    synchronized Owner activeOwner() {
        return current == null ? null : current.owner;
    }
}
