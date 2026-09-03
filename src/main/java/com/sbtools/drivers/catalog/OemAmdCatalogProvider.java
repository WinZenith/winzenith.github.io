package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemAmdCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern ADRENALIN_VERSION = Pattern.compile(
            "Adrenalin[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMD_VERSION = Pattern.compile(
            "(?:AMD|RADEON|Adrenalin|WHQL)[^0-9]*([0-9]{2}\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_VERSION = Pattern.compile(
            "([0-9]{2}\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);

    public OemAmdCatalogProvider() {
        super(OemVendorHelper.AMD);
    }

    public OemAmdCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.AMD, catalogDatabase);
    }

    @Override
    public String id() {
        return "AMD";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // Catalog-only: hardcoded fallback versions go stale and cause
        // downgrades or missed criticals. No device-specific web source.
        AppLogger.debug("AMD: Legacy fallback disabled (use catalog database) for " + driver.friendlyName());
        return null;
    }

    private String getFallbackVersion(InstalledDriver driver) {
        // Disabled: hardcoded versions go stale.
        return null;
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("AMD: Resolving direct download URL for " + driver.friendlyName());

        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";

        if (name.contains("chipset") || name.contains("b450") || name.contains("b550")
                || name.contains("x470") || name.contains("x570")) {
            String url = "https://drivers.amd.com/drivers/installer/AMD_Chipset_Drivers.exe";
            AppLogger.info("AMD: Using chipset installer URL: " + url);
            return url;
        }

        if (name.contains("radeon")) {
            String url = "https://drivers.amd.com/drivers/installer/AMDSoftwareAdrenalinEdition.exe";
            AppLogger.info("AMD: Using Adrenalin installer URL: " + url);
            return url;
        }

        AppLogger.info("AMD: No direct download found, user will be directed to vendor website");
        return null;
    }
}
