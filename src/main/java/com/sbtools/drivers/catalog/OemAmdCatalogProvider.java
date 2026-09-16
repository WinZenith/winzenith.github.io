package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

public class OemAmdCatalogProvider extends AbstractOemCatalogProvider {

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

        if (isPolarisOrVega(driver)) {
            AppLogger.info("AMD: Polaris/Vega device — no silent Adrenalin URL (RDNA installer would be wrong)");
            return null;
        }

        if (name.contains("radeon")) {
            String url = "https://drivers.amd.com/drivers/installer/AMDSoftwareAdrenalinEdition.exe";
            AppLogger.info("AMD: Using Adrenalin installer URL: " + url);
            return url;
        }

        AppLogger.info("AMD: No direct download found, user will be directed to vendor website");
        return null;
    }

    /**
     * GCN Polaris/Vega must not receive the live RDNA Adrenalin EXE.
     * RX 5000+ (Navi) is RDNA and is not matched here.
     */
    static boolean isPolarisOrVega(InstalledDriver driver) {
        if (driver == null) return false;
        String hw = ((driver.hardwareIds() == null ? "" : driver.hardwareIds())
                + " " + (driver.deviceId() == null ? "" : driver.deviceId()))
                .toUpperCase(java.util.Locale.ROOT);
        // Polariss RX 400/500 and Vega 56/64 device IDs.
        if (hw.contains("DEV_67DF") || hw.contains("DEV_67EF") || hw.contains("DEV_67FF")
                || hw.contains("DEV_67C4") || hw.contains("DEV_67C7") || hw.contains("DEV_67E3")
                || hw.contains("DEV_699F") || hw.contains("DEV_67A0") || hw.contains("DEV_67B0")
                || hw.contains("DEV_687F") || hw.contains("DEV_6863") || hw.contains("DEV_6867")) {
            return true;
        }
        String name = driver.friendlyName() != null
                ? driver.friendlyName().toLowerCase(java.util.Locale.ROOT) : "";
        if (name.contains("vega")) return true;
        // RX 4xx / 5xx, but not RX 5xxx (Navi 10).
        return name.matches(".*\\brx\\s*5[0-9]{2}(?![0-9]).*")
                || name.matches(".*\\brx\\s*4[0-9]{2}(?![0-9]).*");
    }
}
