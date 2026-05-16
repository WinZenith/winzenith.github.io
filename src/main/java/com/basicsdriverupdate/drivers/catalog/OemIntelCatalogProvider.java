package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemIntelCatalogProvider extends AbstractOemCatalogProvider {

    public OemIntelCatalogProvider() {
        super(OemVendorHelper.INTEL);
    }

    @Override
    public String id() {
        return "Intel";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        String body = httpGet("https://www.intel.com/content/www/us/en/support/detect.html");
        String v = extractVersion(body, Pattern.compile("version[\"'\\s:]+([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE));
        if (v == null && body != null) {
            v = extractVersion(body, Pattern.compile("([0-9]{2}\\.[0-9]+\\.[0-9]+)"));
        }
        return v;
    }
}
