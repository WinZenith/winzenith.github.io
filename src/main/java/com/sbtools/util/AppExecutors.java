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
    private static final ExecutorService SCAN_POOL = Executors.newFixedThreadPool(6, r -> {
        Thread t = new Thread(r, "scan-worker");
        t.setDaemon(true);
        return t;
    });
    private static final ExecutorService CLEAN_POOL = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "clean-worker");
        t.setDaemon(true);
        return t;
    });

    private AppExecutors() {
    }

    public static ExecutorService ioPool() {
        return IO_POOL;
    }

    public static ExecutorService scanPool() {
        return SCAN_POOL;
    }

    public static ExecutorService cleanPool() {
        return CLEAN_POOL;
    }

    public static void shutdown() {
        UI_POOL.shutdownNow();
        IO_POOL.shutdownNow();
        SCAN_POOL.shutdownNow();
        CLEAN_POOL.shutdownNow();
    }
}
