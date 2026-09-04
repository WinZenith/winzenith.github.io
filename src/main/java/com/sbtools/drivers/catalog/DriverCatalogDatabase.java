package com.sbtools.drivers.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.drivers.model.UpdateSeverity;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.VersionCompare;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Local structured catalog database that maps hardware devices to known-good
 * driver versions. This eliminates brittle web scraping by providing:
 * <ul>
 *   <li>Hardware ID-based matching (PCI\VEN_xxxx&DEV_yyyy)</li>
 *   <li>Name regex matching for friendly device names</li>
 *   <li>Version range validation (min/max)</li>
 *   <li>Direct download URLs with hash verification</li>
 *   <li>Confidence scores for match quality</li>
 * </ul>
 *
 * The catalog is loaded from a JSON file bundled with the application and can
 * be supplemented with user-provided entries from the local app data directory.
 */
public final class DriverCatalogDatabase {

    private static final ObjectMapper MAPPER = JsonMapper.mapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final TypeReference<List<CatalogEntry>> LIST_TYPE = new TypeReference<>() {};

    private final List<CatalogEntry> entries;
    private final Map<String, List<CatalogEntry>> byProvider;
    private final Map<String, List<CatalogEntry>> byHardwareId;
    private final List<CatalogEntry> nameRegexEntries;

    public DriverCatalogDatabase(List<CatalogEntry> entries) {
        this.entries = List.copyOf(entries);
        this.byProvider = indexByProvider(this.entries);
        this.byHardwareId = indexByHardwareId(this.entries);
        this.nameRegexEntries = this.entries.stream()
                .filter(e -> e.matchMethod() == CatalogEntry.MatchMethod.NAME_REGEX)
                .toList();
    }

    /**
     * Loads the catalog from the bundled resource file.
     * User-supplemental catalog is intentionally disabled for security – untrusted
     * URLs/hashes must not be injectable via the app data directory.
     * See decision for portable build: no user override.
     */
    public static DriverCatalogDatabase load() {
        List<CatalogEntry> all = new ArrayList<>();
        all.addAll(loadBundled());
        // Intentionally NOT loading user-supplemental catalog (security, requirement #4)
        Path userCatalog = AppPaths.localAppData().resolve("user-catalog.json");
        if (Files.exists(userCatalog)) {
            AppLogger.info("DriverCatalogDatabase: Ignoring user-catalog.json (user override disabled)");
        }
        AppLogger.info("DriverCatalogDatabase: Loaded " + all.size() + " catalog entries");
        return new DriverCatalogDatabase(all);
    }

    private static List<CatalogEntry> loadBundled() {
        try (InputStream is = DriverCatalogDatabase.class.getResourceAsStream("/catalog/driver-catalog.json")) {
            if (is == null) {
                AppLogger.warning("DriverCatalogDatabase: Bundled catalog not found on classpath");
                return List.of();
            }
            byte[] data = is.readAllBytes();
            return MAPPER.readValue(data, LIST_TYPE);
        } catch (Exception e) {
            AppLogger.warning("DriverCatalogDatabase: Failed to load bundled catalog: " + e.getMessage());
            return List.of();
        }
    }

    /**
     * Finds all catalog entries that match the given installed driver.
     * Returns matches sorted by confidence (highest first).
     */
    public List<CatalogEntry> findMatchingEntries(InstalledDriver driver) {
        // Gather candidate entries by hardware ID and by name regex
        List<CatalogEntry> hwMatches = findByHardwareId(driver);
        List<CatalogEntry> regexMatches = findByNameRegex(driver);

        // Combine candidates, preserving order (hardware matches first)
        List<CatalogEntry> combined = new ArrayList<>();
        combined.addAll(hwMatches);
        for (CatalogEntry e : regexMatches) {
            if (!combined.contains(e)) combined.add(e);
        }

        List<CatalogEntry> filtered = new ArrayList<>();
        for (CatalogEntry e : combined) {
            // Skip test entries in normal matching
            if (e.testOnly()) continue;
            // Enforce version applicability range when the catalog specifies it
            if (!isWithinVersionRange(driver.driverVersion(), e.versionMin(), e.versionMax())) continue;
            // Enforce platform/arch when the catalog specifies them
            if (!isPlatformCompatible(e.platform())) continue;
            if (!isArchCompatible(e.arch())) continue;

            int factors = 0;
            if (hwMatches.contains(e)) factors++;
            if (regexMatches.contains(e)) factors++;

            // INF metadata match counts as a factor when specified
            if (e.matchMethod() == CatalogEntry.MatchMethod.INF_METADATA && e.matchValue() != null && !e.matchValue().isBlank()) {
                String inf = driver.infName() != null ? driver.infName().toUpperCase() : "";
                if (!inf.isBlank() && inf.contains(e.matchValue().toUpperCase())) factors++;
            }

            // Package ID match (if catalog entry encodes a package id)
            if (e.matchMethod() == CatalogEntry.MatchMethod.PACKAGE_ID && e.matchValue() != null && !e.matchValue().isBlank()) {
                String dk = driver.driverKey() != null ? driver.driverKey().toUpperCase() : "";
                if (!dk.isBlank() && dk.contains(e.matchValue().toUpperCase())) factors++;
            }

            // Presence of trusted metadata (hash or certificate thumbprint) counts as an independent factor
            boolean metadataFactor = (e.hashSha256() != null && !e.hashSha256().isBlank())
                    || (e.certThumbprint() != null && !e.certThumbprint().isBlank());
            if (metadataFactor) factors++;

            // Fixed gate: single hardware-ID factor with confidence >=0.8 is sufficient.
            // Previous gate (factors>=2 || confidence>=0.95) excluded all AMD entries (0.9) even with exact HW match.
            String catalogVersionForCompare = e.latestDriverVersion() != null && !e.latestDriverVersion().isBlank()
                    ? e.latestDriverVersion() : e.latestVersion();
            boolean strongSingleFactor = hwMatches.contains(e) && e.confidence() >= 0.8;
            boolean twoFactor = factors >= 2;
            boolean veryHighConfidence = e.confidence() >= 0.95;
            if ((strongSingleFactor || twoFactor || veryHighConfidence) && isVersionNewer(catalogVersionForCompare, driver.driverVersion())) {
                filtered.add(e);
            }
        }

        return filtered.stream()
                .sorted((a, b) -> Double.compare(b.confidence(), a.confidence()))
                .collect(Collectors.toList());
    }

