package com.sbtools.util;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Bridges a polling {@link BooleanSupplier} cancel signal to an {@link AtomicBoolean}
 * polled by {@link ProcessRunner}-style APIs.
 *
 * <p>Centralises the previously duplicated "monitor thread" blocks in
 * {@code SoftwareUpdateService} / {@code WingetRunner} so cancellation semantics stay
 * identical (100ms poll, daemon thread, interrupt on close) with a single implementation.
 * Session-only, no persisted state.</p>
 */
public final class CancelBridge implements AutoCloseable {

    private final AtomicBoolean flag;
    private final Thread monitor;

    private CancelBridge(AtomicBoolean flag, Thread monitor) {
        this.flag = flag;
        this.monitor = monitor;
    }

    /** Live flag updated from {@code supplier}; already {@code true} if cancelled now. */
    public AtomicBoolean flag() {
        return flag;
    }

    @Override
    public void close() {
        Thread m = monitor;
        if (m != null) m.interrupt();
    }

    /**
     * Starts bridging {@code supplier} to a fresh flag.
     * If {@code supplier} is {@code null}, returns an un-cancelled flag with no thread.
     */
    public static CancelBridge bridge(BooleanSupplier supplier, String threadName) {
        AtomicBoolean flag = new AtomicBoolean(false);
        if (supplier == null) return new CancelBridge(flag, null);
        try {
            if (supplier.getAsBoolean()) flag.set(true);
        } catch (Exception ignored) {}
        if (flag.get()) return new CancelBridge(flag, null);
        Thread monitor = new Thread(() -> {
            while (!flag.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                try {
                    if (supplier.getAsBoolean()) flag.set(true);
                } catch (Exception ignored) {}
            }
        }, threadName == null ? "cancel-bridge" : threadName);
        monitor.setDaemon(true);
        monitor.start();
        return new CancelBridge(flag, monitor);
    }
}
