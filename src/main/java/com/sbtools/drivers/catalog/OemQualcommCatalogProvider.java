package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

/**
 * Catalog + Windows Update path for Qualcomm Wi‑Fi (VEN_168C). No public
 * per-device direct-download API comparable to Intel DSA / NVIDIA processFind.
 */
public class OemQualcommCatalogProvider extends AbstractOemCatalogProvider {

    /** PC Wi‑Fi drivers are distributed via OEM/WU; this is the public support entry point. */
    static final String QUALCOMM_WIFI_SUPPORT = "https://www.qualcomm.com/support";

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
        AppLogger.debug("Qualcomm: Catalog-only version for " + driver.friendlyName());
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return QUALCOMM_WIFI_SUPPORT;
    }
}
