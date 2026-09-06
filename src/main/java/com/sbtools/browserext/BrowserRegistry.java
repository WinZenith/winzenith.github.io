package com.sbtools.browserext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads browser definitions: bundled {@code /catalog/browser-catalog.json} is
 * always the fallback; an optional portable-side
 * {@code <portableBase>/catalog/browser-catalog.json} may ADD extra browsers
 * (matched by case-insensitive name — bundled entries win on conflict so a
 * bad override can never break the built-in 11).
 *
 * <p>Thread-safe, lazily loaded, never throws — returns built-in hardcoded
 * list when everything else fails.
 */
public final class BrowserRegistry {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<BrowserDefinition>> LIST_TYPE =
            new TypeReference<>() {};

    private static volatile List<BrowserDefinition> cached;

    private BrowserRegistry() {
    }

    public static List<BrowserDefinition> definitions() {
        List<BrowserDefinition> hit = cached;
        if (hit != null) return hit;
        synchronized (BrowserRegistry.class) {
            if (cached != null) return cached;
            cached = List.copyOf(loadMerged());
            return cached;
        }
    }

    public static List<String> browserNames() {
        List<String> out = new ArrayList<>();
        for (BrowserDefinition d : definitions()) out.add(d.name());
        return List.copyOf(out);
    }

    public static BrowserDefinition find(String name) {
        if (name == null) return null;
        for (BrowserDefinition d : definitions()) {
            if (d.name().equalsIgnoreCase(name)) return d;
        }
        return null;
    }

    /** For tests: resets the lazy cache. */
    static void resetForTest() {
        synchronized (BrowserRegistry.class) {
            cached = null;
        }
    }

    private static List<BrowserDefinition> loadMerged() {
        List<BrowserDefinition> bundled = loadBundled();
        if (bundled.isEmpty()) {
            AppLogger.warning("BrowserRegistry: bundled catalog missing, using hardcoded fallback");
            bundled = hardcodedFallback();
        }
        List<BrowserDefinition> extra = loadPortableOverride();
        if (extra.isEmpty()) return bundled;

        Map<String, BrowserDefinition> merged = new LinkedHashMap<>();
        for (BrowserDefinition d : bundled) {
            if (isValid(d)) merged.put(d.name().toLowerCase(), d);
        }
        int added = 0;
        for (BrowserDefinition d : extra) {
            if (!isValid(d)) {
                AppLogger.warning("BrowserRegistry: ignoring invalid override entry name="
                        + (d == null ? "null" : d.name()));
                continue;
            }
            String key = d.name().toLowerCase();
            if (merged.containsKey(key)) {
                // Bundled wins — override cannot replace built-ins (safety).
                AppLogger.info("BrowserRegistry: override for built-in '" + d.name() + "' ignored (bundled wins)");
                continue;
            }
            merged.put(key, d);
            added++;
        }
        AppLogger.info("BrowserRegistry: loaded " + merged.size()
                + " browsers (bundled=" + bundled.size() + " + extra=" + added + ")");
        return new ArrayList<>(merged.values());
    }

    private static List<BrowserDefinition> loadBundled() {
        try (InputStream is = BrowserRegistry.class.getResourceAsStream("/catalog/browser-catalog.json")) {
            if (is == null) return List.of();
            byte[] data = is.readAllBytes();
            List<BrowserDefinition> list = MAPPER.readValue(data, LIST_TYPE);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            AppLogger.warning("BrowserRegistry: failed to load bundled catalog: " + e.getMessage());
            return List.of();
        }
    }

    private static List<BrowserDefinition> loadPortableOverride() {
        for (Path p : overrideCandidates()) {
            try {
                if (p == null || !Files.exists(p) || Files.size(p) == 0) continue;
                // Size cap: catalog is tiny; reject absurd files (DoS/accident guard).
                if (Files.size(p) > 256 * 1024) {
                    AppLogger.warning("BrowserRegistry: override too large, ignored: " + p);
                    continue;
                }
                byte[] data = Files.readAllBytes(p);
                List<BrowserDefinition> list = MAPPER.readValue(data, LIST_TYPE);
                if (list != null && !list.isEmpty()) {
                    AppLogger.info("BrowserRegistry: found portable override at " + p);
                    return list;
                }
            } catch (Exception e) {
                AppLogger.warning("BrowserRegistry: failed to load override at " + p + ": " + e.getMessage());
            }
        }
        return List.of();
    }

