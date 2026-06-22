package com.sbtools.netoptimizer;

public record NetworkChangeEntry(
        String timestamp,
        String operation,
        String target,
        String details,
        boolean success
) {}
