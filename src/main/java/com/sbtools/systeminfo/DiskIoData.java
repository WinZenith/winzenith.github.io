package com.sbtools.systeminfo;

public record DiskIoData(String deviceId, double readBytesPerSec, double writeBytesPerSec) {

    public String readFormatted() {
        return formatRate(readBytesPerSec);
    }

    public String writeFormatted() {
        return formatRate(writeBytesPerSec);
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
