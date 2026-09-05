package com.sbtools.drivers.catalog;

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

public class WindowsUpdateCatalogProvider implements DriverCatalogProvider {

    private static final long WU_SEARCH_TIMEOUT_SECONDS = 120;

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
        // successful output means "no offers" and is NOT retried.
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                Path script = PowerShellScripts.resolve("wu-search-drivers.ps1");
                ProcessResult result = processRunner.run(
                        ProcessRunner.powershellScript(script.toString(), String.valueOf(WU_SEARCH_TIMEOUT_SECONDS)));
                if (!result.success()) {
                    AppLogger.debug("WindowsUpdate: PowerShell script failed (attempt " + attempt + "/2): " + result.combinedOutput());
                    if (attempt == 1) {
                        try {
                            Thread.sleep(2000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return List.of();
                        }
                        continue;
                    }
                    return List.of();
                }
                AppLogger.debug("WindowsUpdate: Found " + (result.stdout() != null ? result.stdout().length() : 0) + " bytes of output");
                return matchUpdates(installed, result.stdout());
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                AppLogger.debug("WindowsUpdate: Exception (attempt " + attempt + "/2): " + e.getMessage());
                if (attempt == 1 && !(e instanceof InterruptedException)) {
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return List.of();
                    }
                    continue;
                }
                return List.of();
            }
        }
        return List.of();
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
                if (o != null && o.version() != null && !o.version().isBlank()) offers.add(o);
            }
        } else if (root.isObject()) {
            WuDriverOffer o = parseOffer(root);
            if (o != null && o.version() != null && !o.version().isBlank()) offers.add(o);
        }

        List<DriverUpdateCandidate> candidates = new ArrayList<>();
        if (installed == null) return candidates;
        for (InstalledDriver driver : installed) {
            if (driver == null) continue;
            WuDriverOffer best = null;
            for (WuDriverOffer offer : offers) {
                if (matchesDriver(driver, offer)) {
                    if (best == null || VersionCompare.compare(offer.version, best.version) > 0) {
                        best = offer;
                    }
                }
            }
            if (best != null && VersionCompare.isOlder(driver.driverVersion(), best.version)) {
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

    private static final java.util.Set<String> GENERIC_WORDS = java.util.Set.of(
            "driver", "device", "controller", "adapter", "software", "component", "extension", "generic"
    );

    private static boolean matchesDriver(InstalledDriver driver, WuDriverOffer offer) {
        if (driver == null || offer == null) return false;
        if (offer.title == null || offer.title.isBlank()) return false;
        String title = offer.title.toLowerCase(Locale.ROOT);
        String nameRaw = driver.friendlyName() != null ? driver.friendlyName().toLowerCase(Locale.ROOT) : "";
        String name = nameRaw.trim();

        // 1) Exact friendlyName substring — strongest signal, require at least 5 chars to avoid generic matches
        if (name.length() >= 5 && title.contains(name)) {
            return true;
        }

        // 2) INF base name match — must be meaningful (oem*.inf filtered out)
        String inf = driver.infName();
        if (inf != null && !inf.isBlank()) {
            String infBase = inf.replace(".inf", "").toLowerCase(Locale.ROOT).trim();
            boolean isGenericOem = infBase.matches("oem\\d+");
            if (!isGenericOem && infBase.length() >= 5 && title.contains(infBase)) {
                return true;
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
                    return true;
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
                if (validCount >= 2 && providerMatched >= 2 && providerMatched == validCount) return true;
            }
        }

        return false;
    }

    private static WuDriverOffer parseOffer(JsonNode n) {
        return new WuDriverOffer(
                text(n, "updateId"),
                text(n, "title"),
                text(n, "description"),
                text(n, "version"),
                UpdateSeverity.fromString(text(n, "severity"))
        );
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }

    private record WuDriverOffer(String updateId, String title, String description, String version, UpdateSeverity severity) {
    }
}