    private static List<Path> overrideCandidates() {
        List<Path> out = new ArrayList<>();
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) out.add(portable.resolve("catalog").resolve("browser-catalog.json"));
        } catch (Exception ignored) {
        }
        try {
            out.add(AppPaths.localAppData().resolve("catalog").resolve("browser-catalog.json"));
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean isValid(BrowserDefinition d) {
        if (d == null || d.name() == null || d.name().isBlank()) return false;
        if (d.name().length() > 64) return false;
        // userData may be blank only for unknown future engines; engines we scan require it.
        // Still accept here — service skips unscannable entries gracefully.
        if (d.exes() != null && d.exes().size() > 16) return false;
        return true;
    }

    /**
     * Hardcoded fallback mirroring the pre-catalog maps so the tab keeps
     * working even if the bundled resource is missing from the jar.
     */
    static List<BrowserDefinition> hardcodedFallback() {
        return List.of(
                def("Chrome", "chromium-multi", "%LOCALAPPDATA%\\Google\\Chrome\\User Data", "chrome.exe",
                        List.of("%LOCALAPPDATA%\\Google\\Chrome\\Application\\chrome.exe",
                                "%ProgramFiles%\\Google\\Chrome\\Application\\chrome.exe",
                                "%ProgramFiles(x86)%\\Google\\Chrome\\Application\\chrome.exe"),
                        null, "https://chromewebstore.google.com/detail/{id}"),
                def("Chrome Canary", "chromium-multi", "%LOCALAPPDATA%\\Google\\Chrome SxS\\User Data", "chrome.exe",
                        List.of("%LOCALAPPDATA%\\Google\\Chrome SxS\\Application\\chrome.exe"),
                        null, "https://chromewebstore.google.com/detail/{id}"),
                def("Edge", "chromium-multi", "%LOCALAPPDATA%\\Microsoft\\Edge\\User Data", "msedge.exe",
                        List.of("%ProgramFiles%\\Microsoft\\Edge\\Application\\msedge.exe",
                                "%ProgramFiles(x86)%\\Microsoft\\Edge\\Application\\msedge.exe"),
                        null, "https://microsoftedge.microsoft.com/addons/detail/{id}"),
                def("Edge Beta", "chromium-multi", "%LOCALAPPDATA%\\Microsoft\\Edge Beta\\User Data", "msedge.exe",
                        List.of("%ProgramFiles%\\Microsoft\\Edge Beta\\Application\\msedge.exe",
                                "%ProgramFiles(x86)%\\Microsoft\\Edge Beta\\Application\\msedge.exe"),
                        null, "https://microsoftedge.microsoft.com/addons/detail/{id}"),
                def("Edge Dev", "chromium-multi", "%LOCALAPPDATA%\\Microsoft\\Edge Dev\\User Data", "msedge.exe",
                        List.of("%ProgramFiles%\\Microsoft\\Edge Dev\\Application\\msedge.exe",
                                "%ProgramFiles(x86)%\\Microsoft\\Edge Dev\\Application\\msedge.exe"),
                        null, "https://microsoftedge.microsoft.com/addons/detail/{id}"),
                def("Edge Canary", "chromium-multi", "%LOCALAPPDATA%\\Microsoft\\Edge SxS\\User Data", "msedge.exe",
                        List.of("%LOCALAPPDATA%\\Microsoft\\Edge SxS\\Application\\msedge.exe"),
                        null, "https://microsoftedge.microsoft.com/addons/detail/{id}"),
                def("Firefox", "firefox", "%APPDATA%\\Mozilla\\Firefox\\Profiles", "firefox.exe",
                        List.of("%ProgramFiles%\\Mozilla Firefox\\firefox.exe",
                                "%ProgramFiles(x86)%\\Mozilla Firefox\\firefox.exe",
                                "%LOCALAPPDATA%\\Mozilla Firefox\\firefox.exe"),
                        null, "https://addons.mozilla.org/firefox/addon/{id}/"),
                def("Brave", "chromium-multi", "%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\User Data", "brave.exe",
                        List.of("%ProgramFiles%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                                "%ProgramFiles(x86)%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe",
                                "%LOCALAPPDATA%\\BraveSoftware\\Brave-Browser\\Application\\brave.exe"),
                        null, "https://chromewebstore.google.com/detail/{id}"),
                def("Opera", "chromium-single", "%APPDATA%\\Opera Software\\Opera Stable", "opera.exe",
                        List.of("%LOCALAPPDATA%\\Programs\\Opera\\opera.exe",
                                "%APPDATA%\\Opera Software\\Opera Stable\\opera.exe"),
                        "Extensions", "https://addons.opera.com/extensions/details/{id}/"),
                def("Opera GX", "chromium-single", "%APPDATA%\\Opera Software\\Opera GX Stable", "opera.exe",
                        List.of("%LOCALAPPDATA%\\Programs\\Opera GX\\opera.exe"),
                        "Extensions", "https://addons.opera.com/extensions/details/{id}/"),
                def("Vivaldi", "chromium-multi", "%LOCALAPPDATA%\\Vivaldi\\User Data", "vivaldi.exe",
                        List.of("%LOCALAPPDATA%\\Vivaldi\\Application\\vivaldi.exe",
                                "%ProgramFiles%\\Vivaldi\\Application\\vivaldi.exe"),
                        null, "https://chromewebstore.google.com/detail/{id}")
        );
    }

    private static BrowserDefinition def(String name, String engine, String userData,
                                         String proc, List<String> exes,
                                         String subdir, String store) {
        return new BrowserDefinition(name, engine, userData, proc, exes, subdir, store);
    }
}
