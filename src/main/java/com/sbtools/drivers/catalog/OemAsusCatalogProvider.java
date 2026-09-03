package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemAsusCatalogProvider extends AbstractOemCatalogProvider {
    public OemAsusCatalogProvider() {
        super(OemVendorHelper.ASUS);
    }

    public OemAsusCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.ASUS, catalogDatabase);
    }

    @Override
    public String id() {
        return "ASUS";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: generic Download-Center scraping returns the same
        // version for every device (wrong-device risk).
        com.sbtools.util.AppLogger.debug("ASUS: Legacy web scraping disabled (use catalog database). Skipping.");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.asus.com/support/Download-Center/";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        // Catalog-only: do not scrape the generic Download-Center page.
        com.sbtools.util.AppLogger.debug("ASUS: Direct download URL resolution disabled (use catalog database). Skipping.");
        return null;
    }
}
