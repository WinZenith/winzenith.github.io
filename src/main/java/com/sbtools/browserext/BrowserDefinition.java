package com.sbtools.browserext;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Pluggable browser definition. Loaded from {@code /catalog/browser-catalog.json}
 * with a portable-side override. All fields optional except {@code name} —
 * missing fields fall back to safe defaults so a corrupt catalog can never
 * break the built-in 11 browsers.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BrowserDefinition(
        String name,
        String engine,
        String userData,
        String processName,
        List<String> exes,
        String extensionsSubdir,
        String storeUrlTemplate) {

    public String engineOrDefault() {
        if (engine == null || engine.isBlank()) return "chromium-multi";
        String e = engine.trim().toLowerCase();
        return switch (e) {
            case "chromium-multi", "chromium-single", "firefox" -> e;
            default -> "chromium-multi";
        };
    }

    public List<String> exesOrEmpty() {
        return exes == null ? List.of() : List.copyOf(exes);
    }

    public String userDataOrEmpty() {
        return userData == null ? "" : userData;
    }

    public String processNameOrEmpty() {
        return processName == null ? "" : processName;
    }

    public String extensionsSubdirOrDefault() {
        return (extensionsSubdir == null || extensionsSubdir.isBlank()) ? "Extensions" : extensionsSubdir;
    }

    /** Builds a store URL for the given extension id, or "" when unknown. */
    public String storeUrlFor(String extensionId) {
        if (storeUrlTemplate == null || storeUrlTemplate.isBlank()
                || extensionId == null || extensionId.isBlank()) return "";
        try {
            return storeUrlTemplate.replace("{id}", extensionId.trim());
        } catch (Exception e) {
            return "";
        }
    }
}
