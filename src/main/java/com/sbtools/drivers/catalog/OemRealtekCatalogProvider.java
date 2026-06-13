package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.regex.Pattern;

public class OemRealtekCatalogProvider extends AbstractOemCatalogProvider {

    private static final Pattern CARD_READER_VERSION = Pattern.compile(
            "(?:Card\\s*Reader|CardReader|RTS[0-9]+)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GBE_VERSION = Pattern.compile(
            "(?:GbE|RTL[0-9]+|Ethernet)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_VERSION = Pattern.compile(
            "([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    public OemRealtekCatalogProvider() {
        super(OemVendorHelper.REALTEK);
    }

    @Override
    public String id() {
        return "Realtek";
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Realtek: Fetching latest version for " + driver.friendlyName());

        String categoryUrl = detectCategoryUrl(driver);
        String body = httpGet(categoryUrl);

        if (body != null) {
            String v = null;
            String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
            if (name.contains("cardreader") || name.contains("card reader")) {
                v = extractVersion(body, CARD_READER_VERSION);
            }
            if (v == null && (name.contains("gbe") || name.contains("ethernet") || name.contains("rtl81"))) {
                v = extractVersion(body, GBE_VERSION);
            }
            if (v == null) {
                v = extractVersion(body, GENERIC_VERSION);
            }
            if (v != null) {
                AppLogger.debug("Realtek: Found version " + v + " for " + driver.friendlyName());
                return v;
            }
        }

        String fallback = getFallbackVersion(driver);
        if (fallback != null) {
            AppLogger.debug("Realtek: Using fallback version " + fallback + " for " + driver.friendlyName());
            return fallback;
        }

        AppLogger.debug("Realtek: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    private String detectCategoryUrl(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";
        if (name.contains("cardreader") || name.contains("card reader")) {
            return "https://www.realtek.com/en/downloads/downloads2-downloads#702";
        }
        if (name.contains("gbe") || name.contains("ethernet") || name.contains("rtl81")) {
            return "https://www.realtek.com/en/downloads/downloads2-downloads#1019";
        }
        return "https://www.realtek.com/en/downloads";
    }

    private String getFallbackVersion(InstalledDriver driver) {
        String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase() : "";

        if (name.contains("cardreader") || name.contains("card reader")) {
            return "10.0.26100.21384";
        }
        if (name.contains("gbe family controller")) {
            return "10.75.324.2026";
        }

        return null;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return "https://www.realtek.com/en/downloads";
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        AppLogger.info("Realtek: No direct download available for " + driver.friendlyName()
                + " - user will be directed to vendor website");
        return null;
    }
}
