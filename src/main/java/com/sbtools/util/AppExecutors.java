package com.sbtools.util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AppExecutors {

    private static final ExecutorService UI_POOL = Executors.newFixedThreadPool(2);
    private static final ExecutorService IO_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "io-worker");
        t.setDaemon(true);
        return t;
    });

    private AppExecutors() {
    }

    public static ExecutorService uiPool() {
        return UI_POOL;
    }

    public static ExecutorService ioPool() {
        return IO_POOL;
    }

    public static void shutdown() {
        UI_POOL.shutdownNow();
        IO_POOL.shutdownNow();
    }
}
