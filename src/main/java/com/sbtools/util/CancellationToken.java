package com.sbtools.util;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Lightweight cancellation token used to cooperatively stop long-running
 * driver-catalog scans without relying on thread interruption (which is not
 * reliably observed by HTTP / PowerShell calls used by the providers).
 */
public final class CancellationToken {

    public static final CancellationToken NONE = new CancellationToken();

    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public void cancel() {
        cancelled.set(true);
    }

    public boolean isCancelled() {
        return cancelled.get() || Thread.currentThread().isInterrupted();
    }

    public static final class CancellationException extends RuntimeException {
        public CancellationException() {
            super("Operation cancelled");
        }
    }
}
