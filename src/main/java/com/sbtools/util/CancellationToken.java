package com.sbtools.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight cancellation token used to cooperatively stop long-running
 * driver-catalog scans without relying on thread interruption (which is not
 * reliably observed by HTTP / PowerShell calls used by the providers).
 */
public final class CancellationToken {

    private static final CancellationToken SENTINEL = new CancellationToken(true);
    public static final CancellationToken NONE = SENTINEL;

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final boolean sentinel;

    public CancellationToken() {
        this(false);
    }

    private CancellationToken(boolean sentinel) {
        this.sentinel = sentinel;
    }

    public void cancel() {
        if (sentinel) return;
        cancelled.set(true);
    }

    public boolean isCancelled() {
        if (sentinel) return false;
        return cancelled.get();
    }

    public AtomicBoolean asAtomicBoolean() {
        if (sentinel) return new AtomicBoolean(false);
        return cancelled;
    }
}
