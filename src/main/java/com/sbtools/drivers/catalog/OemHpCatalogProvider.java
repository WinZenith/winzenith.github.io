package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

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
        String body = httpGet("https://support.hp.com/us-en/drivers");
        Pattern p = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
        return extractVersion(body, p);
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://support.hp.com/us-en/drivers";
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
