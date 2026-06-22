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
     * Remove a process from the registry.
     */
    public static void deregister(Process process) {
        if (process == null) return;
        try {
            processes.entrySet().removeIf(e -> e.getValue() == process);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Attempts to terminate all currently-registered processes.
     * This is best-effort and may not succeed for processes owned by other users
     * or in some system states.
     */
    public static void shutdownAll() {
        if (processes.isEmpty()) return;
        AppLogger.info("Shutting down all tracked processes (count=" + processes.size() + ")");
        List<Process> snapshot = new ArrayList<>(processes.values());
        for (Process p : snapshot) {
            if (p == null) continue;
            try {
                long pid = -1;
                try { pid = p.pid(); } catch (Throwable ignored) {}
                if (!p.isAlive()) {
                    processes.remove(pid);
                    continue;
                }
                AppLogger.info("Terminating process pid=" + pid);
                try {
                    p.destroy();
                } catch (Throwable ignored) {}
                boolean exited = false;
                try {
                    exited = p.waitFor(3, TimeUnit.SECONDS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
                if (!exited && p.isAlive()) {
                    // Try Windows taskkill as a fallback for process trees
                    try {
                        if (AppPaths.isWindows() && pid > 0) {
                            AppLogger.info("Attempting taskkill for pid=" + pid);
                            new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                                    .inheritIO().start().waitFor(5, TimeUnit.SECONDS);
                        }
                    } catch (Throwable ignored) {
                    }
                    if (p.isAlive()) {
                        try {
                            p.destroyForcibly();
                            p.waitFor(5, TimeUnit.SECONDS);
                        } catch (Throwable ignored) {
                        }
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



