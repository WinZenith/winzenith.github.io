package com.sbtools.defrag;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

public class BenchmarkService {

    private static final long TIMEOUT_SECONDS = 300;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);

    public BenchmarkResult benchmark(String driveLetter, int testSizeMB,
                                     Consumer<String> statusCallback, AtomicBoolean cancelled)
            throws IOException, InterruptedException, CancellationException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("Benchmark is only available on Windows.");
        }
        String letter = driveLetter.replace(":", "");
        Path script = PowerShellScripts.resolve("benchmark-drive.ps1");
        ProcessResult result = processRunner.runStreaming(
                ProcessRunner.powershellScript(script.toString(), letter, String.valueOf(testSizeMB)),
                line -> {
                    if (statusCallback != null) {
                        try {
                            var tree = JsonMapper.mapper().readTree(line);
                            if (tree.has("phase")) {
                                String phase = tree.get("phase").asText();
                                String msg = switch (phase) {
                                    case "write" -> "Sequential write test...";
                                    case "read" -> "Sequential read test...";
                                    case "random_read" -> "Random read test...";
                                    case "done" -> "Benchmark complete.";
                                    case "error" -> tree.has("message") ? tree.get("message").asText() : "Error";
                                    default -> "Running...";
                                };
                                statusCallback.accept(msg);
                            }
                        } catch (Exception ignored) {
                        }
                    }
                },
                null,
                cancelled);

        if (!result.success() && !cancelled.get()) {
            throw new IOException("Benchmark failed: " + result.combinedOutput());
        }

        String json = result.stdout().trim();
        if (json.isBlank()) {
            throw new IOException("No benchmark result returned.");
        }

        try {
            String lastLine = "";
            for (String line : json.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith("{") && trimmed.contains("\"success\"")) {
                    lastLine = trimmed;
                }
            }
            if (lastLine.isEmpty()) {
                throw new IOException("No valid benchmark result found in output.");
            }
            return JsonMapper.mapper().readValue(lastLine, BenchmarkResult.class);
        } catch (IOException e) {
            if (e.getMessage().startsWith("No valid")) throw e;
            AppLogger.error("Failed to parse benchmark result", e);
            throw new IOException("Failed to parse benchmark: " + e.getMessage(), e);
        }
    }
}
