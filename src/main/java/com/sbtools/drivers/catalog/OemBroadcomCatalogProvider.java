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

    @Override
    public String id() {
        return "Broadcom";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Broadcom: Fetching latest version for " + driver.friendlyName());
        
        // Broadcom support page
        String body = httpGet("https://www.broadcom.com/support/download-search");
        String v = extractVersion(body, VERSION);
        
        if (v == null) {
            // Try alternative URL for Bluetooth drivers
            body = httpGet("https://www.broadcom.com/products/bluetooth");
            v = extractVersion(body, VERSION);
        }
        
        if (v != null) {
            AppLogger.debug("Broadcom: Found version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Broadcom: Could not find version for " + driver.friendlyName());
        }
        
        return v;
    }
}
