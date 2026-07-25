package com.sbtools.util;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * A CompletableFuture that can propagate cancellation to a set of child futures
 * and attempt to shutdown the related executor service.
 */
public class CancelableCompletableFuture<T> extends CompletableFuture<T> {

    private final List<CompletableFuture<?>> children;
    private final ExecutorService executor;
    private final boolean ownExecutor;

    public CancelableCompletableFuture(List<CompletableFuture<?>> children, ExecutorService executor) {
        this(children, executor, false);
    }

    public CancelableCompletableFuture(List<CompletableFuture<?>> children, ExecutorService executor,
            boolean ownExecutor) {
        this.children = children;
        this.executor = executor;
        this.ownExecutor = ownExecutor;
    }

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (children != null) {
            for (CompletableFuture<?> c : children) {
                try { c.cancel(mayInterruptIfRunning); } catch (Exception ignored) {}
            }
        }
        if (executor != null && ownExecutor) {
            try { executor.shutdownNow(); } catch (Exception ignored) {}
        }
        return super.cancel(mayInterruptIfRunning);
    }

    public void completeFrom(CompletableFuture<T> source) {
        Objects.requireNonNull(source);
        source.whenComplete((res, ex) -> {
            if (ex != null) {
                this.completeExceptionally(ex);
            } else {
                this.complete(res);
            }
        });
    }
}

