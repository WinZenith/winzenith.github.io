package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemSynapticsCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?:Synaptics|Touchpad|Pointing|PS/2)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_VERSION_PATTERN = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    public OemSynapticsCatalogProvider() {
        super(OemVendorHelper.SYNAPTICS);
    }

    public OemSynapticsCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.SYNAPTICS, catalogDatabase);
    }

    @Override
    public String id() {
        return "Synaptics";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Synaptics: Fetching latest version for " + driver.friendlyName());

        String body = httpGet("https://www.synaptics.com/support");
        if (body != null) {
            String v = extractVersion(body, VERSION_PATTERN);
            if (v != null) {
                AppLogger.debug("Synaptics: Found version " + v + " for " + driver.friendlyName());
                return v;
            }
            v = extractVersion(body, GENERIC_VERSION_PATTERN);
            if (v != null) {
                AppLogger.debug("Synaptics: Found generic version " + v + " for " + driver.friendlyName());
                return v;
            }
        }

        String fallback = getFallbackVersion(driver);
        if (fallback != null) {
            AppLogger.debug("Synaptics: Using fallback version " + fallback + " for " + driver.friendlyName());
            return fallback;
        }

        AppLogger.debug("Synaptics: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    private String getFallbackVersion(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        String infName = driver.infName() != null ? driver.infName().toLowerCase() : "";

        if (name.contains("precision") || infName.contains("synpd")) {
            return "19.5.35.0";
        }
        if (name.contains("clickpad") || name.contains("touchpad") || infName.contains("synhid")) {
            return "19.5.35.0";
        }
        if (name.contains("smbus") || infName.contains("smbus")) {
            return "19.0.0.0";
        }
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.synaptics.com/support";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Synaptics: Resolving direct download URL for " + driver.friendlyName());
        String page = vendorPageUrl != null && !vendorPageUrl.isBlank() ? vendorPageUrl : getVendorPageUrl(driver);
        String body = httpGet(page);
        if (body != null) {
            java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
                    "href\\s*=\\s*\"([^\"]+\\.(?:exe|zip|msi|cab))\"", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = linkPattern.matcher(body);
            while (m.find()) {
                String url = m.group(1);
                if (url.startsWith("//")) url = "https:" + url;
                else if (url.startsWith("/")) url = "https://www.synaptics.com" + url;
                String lower = url.toLowerCase();
                if ((lower.contains("synaptics") || lower.contains("softpaq") || lower.contains("hp.com") || lower.contains("lenovo.com")) && isLikelyStable(url)) {
                    AppLogger.info("Synaptics: Found download URL: " + url);
                    return decodeHtmlEntities(url);
                }
            }
        }
        // Synaptics drivers are often hosted on OEM (HP/Lenovo) – try OEM fallback via Microsoft Update Catalog pattern
        AppLogger.info("Synaptics: No direct Synaptics-hosted download, will rely on Windows Update or OEM site");
        return null;
    }
}
