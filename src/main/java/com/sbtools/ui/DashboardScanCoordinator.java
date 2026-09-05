package com.sbtools.ui;

import com.sbtools.util.CancellationToken;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/**
 * Pure orchestration helper for Dashboard parallel sub-scans.
 *
 * <p>Extracted from {@code DashboardTabView} so the view stays view-only.
 * All waiting is interruptible: workers are plain {@code Future}s submitted via
 * {@code ExecutorService.submit} so {@code cancel(true)} truly interrupts
 * PowerShell / file-walk threads ( {@code CompletableFuture.cancel} would not).
 *
 * <p>Supports per-category soft budgets on top of the overall budget: a slow
 * category is cancelled in isolation (marked timed-out) while the remaining
 * categories continue, preserving partial results.
 */
public final class DashboardScanCoordinator {

    /** Overall Dashboard scan budget (outer coordinator). */
    public static final long OVERALL_TIMEOUT_SECONDS = 360;
    /** Soft budget for the driver sub-scan. */
    public static final long DRIVER_TIMEOUT_SECONDS = 180;
    /** Soft budget for the software sub-scan. */
    public static final long SOFTWARE_TIMEOUT_SECONDS = 180;
    /** Soft budget for the cleanup sub-scan (21 categories, file walks). */
    public static final long CLEANUP_TIMEOUT_SECONDS = 240;

    private DashboardScanCoordinator() {
    }

    /**
     * Interruptible wait for the combined scan.
     *
     * @param tasks              sub-scan futures in order (e.g. driver, software, cleanup)
     * @param perTaskTimeoutSecs per-task soft budgets in seconds; missing entries = no limit
     * @param isStale            returns true when the scan generation was superseded (Stop / new scan)
     * @param token              cooperative cancellation token (may be null)
     * @param isDisposed         returns true when the view was disposed
     * @param overallTimeoutSecs overall budget in seconds (clamped to &gt;= 30)
     * @return indices of tasks cancelled due to per-task timeout (never null)
     * @throws InterruptedException when the waiting thread is interrupted
     * @throws TimeoutException     when the overall budget expires (all tasks cancelled)
     */
    public static Set<Integer> awaitAllInterruptible(
            List<Future<?>> tasks,
            long[] perTaskTimeoutSecs,
            BooleanSupplier isStale,
            CancellationToken token,
            BooleanSupplier isDisposed,
            long overallTimeoutSecs)
            throws InterruptedException, TimeoutException {
        Set<Integer> timedOut = new HashSet<>();
        long overall = Math.max(30, overallTimeoutSecs);
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(overall);
        long startNanos = System.nanoTime();

        while (true) {
            if (isDisposed != null && isDisposed.getAsBoolean()) {
                cancelAll(tasks, timedOut);
                throw new InterruptedException("Dashboard scan interrupted (disposed)");
            }
            if (Thread.currentThread().isInterrupted()) {
                cancelAll(tasks, timedOut);
                throw new InterruptedException("Dashboard scan interrupted");
            }
            if ((isStale != null && isStale.getAsBoolean())
                    || (token != null && token.isCancelled())) {
                cancelAll(tasks, timedOut);
                throw new CancellationException("Dashboard scan cancelled");
            }
            if (System.nanoTime() > deadlineNanos) {
                if (token != null) token.cancel();
                cancelAll(tasks, timedOut);
                throw new TimeoutException(
                        "Dashboard scan timed out after " + overall + "s — partial results kept");
            }
            // Per-task soft budgets: cancel the slow worker only, keep the rest.
            long elapsedSecs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startNanos);
            for (int i = 0; i < tasks.size(); i++) {
                if (timedOut.contains(i)) continue;
                Future<?> t = tasks.get(i);
                if (t == null || t.isDone() || t.isCancelled()) continue;
                long budget = budgetFor(perTaskTimeoutSecs, i);
                if (budget > 0 && elapsedSecs >= budget) {
                    try {
                        t.cancel(true);
                    } catch (Exception ignored) {
                    }
                    timedOut.add(i);
                }
            }
            boolean allDone = true;
            for (int i = 0; i < tasks.size(); i++) {
                Future<?> t = tasks.get(i);
                if (t != null && !t.isDone() && !t.isCancelled() && !timedOut.contains(i)) {
                    allDone = false;
                    break;
                }
                // A timed-out task reports isCancelled after cancel(true); treat as done.
                if (timedOut.contains(i)) continue;
            }
            if (allDone) {
                break;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                cancelAll(tasks, timedOut);
                Thread.currentThread().interrupt();
                throw ie;
            }
        }
        // Surface worker failures (cancellation already handled above).
        for (int i = 0; i < tasks.size(); i++) {
            if (timedOut.contains(i)) continue;
            Future<?> t = tasks.get(i);
            if (t == null) continue;
            try {
                if (!t.isCancelled()) {
                    t.get(0, TimeUnit.MILLISECONDS);
                }
            } catch (java.util.concurrent.ExecutionException ex) {
                Throwable cause = ex.getCause();
                if (cause instanceof CancellationException ce) throw ce;
                if (cause instanceof InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ie;
                }
                if (cause instanceof RuntimeException re) throw re;
                if (cause != null) throw new RuntimeException(cause);
            } catch (TimeoutException impossible) {
                // isDone() was true — ignore
            }
        }
        return timedOut;
    }

    private static long budgetFor(long[] perTaskTimeoutSecs, int index) {
        if (perTaskTimeoutSecs == null || index < 0 || index >= perTaskTimeoutSecs.length) return 0;
        return Math.max(0, perTaskTimeoutSecs[index]);
    }

    private static void cancelAll(List<Future<?>> tasks, Set<Integer> exceptTimedOut) {
        if (tasks == null) return;
        for (Future<?> t : tasks) {
            if (t != null && !t.isDone()) {
                try {
                    t.cancel(true);
                } catch (Exception ignored) {
                }
            }
        }
    }
}
