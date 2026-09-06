package com.sbtools.netoptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Documents what {@code net-optimize.ps1} intends to set for each preset.
 * Used for Preview diff only — the script remains the single source of truth
 * for actual writes. Keys use the same display names as the script results
 * plus the two registry values.
 */
public final class PresetExpectations {

    private PresetExpectations() {
    }

    public record PreviewRow(String setting, String current, String willSet, boolean changes) {
    }

    public static Map<String, String> expectedFor(OptimizationPreset preset) {
        Map<String, String> m = new LinkedHashMap<>();
        if (preset == null) preset = OptimizationPreset.DEFAULT;
        switch (preset) {
            case MAX_PERFORMANCE -> {
                m.put("TCP AutoTuning", "normal");
                m.put("RSS", "enabled");
                m.put("RSC", "enabled");
                m.put("ECN", "disabled");
                m.put("TCP Ack Frequency", "removed (registry default)");
                m.put("TCP No Delay", "removed (registry default)");
            }
            case MAX_STABILITY -> {
                m.put("TCP AutoTuning", "disabled");
                m.put("ECN", "enabled");
                m.put("RSS", "enabled");
                m.put("TCP Ack Frequency", "removed (registry default)");
                m.put("TCP No Delay", "removed (registry default)");
            }
            case GAMING -> {
                m.put("TCP AutoTuning", "disabled");
                m.put("RSS", "enabled");
                m.put("ECN", "disabled");
                m.put("TCP Ack Frequency", "1 (set via registry)");
                m.put("TCP No Delay", "1 (set via registry)");
            }
            default -> {
                m.put("TCP AutoTuning", "normal");
                m.put("RSS", "default");
                m.put("ECN", "default");
                m.put("RSC", "default");
                m.put("TCP Ack Frequency", "removed (registry default)");
                m.put("TCP No Delay", "removed (registry default)");
            }
        }
        return m;
    }
}
