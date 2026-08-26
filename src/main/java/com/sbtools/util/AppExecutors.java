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

    private static final javafx.beans.property.BooleanProperty GLOBAL_BUSY = new javafx.beans.property.SimpleBooleanProperty(false);

    public static javafx.beans.property.BooleanProperty globalBusyProperty() {
        return GLOBAL_BUSY;
    }

    private static void setBusySync(javafx.beans.property.BooleanProperty target, boolean value) {
        if (javafx.application.Platform.isFxApplicationThread()) {
            target.set(value);
        } else {
            javafx.application.Platform.runLater(() -> target.set(value));
        }
        // Keep global mirror in sync (legacy callers may check it)
        if (target != GLOBAL_BUSY) {
            if (javafx.application.Platform.isFxApplicationThread()) {
                GLOBAL_BUSY.set(value);
            } else {
                javafx.application.Platform.runLater(() -> GLOBAL_BUSY.set(value));
            }
        }
    }

    /** Legacy helper — now delegates to BusyProperty's own counting via set(). */
    public static void acquireBusy(javafx.beans.property.BooleanProperty target) {
        setBusySync(target, true);
    }

    /** Legacy helper — now delegates to BusyProperty's own counting via set(). */
    public static void releaseBusy(javafx.beans.property.BooleanProperty target) {
        setBusySync(target, false);
    }

    public static void forceClearBusy(javafx.beans.property.BooleanProperty target) {
        if (target instanceof BusyProperty bp) {
            if (javafx.application.Platform.isFxApplicationThread()) bp.forceClear();
            else javafx.application.Platform.runLater(bp::forceClear);
        } else {
            setBusySync(target, false);
        }
        setBusySync(GLOBAL_BUSY, false);
    }

    public static void shutdown() {
        UI_POOL.shutdownNow();
        IO_POOL.shutdownNow();
        SCAN_POOL.shutdownNow();
        CLEAN_POOL.shutdownNow();
    }
}
