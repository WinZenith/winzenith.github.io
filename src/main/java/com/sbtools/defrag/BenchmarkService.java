package com.sbtools.defrag;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
        if (testSizeMB < 1 || testSizeMB > 1024) {
            throw new IOException("Invalid benchmark size: " + testSizeMB + " MB (allowed 1-1024).");
        }
        String letter = driveLetter.replace(":", "");
        if (letter.isBlank()) {
            throw new IOException("No drive selected for benchmark.");
        }
        // Critical fix: never fill a nearly-full drive to 0 bytes (OS freeze risk).
        // Require test size + 100 MB headroom before launching the script (script
        // double-checks via Get-PSDrive as well).
        try {
            java.io.File root = new java.io.File(letter + ":\\");
            long free = root.getFreeSpace();
            long required = (long) testSizeMB * 1024 * 1024 + 100L * 1024 * 1024;
            if (free > 0 && free < required) {
                throw new IOException("Insufficient free space on " + letter + ": needs ~"
                        + testSizeMB + " MB + 100 MB headroom.");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception ignored) {
            // Non-fatal: fall through to script-side check.
        }
        Path script = PowerShellScripts.resolve("benchmark-drive.ps1");
        // Cooperative cancel: script polls StopFlagPath, while runStreaming also kills
        // the process tree. The flag file must NOT exist until cancel is requested
        // (same blocker pattern as free-space wipe).
        java.io.File stopFlag = java.io.File.createTempFile("winzenith-bench-stop-", ".flag");
        stopFlag.deleteOnExit();
        try {
            java.nio.file.Files.deleteIfExists(stopFlag.toPath());
        } catch (Exception ignored) {
            stopFlag.delete();
        }
        Thread stopSignaler = new Thread(() -> {
            try {
                while (true) {
                    if (cancelled != null && cancelled.get()) {
                        try {
                            if (!stopFlag.exists()) stopFlag.createNewFile();
                        } catch (Exception ignored) {
                        }
                        break;
                    }
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            } catch (Exception ignored) {
            }
        }, "bench-stop-signaler");
        stopSignaler.setDaemon(true);
        stopSignaler.start();
        Consumer<String> lineHandler = line -> {
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
        };
        ProcessResult result = null;
        IOException pendingIo = null;
        List<List<String>> candidates = List.of(
                ProcessRunner.powershellScript(script.toString(), letter, String.valueOf(testSizeMB), stopFlag.getAbsolutePath()),
                ProcessRunner.pwshScript(script.toString(), letter, String.valueOf(testSizeMB), stopFlag.getAbsolutePath())
        );
        try {
        try {
        for (List<String> cmd : candidates) {
            try {
                result = processRunner.runStreaming(cmd, lineHandler, null, cancelled);
                pendingIo = null;
                break;
            } catch (IOException e) {
                String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                boolean missing = msg.contains("cannot run program") || msg.contains("no such file") || msg.contains("error=2");
                if (missing && !cmd.get(0).equals("pwsh.exe")) {
                    AppLogger.warning("Benchmark: powershell.exe not found, trying pwsh.exe: " + e.getMessage());
                    pendingIo = e;
                    continue;
                }
                throw e;
            }
        }
        } finally {
            try {
                stopSignaler.interrupt();
            } catch (Exception ignored) {
            }
        }
        try {
            sweepBenchmarkLeftovers(letter);
        } catch (Exception ignored) {
        }
        try {
            stopFlag.delete();
        } catch (Exception ignored) {
        }
        if (cancelled != null && cancelled.get()) {
            throw new CancellationException("Benchmark cancelled by user");
        }
        if (pendingIo != null && result == null) throw pendingIo;
        if (result == null) throw new IOException("No PowerShell executable available for benchmark");

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
            BenchmarkResult parsed = JsonMapper.mapper().readValue(lastLine, BenchmarkResult.class);
            // Critical fix: script always emits a result object even on failure
            // (success=false). Previously Java ignored that flag and treated any
            // parsed object as success unless the process exit code was non-zero.
            if (!parsed.isSuccess() && (cancelled == null || !cancelled.get())) {
                String detail = parsed.getMessage() != null && !parsed.getMessage().isBlank()
                        ? parsed.getMessage() : "benchmark reported failure";
                throw new IOException("Benchmark failed: " + detail);
            }
            return parsed;
        } catch (IOException e) {
            if (e.getMessage().startsWith("No valid") || e.getMessage().startsWith("Benchmark failed")) throw e;
            AppLogger.error("Failed to parse benchmark result", e);
            throw new IOException("Failed to parse benchmark: " + e.getMessage(), e);
        }
        } finally {
            // Guarantee no orphaned multi-MB temp dirs/files and no leaked stop-flag,
            // even when runStreaming throws (cancel/timeout) before the inline cleanup above.
            try {
                stopSignaler.interrupt();
            } catch (Exception ignored) {
            }
            try {
                sweepBenchmarkLeftovers(letter);
            } catch (Exception ignored) {
            }
            try {
                stopFlag.delete();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * Best-effort cleanup of benchmark temp artifacts. The PowerShell script cleans
     * up in its finally block, but a forcible kill (cancel/timeout) can bypass it.
     */
    private static void sweepBenchmarkLeftovers(String letter) {
        try {
            java.io.File root = new java.io.File(letter + ":\\");
            java.io.File[] stale = root.listFiles((dir, name) ->
                    name.startsWith(".winzenith-bench-") || name.equals("__winzenith_bench__"));
            if (stale != null) {
                for (java.io.File f : stale) {
                    try {
                        deleteRecursively(f.toPath());
                    } catch (Exception ignored) {
                    }
                }
            }
            java.io.File legacy = new java.io.File(root, "Users\\Public\\__winzenith_bench__\\bench_test.tmp");
            try {
                java.nio.file.Files.deleteIfExists(legacy.toPath());
            } catch (Exception ignored) {
            }
            java.io.File legacyDir = new java.io.File(root, "Users\\Public\\__winzenith_bench__");
            try {
                String[] remaining = legacyDir.list();
                if (remaining != null && remaining.length == 0) {
                    java.nio.file.Files.deleteIfExists(legacyDir.toPath());
                }
            } catch (Exception ignored) {
            }
        } catch (Exception ignored) {
        }
    }

    private static void deleteRecursively(java.nio.file.Path path) throws IOException {
        if (!java.nio.file.Files.exists(path)) return;
        try (var walk = java.nio.file.Files.walk(path)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            java.nio.file.Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        }
    }
}
