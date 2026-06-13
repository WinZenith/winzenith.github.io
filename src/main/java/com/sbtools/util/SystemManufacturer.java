package com.sbtools.util;

import java.util.Locale;

public final class SystemManufacturer {

    public enum Manufacturer {
        LENOVO, DELL, HP, ASUS, ACER, MSI, SAMSUNG, TOSHIBA, MICROSOFT, GENERIC
    }

    private static volatile Manufacturer cached;
    private static volatile String cachedName;

    private SystemManufacturer() {
    }

    public static Manufacturer get() {
        if (cached != null) return cached;
        synchronized (SystemManufacturer.class) {
            if (cached != null) return cached;
            cached = detect();
            return cached;
        }
    }

    public static String getName() {
        if (cachedName != null) return cachedName;
        synchronized (SystemManufacturer.class) {
            if (cachedName != null) return cachedName;
            get();
            return cachedName;
        }
    }

    private static Manufacturer detect() {
        String raw = queryWmi();
        cachedName = raw;
        if (raw == null || raw.isBlank()) return Manufacturer.GENERIC;
        String lower = raw.toLowerCase(Locale.ROOT);
        if (lower.contains("lenovo")) return Manufacturer.LENOVO;
        if (lower.contains("dell")) return Manufacturer.DELL;
        if (lower.contains("hp") || lower.contains("hewlett")) return Manufacturer.HP;
        if (lower.contains("asus")) return Manufacturer.ASUS;
        if (lower.contains("acer")) return Manufacturer.ACER;
        if (lower.contains("micro-star") || lower.contains("msi")) return Manufacturer.MSI;
        if (lower.contains("samsung")) return Manufacturer.SAMSUNG;
        if (lower.contains("toshiba")) return Manufacturer.TOSHIBA;
        if (lower.contains("microsoft")) return Manufacturer.MICROSOFT;
        return Manufacturer.GENERIC;
    }

    private static String queryWmi() {
        try {
            ProcessRunner runner = new ProcessRunner(15);
            ProcessResult result = runner.run(java.util.List.of(
                    "powershell", "-NoProfile", "-Command",
                    "(Get-CimInstance Win32_ComputerSystem).Manufacturer"
            ));
            if (result.success() && result.stdout() != null && !result.stdout().isBlank()) {
                return result.stdout().trim();
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to query system manufacturer: " + e.getMessage());
        }
        return null;
    }
}
