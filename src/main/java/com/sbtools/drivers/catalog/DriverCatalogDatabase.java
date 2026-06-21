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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
     * Loads the catalog from the bundled resource file and any supplemental
     * user catalog in the app data directory.
     */
    public static DriverCatalogDatabase load() {
        List<CatalogEntry> all = new ArrayList<>();
        all.addAll(loadBundled());
        all.addAll(loadUserSupplemental());
        AppLogger.info("DriverCatalogDatabase: Loaded " + all.size() + " catalog entries");
        return new DriverCatalogDatabase(all);
    }

    /**
     * Loads only the bundled catalog resource.
     */
    public static DriverCatalogDatabase loadBundledOnly() {
        return new DriverCatalogDatabase(loadBundled());
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

    private static List<CatalogEntry> loadUserSupplemental() {
        Path userCatalog = AppPaths.localAppData().resolve("user-catalog.json");
        if (!Files.exists(userCatalog)) {
            return List.of();
        }
        try {
            byte[] data = Files.readAllBytes(userCatalog);
            List<CatalogEntry> entries = MAPPER.readValue(data, LIST_TYPE);
            AppLogger.info("DriverCatalogDatabase: Loaded " + entries.size() + " supplemental user entries");
            return entries;
        } catch (Exception e) {
            AppLogger.warning("DriverCatalogDatabase: Failed to load user catalog: " + e.getMessage());
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

            // Accept if at least two independent factors match, or very high confidence
            if ((factors >= 2 || e.confidence() >= 0.95) && isVersionNewer(e.latestVersion(), driver.driverVersion())) {
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
     * Finds all entries for a given provider.
     */
    public List<CatalogEntry> findByProvider(String provider) {
        return byProvider.getOrDefault(provider, List.of());
    }

    /**
     * Finds all entries that match a given hardware ID.
     */
    public List<CatalogEntry> findByHardwareId(String hardwareId) {
        String normalized = normalizeHardwareId(hardwareId);
        List<CatalogEntry> result = new ArrayList<>();
        for (Map.Entry<String, List<CatalogEntry>> entry : byHardwareId.entrySet()) {
            if (normalized.contains(entry.getKey()) || entry.getKey().contains(normalized)) {
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
        return new DriverUpdateCandidate(
                driver,
                entry.latestVersion(),
                entry.provider(),
                pkg,
                entry.provider() + " driver update available",
                "Certified " + entry.component() + " driver from " + entry.provider()
                        + " (confidence: " + String.format("%.0f", entry.confidence() * 100) + "%)",
                UpdateSeverity.RECOMMENDED,
                entry.sourceUrl(),
                entry.vendorPageUrl()
        );
    }

    /**
     * Returns the total number of entries in the catalog.
     */
    public int size() {
        return entries.size();
    }

    /**
     * Returns all entries in the catalog.
     */
    public List<CatalogEntry> allEntries() {
        return entries;
    }

    private List<CatalogEntry> findByHardwareId(InstalledDriver driver) {
        String hwId = driver.hardwareIds();
        if (hwId == null || hwId.isBlank()) {
            return List.of();
        }
        String normalized = normalizeHardwareId(hwId);
        List<CatalogEntry> matches = new ArrayList<>();
        for (Map.Entry<String, List<CatalogEntry>> entry : byHardwareId.entrySet()) {
            if (normalized.contains(entry.getKey())) {
                for (CatalogEntry ce : entry.getValue()) {
                    if (ce.hardwareIds() != null) {
                        for (String entryHwId : ce.hardwareIds()) {
                            if (normalized.contains(normalizeHardwareId(entryHwId))) {
                                matches.add(ce);
                                break;
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
        return hwId.toUpperCase().replaceAll("[^A-Z0-9&\\\\]", "");
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
