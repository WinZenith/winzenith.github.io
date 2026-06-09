package com.sbtools.systeminfo;

public record NetworkData(String name, double downloadBytesPerSec, double uploadBytesPerSec) {

    public String downloadFormatted() {
        return formatRate(downloadBytesPerSec);
    }

    public String uploadFormatted() {
        return formatRate(uploadBytesPerSec);
    }

    private static String formatRate(double bytesPerSec) {
        if (bytesPerSec < 0) return "N/A";
        String[] units = {"B/s", "KB/s", "MB/s", "GB/s"};
        int unitIdx = 0;
        double value = bytesPerSec;
        while (value >= 1024 && unitIdx < units.length - 1) {
            value /= 1024;
            unitIdx++;
        }
        return String.format("%.1f %s", value, units[unitIdx]);
    }
}
