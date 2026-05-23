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
        
        // Realtek downloads page
        String body = httpGet("https://www.realtek.com/en/component/zoo/category/driver-downloads");
        String v = extractVersion(body, VERSION);
        
        if (v == null) {
            // Try alternative URL for specific driver categories
            body = httpGet("https://www.realtek.com/en/component/zoo/category/network-driver-downloads");
            v = extractVersion(body, VERSION);
        }
        
        if (v != null) {
            AppLogger.debug("Realtek: Found version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Realtek: Could not find version for " + driver.friendlyName());
        }
        
        return v;
    }
}
