package com.sbtools.util;

public final class DataSizeFormatter {

    private DataSizeFormatter() {
    }

    public static String formatBytes(long bytes) {
        return formatBytes(bytes, 1);
    }

    public static String formatBytes(long bytes, int decimals) {
        if (bytes <= 0) return "";
        double tb = bytes / (1024.0 * 1024 * 1024 * 1024);
        if (tb >= 1) return String.format("%." + decimals + "f TB", tb);
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format("%." + decimals + "f GB", gb);
        double mb = bytes / (1024.0 * 1024);
        if (mb >= 1) return String.format("%." + decimals + "f MB", mb);
        double kb = bytes / 1024.0;
        return String.format("%." + decimals + "f KB", kb);
    }

    public static String formatBytesRounded(long bytes) {
        if (bytes <= 0) return "";
        double tb = bytes / (1024.0 * 1024 * 1024 * 1024);
        if (tb >= 1) return String.format("%.0f TB", tb);
        double gb = bytes / (1024.0 * 1024 * 1024);
        if (gb >= 1) return String.format("%.0f GB", gb);
        double mb = bytes / (1024.0 * 1024);
        return String.format("%.0f MB", mb);
    }

    public static String formatMhz(int mhz) {
        return mhz > 0 ? mhz + " MHz" : "";
    }

    public static String formatKb(int kb) {
        if (kb <= 0) return "";
        if (kb >= 1024) return String.format("%.1f MB", kb / 1024.0);
        return kb + " KB";
    }
}
