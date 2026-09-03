package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemQualcommCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    public OemQualcommCatalogProvider() {
        super(OemVendorHelper.QUALCOMM);
    }

    public OemQualcommCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.QUALCOMM, catalogDatabase);
    }

    @Override
    public String id() {
        return "Qualcomm";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: generic support-homepage first-number regex matches
        // marketing/JS versions, not the device driver (wrong-version risk).
        AppLogger.debug("Qualcomm: Legacy web scraping disabled (use catalog database). Skipping.");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.qualcomm.com/support";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Qualcomm: Resolving direct download URL for " + driver.friendlyName());
        String page = vendorPageUrl != null && !vendorPageUrl.isBlank() ? vendorPageUrl : getVendorPageUrl(driver);
        String body = httpGet(page);
        if (body != null) {
            java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile(
                    "href\\s*=\\s*\"([^\"]+\\.(?:exe|zip|msi))\"", java.util.regex.Pattern.CASE_INSENSITIVE);
            java.util.regex.Matcher m = linkPattern.matcher(body);
            while (m.find()) {
                String url = m.group(1);
                if (url.startsWith("//")) url = "https:" + url;
                else if (url.startsWith("/")) url = "https://www.qualcomm.com" + url;
                if (url.toLowerCase().contains("qualcomm.com") && isLikelyStable(url)) {
                    AppLogger.info("Qualcomm: Found download URL: " + url);
                    return decodeHtmlEntities(url);
                }
            }
        }
        AppLogger.info("Qualcomm: No direct download found, user will be directed to vendor website");
        return null;
    }
}
