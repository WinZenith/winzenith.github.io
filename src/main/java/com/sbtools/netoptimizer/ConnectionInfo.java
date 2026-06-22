package com.sbtools.netoptimizer;

public record ConnectionInfo(
        String protocol,
        String localAddress,
        String remoteAddress,
        String state,
        int pid,
        String processName
) {}
