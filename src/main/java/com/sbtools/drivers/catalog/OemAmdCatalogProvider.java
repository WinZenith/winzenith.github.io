package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemAmdCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern ADRENALIN_VERSION = Pattern.compile(
            "Adrenalin[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMD_VERSION = Pattern.compile(
            "(?:AMD|RADEON|Adrenalin|WHQL)[^0-9]*([0-9]{2}\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_VERSION = Pattern.compile(
            "([0-9]{2}\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);

    public OemAmdCatalogProvider() {
        super(OemVendorHelper.AMD);
    }

    @Override
    public String id() {
        return "AMD";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("AMD: Fetching latest version for " + driver.friendlyName());

        String body = httpGet("https://www.amd.com/en/support");
        if (body != null) {
            String v = extractVersion(body, ADRENALIN_VERSION);
            if (v == null) {
                v = extractVersion(body, AMD_VERSION);
            }
            if (v == null) {
                v = extractVersion(body, GENERIC_VERSION);
            }
            if (v != null) {
                AppLogger.debug("AMD: Found version " + v + " for " + driver.friendlyName());
                return v;
            }
        }

        String fallback = getFallbackVersion(driver);
        if (fallback != null) {
            AppLogger.debug("AMD: Using fallback version " + fallback + " for " + driver.friendlyName());
            return fallback;
        }

        AppLogger.debug("AMD: Could not find version for " + driver.friendlyName());
        return null;
    }

    private String getFallbackVersion(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        if (name.contains("radeon") && name.contains("rx")) {
            return "24.12.1";
        }
        if (name.contains("radeon") && name.contains("vega")) {
            return "24.12.1";
        }
        if (name.contains("radeon") && name.contains("pro")) {
            return "24.12.1";
        }
        if (name.contains("chipset") || name.contains("b450") || name.contains("b550")
                || name.contains("x470") || name.contains("x570")) {
            return "6.03.19.217";
        }
        return null;
    }
}
