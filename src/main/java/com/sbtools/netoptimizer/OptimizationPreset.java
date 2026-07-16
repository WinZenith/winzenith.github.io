package com.sbtools.netoptimizer;

public enum OptimizationPreset {

    DEFAULT("Default (Windows defaults)", "Reset all TCP/IP settings to Windows defaults", "Default"),
    MAX_PERFORMANCE("Maximum Performance", "Optimize for maximum throughput", "MaxPerformance"),
    MAX_STABILITY("Maximum Stability", "Optimize for stable connections, reduce latency spikes", "MaxStability"),
    GAMING("Gaming", "Lowest latency, disable background network throttling", "Gaming");

    private final String displayName;
    private final String description;
    private final String scriptName;

    OptimizationPreset(String displayName, String description, String scriptName) {
        this.displayName = displayName;
        this.description = description;
        this.scriptName = scriptName;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
    public String getScriptName() { return scriptName; }
}
