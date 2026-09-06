package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for startup safety policy + constants + pure helpers.
 * No registry, no PowerShell, no JavaFX — pure logic only.
 */
class StartupSafetyTest {

    @Test
    void criticalServices_detectedCaseInsensitive() {
        assertTrue(StartupSafety.isCriticalService("RpcSs"));
        assertTrue(StartupSafety.isCriticalService("SCHEDULE"));
        assertTrue(StartupSafety.isCriticalService(" windefend "));
        assertFalse(StartupSafety.isCriticalService("spooler"));
        assertFalse(StartupSafety.isCriticalService(""));
        assertFalse(StartupSafety.isCriticalService(null));
    }

    @Test
    void isCriticalDisable_onlyEnabledCriticalServices() {
        StartupItem critical = new StartupItem("RpcSs", "Microsoft", "C:\\x.exe", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertTrue(StartupSafety.isCriticalDisable(critical));

        StartupItem disabledCritical = new StartupItem("RpcSs", "Microsoft", "C:\\x.exe", false,
                "Start Type: Disabled", "", "", "", StartupItemType.SERVICE, "Disabled");
        assertFalse(StartupSafety.isCriticalDisable(disabledCritical));

        StartupItem nonCritical = new StartupItem("Spooler", "Microsoft", "C:\\x.exe", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertFalse(StartupSafety.isCriticalDisable(nonCritical));

        StartupItem registry = new StartupItem("MyApp", "Me", "C:\\app.exe", true,
                "HKCU Run", "MyApp", "", "", StartupItemType.REGISTRY, null);
        assertFalse(StartupSafety.isCriticalDisable(registry));
    }

    @Test
    void describeRisk_returnsWarningOnlyForCriticalDisable() {
        StartupItem critical = new StartupItem("RpcSs", "Microsoft", "C:\\x.exe", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertNotNull(StartupSafety.describeRisk(critical));
        assertTrue(StartupSafety.describeRisk(critical).contains("RpcSs"));

        StartupItem safe = new StartupItem("Spooler", "Microsoft", "C:\\x.exe", true,
                "Start Type: Automatic", "", "", "", StartupItemType.SERVICE, "Automatic");
        assertNull(StartupSafety.describeRisk(safe));
    }

    @Test
    void requiresAdmin_serviceAndHklmAndCommon() {
        StartupItem svc = new StartupItem("X", "D", "C:\\x", true,
                "Start Type: Manual", "", "", "", StartupItemType.SERVICE, "Manual");
        assertTrue(StartupSafety.requiresAdmin(svc));

        StartupItem hklm = new StartupItem("A", "P", "C:\\a", true,
                "HKLM Run", "A", "", "", StartupItemType.REGISTRY, null);
        assertTrue(StartupSafety.requiresAdmin(hklm));

        StartupItem common = new StartupItem("B", "P", "C:\\b", true,
                "Startup Folder (Common)", "B", "C:\\b", "", StartupItemType.REGISTRY, null);
        assertTrue(StartupSafety.requiresAdmin(common));

        StartupItem hkcu = new StartupItem("C", "P", "C:\\c", true,
                "HKCU Run", "C", "", "", StartupItemType.REGISTRY, null);
        assertFalse(StartupSafety.requiresAdmin(hkcu));
    }

    @Test
    void isSystemTask_detectsMicrosoftAndWindowsPaths() {
        StartupItem sys = new StartupItem("T", "P", "C:\\x", true,
                "Scheduled Task", "", "", "\\Microsoft\\Windows\\Update\\T", StartupItemType.TASK, null);
        assertTrue(StartupSafety.isSystemTask(sys));

        StartupItem user = new StartupItem("T", "P", "C:\\x", true,
                "Scheduled Task", "", "", "\\MyVendor\\T", StartupItemType.TASK, null);
        assertFalse(StartupSafety.isSystemTask(user));

        StartupItem notTask = new StartupItem("A", "P", "C:\\a", true,
                "HKCU Run", "A", "", "", StartupItemType.REGISTRY, null);
        assertFalse(StartupSafety.isSystemTask(notTask));
    }

    @Test
    void approvedBytes_preserveTimestampTail() {
        byte[] existing = new byte[]{0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB};
        byte[] disabled = StartupConstants.withStatePreservingTimestamp(existing, false);
        assertEquals(0x03, disabled[0] & 0xFF);
        // Tail preserved
        for (int i = 1; i < 12; i++) {
            assertEquals(existing[i], disabled[i], "byte " + i + " must be preserved");
        }
        byte[] enabled = StartupConstants.withStatePreservingTimestamp(disabled, true);
        assertEquals(0x02, enabled[0] & 0xFF);
        for (int i = 1; i < 12; i++) {
            assertEquals(existing[i], enabled[i], "byte " + i + " must survive round-trip");
        }
    }

    @Test
    void approvedBytes_nullFallsBackToTemplate() {
        byte[] en = StartupConstants.withStatePreservingTimestamp(null, true);
        assertEquals(0x02, en[0] & 0xFF);
        assertEquals(12, en.length);
        byte[] dis = StartupConstants.withStatePreservingTimestamp(null, false);
        assertEquals(0x03, dis[0] & 0xFF);
    }

    @Test
    void fallbackPaths_mirrorLegacyMapping() {
        assertArrayEquals(
                new String[]{StartupConstants.REG_RUN, StartupConstants.REG_STARTUP_APPROVED},
                StartupService.fallbackPathsForLocation("HKCU Run"));
        assertArrayEquals(
                new String[]{StartupConstants.REG_RUN_ONCE, StartupConstants.REG_STARTUP_APPROVED_RUNONCE},
                StartupService.fallbackPathsForLocation("HKLM RunOnce"));
        assertArrayEquals(
                new String[]{StartupConstants.REG_RUN_DISABLED, StartupConstants.REG_STARTUP_APPROVED},
                StartupService.fallbackPathsForLocation("HKCU Run (Disabled)"));
        assertArrayEquals(
                new String[]{StartupConstants.REG_WOW6432_RUN, StartupConstants.REG_WOW6432_APPROVED},
                StartupService.fallbackPathsForLocation("HKLM (32-bit) Run"));
        assertArrayEquals(
                new String[]{StartupConstants.REG_WOW6432_RUN_ONCE, StartupConstants.REG_WOW6432_APPROVED_RUNONCE},
                StartupService.fallbackPathsForLocation("HKCU (32-bit) RunOnce"));
    }

    @Test
    void toApprovedPath_mapping() {
        assertEquals(StartupConstants.REG_STARTUP_APPROVED,
                StartupConstants.toApprovedPath(StartupConstants.REG_RUN));
        assertEquals(StartupConstants.REG_STARTUP_APPROVED_RUNONCE,
                StartupConstants.toApprovedPath(StartupConstants.REG_RUN_ONCE));
        assertEquals(StartupConstants.REG_WOW6432_APPROVED,
                StartupConstants.toApprovedPath(StartupConstants.REG_WOW6432_RUN));
        assertEquals(StartupConstants.REG_WOW6432_APPROVED_RUNONCE,
                StartupConstants.toApprovedPath(StartupConstants.REG_WOW6432_RUN_ONCE));
    }
}
