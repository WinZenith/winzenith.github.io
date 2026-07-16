package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

public class OemDellCatalogProvider extends AbstractOemCatalogProvider {
    public OemDellCatalogProvider() {
        super(OemVendorHelper.DELL);
    }

    public OemDellCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.DELL, catalogDatabase);
    }

    @Override
    public String id() {
        return "Dell";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Dell: Legacy web scraping not supported for Dell (use catalog database). Skipping.");
        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        String hardwareIds = driver.hardwareIds() != null ? driver.hardwareIds() : "";
        String friendlyName = driver.friendlyName() != null ? driver.friendlyName() : "";
        String productCode = extractProductCode(hardwareIds, friendlyName);
        if (productCode != null) {
            return "https://www.dell.com/support/home/en-us/product-support/product/" + productCode + "/drivers";
        }
        return "https://www.dell.com/support/home/en-us/drivers";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.debug("Dell: Direct download URL resolution not supported for Dell (use catalog database). Skipping.");
        return null;
    }

    private String extractProductCode(String hardwareIds, String friendlyName) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("SYS_([A-Z0-9]+)")
                .matcher(hardwareIds);
        if (m.find()) {
            return m.group(1);
        }
        m = java.util.regex.Pattern.compile("(?:Latitude|OptiPlex|Precision|Inspiron|Vostro|XPS|Alienware)\\s+([A-Za-z0-9]+)")
                .matcher(friendlyName);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
