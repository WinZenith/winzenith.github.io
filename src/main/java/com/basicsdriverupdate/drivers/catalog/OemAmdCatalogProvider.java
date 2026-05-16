package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemAmdCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "([0-9]{2}\\.[0-9]+\\.[0-9]+)", Pattern.CASE_INSENSITIVE);

    public OemAmdCatalogProvider() {
        super(OemVendorHelper.AMD);
    }

    @Override
    public String id() {
        return "AMD";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        String body = httpGet("https://www.amd.com/en/support");
        String v = extractVersion(body, Pattern.compile("Adrenalin[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+)"));
        if (v == null) {
            v = extractVersion(body, VERSION);
        }
        return v;
    }
}
