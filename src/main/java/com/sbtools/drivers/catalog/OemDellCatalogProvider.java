package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

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
        String body = httpGet("https://www.dell.com/support/home/en-us/drivers");
        Pattern p = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
        return extractVersion(body, p);
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.dell.com/support/home/en-us/drivers";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        String body = httpGet(vendorPageUrl);
        Pattern p = Pattern.compile("https?://[^\"]*\\.(exe|zip|msi)", Pattern.CASE_INSENSITIVE);
        String found = findFirstMatchingLink(body, p);
        if (found != null && isLikelyStable(found)) return found;
        return null;
    }
}
