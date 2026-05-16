package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;

import java.util.regex.Pattern;

public class OemNvidiaCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION = Pattern.compile(
            "\"version\"\\s*:\\s*\"([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)\"", Pattern.CASE_INSENSITIVE);

    public OemNvidiaCatalogProvider() {
        super(OemVendorHelper.NVIDIA);
    }

    @Override
    public String id() {
        return "Nvidia";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        // NVIDIA driver search API (public JSON endpoint used by driver search pages)
        String body = httpGet("https://gfwsl.geforce.com/services/graphql?"
                + "query=query%20DriverReleases%7BdriverReleases%7Bversion%7D%7D");
        if (body == null) {
            body = httpGet("https://www.nvidia.com/en-us/drivers/");
        }
        String v = extractVersion(body, VERSION);
        if (v == null && body != null) {
            v = extractVersion(body, Pattern.compile("Driver Version\\s+([0-9]+\\.[0-9]+(?:\\.[0-9]+)?)"));
        }
        return v;
    }
}
