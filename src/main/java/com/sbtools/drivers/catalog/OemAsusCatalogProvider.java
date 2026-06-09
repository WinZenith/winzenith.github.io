package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemAsusCatalogProvider extends AbstractOemCatalogProvider {
    public OemAsusCatalogProvider() {
        super(OemVendorHelper.ASUS);
    }

    @Override
    public String id() {
        return "ASUS";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        String body = httpGet("https://www.asus.com/support/Download-Center/");
        Pattern p = Pattern.compile("\"version\"\\s*:\\s*\"([^\"]+)\"");
        return extractVersion(body, p);
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.asus.com/support/Download-Center/";
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
