package com.sbtools.netoptimizer;

public record PortScanResult(
        String host,
        int port,
        boolean open,
        long latencyMs,
        String rawOutput
) {}
