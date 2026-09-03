package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemBroadcomCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

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

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.broadcom.com/support/download-search";
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
        while (m.find()) {
            String url = m.group(1);
            if (url.startsWith("//")) url = "https:" + url;
            else if (url.startsWith("/")) url = "https://www.broadcom.com" + url;
            if (url.toLowerCase().contains("broadcom.com") && isLikelyStable(url)) {
                AppLogger.info("Broadcom: Found download URL: " + url);
                return decodeHtmlEntities(url);
            }
        }
        // Fallback to generic scrape (AbstractOemCatalogProvider)
        AppLogger.info("Broadcom: No direct download found, user will be directed to vendor website");
        return null;
    }
}
