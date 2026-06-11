package com.sbtools.netoptimizer;

public enum OptimizationPreset {

    DEFAULT("Default (Windows defaults)", "Reset all TCP/IP settings to Windows defaults"),
    MAX_PERFORMANCE("Maximum Performance", "Optimize for maximum throughput"),
    MAX_STABILITY("Maximum Stability", "Optimize for stable connections, reduce latency spikes"),
    GAMING("Gaming", "Lowest latency, disable background network throttling");

    private final String displayName;
    private final String description;

    OptimizationPreset(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
