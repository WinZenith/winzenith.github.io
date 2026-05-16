package com.basicsdriverupdate.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class ProcessRunner {

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
        boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IOException("Process timed out after " + timeoutSeconds + "s");
        }
        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), stdout, stderr);
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
