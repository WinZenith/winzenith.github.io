package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemAmdCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
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
        String v = extractVersion(body, Pattern.compile("Adrenalin[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+)"));
        if (v == null) {
            v = extractVersion(body, VERSION);
        }
        
        if (v != null) {
            AppLogger.debug("AMD: Found version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("AMD: Could not find version for " + driver.friendlyName());
        }
        
        return v;
    }
}
