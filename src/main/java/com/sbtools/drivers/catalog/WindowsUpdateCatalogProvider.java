package com.sbtools.drivers.catalog;

import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.drivers.model.UpdateSeverity;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.VersionCompare;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;

public class WindowsUpdateCatalogProvider implements DriverCatalogProvider {

    private record MatchProposal(InstalledDriver driver, WuDriverOffer offer, int titleStrength, int hwRank) {}

    private static final long WU_SEARCH_TIMEOUT_SECONDS = 120;
    private static final long WU_INTER_ATTEMPT_PAUSE_SECONDS = 2;
    private static final long WU_CATALOG_WAIT_SLACK_SECONDS = 5;

    /**
     * Wall-clock budget for {@link DriverCatalogAggregator} to wait on this
     * provider: two script attempts, inter-attempt pause, and small slack.
     */
    public static long catalogAggregatorWaitBudgetSeconds() {
        return 2L * WU_SEARCH_TIMEOUT_SECONDS + WU_INTER_ATTEMPT_PAUSE_SECONDS + WU_CATALOG_WAIT_SLACK_SECONDS;
    }

    private final ProcessRunner processRunner = new ProcessRunner(WU_SEARCH_TIMEOUT_SECONDS);

    @Override
    public String id() {
        return "WindowsUpdate";
    }

