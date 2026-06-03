package com.sbtools.util;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public final class AppLogger {

    private static final Logger LOG = Logger.getLogger("BasicSDriverUpdate");
    private static volatile boolean initialized;

    private AppLogger() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        synchronized (AppLogger.class) {
            if (initialized) {
                return;
            }
            try {
                Files.createDirectories(AppPaths.logsDir());
                FileHandler handler = new FileHandler(AppPaths.logFile().toString(), true);
                handler.setFormatter(new SimpleFormatter());
                LOG.addHandler(handler);
                LOG.setUseParentHandlers(false);
            } catch (IOException e) {
                LOG.setUseParentHandlers(true);
            }
            initialized = true;
        }
    }

    public static void info(String msg) {
        init();
        LOG.info(msg);
    }

    public static void debug(String msg) {
        init();
        LOG.info(msg);
    }

    public static void warning(String msg, Throwable t) {
        init();
        LOG.log(Level.WARNING, msg, t);
    }

    public static void warning(String msg) {
        warning(msg, null);
    }

    public static void error(String msg, Throwable t) {
        init();
        LOG.log(Level.SEVERE, msg, t);
        try (PrintWriter pw = new PrintWriter(System.err, true)) {
            pw.println("[" + Instant.now() + "] " + msg);
            if (t != null) {
                t.printStackTrace(pw);
            }
        }
    }

    public static void error(String msg) {
        error(msg, null);
    }
}
