package com.sbtools.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry for processes started by the application that should be
 * terminated when the application exits. This class provides a lightweight
 * best-effort shutdown: it first attempts a graceful destroy(), then
 * falls back to taskkill on Windows and finally destroyForcibly().
 */
public final class ProcessManager {

    private static final ConcurrentHashMap<Long, Process> processes = new ConcurrentHashMap<>();
    private static final AtomicLong syntheticId = new AtomicLong(-1L);

    private ProcessManager() {
    }

    static {
        // Ensure tracked processes are attempted to be shut down on JVM exit.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                AppLogger.info("JVM shutdown hook: terminating tracked processes");
            } catch (Throwable ignored) {
            }
            shutdownAll();
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
     * <p>Bounded for fast application exit: an overall budget keeps window-close
     * snappy even when a child (winget/powershell/dism) hangs. Per-process waits
     * are short; anything still alive after the budget is left to the OS after
     * {@code System.exit} plus a best-effort destroyForcibly.</p>
     */
    public static void shutdownAll() {
        shutdownAll(8000);
    }

    /**
     * Same as {@link #shutdownAll()} but with an explicit overall budget.
     *
     * @param budgetMs maximum time to spend across all processes
     */
    public static void shutdownAll(long budgetMs) {
        if (processes.isEmpty()) return;
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1000, budgetMs));
        AppLogger.info("Shutting down all tracked processes (count=" + processes.size() + ")");
        List<Process> snapshot = new ArrayList<>(processes.values());
        for (Process p : snapshot) {
            if (p == null) continue;
            if (System.nanoTime() > deadlineNanos) {
                AppLogger.warning("Process shutdown budget exceeded; forcing remaining.");
                break;
            }
            try {
                long pid = -1;
                try { pid = p.pid(); } catch (Throwable ignored) {}
                if (!p.isAlive()) {
                    if (pid > 0) processes.remove(pid);
                    continue;
                }
                AppLogger.info("Terminating process pid=" + pid);
                try {
                    p.destroy();
                } catch (Throwable ignored) {}
                boolean exited = false;
                try {
                    exited = p.waitFor(2, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (!exited && p.isAlive() && System.nanoTime() <= deadlineNanos) {
                    // Try Windows taskkill as a fallback for process trees.
                    // Discard output (never inheritIO: the packaged .exe has no
                    // console and inherited streams can block shutdown).
                    try {
                        if (AppPaths.isWindows() && pid > 0) {
                            AppLogger.info("Attempting taskkill for pid=" + pid);
                            new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                                    .start().waitFor(3, TimeUnit.SECONDS);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (p.isAlive()) {
                        try {
                            p.destroyForcibly();
                            p.waitFor(2, TimeUnit.SECONDS);
                        } catch (Throwable ignored) {
                        }
                    }
                } else if (p.isAlive()) {
                    try {
                        p.destroyForcibly();
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable e) {
                AppLogger.error("Error while shutting down process", e);
            }
        }
        try { processes.clear(); } catch (Throwable ignored) {}
        AppLogger.info("Tracked processes shutdown complete");
    }
}



