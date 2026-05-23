package com.basicsdriverupdate.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class ProcessRunnerTest {

    @Test
    void drainsLargeStdoutWhileWaiting() throws Exception {
        List<String> cmd = List.of(
                "powershell", "-NoProfile", "-Command",
                "1..5000 | ForEach-Object { 'line-' + $_ }"
        );
        ProcessRunner runner = new ProcessRunner(30);
        ProcessResult result = runner.run(cmd);
        assertEquals(0, result.exitCode());
        long lines = result.stdout().lines().filter(l -> !l.isBlank()).count();
        assertTrue(lines >= 5000, "expected at least 5000 lines, got " + lines);
    }

    @Test
    void enumerateDevicesScriptCompletes() throws Exception {
        var script = java.nio.file.Path.of("src/main/resources/powershell/enumerate-devices.ps1");
        ProcessRunner runner = new ProcessRunner(30);
        ProcessResult result = runner.run(ProcessRunner.powershellScript(script.toString()));
        assertTrue(result.success(), result.combinedOutput());
        assertTrue(result.stdout().length() > 100, "enumeration JSON should not be empty");
    }
}
