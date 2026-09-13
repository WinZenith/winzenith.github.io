package com.sbtools.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WindowsUpdateInstallResultTest {

    @Test
    void parsesSuccessWithProgressPrefix() {
        String stdout = "Searching...\n{\"resultCode\":2,\"installed\":2,\"rebootRequired\":false}";
        WindowsUpdateInstallResult.Parsed p = WindowsUpdateInstallResult.parse(stdout);
        assertTrue(p.success());
        assertFalse(p.rebootRequired());
    }

    @Test
    void parsesSuccessWithReboot() {
        String stdout = "{\"resultCode\":2,\"installed\":2,\"rebootRequired\":true}";
        WindowsUpdateInstallResult.Parsed p = WindowsUpdateInstallResult.parse(stdout);
        assertTrue(p.success());
        assertTrue(p.rebootRequired());
    }

    @Test
    void failsOnMalformedJson() {
        assertFalse(WindowsUpdateInstallResult.parse("not json").success());
        assertFalse(WindowsUpdateInstallResult.parse("{\"resultCode\":2}").success());
    }

    @Test
    void failsWhenOverallOrPerUpdateNotSuccess() {
        assertFalse(WindowsUpdateInstallResult.parse(
                "{\"resultCode\":4,\"installed\":2,\"rebootRequired\":true}").success());
        assertFalse(WindowsUpdateInstallResult.parse(
                "{\"resultCode\":2,\"installed\":4,\"rebootRequired\":false}").success());
    }
}
