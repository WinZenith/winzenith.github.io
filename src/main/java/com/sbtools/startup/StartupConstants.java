package com.sbtools.startup;

import com.sun.jna.platform.win32.WinReg;

public final class StartupConstants {

    private StartupConstants() {}

    public static final String REG_RUN = "Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String REG_RUN_ONCE = "Software\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    public static final String REG_RUN_DISABLED = "Software\\Microsoft\\Windows\\CurrentVersion\\RunDisabled";
    public static final String REG_STARTUP_APPROVED = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    public static final String REG_STARTUP_APPROVED_RUNONCE = "Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\RunOnce";
    public static final String REG_WOW6432_RUN = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Run";
    public static final String REG_WOW6432_RUN_ONCE = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\RunOnce";
    public static final String REG_WOW6432_RUN_DISABLED = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\RunDisabled";
    public static final String REG_WOW6432_APPROVED = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\Run";
    public static final String REG_WOW6432_APPROVED_RUNONCE = "Software\\Wow6432Node\\Microsoft\\Windows\\CurrentVersion\\Explorer\\StartupApproved\\RunOnce";

    public static String toApprovedPath(String keyPath) {
        if (keyPath == null) return REG_STARTUP_APPROVED;
        // Order matters: most specific first
        if (keyPath.equals(REG_WOW6432_RUN_DISABLED) || keyPath.equals(REG_WOW6432_RUN)) {
            return REG_WOW6432_APPROVED;
        }
        if (keyPath.equals(REG_WOW6432_RUN_ONCE)) {
            return REG_WOW6432_APPROVED_RUNONCE;
        }
        if (keyPath.equals(REG_RUN_ONCE)) {
            return REG_STARTUP_APPROVED_RUNONCE;
        }
        if (keyPath.equals(REG_RUN_DISABLED) || keyPath.equals(REG_RUN)) {
            return REG_STARTUP_APPROVED;
        }
        // Fallback: generic mapping by suffix
        if (keyPath.endsWith("\\RunOnce")) {
            if (keyPath.contains("Wow6432Node")) return REG_WOW6432_APPROVED_RUNONCE;
            return REG_STARTUP_APPROVED_RUNONCE;
        }
        if (keyPath.contains("Wow6432Node")) return REG_WOW6432_APPROVED;
        return REG_STARTUP_APPROVED;
    }

    public static boolean isRunOnceKey(String keyPath) {
        return REG_RUN_ONCE.equals(keyPath) || REG_WOW6432_RUN_ONCE.equals(keyPath)
                || (keyPath != null && keyPath.endsWith("\\RunOnce"));
    }

    public static boolean isWow6432Key(String keyPath) {
        return keyPath != null && keyPath.contains("Wow6432Node");
    }

    public static byte[] enabledBytes() {
        return new byte[]{0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    public static byte[] disabledBytes() {
        return new byte[]{0x03, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
    }

    public static boolean isEnabledByte(byte[] bytes) {
        if (bytes == null || bytes.length == 0) return true;
        // 0x03 = disabled (Task Manager convention); any other first byte (0x02 enabled,
        // 0x00 unconfigured, etc.) is treated as enabled to avoid inverting unknown states
        return (bytes[0] & 0xFF) != 0x03;
    }

    public static boolean isDisabledByte(byte[] bytes) {
        return bytes != null && bytes.length > 0 && (bytes[0] & 0xFF) == 0x03;
    }
}
