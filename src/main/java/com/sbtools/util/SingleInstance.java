package com.sbtools.util;

import java.io.RandomAccessFile;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Single-instance guard.
 *
 * <p>The first instance holds an OS-level file lock and serves a loopback "focus"
 * socket. Any later launch signals the primary to come to front and then exits
 * quietly <b>before</b> any UAC prompt — so double-clicking the shortcut twice
 * produces exactly one elevation dialog, not two.</p>
 *
 * <p>The lock lives in per-user {@code %LOCALAPPDATA%/WinZenith} (not the portable
 * dir) so unelevated and elevated instances of the same user share it. OS file locks
 * are released automatically if a process dies, so a crashed instance can never
 * wedge the app permanently.</p>
 */
public final class SingleInstance {

    private static final String LOCK_NAME = "app.lock";
    private static final String PORT_NAME = "app.port";
    private static final String FOCUS_LINE = "SHOW";
    /** Elevated child: parent is on its way out, wait for its lock to release. */
    private static final long ELEVATED_TAKEOVER_TIMEOUT_MS = 8000;
    private static final long ELEVATED_RETRY_MS = 250;
    private static final int SIGNAL_TIMEOUT_MS = 2000;

    public enum Role {
        /** Holds the lock (and focus socket). Proceed to launch. */
        PRIMARY,
        /** Another instance is up. Signal it, then exit without prompting. */
        SECONDARY,
        /**
         * Elevated relaunch whose parent did not release the lock in time.
         * Proceed with startup anyway (no lock, no focus socket) — an app window
         * is always better than silently exiting.
         */
        PRIMARY_UNLOCKED
    }

    private static volatile RandomAccessFile lockFile;
    private static volatile FileChannel lockChannel;
    private static volatile FileLock lock;
    private static volatile ServerSocket server;
    private static volatile Thread listener;
    private static volatile boolean ownsPortFile;
    private static final AtomicReference<Runnable> focusHandler = new AtomicReference<>();

    static {
        // Last-resort release if the app is killed without going through App.stop()
        // (e.g. System.exit watchdog). Idempotent and quiet by design.
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(SingleInstance::release, "single-instance-release"));
        } catch (Throwable ignored) {
        }
    }

    private SingleInstance() {
    }

    /** Called by the UI layer once the main window exists. Runs off the FX thread. */
    public static void onFocusRequested(Runnable handler) {
        focusHandler.set(handler);
    }

    /**
     * @param elevatedChild true when this process is the elevated relaunch
     *                      (carries {@code --elevated-relaunch}); its unelevated
     *                      parent may still hold the lock for a moment.
     */
    public static synchronized Role acquire(boolean elevatedChild) {
        Path dir = AppPaths.localAppData();
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            System.err.println("[SingleInstance] Cannot create lock dir " + dir + ": " + e.getMessage());
            return Role.PRIMARY_UNLOCKED;
        }

        Path lockPath = dir.resolve(LOCK_NAME);
        try {
            lockFile = new RandomAccessFile(lockPath.toFile(), "rw");
            lockChannel = lockFile.getChannel();
        } catch (Exception e) {
            System.err.println("[SingleInstance] Cannot open lock file: " + e.getMessage());
            return Role.PRIMARY_UNLOCKED;
        }

        try {
            lock = lockChannel.tryLock();
        } catch (Exception e) {
            lock = null;
        }

        long deadline = System.currentTimeMillis() + ELEVATED_TAKEOVER_TIMEOUT_MS;
        while (lock == null && elevatedChild && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(ELEVATED_RETRY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
            try {
                lock = lockChannel.tryLock();
            } catch (Exception ignored) {
                lock = null;
            }
        }

        if (lock == null) {
            if (elevatedChild) {
                System.err.println("[SingleInstance] Lock still held after relaunch; proceeding unlocked.");
                closeLockResources();
                return Role.PRIMARY_UNLOCKED;
            }
            closeLockResources();
            return Role.SECONDARY;
        }

        startFocusServer(dir);
        return Role.PRIMARY;
    }

    /** Best-effort "bring to front" ping to the primary instance. */
    public static boolean signalPrimary() {
        Path portFile = AppPaths.localAppData().resolve(PORT_NAME);
        int port = -1;
        try {
            String raw = Files.readString(portFile, StandardCharsets.US_ASCII).trim();
            port = Integer.parseInt(raw);
        } catch (Exception e) {
            System.err.println("[SingleInstance] No primary port file: " + e.getMessage());
            return false;
        }
        try (Socket s = new Socket()) {
            s.connect(new java.net.InetSocketAddress(InetAddress.getLoopbackAddress(), port), SIGNAL_TIMEOUT_MS);
            s.setSoTimeout(SIGNAL_TIMEOUT_MS);
            s.getOutputStream().write((FOCUS_LINE + "\n").getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            return true;
        } catch (Exception e) {
            System.err.println("[SingleInstance] Focus signal failed: " + e.getMessage());
            return false;
        }
    }

    public static synchronized void release() {
        focusHandler.set(null);
        if (listener != null) {
            try {
                if (server != null) {
                    server.close();
                }
            } catch (Exception ignored) {
            }
            listener = null;
            server = null;
        }
        if (ownsPortFile) {
            try {
                Files.deleteIfExists(AppPaths.localAppData().resolve(PORT_NAME));
            } catch (Exception ignored) {
            }
            ownsPortFile = false;
        }
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (Exception ignored) {
        }
        lock = null;
        closeLockResources();
    }

    private static void startFocusServer(Path dir) {
        try {
            server = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
            Files.writeString(dir.resolve(PORT_NAME),
                    Integer.toString(server.getLocalPort()), StandardCharsets.US_ASCII);
            ownsPortFile = true;
        } catch (Exception e) {
            System.err.println("[SingleInstance] Focus socket unavailable: " + e.getMessage());
            server = null;
            return;
        }
        final ServerSocket srv = server;
        listener = new Thread(() -> {
            while (!srv.isClosed()) {
                try (Socket client = srv.accept()) {
                    client.setSoTimeout(SIGNAL_TIMEOUT_MS);
                    byte[] buf = new byte[16];
                    int read = client.getInputStream().read(buf);
                    if (read > 0
                            && new String(buf, 0, read, StandardCharsets.US_ASCII).contains(FOCUS_LINE)) {
                        Runnable handler = focusHandler.get();
                        if (handler != null) {
                            try {
                                handler.run();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Exception e) {
                    if (srv.isClosed()) {
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "SingleInstance-Focus");
        listener.setDaemon(true);
        listener.start();
    }

    private static void closeLockResources() {
        try {
            if (lockChannel != null && lockChannel.isOpen()) {
                lockChannel.close();
            }
        } catch (Exception ignored) {
        }
        lockChannel = null;
        try {
            if (lockFile != null) {
                lockFile.close();
            }
        } catch (Exception ignored) {
        }
        lockFile = null;
    }
}
