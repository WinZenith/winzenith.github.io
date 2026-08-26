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

    private static final Logger LOG = Logger.getLogger("WinZenith");
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
                // Try portable logs dir first (next to exe) for true portable mode; fallback to LOCALAPPDATA
                java.nio.file.Path portableDir = AppPaths.portableLogsDir();
                java.nio.file.Path appDataDir = AppPaths.logsDir();
                java.nio.file.Path chosen = portableDir;
                try {
                    Files.createDirectories(chosen);
                    if (!Files.isWritable(chosen)) throw new IOException("not writable");
                } catch (IOException ex) {
                    chosen = appDataDir;
                    Files.createDirectories(chosen);
                }
                java.nio.file.Path logFile = chosen.resolve("app.log");
                FileHandler handler = new FileHandler(logFile.toString(), 500 * 1024, 3, true);
                handler.setFormatter(new SimpleFormatter());
                LOG.addHandler(handler);
                LOG.setUseParentHandlers(false);
                LOG.info("Logging to " + logFile.toString() + " (portable=" + chosen.equals(portableDir) + ")");
            } catch (IOException e) {
                try {
                    Files.createDirectories(AppPaths.logsDir());
                    FileHandler fallback = new FileHandler(AppPaths.logFile().toString(), true);
                    fallback.setFormatter(new SimpleFormatter());
                    LOG.addHandler(fallback);
                    LOG.setUseParentHandlers(false);
                } catch (IOException ex) {
                    LOG.setUseParentHandlers(true);
                }
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
        LOG.log(Level.FINE, msg);
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
