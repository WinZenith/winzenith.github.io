package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

import java.util.regex.Pattern;

public class OemRealtekCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    public OemRealtekCatalogProvider() {
        super(OemVendorHelper.REALTEK);
    }

    @Override
    public String id() {
        return "Realtek";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Realtek: Fetching latest version for " + driver.friendlyName());
        
        String v = getFallbackVersion(driver);
        
        if (v != null) {
            AppLogger.debug("Realtek: Using fallback version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Realtek: No fallback version for " + driver.friendlyName() + " - skipping");
        }
        
        return v;
    }

    private String getFallbackVersion(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        
        if (name.contains("cardreader") || name.contains("card reader")) {
            return "10.0.26100.21384";
        }
        if (name.contains("gbe family controller")) {
            return "10.75.324.2026";
        }
        
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://realtek-hd-audio-drivers-x64.en.softonic.com/download";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Realtek: No direct download available for " + driver.friendlyName()
                + " - user will be directed to vendor website");
        return null;
    }
}
