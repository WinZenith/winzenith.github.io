package com.sbtools.netoptimizer;

public record PingResult(
        String host,
        int packetsSent,
        int packetsReceived,
        int packetLossPercent,
        double minMs,
        double maxMs,
        double avgMs,
        String rawOutput
) {
    public static PingResult fail(String host, String rawOutput) {
        return new PingResult(host, 0, 0, 100, 0, 0, 0, rawOutput);
    }
}
