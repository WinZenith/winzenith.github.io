package com.sbtools.drivers.model;

public enum UpdateSeverity {
    OPTIONAL,
    RECOMMENDED,
    IMPORTANT,
    CRITICAL,
    UNKNOWN;

    public static UpdateSeverity fromString(String s) {
        if (s == null || s.isBlank()) {
            return UNKNOWN;
        }
        String t = s.strip();
        try {
            return UpdateSeverity.valueOf(t.toUpperCase());
        } catch (IllegalArgumentException e) {
            return switch (t.toLowerCase()) {
                case "critical" -> CRITICAL;
                case "important", "security" -> IMPORTANT;
                case "moderate", "recommended" -> RECOMMENDED;
                case "optional", "low" -> OPTIONAL;
                default -> UNKNOWN;
            };
        }
    }
}
