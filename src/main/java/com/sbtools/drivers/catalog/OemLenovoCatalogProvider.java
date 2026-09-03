package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemLenovoCatalogProvider extends AbstractOemCatalogProvider {
    public OemLenovoCatalogProvider() {
        super(OemVendorHelper.LENOVO);
    }

    public OemLenovoCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.LENOVO, catalogDatabase);
    }

    @Override
    public String id() {
        return "Lenovo";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: generic support-homepage scraping returns the same
        // version for every device (wrong-device risk). Only the HW-matched
        // catalog database may produce a candidate.
        com.sbtools.util.AppLogger.debug("Lenovo: Legacy web scraping disabled (use catalog database). Skipping.");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://pcsupport.lenovo.com";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        // Catalog-only: do not scrape the generic support homepage for an
        // arbitrary exe/zip (wrong-file risk). Catalog entries carry the URL.
        com.sbtools.util.AppLogger.debug("Lenovo: Direct download URL resolution disabled (use catalog database). Skipping.");
        return null;
    }
}
