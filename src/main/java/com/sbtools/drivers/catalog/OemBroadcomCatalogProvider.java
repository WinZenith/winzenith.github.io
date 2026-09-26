package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

public class OemBroadcomCatalogProvider extends AbstractOemCatalogProvider {

    public OemBroadcomCatalogProvider() {
        super(OemVendorHelper.BROADCOM);
    }

    public OemBroadcomCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.BROADCOM, catalogDatabase);
    }

    @Override
    public String id() {
        return "Broadcom";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: generic support-homepage first-number regex matches
        // JS/product versions, not the device driver (wrong-version risk).
        AppLogger.debug("Broadcom: Legacy web scraping disabled (use catalog database). Skipping.");
        return null;
    }

    private static final String WIRELESS_SUPPORT = "https://www.broadcom.com/support/wireless-networking";
    private static final String DOWNLOAD_SEARCH = "https://www.broadcom.com/support/download-search";

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        if (driver != null && driver.friendlyName() != null) {
            String name = driver.friendlyName().toLowerCase(java.util.Locale.ROOT);
            if (name.contains("wifi") || name.contains("wireless") || name.contains("wlan")
                    || name.contains("bcm43") || name.contains("broadcom")) {
                return WIRELESS_SUPPORT;
            }
        }
        return DOWNLOAD_SEARCH;
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Broadcom: Resolving direct download URL for " + driver.friendlyName());
        String page = vendorPageUrl != null && !vendorPageUrl.isBlank() ? vendorPageUrl : getVendorPageUrl(driver);
        String body = httpGet(page);
        if (body == null) {
            AppLogger.warning("Broadcom: Could not fetch download page");
            return null;
        }
        java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
                "href\\s*=\\s*\"([^\"]+\\.(?:exe|zip|msi))\"", java.util.regex.Pattern.CASE_INSENSITIVE);
        java.util.regex.Matcher m = linkPattern.matcher(body);
        java.util.List<String> found = new java.util.ArrayList<>();
        while (m.find()) {
            String url = m.group(1);
            if (url.startsWith("//")) url = "https:" + url;
            else if (url.startsWith("/")) url = "https://www.broadcom.com" + url;
            if (url.toLowerCase().contains("broadcom.com") && isLikelyStable(url)) {
                found.add(decodeHtmlEntities(url));
            }
        }
        String picked = com.sbtools.drivers.DriverInstallTrust.pickBestDownloadUrl(found, driver);
        if (picked != null) {
            AppLogger.info("Broadcom: Found download URL: " + picked);
            return picked;
        }
        AppLogger.info("Broadcom: No device-matching download found, user will be directed to vendor website");
        return null;
    }
}
