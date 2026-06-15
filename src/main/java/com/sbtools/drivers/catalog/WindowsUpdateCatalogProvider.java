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
        try {
            Path script = PowerShellScripts.resolve("wu-search-drivers.ps1");
            ProcessResult result = processRunner.run(
                    ProcessRunner.powershellScript(script.toString(), String.valueOf(WU_SEARCH_TIMEOUT_SECONDS)));
            if (!result.success()) {
                AppLogger.debug("WindowsUpdate: PowerShell script failed: " + result.combinedOutput());
                return List.of();
            }
            AppLogger.debug("WindowsUpdate: Found " + result.stdout().length() + " bytes of output");
            return matchUpdates(installed, result.stdout());
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            AppLogger.debug("WindowsUpdate: Exception: " + e.getMessage());
            return List.of();
        }
    }

    static List<DriverUpdateCandidate> matchUpdates(List<InstalledDriver> installed, String json)
            throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonMapper.parseTree(json);
        List<WuDriverOffer> offers = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                offers.add(parseOffer(n));
            }
        } else if (root.isObject()) {
            offers.add(parseOffer(root));
        }

        List<DriverUpdateCandidate> candidates = new ArrayList<>();
        for (InstalledDriver driver : installed) {
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
        String title = offer.title.toLowerCase(Locale.ROOT);
        String name = driver.friendlyName().toLowerCase(Locale.ROOT);

        if (!name.isBlank() && title.contains(name)) {
            return true;
        }

        String inf = driver.infName();
        if (inf != null && !inf.isBlank()) {
            String infBase = inf.replace(".inf", "").toLowerCase(Locale.ROOT);
            if (infBase.length() >= 4 && title.contains(infBase)) {
                return true;
            }
        }

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
            if (validTokensCount > 0 && matched == validTokensCount) {
                return true;
            }
            if (matched >= 3) {
                return true;
            }
        }

        if (driver.provider() != null && !driver.provider().isBlank()) {
            String prov = driver.provider().toLowerCase(Locale.ROOT);
            if (!name.isBlank() && title.contains(prov) && title.contains(name)) {
                return true;
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
