package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PowerShellStartupScriptsTest {

    @Test
    void getStartupDetails_excludesRegistrationTrigger() throws Exception {
        String script = readResource("/powershell/get-startup-details.ps1");
        assertFalse(script.contains("MSFT_TaskRegistrationTrigger"));
        assertTrue(script.contains("MSFT_TaskLogonTrigger"));
        assertTrue(script.contains("MSFT_TaskBootTrigger"));
    }

    @Test
    void setStartupTaskState_enablesTriggersBeforeTask() throws Exception {
        String script = readResource("/powershell/set-startup-task-state.ps1");
        int setIdx = script.indexOf("Set-ScheduledTask");
        int enableIdx = script.indexOf("Enable-ScheduledTask");
        assertTrue(setIdx > 0 && enableIdx > setIdx, "Enable-ScheduledTask must run after trigger updates");
        assertFalse(script.contains("MSFT_TaskRegistrationTrigger"));
    }

    private static String readResource(String path) throws Exception {
        try (var in = PowerShellStartupScriptsTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
