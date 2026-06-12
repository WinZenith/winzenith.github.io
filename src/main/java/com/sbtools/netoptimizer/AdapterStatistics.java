package com.sbtools.netoptimizer;

public record AdapterStatistics(
        String adapterName,
        long bytesSent,
        long bytesReceived,
        long unicastPacketsSent,
        long unicastPacketsReceived,
        long multicastPacketsSent,
        long multicastPacketsReceived,
        long broadcastPacketsSent,
        long broadcastPacketsReceived,
        long discardedPackets,
        long errorPackets
) {
    public static AdapterStatistics empty(String adapterName) {
        return new AdapterStatistics(adapterName, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        double kb = bytes / 1024.0;
        if (kb < 1024) return String.format("%.1f KB", kb);
        double mb = kb / 1024.0;
        if (mb < 1024) return String.format("%.1f MB", mb);
        double gb = mb / 1024.0;
        return String.format("%.2f GB", gb);
    }
}
