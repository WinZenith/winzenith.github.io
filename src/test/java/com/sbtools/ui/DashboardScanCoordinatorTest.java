package com.sbtools.ui;

import com.sbtools.util.CancellationToken;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DashboardScanCoordinatorTest {

    @Test
    void perTaskTimeoutCancelsSlowTask() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = pool.submit(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            Set<Integer> timedOut = DashboardScanCoordinator.awaitAllInterruptible(
                    List.of(slow),
                    new long[]{1},
                    () -> false,
                    null,
                    () -> false,
                    30,
                    null,
                    1,
                    50);
            assertTrue(timedOut.contains(0));
            assertTrue(slow.isCancelled() || slow.isDone());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleScanThrowsCancellation() {
        Future<?> done = Executors.newSingleThreadExecutor().submit(() -> {});
        try {
            assertThrows(CancellationException.class, () ->
                    DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(done),
                            new long[]{0},
                            () -> true,
                            null,
                            () -> false,
                            30,
                            null,
                            1,
                            10));
        } finally {
            done.cancel(true);
        }
    }

    @Test
    void cancelledTokenThrowsWithoutSettingInterruptFlag() throws Exception {
        CancellationToken token = new CancellationToken();
        token.cancel();
        Future<?> pending = Executors.newSingleThreadExecutor().submit(() -> {
            try {
                Thread.sleep(5_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            boolean interruptedBefore = Thread.currentThread().isInterrupted();
            assertThrows(CancellationException.class, () ->
                    DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(pending),
                            new long[]{0},
                            () -> false,
                            token,
                            () -> false,
                            30,
                            null,
                            1,
                            10));
            assertEquals(interruptedBefore, Thread.currentThread().isInterrupted());
        } finally {
            pending.cancel(true);
        }
    }

    @Test
    void overallTimeoutThrowsTimeoutException() {
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> slow = pool.submit(() -> {
                try {
                    Thread.sleep(60_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThrows(TimeoutException.class, () ->
                    DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(slow),
                            new long[]{0},
                            () -> false,
                            null,
                            () -> false,
                            1,
                            null,
                            1,
                            50));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void workerExecutionExceptionPropagates() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> failed = pool.submit(() -> {
                throw new IllegalStateException("boom");
            });
            while (!failed.isDone()) {
                Thread.sleep(10);
            }
            ExecutionException wrapper = assertThrows(ExecutionException.class,
                    () -> failed.get(1, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, wrapper.getCause());
            AtomicBoolean disposed = new AtomicBoolean(false);
            assertThrows(IllegalStateException.class, () ->
                    DashboardScanCoordinator.awaitAllInterruptible(
                            List.of(failed),
                            new long[]{0},
                            () -> false,
                            null,
                            disposed::get,
                            30,
                            null,
                            1,
                            10));
        } finally {
            pool.shutdownNow();
        }
    }
}
