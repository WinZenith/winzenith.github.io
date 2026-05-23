package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

import java.util.regex.Pattern;

public class OemNvidiaCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern GPU_MODEL_PATTERN = Pattern.compile(
            "(GTX|RTX|GT|GTX\\s+\\d+|RTX\\s+\\d+|GT\\s+\\d+)", Pattern.CASE_INSENSITIVE);

    public OemNvidiaCatalogProvider() {
        super(OemVendorHelper.NVIDIA);
    }

    @Override
    public String id() {
        return "Nvidia";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Nvidia: Fetching latest version for " + driver.friendlyName());
        
        String gpuModel = extractGpuModel(driver);
        AppLogger.debug("Nvidia: Detected GPU model: " + gpuModel + " for " + driver.friendlyName());
        
        // NVIDIA driver search API (public JSON endpoint used by driver search pages)
        String body = httpGet("https://gfwsl.geforce.com/services/graphql?"
                + "query=query%20DriverReleases%7BdriverReleases%7Bversion%7D%7D");
        
        if (body == null) {
            body = httpGet("https://www.nvidia.com/en-us/drivers/");
        }
        
        String v = extractVersion(body, VERSION);
        if (v == null && body != null) {
            v = extractVersion(body, Pattern.compile("Driver Version\\s+([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)"));
        }
        
        if (v != null) {
            AppLogger.debug("Nvidia: Found version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Nvidia: Could not find version for " + driver.friendlyName());
        }
        
        return v;
    }

    private String extractGpuModel(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName() : "";
        java.util.regex.Matcher m = GPU_MODEL_PATTERN.matcher(name);
        if (m.find()) {
            return m.group(1).trim();
        }
        return "unknown";
    }
}
