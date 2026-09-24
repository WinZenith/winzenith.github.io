package com.sbtools.i18n;

import java.util.Locale;

/**
 * UI languages. The stored value is the BCP 47 {@link #code()}.
 */
public enum AppLanguage {
    EN("en", "English", Locale.ENGLISH),
    DE("de", "Deutsch", Locale.GERMAN),
    ES("es", "Español", Locale.forLanguageTag("es")),
    FR("fr", "Français", Locale.FRENCH),
    IT("it", "Italiano", Locale.ITALIAN),
    PT("pt", "Português", Locale.forLanguageTag("pt")),
    PT_BR("pt-BR", "Português (Brasil)", Locale.forLanguageTag("pt-BR"));

    private final String code;
    private final String nativeName;
    private final Locale locale;

    AppLanguage(String code, String nativeName, Locale locale) {
        this.code = code;
        this.nativeName = nativeName;
        this.locale = locale;
    }

    public String code() {
        return code;
    }

    public String nativeName() {
        return nativeName;
    }

    public Locale locale() {
        return locale;
    }

    /** English when {@code code} is null, blank, or not one of the seven languages. */
    public static AppLanguage fromCode(String code) {
        if (code == null || code.isBlank()) {
            return EN;
        }
        String trimmed = code.trim();
        for (AppLanguage language : values()) {
            if (language.code.equalsIgnoreCase(trimmed)) {
                return language;
            }
        }
        if ("pt_BR".equalsIgnoreCase(trimmed)) {
            return PT_BR;
        }
        return EN;
    }

    /** Canonical stored code, or {@code en} when unknown. */
    public static String canonical(String code) {
        return fromCode(code).code;
    }

    @Override
    public String toString() {
        return nativeName;
    }
}
