package com.sbtools.drivers.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
            .enable(SerializationFeature.INDENT_OUTPUT)
            // Unknown future MatchMethod values map to UNKNOWN instead of
            // aborting the entire catalog load (forward-compat with feeds).
            .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);

    private static final TypeReference<List<CatalogEntry>> LIST_TYPE = new TypeReference<>() {};

    private final List<CatalogEntry> entries;
    private final Map<String, List<CatalogEntry>> byProvider;
    private final Map<String, List<CatalogEntry>> byHardwareId;
    private final List<CatalogEntry> nameRegexEntries;
    private final String sourceLabel;

    public DriverCatalogDatabase(List<CatalogEntry> entries) {
        this(entries, "bundled");
    }

    public DriverCatalogDatabase(List<CatalogEntry> entries, String sourceLabel) {
        this.entries = List.copyOf(entries);
        this.byProvider = indexByProvider(this.entries);
        this.byHardwareId = indexByHardwareId(this.entries);
        this.nameRegexEntries = this.entries.stream()
                .filter(e -> e.matchMethod() == CatalogEntry.MatchMethod.NAME_REGEX)
                .toList();
        this.sourceLabel = sourceLabel == null ? "bundled" : sourceLabel;
    }

    public String sourceLabel() {
        return sourceLabel;
    }

    public int entryCount() {
        return entries.size();
    }

    /**
     * Loads the catalog: bundled resource as the safe fallback, plus an
     * optionally refreshed copy at {@code <portableBase>/catalog/driver-catalog.json}
     * (written by {@link CatalogUpdateService}). Refreshed entries are validated
     * (https-only URLs, sane versions) and win on id conflicts; if the refreshed
     * file is missing/corrupt the bundled catalog is used unchanged.
     * User-supplied {@code user-catalog.json} injection remains disabled.
     */
    public static DriverCatalogDatabase load() {
        List<CatalogEntry> bundled = loadBundled();
        // Intentionally NOT loading user-supplemental catalog (security, requirement #4)
        Path userCatalog = AppPaths.localAppData().resolve("user-catalog.json");
        if (Files.exists(userCatalog)) {
            AppLogger.info("DriverCatalogDatabase: Ignoring user-catalog.json (user override disabled)");
        }
        List<CatalogEntry> refreshed = loadRefreshed();
        if (refreshed.isEmpty()) {
            AppLogger.info("DriverCatalogDatabase: Loaded " + bundled.size() + " catalog entries (bundled)");
            return new DriverCatalogDatabase(bundled, "bundled");
        }
        // Merge: refreshed wins on id conflict; validate each refreshed entry.
        Map<String, CatalogEntry> merged = new HashMap<>();
        for (CatalogEntry e : bundled) {
            if (e != null && e.id() != null) merged.put(e.id(), e);
        }
        int accepted = 0;
        for (CatalogEntry e : refreshed) {
            if (isValidRefreshedEntry(e)) {
                merged.put(e.id(), e);
                accepted++;
            } else {
                AppLogger.warning("DriverCatalogDatabase: Rejecting invalid refreshed entry id="
                        + (e == null ? "null" : e.id()));
            }
        }
        List<CatalogEntry> all = new ArrayList<>(merged.values());
        AppLogger.info("DriverCatalogDatabase: Loaded " + all.size() + " catalog entries (bundled="
                + bundled.size() + " + refreshedAccepted=" + accepted + ")");
        return new DriverCatalogDatabase(all, "bundled+refreshed(" + accepted + ")");
    }

    private static List<CatalogEntry> loadRefreshed() {
        // Newest valid file wins: first-file-wins let a stale portable copy
        // shadow a fresher localAppData one (USB-moved machines), and an
        // mtime tie or 0-byte file confused refreshedCatalogTime() reporters.
        List<CatalogEntry> best = List.of();
        long bestMtime = Long.MIN_VALUE;
        Path bestPath = null;
        for (Path p : refreshedCandidates()) {
            try {
                if (p == null || !Files.exists(p) || Files.size(p) == 0) continue;
                byte[] data = Files.readAllBytes(p);
                List<CatalogEntry> list = MAPPER.readValue(data, LIST_TYPE);
                if (list == null || list.isEmpty()) continue;
                long mtime;
                try {
                    mtime = Files.getLastModifiedTime(p).toMillis();
                } catch (Exception ignored) {
                    mtime = 0;
                }
                if (bestPath == null || mtime > bestMtime) {
                    best = list;
                    bestMtime = mtime;
                    bestPath = p;
                }
            } catch (Exception e) {
                AppLogger.warning("DriverCatalogDatabase: Failed to load refreshed catalog at " + p + ": " + e.getMessage());
            }
        }
        if (bestPath != null) {
            AppLogger.info("DriverCatalogDatabase: Found refreshed catalog at " + bestPath + " (" + best.size() + " entries)");
        }
        return best;
    }

    private static List<Path> refreshedCandidates() {
        List<Path> out = new ArrayList<>();
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) out.add(portable.resolve("catalog").resolve("driver-catalog.json"));
        } catch (Exception ignored) {
        }
        try {
            out.add(AppPaths.localAppData().resolve("catalog").resolve("driver-catalog.json"));
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean isValidRefreshedEntry(CatalogEntry e) {
        if (e == null || e.id() == null || e.id().isBlank()) return false;
        if (e.provider() == null || e.provider().isBlank()) return false;
        String ver = e.latestDriverVersion() != null && !e.latestDriverVersion().isBlank()
                ? e.latestDriverVersion() : e.latestVersion();
        // Numeric plausibility (same bar as scraped versions): a "9999" style
        // version would otherwise become a phantom update for every match.
        if (ver == null || ver.isBlank() || ver.length() > 64) return false;
        if (!AbstractOemCatalogProvider.isPlausibleVersion(ver)) return false;
        if (e.confidence() < 0 || e.confidence() > 1) return false;
        // URLs must stay https (sanitizeSourceUrl enforces; reject non-https here).
        for (String url : new String[]{e.sourceUrl(), e.vendorPageUrl()}) {
            if (url != null && !url.isBlank()) {
                String s = sanitizeSourceUrl(url);
                if (s.isBlank()) return false;
            }
        }
        // Hashes/thumbprints buy gate trust: junk values ("x") must not pass.
        if (e.hashSha256() != null && !e.hashSha256().isBlank()
                && !e.hashSha256().trim().matches("(?i)[0-9a-f]{64}")) return false;
        if (e.certThumbprint() != null && !e.certThumbprint().isBlank()) {
            String norm = e.certThumbprint().replaceAll("[^0-9a-fA-F]", "");
            if (!(norm.matches("(?i)[0-9a-f]{40}") || norm.matches("(?i)[0-9a-f]{64}"))) return false;
        }
        // NAME_REGEX must carry a specific pattern: ".*"/".+" would match
        // every device, and >=0.95 confidence alone clears the gate.
        if (e.matchMethod() == CatalogEntry.MatchMethod.NAME_REGEX) {
            String mv = e.matchValue();
            if (mv == null || mv.isBlank()) return false;
            String literal = mv.replaceAll("[.*+?^$|(){}\\[\\]\\\\]", "");
            if (literal.replaceAll("[^A-Za-z0-9]", "").length() < 3) return false;
        }
        // Top confidence without any hardware evidence is not refreshable:
        // HW-strong entries still pass via the >=0.8 single-factor rule.
        if (e.confidence() >= 0.95 && (e.hardwareIds() == null || e.hardwareIds().isEmpty())) return false;
        return true;
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
            // Blank installed version with a capped range: the true version is
            // unknown, so a range cannot be honored — skip rather than risk a
            // downgrade. Exception: problem devices (e.g. Code 28, no driver
            // at all) cannot be downgraded, so they keep range-agnostic offers.
            if ((driver.driverVersion() == null || driver.driverVersion().isBlank())
                    && ((e.versionMin() != null && !e.versionMin().isBlank())
                        || (e.versionMax() != null && !e.versionMax().isBlank()))
                    && !isProblemDevice(driver)) continue;
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
                .sorted((a, b) -> {
                    // Prefer more specific HWID matches (SUBSYS-full > VEN&DEV prefix),
                    // then higher confidence. Ranking only — gating above is unchanged.
                    int spec = Integer.compare(hwidSpecificity(driver, b), hwidSpecificity(driver, a));
                    if (spec != 0) return spec;
                    return Double.compare(b.confidence(), a.confidence());
                })
                .collect(Collectors.toList());
    }

    /**
     * Human-readable explanation of why a driver matched its best catalog entry.
     * Used in the Details dialog ("Why this match"). Returns "" when no match.
     */
    public String describeMatch(InstalledDriver driver) {
        if (driver == null) return "";
        List<CatalogEntry> matches = findMatchingEntries(driver);
        if (matches.isEmpty()) return "";
        CatalogEntry best = matches.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(best.id()).append(" · confidence ").append(String.format("%.0f", best.confidence() * 100)).append("%");
        String method = best.matchMethod() != null ? best.matchMethod().name() : "HARDWARE_ID";
        sb.append(" · ").append(method);
        if (best.hardwareIds() != null && !best.hardwareIds().isEmpty()
                && driver.hardwareIds() != null && !driver.hardwareIds().isBlank()) {
            String hwUpper = driver.hardwareIds().toUpperCase();
            boolean subsys = hwUpper.contains("SUBSYS_") && best.hardwareIds().stream()
                    .anyMatch(h -> h != null && h.toUpperCase().contains("SUBSYS_"));
            sb.append(subsys ? " (SUBSYS-specific)" : " (VEN/DEV)");
        }
        if (best.matchValue() != null && !best.matchValue().isBlank()
                && best.matchMethod() == CatalogEntry.MatchMethod.NAME_REGEX) {
            sb.append(" /").append(best.matchValue()).append("/");
        }
        return sb.toString();
    }

    /**
     * Specificity score for HWID matching: number of '&amp;'-separated segments
     * of the longest catalog HWID that prefix-matches the device. A full
     * VEN+DEV+SUBSYS match outranks a VEN+DEV prefix; non-HWID entries score 0.
     */
    static int hwidSpecificity(InstalledDriver driver, CatalogEntry entry) {
        try {
            if (driver == null || entry == null || entry.hardwareIds() == null) return 0;
            String hw = driver.hardwareIds();
            if (hw == null || hw.isBlank()) return 0;
            String[] deviceParts = hw.split(";");
            int best = 0;
            for (String devPart : deviceParts) {
                String normDev = normalizeHardwareId(devPart);
                if (normDev.isEmpty()) continue;
                for (String catalogHw : entry.hardwareIds()) {
                    String normCat = normalizeHardwareId(catalogHw);
                    if (normCat.isEmpty()) continue;
                    if (matchesHardwareId(normDev, normCat)) {
                        int segments = normCat.isEmpty() ? 0 : normCat.split("&").length;
                        if (segments > best) best = segments;
                    }
                }
            }
            return best;
        } catch (Exception ignored) {
            return 0;
        }
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

    /**
     * Ranks tags through the shared {@link UpdateSeverity#fromString} parser so
     * catalog severities agree with WU ones. Substring matching ("non-critical"
     * contains "critical", "insecurity" contains "security") used to inflate
     * badges. Untagged entries keep the historical RECOMMENDED default.
     */
    static UpdateSeverity severityFromTags(java.util.List<String> tags) {
        UpdateSeverity best = null;
        if (tags != null) {
            for (String t : tags) {
                UpdateSeverity s = UpdateSeverity.fromString(t);
                if (s != null && rank(s) > rank(best)) {
                    best = s;
                }
            }
        }
        return best == null || best == UpdateSeverity.UNKNOWN ? UpdateSeverity.RECOMMENDED : best;
    }

    private static int rank(UpdateSeverity s) {
        if (s == null) return -1;
        return switch (s) {
            case CRITICAL -> 4;
            case IMPORTANT -> 3;
            case RECOMMENDED -> 2;
            case OPTIONAL -> 1;
            case UNKNOWN -> 0;
        };
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

    /** A device reporting a problem (or nothing at all) has no working driver to downgrade. */
    static boolean isProblemDevice(InstalledDriver driver) {
        if (driver == null) return false;
        String status = driver.status();
        return status != null && !status.isBlank() && !"OK".equalsIgnoreCase(status);
    }

    static boolean isWithinVersionRange(String installed, String min, String max) {        try {
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
        // Split x64 vs ARM64 explicitly: osArch.contains("64") is true on
        // aarch64 too, which previously let x64 kernel drivers through on ARM64.
        boolean isArm64 = osArch.contains("aarch64") || osArch.contains("arm64");
        boolean isX64 = !isArm64 && (osArch.contains("amd64") || osArch.contains("x86_64") || osArch.contains("64"));
        // ARM64 first: "arm64" contains "64" and must not take the x64 branch.
        if (a.contains("arm64") || a.contains("aarch64")) return isArm64;
        if (a.contains("64") || a.contains("amd64") || a.contains("x64")) return isX64;
        if (a.equals("x86") || a.equals("32") || a.contains("386")) return !isX64 && !isArm64;
        // Generic "arm": "aarch64" has no contiguous "arm" substring, so test
        // the flag explicitly or the entry is wrongly refused on ARM64.
        if (a.contains("arm")) return isArm64 || osArch.contains("arm");
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

    // Compiled-pattern cache: findByNameRegex runs per driver per scan, and
    // recompiling every time wastes CPU. Kept static (entries are immutable).
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> REGEX_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    // Cap match input: bounds catastrophic backtracking from hostile
    // refreshed patterns (nested quantifiers pass the literal-length gate).
    private static final int REGEX_INPUT_CAP = 256;

    private static Pattern cachedRegex(String matchValue) {
        Pattern cached = REGEX_CACHE.get(matchValue);
        if (cached != null) return cached;
        Pattern compiled = Pattern.compile(matchValue, Pattern.CASE_INSENSITIVE);
        Pattern prev = REGEX_CACHE.putIfAbsent(matchValue, compiled);
        return prev != null ? prev : compiled;
    }

    private List<CatalogEntry> findByNameRegex(InstalledDriver driver) {
        String name = driver.friendlyName();
        if (name == null || name.isBlank()) {
            return List.of();
        }
        String query = name.length() > REGEX_INPUT_CAP ? name.substring(0, REGEX_INPUT_CAP) : name;
        String nameUpper = query.toUpperCase();
        List<CatalogEntry> matches = new ArrayList<>();
        for (CatalogEntry entry : nameRegexEntries) {
            if (entry.matchValue() != null) {
                try {
                    Pattern p = cachedRegex(entry.matchValue());
                    if (p.matcher(query).find()) {
                        matches.add(entry);
                    }
                } catch (PatternSyntaxException e) {
                    if (nameUpper.contains(entry.matchValue().toUpperCase())) {
                        matches.add(entry);
                    }
                } catch (StackOverflowError | IllegalStateException e) {
                    // Pathological backtracking on hostile patterns: skip the
                    // entry rather than hanging the provider thread.
                    AppLogger.warning("DriverCatalogDatabase: Skipping hostile regex for entry " + entry.id());
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
     * match DEV_27231). Falls back to bus-agnostic VEN+DEV (or VID+PID)
     * token comparison so HDAUDIO\FUNC_01&amp;VEN_10EC&amp;DEV_0888 matches
     * catalog PCI\VEN_10EC&amp;DEV_0888 entries for the same codec.
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
        return matchesDeviceTokens(a, b);
    }

    /**
     * Bus-agnostic fallback: same physical device enumerated under a different
     * bus prefix (HDAUDIO vs PCI, USB vs PCI). Requires both vendor and device
     * tokens to match exactly; a vendor-only match is rejected (wrong-device risk).
     * When either side carries a SUBSYS_ variant tag, the variants must be
     * equal: a catalog entry for SUBSYS_AAA must never match a SUBSYS_BBB
     * device (or a device with no SUBSYS evidence at all).
     */
    private static boolean matchesDeviceTokens(String a, String b) {
        // SUBSYS variant rule: reject only when BOTH sides name a variant and
        // they differ (OEM-specific entry vs different-OEM device). When only
        // one side has SUBSYS_ (generic catalog entry vs specific device, or
        // vice versa) there is no contradiction: fall through to VEN/DEV.
        // (Requiring either-side presence broke bus-agnostic generic matches
        // such as HDAUDIO VEN_10EC&DEV_0888&SUBSYS_X vs PCI VEN_10EC&DEV_0888.)
        String subA = subsysToken(a);
        String subB = subsysToken(b);
        if (subA != null && subB != null && !subA.equals(subB)) {
            return false;
        }
        String venA = token(a, "VEN_");
        String devA = token(a, "DEV_");
        String venB = token(b, "VEN_");
        String devB = token(b, "DEV_");
        if (venA != null && devA != null && venA.equals(venB) && devA.equals(devB)) {
            return true;
        }
        String vidA = token(a, "VID_");
        String pidA = token(a, "PID_");
        String vidB = token(b, "VID_");
        String pidB = token(b, "PID_");
        return vidA != null && pidA != null && vidA.equals(vidB) && pidA.equals(pidB);
    }

    private static String token(String norm, String prefix) {
        int i = norm.indexOf(prefix);
        if (i < 0 || i + prefix.length() + 4 > norm.length()) return null;
        String v = norm.substring(i, i + prefix.length() + 4);
        return v.matches(prefix + "[0-9A-F]{4}") ? v : null;
    }

    /** Extracts SUBSYS_XXXXXXXX (8 hex) or null when absent/malformed. */
    private static String subsysToken(String norm) {
        int i = norm.indexOf("SUBSYS_");
        if (i < 0 || i + 7 + 8 > norm.length()) return null;
        String v = norm.substring(i, i + 7 + 8);
        return v.matches("SUBSYS_[0-9A-F]{8}") ? v : null;
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
