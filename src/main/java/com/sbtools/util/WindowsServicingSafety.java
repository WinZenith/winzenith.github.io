package com.sbtools.util;

import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;

import java.util.ArrayList;
import java.util.List;

public final class WindowsServicingSafety {

    private WindowsServicingSafety() {
    }

    public static boolean isServicingPending() {
        return !getPendingReasons().isEmpty();
    }

    public static List<String> getPendingReasons() {
        List<String> reasons = new ArrayList<>();

        if (keyExists(WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\WindowsUpdate\\Auto Update\\RebootRequired")) {
            reasons.add("Windows Update requires a reboot");
        }

        if (keyExists(WinReg.HKEY_LOCAL_MACHINE,
                "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Component Based Servicing\\RebootPending")) {
            reasons.add("Component Based Servicing has pending operations");
        }

        if (hasPendingFileRenames()) {
            reasons.add("Pending file rename operations exist");
        }

        if (isImageStateIncomplete()) {
            reasons.add("Windows setup/upgrade is not complete");
        }

        return reasons;
    }

    private static boolean keyExists(WinReg.HKEY hive, String keyPath) {
        try {
            return Advapi32Util.registryKeyExists(hive, keyPath);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean hasPendingFileRenames() {
        try {
            Object val = Advapi32Util.registryGetValue(WinReg.HKEY_LOCAL_MACHINE,
                    "SYSTEM\\CurrentControlSet\\Control\\Session Manager",
                    "PendingFileRenameOperations");
            if (val instanceof String[] arr) {
                return arr.length > 0;
            }
            if (val instanceof String s) {
                return !s.isBlank();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static boolean isImageStateIncomplete() {
        try {
            String state = Advapi32Util.registryGetStringValue(WinReg.HKEY_LOCAL_MACHINE,
                    "SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Setup\\State",
                    "ImageState");
            if (state != null && !state.isBlank()) {
                return !state.equalsIgnoreCase("IMAGE_STATE_COMPLETE");
            }
        } catch (Exception ignored) {
        }
        return false;
    }
}
