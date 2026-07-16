package com.sbtools.netoptimizer;

public record SpeedTestResult(
        double downloadMbps,
        double uploadMbps,
        long latencyMs,
        String serverInfo,
        String rawOutput
) {
    public static SpeedTestResult fail(String rawOutput) {
        return new SpeedTestResult(0, 0, 0, "", rawOutput);
    }
}
