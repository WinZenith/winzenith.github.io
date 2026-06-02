package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

public class OemSynapticsCatalogProvider extends AbstractOemCatalogProvider {

    public OemSynapticsCatalogProvider() {
        super(OemVendorHelper.SYNAPTICS);
    }

    @Override
    public String id() {
        return "Synaptics";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Synaptics: Fetching latest version for " + driver.friendlyName());
        AppLogger.debug("Synaptics: No version catalog available for " + driver.friendlyName() + " - skipping");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.synaptics.com/support";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Synaptics: No direct download available for " + driver.friendlyName()
                + " - user will be directed to vendor website");
        return null;
    }
}
