package com.basicsdriverupdate.drivers.model;

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
        try {
            return UpdateSeverity.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            return switch (s.toLowerCase()) {
                case "critical" -> CRITICAL;
                case "important" -> IMPORTANT;
                case "moderate", "recommended" -> RECOMMENDED;
                default -> UNKNOWN;
            };
        }
    }
}
