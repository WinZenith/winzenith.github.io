package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OemRealtekCatalogProvider extends AbstractOemCatalogProvider {

    static final String REALTEK_DOWNLOAD_HUB = "https://www.realtek.com/Download/List";
    private static final String REALTEK_AUDIO_LIST =
            "https://www.realtek.com/Download/List?cate_id=593&menu_id=298";
    private static final String REALTEK_ETHERNET_LIST =
            "https://www.realtek.com/Download/List?cate_id=584";
    private static final String REALTEK_CARD_READER_LIST =
            "https://www.realtek.com/Download/List?cate_id=590&menu_id=405";

    private static final Pattern CARD_READER_VERSION = Pattern.compile(
            "(?:Card\\s*Reader|CardReader|RTS[0-9]+)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern GBE_VERSION = Pattern.compile(
            "(?:GbE|RTL[0-9]+|Ethernet)[^0-9]*([0-9]+\\.[0-9]+\\.[0-9]+(?:\\.[0-9]+)?)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern FILE_LINK = Pattern.compile(
            "href\\s*=\\s*\"([^\"]+\\.(?:exe|zip|msi))\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern TO_DOWNLOAD_LINK = Pattern.compile(
            "href\\s*=\\s*\"([^\"]*?/Download/ToDownload\\?[^\"]+)\"", Pattern.CASE_INSENSITIVE);

    public OemRealtekCatalogProvider() {
        super(OemVendorHelper.REALTEK);
    }

    public OemRealtekCatalogProvider(DriverCatalogDatabase catalogDatabase) {
        super(OemVendorHelper.REALTEK, catalogDatabase);
    }

    @Override
    public String id() {
        return "Realtek";
    }

    @Override
    protected boolean resolveDownloadOnEnrich(InstalledDriver driver) {
        return allowsAutomaticDownload(driver);
    }

    @Override
    protected String fetchLatestVersion(InstalledDriver driver) {
        AppLogger.debug("Realtek: Fetching latest version for " + driver.friendlyName());

        String categoryUrl = realtekListPageUrl(driver);
        String body = httpGet(categoryUrl);

        if (body != null) {
            String v = null;
            String name = driver.friendlyName() != null ? driver.friendlyName().toLowerCase(Locale.ROOT) : "";
            if (name.contains("cardreader") || name.contains("card reader")) {
                v = extractVersion(body, CARD_READER_VERSION);
            }
            if (v == null && (name.contains("gbe") || name.contains("ethernet") || name.contains("rtl81"))) {
                v = extractVersion(body, GBE_VERSION);
            }
            // No GENERIC_VERSION fallback: a bare number on the downloads
            // landing page is not device-specific (wrong-version risk).
            if (v != null) {
                AppLogger.debug("Realtek: Found version " + v + " for " + driver.friendlyName());
                return v;
            }
        }

        // No hardcoded fallback versions: stale hardcodes cause downgrades
        // or missed criticals. Catalog-only when no device-specific match.
        AppLogger.debug("Realtek: Could not determine latest version for " + driver.friendlyName());
        return null;
    }

    static String realtekListPageUrl(InstalledDriver driver) {
        String name = driver != null && driver.friendlyName() != null
                ? driver.friendlyName().toLowerCase(Locale.ROOT) : "";
        if (name.contains("cardreader") || name.contains("card reader") || name.contains("rts")) {
            return REALTEK_CARD_READER_LIST;
        }
        if (name.contains("gbe") || name.contains("ethernet") || name.contains("rtl81")) {
            return REALTEK_ETHERNET_LIST;
        }
        if (name.contains("wifi") || name.contains("wireless") || name.contains("8852") || name.contains("wlan")) {
            return REALTEK_DOWNLOAD_HUB;
        }
        if (name.contains("audio") || name.contains("hda") || name.contains("alc")) {
            return REALTEK_AUDIO_LIST;
        }
        return REALTEK_AUDIO_LIST;
    }

    @Override
    protected String getVendorPageUrl(InstalledDriver driver) {
        return realtekListPageUrl(driver);
    }

    @Override
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        if (!allowsAutomaticDownload(driver)) {
            AppLogger.info("Realtek: Automatic download disabled for " + driver.friendlyName()
                    + " — use vendor page or Windows Update");
            return null;
        }

        AppLogger.info("Realtek: Resolving direct download URL for " + driver.friendlyName());

        LinkedHashSet<String> tryUrls = new LinkedHashSet<>();
        tryUrls.add(realtekListPageUrl(driver));
        tryUrls.add(getVendorPageUrl(driver));
        if (vendorPageUrl != null && !vendorPageUrl.isBlank()) {
            tryUrls.add(vendorPageUrl);
        }
        List<String> found = new ArrayList<>();
        for (String tryUrl : tryUrls) {
            if (tryUrl == null || tryUrl.isBlank()) {
                continue;
            }
            String body = httpGet(tryUrl);
            if (body == null) {
                continue;
            }
            collectDownloadLinksFromHtml(body, found);
        }

        List<String> ranked = new ArrayList<>(found);
        ranked.sort(Comparator.comparingInt(OemRealtekCatalogProvider::realtekLinkRank).reversed());

        String picked = com.sbtools.drivers.DriverInstallTrust.pickBestDownloadUrl(ranked, driver);
        if (picked != null && com.sbtools.drivers.DriverInstallTrust.hasDeviceModelToken(driver)
                && com.sbtools.drivers.DriverInstallTrust.tokenScore(picked, driver) == 0) {
            AppLogger.info("Realtek: refusing first-link download that does not name device "
                    + driver.friendlyName());
            return null;
        }
        if (picked != null && !com.sbtools.drivers.DriverInstallTrust.hasDeviceModelToken(driver)
                && ambiguousToDownloadChoices(found)) {
            AppLogger.info("Realtek: multiple generic download links — manual install only for "
                    + driver.friendlyName());
            return null;
        }
        if (picked != null) {
            AppLogger.info("Realtek: Found download URL: " + picked);
            return picked;
        }

        AppLogger.info("Realtek: No device-matching download found, user will be directed to vendor website");
        return null;
    }

    /**
     * HDA and Wi‑Fi: catalog DCH/WU versions do not match generic Realtek list
     * packages; hub pages may offer the wrong WLAN bundle.
     */
    static boolean allowsAutomaticDownload(InstalledDriver driver) {
        return !isRealtekWifi(driver) && !isRealtekAudio(driver);
    }

    static boolean isRealtekWifi(InstalledDriver driver) {
        if (driver == null || driver.friendlyName() == null) {
            return false;
        }
        String name = driver.friendlyName().toLowerCase(Locale.ROOT);
        return name.contains("wifi") || name.contains("wireless") || name.contains("8852") || name.contains("wlan");
    }

    static boolean isRealtekAudio(InstalledDriver driver) {
        if (driver == null) {
            return false;
        }
        return !isRealtekWifi(driver) && !isRealtekEthernet(driver) && !isRealtekCardReader(driver);
    }

    private static boolean isRealtekCardReader(InstalledDriver driver) {
        if (driver == null || driver.friendlyName() == null) {
            return false;
        }
        String name = driver.friendlyName().toLowerCase(Locale.ROOT);
        return name.contains("cardreader") || name.contains("card reader") || name.contains("rts");
    }

    private static boolean isRealtekEthernet(InstalledDriver driver) {
        if (driver == null || driver.friendlyName() == null) {
            return false;
        }
        String name = driver.friendlyName().toLowerCase(Locale.ROOT);
        return name.contains("gbe") || name.contains("ethernet") || name.contains("rtl81");
    }

    private static boolean ambiguousToDownloadChoices(List<String> found) {
        long toDownload = found.stream()
                .filter(u -> u != null && u.toLowerCase(Locale.ROOT).contains("/download/todownload"))
                .count();
        return toDownload > 1;
    }

    private static int realtekLinkRank(String url) {
        if (url == null) {
            return 0;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        int rank = 0;
        if (lower.contains("type=direct")) {
            rank += 4;
        } else if (lower.contains("type=agree")) {
            rank += 2;
        }
        if (lower.contains("win11") || lower.contains("win10") || lower.contains("windows10")
                || lower.contains("windows11")) {
            rank += 2;
        }
        if (lower.contains("64bit") || lower.contains("64-bit") || lower.contains("x64")) {
            rank += 1;
        }
        return rank;
    }

    public static void collectDownloadLinksFromHtml(String html, List<String> found) {
        if (html == null || found == null) {
            return;
        }
        Matcher fileMatcher = FILE_LINK.matcher(html);
        while (fileMatcher.find()) {
            addRealtekLink(found, fileMatcher.group(1));
        }
        Matcher toDlMatcher = TO_DOWNLOAD_LINK.matcher(html);
        while (toDlMatcher.find()) {
            addRealtekLink(found, toDlMatcher.group(1));
        }
    }

    private static void addRealtekLink(List<String> found, String raw) {
        String url = decodeHtmlEntities(raw);
        if (url == null || url.isBlank()) {
            return;
        }
        if (url.startsWith("//")) {
            url = "https:" + url;
        } else if (url.startsWith("/")) {
            url = "https://www.realtek.com" + url;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.contains("realtek.com") || lower.contains("downloadlist")) {
            return;
        }
        if (lower.contains("/download/todownload") && !lower.contains("downloadid=")) {
            return;
        }
        if (!lower.contains("/download/todownload")
                && !lower.matches(".*\\.(exe|zip|msi)(?:[?#].*)?$")) {
            return;
        }
        if (!likelyStable(url)) {
            return;
        }
        if (!found.contains(url)) {
            found.add(url);
        }
    }

    private static boolean likelyStable(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return !lower.matches(".*\\b(alpha|beta|rc|preview|test)\\d*\\b.*");
    }
}
