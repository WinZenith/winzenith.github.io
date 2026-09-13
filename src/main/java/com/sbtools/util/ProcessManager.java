package com.sbtools.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry for processes started by the application that should be
 * terminated when the application exits. This class provides a lightweight
 * best-effort shutdown: it first attempts a graceful destroy(), then
 * destroyForcibly(). The wait-loop runs at most once; the JVM shutdown hook
 * never waits, so {@code System.exit} cannot hang on a child process tree.
 */
public final class ProcessManager {

    private static final ConcurrentHashMap<Long, Process> processes = new ConcurrentHashMap<>();
    private static final AtomicLong syntheticId = new AtomicLong(-1L);
    private static final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    /** Default budget for {@link #shutdownAll()} so App.stop() stays snappy. */
    private static final long DEFAULT_SHUTDOWN_BUDGET_MS = 500;

    private ProcessManager() {
    }

    static {
        // Never wait here: System.exit blocks until hooks return, and a second
        // System.exit from the force-exit watchdog is a no-op until then.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                AppLogger.info("JVM shutdown hook: terminating tracked processes");
            } catch (Throwable ignored) {
            }
            destroyRemainingForcibly();
        }, "process-shutdown-hook"));
    }

    /**
     * Starts the given ProcessBuilder and registers the resulting process for shutdown.
     * Caller may configure the builder (inheritIO, redirect, etc.) before passing it in.
     */
    public static Process start(ProcessBuilder pb) throws IOException {
        Process p = pb.start();
        register(p);
        return p;
    }

    /**
     * Register a started process so it will be terminated by shutdownAll().
     */
    public static void register(Process process) {
        if (process == null) return;
        final long key;
        long _tmpId = -1L;
        try {
            _tmpId = process.pid();
        } catch (Throwable t) {
            _tmpId = syntheticId.getAndDecrement();
        }
        key = _tmpId;
        processes.put(key, process);
        AppLogger.info("Registered process key=" + key);
        // Deregister automatically when the process exits
        process.onExit().thenRun(() -> {
            try {
                processes.remove(key);
                AppLogger.info("Process exited key=" + key);
            } catch (Throwable ignored) {
            }
        });
    }

    /**
     * Attempts to terminate all currently-registered processes.
     * This is best-effort and may not succeed for processes owned by other users
     * or in some system states.
     *
     * <p>Bounded for fast application exit: a short overall budget keeps
     * window-close snappy even when a child (winget/powershell/dism) hangs.
     * Anything still alive is {@code destroyForcibly}'d and left to the OS
     * after {@code System.exit} / the halt watchdog.</p>
     */
    public static void shutdownAll() {
        shutdownAll(DEFAULT_SHUTDOWN_BUDGET_MS);
    }

    /**
     * Same as {@link #shutdownAll()} but with an explicit overall budget.
     * The wait-loop runs at most once; a concurrent/re-entrant call only
     * {@code destroyForcibly}s whatever is still alive.
     *
     * @param budgetMs maximum time to spend waiting across all processes
     */
    public static void shutdownAll(long budgetMs) {
        if (!shuttingDown.compareAndSet(false, true)) {
            destroyRemainingForcibly();
            return;
        }
        if (processes.isEmpty()) return;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, budgetMs));
        AppLogger.info("Shutting down all tracked processes (count=" + processes.size() + ")");
        List<Process> snapshot = new ArrayList<>(processes.values());
        for (Process p : snapshot) {
            if (p == null) continue;
            try {
                if (!p.isAlive()) continue;
                long pid = -1;
                try { pid = p.pid(); } catch (Throwable ignored) {}
                AppLogger.info("Terminating process pid=" + pid);
                try {
                    p.destroy();
                } catch (Throwable ignored) {}
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs > 0) {
                    try {
                        p.waitFor(remainingMs, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (p.isAlive()) {
                    try {
                        p.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                AppLogger.error("Error while shutting down process", e);
            }
        }
        destroyRemainingForcibly();
        AppLogger.info("Tracked processes shutdown complete");
    }

    /** Best-effort kill with no waits. Safe to call from a shutdown hook. */
    private static void destroyRemainingForcibly() {
        List<Process> snapshot = new ArrayList<>(processes.values());
        for (Process p : snapshot) {
            if (p == null) continue;
            try {
                if (p.isAlive()) {
                    p.destroyForcibly();
                }
            } catch (Throwable ignored) {
            }
        }
        try { processes.clear(); } catch (Throwable ignored) {}
    }
}
