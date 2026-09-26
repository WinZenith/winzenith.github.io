package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemSynapticsCatalogProvider extends AbstractOemCatalogProvider {

    private static final String TOUCHPAD_DRIVERS_PAGE = "https://www.synaptics.com/products/touchpad-drivers";

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "(?:Synaptics|Touchpad|Pointing|PS/2)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);

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

        String body = httpGet(TOUCHPAD_DRIVERS_PAGE);
        if (body != null) {
            String v = extractVersion(body, VERSION_PATTERN);
            if (v != null) {
                AppLogger.debug("Synaptics: Found version " + v + " for " + driver.friendlyName());
                return v;
            }
            // No generic-number fallback: first bare number on the support
            // homepage is not device-specific (wrong-version risk).
        }

        // No hardcoded fallback versions: stale hardcodes cause downgrades.
        AppLogger.debug("Synaptics: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return TOUCHPAD_DRIVERS_PAGE;
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
            java.util.List<String> found = new java.util.ArrayList<>();
            while (m.find()) {
                String url = m.group(1);
                if (url.startsWith("//")) url = "https:" + url;
                else if (url.startsWith("/")) url = "https://www.synaptics.com" + url;
                String lower = url.toLowerCase();
                if ((lower.contains("synaptics") || lower.contains("softpaq") || lower.contains("hp.com") || lower.contains("lenovo.com")) && isLikelyStable(url)) {
                    found.add(decodeHtmlEntities(url));
                }
            }
            String picked = com.sbtools.drivers.DriverInstallTrust.pickBestDownloadUrl(found, driver);
            if (picked != null) {
                AppLogger.info("Synaptics: Found download URL: " + picked);
                return picked;
            }
        }
        AppLogger.info("Synaptics: No device-matching download found, will rely on Windows Update or OEM site");
        return null;
    }
}
