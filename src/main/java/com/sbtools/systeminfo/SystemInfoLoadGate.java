package com.sbtools.systeminfo;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generation-aware load/busy gate so a finishing gather cannot steal the
 * global busy hold from a newer Refresh, or re-enable Cancel-disabled UI
 * while the new gather is still running.
 */
public final class SystemInfoLoadGate {

    public record Begin(boolean started, int generation, boolean acquiredBusy) {}

    private final AtomicBoolean loading = new AtomicBoolean(false);
    private final AtomicBoolean busyHeld = new AtomicBoolean(false);
    private final AtomicInteger generation = new AtomicInteger(0);

    public Begin tryBegin() {
        if (!loading.compareAndSet(false, true)) {
            return new Begin(false, generation.get(), false);
        }
        int gen = generation.incrementAndGet();
        boolean acquiredBusy = busyHeld.compareAndSet(false, true);
        return new Begin(true, gen, acquiredBusy);
    }

    public boolean isStale(int gen) {
        return gen != generation.get();
    }

    public boolean isLoading() {
        return loading.get();
    }

    /**
     * Marks this generation idle. Does not touch {@code busyHeld} so a newer
     * begin that inherited the hold can still release it.
     */
    public boolean finishIfCurrent(int gen) {
        if (isStale(gen)) {
            return false;
        }
        loading.set(false);
        return true;
    }

    public boolean releaseBusyIfCurrent(int gen) {
        if (isStale(gen)) {
            return false;
        }
        return busyHeld.compareAndSet(true, false);
    }

    /** Invalidates in-flight gathers and drops the busy hold if this gate owns it. */
    public boolean disposeAndReleaseBusy() {
        generation.incrementAndGet();
        loading.set(false);
        return busyHeld.compareAndSet(true, false);
    }
}
