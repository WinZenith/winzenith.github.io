package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemLenovoCatalogProvider extends AbstractOemCatalogProvider {
    public OemLenovoCatalogProvider() {
        super(OemVendorHelper.LENOVO);
    }

    @Override
    public String id() {
        return "Lenovo";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        String body = httpGet("https://pcsupport.lenovo.com/us/en/api/v4/downloads/drivers?type=managed&category=All&page=1&size=1");
        Pattern p = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
        return extractVersion(body, p);
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://pcsupport.lenovo.com";
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
