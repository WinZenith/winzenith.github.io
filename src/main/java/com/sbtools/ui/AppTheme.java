package com.sbtools.ui;

/**
 * UI color themes. The stored value is the {@link #code()}.
 */
public enum AppTheme {
    DRACULA("dracula", "Dracula", false),
    NORD_LIGHT("nord-light", "Nord Light", true);

    private final String code;
    private final String displayName;
    private final boolean light;

    AppTheme(String code, String displayName, boolean light) {
        this.code = code;
        this.displayName = displayName;
        this.light = light;
    }

    public String code() {
        return code;
    }

    public String displayName() {
        return displayName;
    }

    public boolean isLight() {
        return light;
    }

    /** Dracula when {@code code} is null, blank, or unknown. */
    public static AppTheme fromCode(String code) {
        if (code == null || code.isBlank()) {
            return DRACULA;
        }
        String trimmed = code.trim();
        for (AppTheme theme : values()) {
            if (theme.code.equalsIgnoreCase(trimmed)) {
                return theme;
            }
        }
        return DRACULA;
    }

    /** Canonical stored code, or {@code dracula} when unknown. */
    public static String canonical(String code) {
        return fromCode(code).code;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
