package com.sbtools.browserext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Cached process probe for the extensions tab. Uses
 * {@code browser-process-snapshot.ps1} (Win32_Process command lines) so
 * Chrome Stable vs Canary can be distinguished; falls back to {@code tasklist}
 * image names only when the script fails ({@link Snapshot#detailedOk()} false).
 */
public final class BrowserProcessProbe {

    private static final long TTL_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static volatile Snapshot cachedSnapshot = Snapshot.empty();
    private static volatile long cachedAt = 0;
    private static volatile boolean lastProbeOk = false;

    private BrowserProcessProbe() {
    }

    public record ProcessEntry(String name, String executablePath, String commandLine) {
    }

    public record Snapshot(List<ProcessEntry> entries, boolean detailedOk, Set<String> runningExeNames,
                           boolean probeOk) {
        static Snapshot empty() {
            return new Snapshot(List.of(), false, Set.of(), false);
        }
    }

    public static synchronized Snapshot snapshot() {
        long now = System.nanoTime();
        if (now - cachedAt < TTL_NANOS) return cachedSnapshot;
        return snapshotFresh();
    }

    public static synchronized Snapshot snapshotFresh() {
        Snapshot fresh = querySnapshot();
        cachedSnapshot = fresh;
        cachedAt = System.nanoTime();
        lastProbeOk = fresh.probeOk();
        return fresh;
    }

    /** Returns lower-cased running image names (legacy / fallback). */
    public static synchronized Set<String> runningExes() {
        return snapshot().runningExeNames();
    }

    public static synchronized Set<String> runningExesFresh() {
        return snapshotFresh().runningExeNames();
    }

    public static boolean lastProbeOk() {
        return lastProbeOk;
    }

    private static Snapshot querySnapshot() {
        try {
            Path script = PowerShellScripts.resolve("browser-process-snapshot.ps1");
            List<String> cmd;
            try {
                cmd = ProcessRunner.powershellScript(script.toString());
            } catch (Exception e) {
                cmd = ProcessRunner.bestPowerShellScript(script.toString());
            }
            ProcessResult pr;
            try {
                pr = new ProcessRunner(15).run(cmd, 15, null);
            } catch (IOException ioe) {
                if (cmd.get(0).equalsIgnoreCase("powershell.exe")) {
                    cmd = ProcessRunner.pwshScript(script.toString());
                    pr = new ProcessRunner(15).run(cmd, 15, null);
                } else {
                    throw ioe;
                }
            }
            String stdout = pr.stdout().trim();
            if (stdout.startsWith("\uFEFF")) stdout = stdout.substring(1);
            if (pr.exitCode() != 0 || stdout.isEmpty()) {
                AppLogger.warning("browser-process-snapshot exit=" + pr.exitCode()
                        + " stderr=" + pr.stderr().trim());
                return fallbackTasklist();
            }
            List<ProcessEntry> entries = parseEntries(stdout);
            Set<String> exes = new HashSet<>();
            for (ProcessEntry e : entries) {
                if (e.name() != null && !e.name().isBlank()) {
                    exes.add(e.name().toLowerCase(Locale.ROOT));
                }
            }
            return new Snapshot(List.copyOf(entries), true, Set.copyOf(exes), true);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return fallbackTasklist();
        } catch (Exception e) {
            AppLogger.warning("browser-process-snapshot failed: " + e.getMessage());
            return fallbackTasklist();
        }
    }

    private static List<ProcessEntry> parseEntries(String stdout) throws IOException {
        if ("[]".equals(stdout.trim())) return List.of();
        List<ProcessEntry> raw = MAPPER.readValue(stdout, new TypeReference<List<ProcessEntry>>() {});
        List<ProcessEntry> out = new ArrayList<>(raw.size());
        for (ProcessEntry e : raw) {
            out.add(new ProcessEntry(
                    e.name() != null ? e.name() : "",
                    e.executablePath() != null ? e.executablePath() : "",
                    e.commandLine() != null ? e.commandLine() : ""));
        }
        return out;
    }

    private static Snapshot fallbackTasklist() {
        TasklistOutcome o = queryTasklistImageNames();
        return new Snapshot(List.of(), false, o.exes, o.ok);
    }

    static Set<String> parseTasklistImageNames(String output) {
        Set<String> out = new HashSet<>();
        if (output == null || output.isBlank()) return out;
        for (String line : output.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.startsWith("\"")) {
                int end = trimmed.indexOf('"', 1);
                if (end > 1) out.add(trimmed.substring(1, end).toLowerCase(Locale.ROOT));
            } else {
                out.add(trimmed.split("[,\\s]")[0].toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    /** Empty/timeout/failed tasklist is unreliable — never inherit a prior probeOk. */
    static boolean tasklistProbeOk(String output, boolean timedOut) {
        return !timedOut && output != null && !output.isBlank();
    }

    private record TasklistOutcome(Set<String> exes, boolean ok) {
    }

    private static TasklistOutcome queryTasklistImageNames() {
        Process p = null;
        boolean timedOut = false;
        try {
            p = new ProcessBuilder("tasklist", "/FO", "CSV", "/NH")
                    .redirectErrorStream(true).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (p.isAlive()) {
                if (System.nanoTime() > deadline) {
                    timedOut = true;
                    p.destroyForcibly();
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    p.destroyForcibly();
                    return new TasklistOutcome(Set.of(), false);
                }
            }
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!tasklistProbeOk(output, timedOut)) {
                return new TasklistOutcome(Set.of(), false);
            }
            return new TasklistOutcome(parseTasklistImageNames(output), true);
        } catch (Exception ignored) {
            return new TasklistOutcome(Set.of(), false);
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Exception ignored) {
                }
                try {
                    if (p.isAlive()) p.destroyForcibly();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
