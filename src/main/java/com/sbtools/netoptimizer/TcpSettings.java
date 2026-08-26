package com.sbtools.netoptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

public record TcpSettings(Map<String, String> settings) {

    public static TcpSettings parse(String netshOutput) {
        Map<String, String> map = new LinkedHashMap<>();
        if (netshOutput == null || netshOutput.isBlank()) return new TcpSettings(map);
        for (String line : netshOutput.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            // Skip dashed separators like "--------------------------------------------"
            if (trimmed.matches("^-+$")) continue;
            // Skip header lines without colon
            int colon = trimmed.indexOf(':');
            if (colon > 0 && colon < trimmed.length() - 1) {
                String key = trimmed.substring(0, colon).trim();
                String value = trimmed.substring(colon + 1).trim();
                // Filter out header/footer noise
                if (key.isEmpty() || key.startsWith("-")) continue;
                // Avoid duplicate keys overwriting with empty
                if (!value.isEmpty()) map.put(key, value);
                else if (!map.containsKey(key)) map.put(key, value);
            }
        }
        return new TcpSettings(map);
    }

    public String get(String key) {
        return settings.getOrDefault(key, "N/A");
    }
}
