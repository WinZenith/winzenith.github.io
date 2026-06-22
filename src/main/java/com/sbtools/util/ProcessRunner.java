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
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            joinReaders(stdoutReader, stderrReader);
            throw new IOException("Process timed out after " + timeoutSeconds + "s");
        }
        joinReaders(stdoutReader, stderrReader);
        String stdout = stdoutBuf.toString(StandardCharsets.UTF_8);
        String stderr = stderrBuf.toString(StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    /**
     * Runs a command, reading stdout line by line in real-time.
     * Each line is passed to lineCallback. If a line is valid JSON with a "progress" (0-100)
     * field, it is also passed to progressCallback as a 0.0-1.0 double.
     * Checks cancelled between lines; if true, destroys the process and throws CancellationException.
     */
    public ProcessResult runStreaming(List<String> command, Consumer<String> lineCallback,
                             Consumer<Double> progressCallback, AtomicBoolean cancelled)
            throws IOException, CancellationException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        AppLogger.info("Running (streaming): " + String.join(" ", command));
        Process process = pb.start();
        // Track process so it can be terminated on application shutdown if still running
        try { ProcessManager.register(process); } catch (Throwable ignored) {}
        StringBuilder outBuf = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled != null && cancelled.get()) {
                    process.destroyForcibly();
                    throw new CancellationException("Operation cancelled by user");
                }
                if (lineCallback != null) {
                    lineCallback.accept(line);
                }
                outBuf.append(line).append(System.lineSeparator());
                if (progressCallback != null) {
                    try {
                        var tree = JsonMapper.mapper().readTree(line);
                        if (tree.has("progress")) {
                            double pct = tree.get("progress").asDouble(0);
                            progressCallback.accept(Math.min(1.0, Math.max(0, pct / 100.0)));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (IOException e) {
            if (cancelled != null && cancelled.get()) {
                process.destroyForcibly();
                throw new CancellationException("Operation cancelled by user");
            }
            throw e;
        }

        try {
            boolean finished = process.waitFor(defaultTimeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("Process timed out after " + defaultTimeoutSeconds + "s");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new CancellationException("Operation cancelled by user");
        }

        return new ProcessResult(process.exitValue(), outBuf.toString(), "");
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

    private static void joinReaders(Thread... readers) throws InterruptedException {
        for (Thread reader : readers) {
            reader.join(TimeUnit.SECONDS.toMillis(STREAM_JOIN_SECONDS));
        }
    }

    public static List<String> powershellScript(String scriptPath, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("powershell");
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
}