    @Override
    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        AppLogger.debug("WindowsUpdate: Searching for driver updates");
        if (!com.sbtools.util.AppPaths.isWindows()) {
            AppLogger.debug("WindowsUpdate: Not running on Windows, skipping");
            return List.of();
        }
        // One retry on script-level failure (timeout/non-zero exit). Empty but
        // successful output means "no offers" and is NOT retried. Failures throw
        // so callers can tell "search failed" from "no offers" (a returned empty
        // list used to be cached and shown as a healthy PC).
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Path script = PowerShellScripts.resolve("wu-search-drivers.ps1");
                ProcessResult result = processRunner.run(
                        ProcessRunner.powershellScriptNonInteractive(
                                script.toString(), String.valueOf(WU_SEARCH_TIMEOUT_SECONDS)));
                if (!result.success()) {
                    AppLogger.debug("WindowsUpdate: PowerShell script failed (attempt " + attempt + "/2): " + result.combinedOutput());
                    if (attempt == 1 && !Thread.currentThread().isInterrupted()) {
                        pauseBeforeRetry();
                        continue;
                    }
                    throw new IllegalStateException("Windows Update driver search failed");
                }
                AppLogger.debug("WindowsUpdate: Found " + (result.stdout() != null ? result.stdout().length() : 0) + " bytes of output");
                return matchUpdates(installed, result.stdout());
            } catch (CancellationException ce) {
                throw ce;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Windows Update driver search interrupted");
            } catch (IOException e) {
                AppLogger.debug("WindowsUpdate: Exception (attempt " + attempt + "/2): " + e.getMessage());
                if (attempt == 1 && !Thread.currentThread().isInterrupted()) {
                    pauseBeforeRetry();
                    continue;
                }
                throw new IllegalStateException("Windows Update driver search failed: " + e.getMessage(), e);
            }
        }
        throw new IllegalStateException("Windows Update driver search failed");
    }

    private static void pauseBeforeRetry() {
        try {
            Thread.sleep(WU_INTER_ATTEMPT_PAUSE_SECONDS * 1000L);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Windows Update driver search interrupted");
        }
    }

    static List<DriverUpdateCandidate> matchUpdates(List<InstalledDriver> installed, String json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        // Empty WU output (no offers) is normal — not an error. Guard here so
        // callers never see a parse exception for the common no-update case.
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root = JsonMapper.parseTree(json);
        List<WuDriverOffer> offers = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                WuDriverOffer o = parseOffer(n);
                if (isInstallableOffer(o)) offers.add(o);
            }
        } else if (root.isObject()) {
            WuDriverOffer o = parseOffer(root);
            if (isInstallableOffer(o)) offers.add(o);
        }

        List<DriverUpdateCandidate> candidates = new ArrayList<>();
        if (installed == null) return candidates;

        List<MatchProposal> proposals = new ArrayList<>();
        for (InstalledDriver driver : installed) {
            if (driver == null) continue;
            for (WuDriverOffer offer : offers) {
                if (offer.driverHardwareId == null || offer.driverHardwareId.isBlank()) {
                    AppLogger.debug("WindowsUpdate: Skipping offer without hardware ID: " + offer.updateId);
                    continue;
                }
                if (!DriverCatalogDatabase.hardwareCompatible(driver, offer.driverHardwareId)) {
                    continue;
                }
                int titleStrength = matchStrength(driver, offer);
                int hwRank = hardwareMatchRank(driver, offer.driverHardwareId);
                proposals.add(new MatchProposal(driver, offer, titleStrength, hwRank));
            }
        }

        java.util.Map<String, MatchProposal> bestPerUpdateId = new java.util.HashMap<>();
        for (MatchProposal p : proposals) {
            String uid = p.offer.updateId;
            MatchProposal existing = bestPerUpdateId.get(uid);
            if (existing == null || compareProposals(p, existing) > 0) {
                bestPerUpdateId.put(uid, p);
            }
        }

        for (MatchProposal p : bestPerUpdateId.values()) {
            InstalledDriver driver = p.driver;
            WuDriverOffer best = p.offer;
            if (VersionCompare.isOlder(driver.driverVersion(), best.version)) {
                candidates.add(new DriverUpdateCandidate(
                        driver,
                        best.version,
                        "WindowsUpdate",
                        best.updateId,
                        best.title,
                        best.description,
                        best.severity,
                        "",
                        "https://www.catalog.update.microsoft.com"
                ));
            }
        }
        return candidates;
    }

    private static int compareProposals(MatchProposal a, MatchProposal b) {
        int c = Integer.compare(a.hwRank, b.hwRank);
        if (c != 0) return c;
        c = Integer.compare(a.titleStrength, b.titleStrength);
        if (c != 0) return c;
        c = compareOffers(a.offer, b.offer);
        if (c != 0) return c;
        String da = a.driver.deviceId() == null ? "" : DriverScanService.normalizeDeviceKey(a.driver.deviceId());
        String db = b.driver.deviceId() == null ? "" : DriverScanService.normalizeDeviceKey(b.driver.deviceId());
        return da.compareTo(db);
    }

    private static int hardwareMatchRank(InstalledDriver driver, String offerHw) {
        if (driver == null || offerHw == null) return 0;
        String offerNorm = offerHw.toUpperCase(Locale.ROOT);
        if (driver.deviceId() != null && offerNorm.equals(driver.deviceId().toUpperCase(Locale.ROOT))) {
            return 4;
        }
        String hw = driver.hardwareIds() == null ? "" : driver.hardwareIds().toUpperCase(Locale.ROOT);
        if (hw.contains(offerNorm)) {
            return 3;
        }
        return 2;
    }

    /**
     * An offer is only usable when it carries a plausible version AND a
     * non-blank updateId/title. A newer-but-identity-less offer must never
     * displace an older installable one (empty packageId installs nothing).
     */
    private static boolean isInstallableOffer(WuDriverOffer o) {
        if (o == null) return false;
        if (!isPlausibleVersion(o.version())) return false;
        if (o.updateId() == null || o.updateId().isBlank()) return false;
        return o.title() != null && !o.title().isBlank();
    }

    /**
     * Best-pick order: version first, then severity rank, then updateId for a
     * deterministic winner (the old strict-greater comparison left ties to
     * COM enumeration order, flipping severity run to run).
     */
    private static int compareOffers(WuDriverOffer a, WuDriverOffer b) {
        int cmp = VersionCompare.compare(a.version(), b.version());
        if (cmp != 0) return cmp;
        cmp = Integer.compare(severityRank(a.severity()), severityRank(b.severity()));
        if (cmp != 0) return cmp;
        String idA = a.updateId() == null ? "" : a.updateId();
        String idB = b.updateId() == null ? "" : b.updateId();
        return idA.compareTo(idB);
    }

    private static int severityRank(UpdateSeverity s) {
        if (s == null) return 0;
        return switch (s) {
            case CRITICAL -> 4;
            case IMPORTANT -> 3;
            case RECOMMENDED -> 2;
            case OPTIONAL -> 1;
            case UNKNOWN -> 0;
        };
    }

    private static final java.util.Set<String> GENERIC_WORDS = java.util.Set.of(
            "driver", "device", "controller", "adapter", "software", "component", "extension", "generic"
    );

    /**
     * Windows in-box class INFs: they identify a setup class, not a device.
     * Matching offers on these alone cross-matches any same-class vendor
     * driver (e.g. display.inf vs "NVIDIA - Display").
     */
    private static final java.util.Set<String> GENERIC_INFS = java.util.Set.of(
            "display", "machine", "usb", "usbport", "volume", "wpdfs", "wpfsm",
            "swenum", "ks", "kscaptur", "wdmaudio", "wdma_usb", "netav",
            "basicdisplay", "basicrender", "monitor", "keyboard", "mouse",
            "disk", "cdrom", "volsnap", "partmgr", "msports", "serenum"
    );

    /**
     * Match evidence strength 0-4 (mirrors the numbered rules below). The
     * best-pick ranks strength before version so a weak fallback match with a
     * coincidental tag can never displace a strong exact match at the same
     * version (previously severity-then-GUID decided, flipping run to run).
     */
    static int matchStrength(InstalledDriver driver, WuDriverOffer offer) {
        if (driver == null || offer == null) return 0;
        if (offer.title == null || offer.title.isBlank()) return 0;
        String title = offer.title.toLowerCase(Locale.ROOT);
        String nameRaw = driver.friendlyName() != null ? driver.friendlyName().toLowerCase(Locale.ROOT) : "";
        String name = nameRaw.trim();

        // 1) Exact friendlyName substring — strongest signal, require at least 5 chars to avoid generic matches
        if (name.length() >= 5 && title.contains(name)) {
            return 4;
        }

        // 2) INF base name match — must be meaningful (oem*.inf filtered out).
        // In-box generic INFs (display.inf, machine.inf, usb.inf, ...) name a
        // device *class*, not a device: "display" matches every NVIDIA Display
        // offer. Denylisted so they can never match on INF alone.
        String inf = driver.infName();
        if (inf != null && !inf.isBlank()) {
            String infBase = inf.replace(".inf", "").toLowerCase(Locale.ROOT).trim();
            boolean isGenericOem = infBase.matches("oem\\d+");
            if (!isGenericOem && !GENERIC_INFS.contains(infBase)
                    && infBase.length() >= 5 && title.contains(infBase)) {
                return 3;
            }
        }

        // 3) Full significant-token match — require at least 2 significant tokens from device name, all must appear in title
        //    and offer must not introduce extra significant tokens (strict). This prevents single-token “Realtek” matching every Realtek offer.
        if (!name.isBlank()) {
            String[] tokens = name.split("[\\s,\\-()]+");
            int validTokensCount = 0;
            int matched = 0;
            for (String token : tokens) {
                if (token.length() >= 3 && !GENERIC_WORDS.contains(token)) {
                    validTokensCount++;
                    if (title.contains(token)) {
                        matched++;
                    }
                }
            }
            if (validTokensCount >= 2 && matched == validTokensCount) {
                String[] offerTokens = title.split("[\\s,\\-()]+");
                int offerSignificantCount = 0;
                for (String ot : offerTokens) {
                    if (ot.length() >= 3 && !GENERIC_WORDS.contains(ot)) {
                        offerSignificantCount++;
                    }
                }
                if (offerSignificantCount - validTokensCount <= 1) {
                    return 2;
                }
            }
        }

        // 4) Provider-anchored fallback: provider must appear in title plus at least two significant device tokens.
        //    Single-token matches (e.g. "HP" + "keyboard") are rejected: they
        //    cross-match any same-vendor offer (wrong-device risk).
        if (driver.provider() != null && !driver.provider().isBlank() && !name.isBlank()) {
            String prov = driver.provider().toLowerCase(Locale.ROOT).trim();
            if (prov.length() >= 3 && title.contains(prov)) {
                String[] tokens = name.split("[\\s,\\-()]+");
                int providerMatched = 0;
                int validCount = 0;
                for (String token : tokens) {
                    if (token.length() >= 3 && !GENERIC_WORDS.contains(token)) {
                        validCount++;
                        if (title.contains(token)) providerMatched++;
                    }
                }
                if (validCount >= 2 && providerMatched >= 2 && providerMatched == validCount) return 1;
            }
        }

        return 0;
    }

    private static WuDriverOffer parseOffer(JsonNode n) {
        // Prefer the script-computed version (it sees both DriverModel and
        // Title); fall back to the title-derived fullest version when the
        // field is missing or implausible.
        String scripted = text(n, "version");
        String version = isPlausibleVersion(scripted)
                ? scripted : bestVersionFrom("", text(n, "title"));
        return new WuDriverOffer(
                text(n, "updateId"),
                text(n, "title"),
                text(n, "description"),
                version,
                UpdateSeverity.fromString(text(n, "severity")),
                text(n, "driverHardwareId"),
                text(n, "driverModel"),
                text(n, "driverProvider"),
                text(n, "driverClass")
        );
    }

    /**
     * Picks the fullest version across DriverModel and Title (most dot-separated
     * numeric parts wins): the old cascade preferred a short DriverModel
     * ("6.0.9678") over the full Title ("6.0.9678.1"), understating the offer
     * into missed updates and false VERIFIEDs.
     */
    static String bestVersionFrom(String driverModel, String title) {
        String best = "";
        int bestParts = 0;
        for (String field : new String[]{driverModel, title}) {
            if (field == null || field.isBlank()) continue;
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)+\\b").matcher(field);
            while (m.find()) {
                String v = m.group(0);
                int parts = v.split("\\.").length;
                if (parts > bestParts || (parts == bestParts && v.length() > best.length())) {
                    best = v;
                    bestParts = parts;
                }
            }
        }
        return best;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }

    /**
     * Rejects fabricated versions (raw titles / DriverModel strings). Mirrors
     * {@code AbstractOemCatalogProvider.isPlausibleVersion}: requires numeric
     * major.minor form so a title like "NVIDIA - Display" never becomes an
     * Available version and never flips outdated detection via lexicographic
     * fallback in {@code VersionCompare}.
     */
    static boolean isPlausibleVersion(String v) {
        if (v == null || v.isBlank()) return false;
        String t = v.trim();
        if (!t.matches("(?i).*\\d+\\.\\d+.*")) return false;
        if (t.length() > 64) return false;
        return true;
    }

    private record WuDriverOffer(String updateId, String title, String description, String version,
                                 UpdateSeverity severity, String driverHardwareId, String driverModel,
                                 String driverProvider, String driverClass) {
    }
}
