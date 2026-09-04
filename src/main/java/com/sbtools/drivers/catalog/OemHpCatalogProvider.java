package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

public class OemHpCatalogProvider extends AbstractOemCatalogProvider {
    public OemHpCatalogProvider() {
        super(OemVendorHelper.HP);
    }

    public OemHpCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.HP, catalogDatabase);
    }

    @Override
    public String id() {
        return "HP";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: generic support-homepage scraping returns the same
        // version for every device (wrong-device risk).
        com.sbtools.util.AppLogger.debug("HP: Legacy web scraping disabled (use catalog database). Skipping.");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://support.hp.com/us-en/drivers";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        // Catalog-only: do not scrape the generic support homepage for an
        // arbitrary exe/zip (wrong-file risk).
        com.sbtools.util.AppLogger.debug("HP: Direct download URL resolution disabled (use catalog database). Skipping.");
        return null;
    }
}
