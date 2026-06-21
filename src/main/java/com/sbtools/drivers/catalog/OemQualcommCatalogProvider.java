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
        AppLogger.debug("Qualcomm: Fetching latest version for " + driver.friendlyName());
        
        // Qualcomm support page
        String body = httpGet("https://www.qualcomm.com/products");
        String v = extractVersion(body, VERSION);
        
        if (v == null) {
            // Try alternative URL for drivers
            body = httpGet("https://www.qualcomm.com/support");
            v = extractVersion(body, VERSION);
        }
        
        if (v != null) {
            AppLogger.debug("Qualcomm: Found version " + v + " for " + driver.friendlyName());
        } else {
            AppLogger.debug("Qualcomm: Could not find version for " + driver.friendlyName());
        }
        
        return v;
    }
}