    /**
     * Finds the best catalog entry for a driver (highest confidence match
     * with a newer version than currently installed).
     */
    public Optional<CatalogEntry> findBestMatch(InstalledDriver driver) {
        return findMatchingEntries(driver).stream().findFirst();
    }

    /**
     * Finds all entries that match a given hardware ID.
     */
    public List<CatalogEntry> findByHardwareId(String hardwareId) {
        String normalized = normalizeHardwareId(hardwareId);
        List<CatalogEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<CatalogEntry>> entry : byHardwareId.entrySet()) {
            if (matchesHardwareId(normalized, entry.getKey())) {
                result.addAll(entry.getValue());
            }
        }
        return result;
    }

    /**
     * Converts a catalog entry into a DriverUpdateCandidate for use in the
     * existing update pipeline.
     */
    public static DriverUpdateCandidate toCandidate(CatalogEntry entry, InstalledDriver driver) {
        String pkg = entry.packageId() != null && !entry.packageId().isBlank() ? entry.packageId() : entry.id();
        String effectiveVersion = entry.latestDriverVersion() != null && !entry.latestDriverVersion().isBlank()
                ? entry.latestDriverVersion() : entry.latestVersion();
        String sourceUrl = sanitizeSourceUrl(entry.sourceUrl());
        String vendorPageUrl = sanitizeSourceUrl(entry.vendorPageUrl());
        return new DriverUpdateCandidate(
                driver,
                effectiveVersion,
                entry.provider(),
                pkg,
                entry.provider() + " driver update available",
                "Certified " + entry.component() + " driver from " + entry.provider()
                        + " (confidence: " + String.format("%.0f", entry.confidence() * 100) + "%)",
                severityFromTags(entry.tags()),
                sourceUrl,
                vendorPageUrl
        );
    }

    static UpdateSeverity severityFromTags(java.util.List<String> tags) {
        if (tags != null) {
            for (String t : tags) {
                if (t == null) continue;
                String l = t.toLowerCase();
                if (l.contains("critical")) return UpdateSeverity.CRITICAL;
            }
            for (String t : tags) {
                if (t == null) continue;
                String l = t.toLowerCase();
                if (l.contains("important") || l.contains("security")) return UpdateSeverity.IMPORTANT;
            }
            for (String t : tags) {
                if (t == null) continue;
                String l = t.toLowerCase();
                if (l.contains("optional")) return UpdateSeverity.OPTIONAL;
            }
        }
        return UpdateSeverity.RECOMMENDED;
    }

    static String sanitizeSourceUrl(String url) {
        if (url == null || url.isBlank()) return "";
        String trimmed = url.trim();
        try {
            java.net.URI uri = new java.net.URI(trimmed);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
            if (!"https".equals(scheme)) {
                AppLogger.warning("DriverCatalogDatabase: Rejecting non-https catalog URL: " + trimmed);
                return "";
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) return "";
            return trimmed;
        } catch (Exception ex) {
            AppLogger.warning("DriverCatalogDatabase: Rejecting malformed catalog URL: " + trimmed);
            return "";
        }
    }

    static boolean isWithinVersionRange(String installed, String min, String max) {
        try {
            if (min != null && !min.isBlank()) {
                if (installed == null || installed.isBlank()) return true;
                if (VersionCompare.compare(installed, min) < 0) return false;
            }
            if (max != null && !max.isBlank()) {
                if (installed == null || installed.isBlank()) return true;
                if (VersionCompare.compare(installed, max) > 0) return false;
            }
        } catch (Exception ignored) {
            return true;
        }
        return true;
    }

    static boolean isPlatformCompatible(String platform) {
        if (platform == null || platform.isBlank()) return true;
        String p = platform.toLowerCase();
        if (p.contains("win")) return AppPaths.isWindows() || p.contains("windows");
        // Unknown platform tags: fail closed (do not offer).
        AppLogger.warning("DriverCatalogDatabase: Skipping entry with incompatible platform: " + platform);
        return false;
    }

    static boolean isArchCompatible(String arch) {
        if (arch == null || arch.isBlank()) return true;
        String a = arch.toLowerCase().replaceAll("[^a-z0-9]", "");
        String osArch = System.getProperty("os.arch", "").toLowerCase();
        boolean is64 = osArch.contains("64") || osArch.contains("amd64") || osArch.contains("x86_64");
        if (a.contains("64") || a.contains("amd64") || a.contains("x64")) return is64;
        if (a.equals("x86") || a.equals("32") || a.contains("386")) return !is64;
        if (a.contains("arm64") || a.contains("aarch64")) return osArch.contains("aarch64") || osArch.contains("arm64");
        if (a.contains("arm")) return osArch.contains("arm");
        return true;
    }

    private List<CatalogEntry> findByHardwareId(InstalledDriver driver) {
        String hwId = driver.hardwareIds();
        if (hwId == null || hwId.isBlank()) {
            return List.of();
        }
        // hardwareIds may be ';'-separated list (multi-string from enumerate-devices.ps1)
        String[] parts = hwId.split(";");
        List<CatalogEntry> matches = new ArrayList<>();
        for (String part : parts) {
            if (part == null || part.isBlank()) continue;
            String normalized = normalizeHardwareId(part);
            if (normalized.isEmpty()) continue;
            for (Map.Entry<String, List<CatalogEntry>> entry : byHardwareId.entrySet()) {
                if (matchesHardwareId(normalized, entry.getKey())) {
                    for (CatalogEntry ce : entry.getValue()) {
                        if (ce.hardwareIds() != null) {
                            for (String entryHwId : ce.hardwareIds()) {
                                if (matchesHardwareId(normalized, normalizeHardwareId(entryHwId))) {
                                    if (!matches.contains(ce)) {
                                        matches.add(ce);
                                    }
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        return matches;
    }

    private List<CatalogEntry> findByNameRegex(InstalledDriver driver) {
        String name = driver.friendlyName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String nameUpper = name.toUpperCase();
        List<CatalogEntry> matches = new ArrayList<>();
        for (CatalogEntry entry : nameRegexEntries) {
            if (entry.matchValue() != null) {
                try {
                    Pattern p = Pattern.compile(entry.matchValue(), Pattern.CASE_INSENSITIVE);
                    if (p.matcher(name).find()) {
                        matches.add(entry);
                    }
                } catch (PatternSyntaxException e) {
                    if (nameUpper.contains(entry.matchValue().toUpperCase())) {
                        matches.add(entry);
                    }
                }
            }
        }
        return matches;
    }

    private static boolean isVersionNewer(String catalogVersion, String installedVersion) {
        if (catalogVersion == null || catalogVersion.isBlank()) {
            return false;
        }
        if (installedVersion == null || installedVersion.isBlank()) {
            return true;
        }
        return VersionCompare.isOlder(installedVersion, catalogVersion);
    }

    private static String normalizeHardwareId(String hwId) {
        return hwId.toUpperCase().replaceAll("[^A-Z0-9&\\\\_]", "");
    }

    /**
     * Checks if two normalized hardware IDs match, allowing one to be a
     * prefix of the other (e.g. PCI_VEN_8086&amp;DEV_2723 matches
     * PCI_VEN_8086&amp;DEV_2723&amp;SUBSYS_12345678) but rejecting
     * substring matches at non-segment boundaries (e.g. DEV_2723 must not
     * match DEV_27231).
     */
    private static boolean matchesHardwareId(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (a.startsWith(b)) {
            return b.isEmpty() || b.charAt(b.length() - 1) == '&' || b.charAt(b.length() - 1) == '\\'
                    || a.charAt(b.length()) == '&' || a.charAt(b.length()) == '\\';
        }
        if (b.startsWith(a)) {
            return a.isEmpty() || a.charAt(a.length() - 1) == '&' || a.charAt(a.length() - 1) == '\\'
                    || b.charAt(a.length()) == '&' || b.charAt(a.length()) == '\\';
        }
        return false;
    }

    private static Map<String, List<CatalogEntry>> indexByProvider(List<CatalogEntry> entries) {
        Map<String, List<CatalogEntry>> map = new HashMap<>();
        for (CatalogEntry entry : entries) {
            if (entry.provider() != null) {
                map.computeIfAbsent(entry.provider(), k -> new ArrayList<>()).add(entry);
            }
        }
        return Collections.unmodifiableMap(map);
    }

    private static Map<String, List<CatalogEntry>> indexByHardwareId(List<CatalogEntry> entries) {
        Map<String, List<CatalogEntry>> map = new HashMap<>();
        for (CatalogEntry entry : entries) {
            if (entry.hardwareIds() != null) {
                for (String hwId : entry.hardwareIds()) {
                    String normalized = normalizeHardwareId(hwId);
                    if (!normalized.isEmpty()) {
                        map.computeIfAbsent(normalized, k -> new ArrayList<>()).add(entry);
                    }
                }
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
