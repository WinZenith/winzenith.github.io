package com.sbtools.netoptimizer;

public record TracerouteHop(
        int hopNumber,
        String address,
        String latency1,
        String latency2,
        String latency3
) {}
