package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.drivers.model.UpdateSeverity;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.JsonMapper;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;
import com.basicsdriverupdate.util.VersionCompare;
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
        if (!com.basicsdriverupdate.util.AppPaths.isWindows()) {
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
                        best.severity
                ));
            }
        }
        return candidates;
    }

    private static boolean matchesDriver(InstalledDriver driver, WuDriverOffer offer) {
        String title = offer.title.toLowerCase(Locale.ROOT);
        String name = driver.friendlyName().toLowerCase(Locale.ROOT);
        if (!name.isBlank() && title.contains(name)) {
            return true;
        }
        if (driver.provider() != null && !driver.provider().isBlank()) {
            String prov = driver.provider().toLowerCase(Locale.ROOT);
            if (title.contains(prov)) {
                return true;
            }
        }
        String inf = driver.infName();
        if (inf != null && !inf.isBlank() && title.contains(inf.replace(".inf", "").toLowerCase(Locale.ROOT))) {
            return true;
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
