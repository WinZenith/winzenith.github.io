package com.sbtools.startup;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

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
    }

    @Test
    void requiresAdminForBackup_hklmAndSystemTask() {
        StartupService.StartupBackupEntry reg = new StartupService.StartupBackupEntry();
        reg.setId("11111111-1111-1111-1111-111111111111");
        reg.setType("Registry");
        reg.setHive("HKLM");
        reg.setKeyPath(StartupConstants.REG_RUN);
        reg.setValueName("X");
        reg.setCommand("C:\\x.exe");
        assertTrue(StartupSafety.requiresAdminForBackup(reg));

        StartupService.StartupBackupEntry task = new StartupService.StartupBackupEntry();
        task.setId("22222222-2222-2222-2222-222222222222");
        task.setType("Task");
        task.setName("T");
        task.setTaskPath("\\Microsoft\\Windows\\T");
        task.setBackupXmlName("task.xml");
        assertTrue(StartupSafety.requiresAdminForBackup(task));
    }

    @Test
    void approvedBytes_preserveTimestampTail() {
        byte[] existing = new byte[]{0x02, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, (byte) 0x88, (byte) 0x99, (byte) 0xAA, (byte) 0xBB};
        byte[] disabled = StartupConstants.withStatePreservingTimestamp(existing, false);
        assertEquals(0x03, disabled[0] & 0xFF);
        for (int i = 1; i < 12; i++) {
            assertEquals(existing[i], disabled[i], "byte " + i + " must be preserved");
        }
    }

    @Test
    void fallbackPaths_mirrorLegacyMapping() {
        assertArrayEquals(
                new String[]{StartupConstants.REG_RUN, StartupConstants.REG_STARTUP_APPROVED},
                StartupService.fallbackPathsForLocation("HKCU Run"));
        assertArrayEquals(
                new String[]{StartupConstants.REG_WOW6432_RUN, StartupConstants.REG_WOW6432_APPROVED},
                StartupService.fallbackPathsForLocation("HKLM (32-bit) Run"));
    }

    @Test
    void toApprovedPath_mapping() {
        assertEquals(StartupConstants.REG_STARTUP_APPROVED,
                StartupConstants.toApprovedPath(StartupConstants.REG_RUN));
        assertEquals(StartupConstants.REG_WOW6432_APPROVED,
                StartupConstants.toApprovedPath(StartupConstants.REG_WOW6432_RUN));
    }
}
