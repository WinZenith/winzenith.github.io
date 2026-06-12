package com.sbtools.netoptimizer;

import java.util.LinkedHashMap;
import java.util.Map;

public record TcpSettings(Map<String, String> settings) {

    public static TcpSettings parse(String netshOutput) {
        Map<String, String> map = new LinkedHashMap<>();
        if (netshOutput == null || netshOutput.isBlank()) return new TcpSettings(map);
        for (String line : netshOutput.split("\\R")) {
            line = line.trim();
            int colon = line.indexOf(':');
            if (colon > 0 && colon < line.length() - 1) {
                String key = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (!key.isEmpty()) map.put(key, value);
            }
        }
        return new TcpSettings(map);
    }

    public String get(String key) {
        return settings.getOrDefault(key, "N/A");
    }
}
