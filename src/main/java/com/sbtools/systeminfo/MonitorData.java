package com.sbtools.systeminfo;

import java.util.List;

public record MonitorData(
    double cpuUsage,
    double cpuTempCelsius,
    double gpuUsage,
    double gpuTempCelsius,
    double gpuVramUsed,
    double gpuVramTotal,
    double ramUsedBytes,
    double ramTotalBytes,
    List<DiskIoData> diskIO,
    List<NetworkData> network
) {

    public double ramUsagePercent() {
        return ramTotalBytes > 0 ? (ramUsedBytes / ramTotalBytes) * 100.0 : 0;
    }

    public String ramFormatted() {
        return formatBytes(ramUsedBytes) + " / " + formatBytes(ramTotalBytes);
    }

    public String gpuVramFormatted() {
        if (gpuVramTotal <= 0) return "N/A";
        return formatBytes(gpuVramUsed) + " / " + formatBytes(gpuVramTotal);
    }

    public double gpuVramPercent() {
        return gpuVramTotal > 0 ? (gpuVramUsed / gpuVramTotal) * 100.0 : 0;
    }

    private static String formatBytes(double bytes) {
        if (bytes < 0) return "N/A";
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unitIdx = 0;
        double value = bytes;
        while (value >= 1024 && unitIdx < units.length - 1) {
            value /= 1024;
            unitIdx++;
        }
        return String.format("%.1f %s", value, units[unitIdx]);
    }
}
