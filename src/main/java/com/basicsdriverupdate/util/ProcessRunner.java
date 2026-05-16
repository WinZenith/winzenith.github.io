package com.basicsdriverupdate.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

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

    private static Thread startStreamReader(InputStream stream, ByteArrayOutputStream target) {
        Thread reader = new Thread(() -> {
            try {
                stream.transferTo(target);
            } catch (IOException ignored) {
                // Process may be destroyed while streams are still open.
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
