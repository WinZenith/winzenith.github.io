package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.AppLogger;

import java.util.regex.Pattern;

public class OemIntelCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern VERSION_PATTERN = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    public OemIntelCatalogProvider() {
        super(OemVendorHelper.INTEL);
    }

    @Override
    public String id() {
        return "Intel";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Intel: Fetching latest version for " + driver.friendlyName());
        
        String category = detectCategory(driver);
        AppLogger.debug("Intel: Detected category: " + category + " for " + driver.friendlyName());
        
        String url = getUrlForCategory(category);
        String body = httpGet(url);
        
        String v = extractVersion(body, VERSION_PATTERN);
        if (v == null && body != null) {
            v = extractVersion(body, Pattern.compile("([0-9]{2}\\.[0-9]+\\.[0-9]+)"));
        }
        
        if (v != null) {
            AppLogger.debug("Intel: Found version " + v + " for " + driver.friendlyName() + " from " + url);
        } else {
            AppLogger.debug("Intel: Could not find version for " + driver.friendlyName() + " from " + url);
        }
        
        return v;
    }

    private String detectCategory(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        String provider = driver.provider() != null ? driver.provider().toLowerCase() : "";
        
        if (name.contains("bluetooth") || name.contains("wireless bluetooth")) {
            return "bluetooth";
        }
        if (name.contains("wifi") || name.contains("wireless-ac") || name.contains("wireless") && name.contains("ac")) {
            return "wifi";
        }
        if (name.contains("management engine") || name.contains("me") || name.contains("intel mei")) {
            return "management-engine";
        }
        if (name.contains("graphics") || name.contains("iris") || name.contains("uhd") || name.contains("arc")) {
            return "graphics";
        }
        if (name.contains("chipset") || name.contains("pch")) {
            return "chipset";
        }
        
        // Default to general detect page
        return "general";
    }

    private String getUrlForCategory(String category) {
        return switch (category) {
            case "bluetooth" -> "https://www.intel.com/content/www/us/en/download-center/home.html";
            case "wifi" -> "https://www.intel.com/content/www/us/en/download-center/home.html";
            case "management-engine" -> "https://www.intel.com/content/www/us/en/download-center/home.html";
            case "graphics" -> "https://www.intel.com/content/www/us/en/download-center/home.html";
            case "chipset" -> "https://www.intel.com/content/www/us/en/download-center/home.html";
            default -> "https://www.intel.com/content/www/us/en/support/detect.html";
        };
    }
}
