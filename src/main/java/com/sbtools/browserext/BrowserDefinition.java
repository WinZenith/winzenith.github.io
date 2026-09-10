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
            String id = extensionId.trim();
            if (id.isBlank()) return "";
            String engine = engineOrDefault();
            if ("firefox".equals(engine) && isFirefoxInternalId(id)) {
                // AMO detail pages are slug-based (e.g. /addon/ublock-origin/);
                // raw add-on IDs are GUIDs ({...}) or emails (jid..@jetpack,
                // ...@mozilla.org) and 404 as detail URLs. Link search instead.
                return "https://addons.mozilla.org/firefox/search/?q=" + urlEncode(id);
            }
            if ("chromium-single".equals(engine) && id.matches("[a-pA-P0-9]{32}")) {
                // Opera store pages are slug-based; a raw 32-char Chromium ID
                // never resolves there. These IDs come from Chrome Web Store
                // installs, so link the CWS entry (lower-cased canonical form).
                return "https://chromewebstore.google.com/detail/" + id.toLowerCase();
            }
            return storeUrlTemplate.replace("{id}", id);
        } catch (Exception e) {
            return "";
        }
    }

    private static boolean isFirefoxInternalId(String id) {
        return id.contains("@") || (id.startsWith("{") && id.endsWith("}"));
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return value;
        }
    }
}
