package com.sbtools.util;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class ProcessRunner {

    private static final long STREAM_JOIN_SECONDS = 10;

    private final long defaultTimeoutSeconds;

    public ProcessRunner() {
        this(600);
    }

    public ProcessRunner(long defaultTimeoutSeconds) {
        this.defaultTimeoutSeconds = defaultTimeoutSeconds;
    }

    public ProcessResult run(List<String> command) throws IOException, InterruptedException {
        return run(command, defaultTimeoutSeconds);
    }

    public ProcessResult run(List<String> command, long timeoutSeconds) throws IOException, InterruptedException {
        return run(command, timeoutSeconds, null);
    }

    public ProcessResult run(List<String> command, AtomicBoolean cancelled) throws IOException, InterruptedException {
        return run(command, defaultTimeoutSeconds, cancelled);
    }

    public ProcessResult run(List<String> command, long timeoutSeconds, AtomicBoolean cancelled) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(false);
        AppLogger.info("Running: " + String.join(" ", command));
        Process process = pb.start();
        // Track process so it can be terminated on application shutdown if still running
        try {
            ProcessManager.register(process);
        } catch (Throwable ignored) {
        }
        ByteArrayOutputStream stdoutBuf = new ByteArrayOutputStream();
        ByteArrayOutputStream stderrBuf = new ByteArrayOutputStream();
        Thread stdoutReader = startStreamReader(process.getInputStream(), stdoutBuf);
        Thread stderrReader = startStreamReader(process.getErrorStream(), stderrBuf);
        try {
            long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
            while (true) {
                if (cancelled != null && cancelled.get()) {
                    killProcessTree(process);
                    joinReaders(stdoutReader, stderrReader);
                    throw new CancellationException("Operation cancelled by user");
                }
                long remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
                if (remainingMs <= 0) {
                    killProcessTree(process);
                    joinReaders(stdoutReader, stderrReader);
                    throw new IOException("Process timed out after " + timeoutSeconds + "s");
                }
                boolean finished = process.waitFor(Math.min(100, remainingMs), TimeUnit.MILLISECONDS);
                if (finished) {
                    break;
                }
            }
            joinReaders(stdoutReader, stderrReader);
        } catch (InterruptedException e) {
            killProcessTree(process);
            joinReaders(stdoutReader, stderrReader);
            Thread.currentThread().interrupt();
            throw e;
        } catch (CancellationException e) {
            throw e;
        }
        String stdout = stdoutBuf.toString(StandardCharsets.UTF_8);
        String stderr = stderrBuf.toString(StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    /**
     * Runs a command, reading stdout line by line in real-time.
     * Each line is passed to lineCallback. If a line is valid JSON with a "progress" (0-100)
     * field, it is also passed to progressCallback as a 0.0-1.0 double.
     * The process is polled for cancellation and an end-to-end deadline, so cancel/timeout
     * work even while the child process emits no output.
     *
     * @param command          the command to run
     * @param lineCallback     callback invoked for each output line (may be null)
     * @param progressCallback callback invoked with progress values (may be null)
     * @param cancelled        flag checked while the process runs; when set, the process is
     *                         destroyed and a CancellationException is thrown (may be null)
     */
    public ProcessResult runStreaming(List<String> command, Consumer<String> lineCallback,
                             Consumer<Double> progressCallback, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        return runStreaming(command, lineCallback, progressCallback, cancelled, defaultTimeoutSeconds);
    }

    /**
     * Same as {@link #runStreaming(List, Consumer, Consumer, AtomicBoolean)} but with an
     * explicit end-to-end timeout. If the process is still running after timeoutSeconds, it
     * is forcibly destroyed and an IOException is thrown.
     */
    public ProcessResult runStreaming(List<String> command, Consumer<String> lineCallback,
                             Consumer<Double> progressCallback, AtomicBoolean cancelled,
                             long timeoutSeconds)
            throws IOException, CancellationException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        AppLogger.info("Running (streaming): " + String.join(" ", command));
        Process process = pb.start();
        // Track process so it can be terminated on application shutdown if still running
        try { ProcessManager.register(process); } catch (Throwable ignored) {}
        StringBuilder outBuf = new StringBuilder();

        Thread reader = new Thread(() -> {
            try (BufferedReader bufferedReader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    synchronized (outBuf) {
                        outBuf.append(line).append(System.lineSeparator());
                    }
                    try {
                        if (lineCallback != null) {
                            lineCallback.accept(line);
                        }
                        if (progressCallback != null) {
                            handleProgress(line, progressCallback);
                        }
                    } catch (Throwable t) {
                        AppLogger.warning("Stream callback error: " + t.getMessage());
                    }
                }
            } catch (IOException ignored) {
                // Stream closed because the process was destroyed
            }
        }, "process-stream-reader");
        reader.setDaemon(true);
        reader.start();

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.max(1, timeoutSeconds));
        try {
            while (process.isAlive()) {
                if (cancelled != null && cancelled.get()) {
                    killProcessTree(process);
                    throw new CancellationException("Operation cancelled by user");
                }
                if (System.nanoTime() > deadlineNanos) {
                    killProcessTree(process);
                    throw new IOException("Process timed out after " + timeoutSeconds + "s");
                }
                Thread.sleep(100);
            }
            reader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_SECONDS));
        } catch (InterruptedException e) {
            killProcessTree(process);
            try {
                reader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_SECONDS));
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            throw new CancellationException("Operation cancelled by user");
        }

        String output;
        synchronized (outBuf) {
            output = outBuf.toString();
        }
        return new ProcessResult(process.exitValue(), output, "");
    }

    private static void handleProgress(String line, Consumer<Double> progressCallback) {
        boolean jsonProgressFired = false;
        try {
            var tree = JsonMapper.mapper().readTree(line);
            if (tree.has("progress")) {
                double pct = tree.get("progress").asDouble(0);
                progressCallback.accept(Math.min(1.0, Math.max(0, pct / 100.0)));
                jsonProgressFired = true;
            }
        } catch (Exception ignored) {
        }
        if (!jsonProgressFired) {
            String trimmed = line.trim();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%").matcher(trimmed);
            if (m.find()) {
                try {
                    double pct = Double.parseDouble(m.group(1));
                    progressCallback.accept(Math.min(1.0, Math.max(0, pct / 100.0)));
                } catch (NumberFormatException ignored) {
                }
            } else if (trimmed.toLowerCase().startsWith("downloading") || trimmed.toLowerCase().startsWith("installing")) {
                progressCallback.accept(-1.0);
            }
        }
    }

    private static Thread startStreamReader(InputStream stream, ByteArrayOutputStream target) {
        Thread reader = new Thread(() -> {
            try {
                stream.transferTo(target);
            } catch (IOException ignored) {
            }
        }, "process-stream-reader");
        reader.setDaemon(true);
        reader.start();
        return reader;
    }

    /**
     * Terminates a process and, on Windows, its entire process tree (so child processes
     * such as winget launched via cmd.exe are killed as well). Falls back to
     * {@link Process#destroyForcibly()} when taskkill is unavailable or fails.
     */
    private static void killProcessTree(Process process) {
        if (process == null) return;
        if (AppPaths.isWindows()) {
            try {
                long pid = process.pid();
                if (pid > 0) {
                    Process kill = new ProcessBuilder(
                            "taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                            .redirectErrorStream(true)
                            .start();
                    kill.waitFor(5, TimeUnit.SECONDS);
                    if (!process.isAlive()) return;
                }
            } catch (Throwable ignored) {
            }
        }
        process.destroyForcibly();
    }

    private static void joinReaders(Thread... readers) throws InterruptedException {
        for (Thread reader : readers) {
            reader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_SECONDS));
        }
    }

    public static List<String> powershellScript(String scriptPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-File");
        cmd.add(scriptPath);
        for (String arg : args) {
            cmd.add(arg);
        }
        return cmd;
    }

    public static List<String> pwshScript(String scriptPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("pwsh.exe");
        cmd.add("-NoProfile");
        cmd.add("-ExecutionPolicy");
        cmd.add("Bypass");
        cmd.add("-File");
        cmd.add(scriptPath);
        for (String arg : args) {
            cmd.add(arg);
        }
        return cmd;
    }

    /**
     * Returns the best available PowerShell executable command for the given script.
     * Tries powershell.exe first, then pwsh.exe if the former is not found.
     */
    public static List<String> bestPowerShellScript(String scriptPath, String... args) {
        // Prefer powershell.exe for compatibility; caller may fallback to pwsh on IOException
        return powershellScript(scriptPath, args);
    }

    /**
     * Escapes a string for safe use inside a PowerShell single-quoted string literal.
     * Single quotes within the value are doubled, which is PowerShell's escape mechanism.
     */
    public static String psQuote(String value) {
        if (value == null) return "''";
        return "'" + value.replace("'", "''") + "'";
    }
}
