package com.sbtools.drivers.catalog;

import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.drivers.model.UpdateSeverity;
import com.sbtools.util.AppLogger;
import com.sbtools.util.VersionCompare;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractOemCatalogProvider implements DriverCatalogProvider {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final OemVendorHelper vendor;
    private final DriverCatalogDatabase catalogDatabase;

    protected AbstractOemCatalogProvider(OemVendorHelper vendor) {
        this(vendor, null);
    }

    protected AbstractOemCatalogProvider(OemVendorHelper vendor, DriverCatalogDatabase catalogDatabase) {
        this.vendor = vendor;
        this.catalogDatabase = catalogDatabase;
    }

    /**
     * Returns true if this provider's vendor is present in the detected vendor set.
     * Used to skip irrelevant providers during scanning.
     */
    public boolean isVendorPresent(Set<OemVendorHelper> presentVendors) {
        return presentVendors.contains(vendor);
    }

    @Override
    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        List<DriverUpdateCandidate> out = new ArrayList<>();
        if (installed == null) return out;
        for (InstalledDriver driver : installed) {
            try {
                if (driver == null) continue;
                if (driver.deviceId() == null || driver.deviceId().isBlank()) continue;
            if (OemVendorHelper.detect(driver) != vendor) {
                continue;
            }
            AppLogger.debug(vendor.label() + ": Matched driver " + driver.friendlyName() + " (current version: " + driver.driverVersion() + ")");

            DriverUpdateCandidate catalogCandidate = catalogCandidate(driver);
            DriverUpdateCandidate liveCandidate = liveCandidate(driver);
            DriverUpdateCandidate chosen = preferCandidate(catalogCandidate, liveCandidate);
            if (chosen != null) {
                out.add(enrichChosenCandidate(driver, chosen));
            }
            } catch (Exception ex) {
                AppLogger.warning(vendor.label() + ": Skipping driver due to error: " + ex.getMessage());
            }
        }
        return out;
    }

    private DriverUpdateCandidate catalogCandidate(InstalledDriver driver) {
        if (catalogDatabase == null) {
            return null;
        }
        List<CatalogEntry> catalogMatches = catalogDatabase.findMatchingEntries(driver);
        CatalogEntry bestCatalogMatch = catalogMatches.stream()
                .filter(e -> vendor.label().equalsIgnoreCase(e.provider()))
                .findFirst()
                .orElse(null);
        if (bestCatalogMatch == null) {
            return null;
        }
        AppLogger.info(vendor.label() + ": Found catalog entry for " + driver.friendlyName()
                + " (version: " + bestCatalogMatch.latestVersion()
                + ", confidence: " + String.format("%.0f", bestCatalogMatch.confidence() * 100) + "%)");
        DriverUpdateCandidate candidate = DriverCatalogDatabase.toCandidate(bestCatalogMatch, driver);
        // Landing pages must not become downloadUrl (installer would fetch HTML).
        // Leave the URL empty here and let liveCandidate supply a file URL.
        if (!hasDirectFile(candidate.downloadUrl())) {
            return withDownloadUrl(candidate, "");
        }
        return candidate;
    }

    private DriverUpdateCandidate liveCandidate(InstalledDriver driver) {
        String latest = fetchLatestVersion(driver);
        if (!isPlausibleVersion(latest)) {
            if (latest != null) {
                AppLogger.warning(vendor.label() + ": Rejecting implausible scraped version '" + latest
                        + "' for " + driver.friendlyName());
            }
            return null;
        }
        if (!VersionCompare.isOlder(driver.driverVersion(), latest)) {
            AppLogger.debug(vendor.label() + ": Driver " + driver.friendlyName()
                    + " is up to date (current: " + driver.driverVersion() + ", latest: " + latest + ")");
            return null;
        }
        AppLogger.debug(vendor.label() + ": Update available for " + driver.friendlyName()
                + " (current: " + driver.driverVersion() + ", latest: " + latest + ")");
        String vendorPageUrl = getVendorPageUrl(driver);
        String downloadUrl = resolveDirectDownloadUrl(driver, vendorPageUrl);
        if (downloadUrl == null || !hasDirectFile(downloadUrl)) {
            downloadUrl = "";
        }
        if (vendorPageUrl == null) {
            vendorPageUrl = "";
        }
        return new DriverUpdateCandidate(
                driver,
                latest,
                vendor.label(),
                vendor.name() + ":" + sanitize(deviceKey(driver)),
                vendor.label() + " driver update available",
                "Check " + vendor.label() + " support site for certified package.",
                UpdateSeverity.RECOMMENDED,
                downloadUrl,
                vendorPageUrl
        );
    }

    /**
     * Live version wins when newer. On a version tie, a direct installer URL
     * wins over a manual/vendor-page catalog hit. Catalog-only when live is null.
     */
    static DriverUpdateCandidate preferCandidate(DriverUpdateCandidate catalog, DriverUpdateCandidate live) {
        if (catalog == null) {
            return live;
        }
        if (live == null) {
            return catalog;
        }
        int cmp = VersionCompare.compare(live.availableVersion(), catalog.availableVersion());
        if (cmp > 0) {
            return live;
        }
        if (cmp < 0) {
            return catalog;
        }
        boolean liveFile = hasWorkingDownload(live);
        boolean catalogFile = hasWorkingDownload(catalog);
        if (liveFile && !catalogFile) {
            return live;
        }
        if (catalogFile && !liveFile) {
            return catalog;
        }
        return liveFile ? live : catalog;
    }

    static boolean hasWorkingDownload(DriverUpdateCandidate candidate) {
        return candidate != null && hasDirectFile(candidate.downloadUrl());
    }

    static DriverUpdateCandidate withDownloadUrl(DriverUpdateCandidate candidate, String downloadUrl) {
        return new DriverUpdateCandidate(
                candidate.installed(),
                candidate.availableVersion(),
                candidate.source(),
                candidate.packageId(),
                candidate.title(),
                candidate.description(),
                candidate.severity(),
                downloadUrl == null ? "" : downloadUrl,
                candidate.vendorPageUrl());
    }

    static DriverUpdateCandidate withVendorPageUrl(DriverUpdateCandidate candidate, String vendorPageUrl) {
        return new DriverUpdateCandidate(
                candidate.installed(),
                candidate.availableVersion(),
                candidate.source(),
                candidate.packageId(),
                candidate.title(),
                candidate.description(),
                candidate.severity(),
                candidate.downloadUrl(),
                vendorPageUrl == null ? "" : vendorPageUrl);
    }

    private DriverUpdateCandidate enrichChosenCandidate(InstalledDriver driver, DriverUpdateCandidate chosen) {
        if (shouldReplaceVendorPageUrl(chosen.vendorPageUrl())) {
            String providerVendorPage = getVendorPageUrl(driver);
            if (providerVendorPage != null && !providerVendorPage.isBlank()) {
                chosen = withVendorPageUrl(chosen, providerVendorPage);
            }
        }
        if (resolveDownloadOnEnrich(driver) && !hasWorkingDownload(chosen)) {
            String resolved = resolveDirectDownloadUrl(driver, chosen.vendorPageUrl());
            if (resolved != null && hasDirectFile(resolved)) {
                chosen = withDownloadUrl(chosen, resolved);
            }
        }
        return chosen;
    }

    /**
     * When false, keep catalog-curated vendor pages (device-specific Intel/Synaptics/Broadcom URLs).
     */
    protected boolean shouldReplaceVendorPageUrl(String currentUrl) {
        return currentUrl == null || currentUrl.isBlank() || isKnownDeadVendorUrl(currentUrl);
    }

    /** Legacy vendor paths that 404 after site redesigns. */
    static boolean isKnownDeadVendorUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("realtek.com/en/downloads");
    }

    /** When false, skip network resolve during enrich (Intel/NVIDIA/Broadcom scans stay fast). */
    protected boolean resolveDownloadOnEnrich(InstalledDriver driver) {
        return false;
    }

    protected abstract String fetchLatestVersion(InstalledDriver driver);

    private static final int HTTP_MAX_RETRIES = 2;
    private static final long HTTP_INITIAL_BACKOFF_MS = 500;

    protected String httpGet(String url) {
        if (Thread.currentThread().isInterrupted()) {
            return null;
        }
        long backoffMs = HTTP_INITIAL_BACKOFF_MS;
        for (int attempt = 1; attempt <= HTTP_MAX_RETRIES; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                return null;
            }
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                        .GET()
                        .build();
                HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                if (resp.statusCode() == 429) {
                    AppLogger.warning(vendor.label() + ": HTTP 429 rate limited on " + url + " (attempt " + attempt + "/" + HTTP_MAX_RETRIES + ")");
                    if (attempt < HTTP_MAX_RETRIES) {
                        try {
                            Thread.sleep(backoffMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        backoffMs *= 2;
                        continue;
                    }
                    return null;
                }
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    return resp.body();
                }
                if (resp.statusCode() >= 500 && attempt < HTTP_MAX_RETRIES) {
                    AppLogger.warning(vendor.label() + ": HTTP " + resp.statusCode() + " on " + url + " (attempt " + attempt + "/" + HTTP_MAX_RETRIES + "), retrying...");
                    try {
                        Thread.sleep(backoffMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    backoffMs *= 2;
                    continue;
                }
                return null;
            } catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (Thread.currentThread().isInterrupted()) {
                    return null;
                }
                if (attempt < HTTP_MAX_RETRIES) {
                    AppLogger.warning(vendor.label() + ": HTTP error on " + url + " (attempt " + attempt + "/" + HTTP_MAX_RETRIES + "): " + e.getMessage());
                    try {
                        Thread.sleep(backoffMs);
                        backoffMs *= 2;
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                    continue;
                }
            }
        }
        return null;
    }

    protected String extractVersion(String body, Pattern pattern) {
        if (body == null) {
            return null;
        }
        Matcher m = pattern.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Decodes common HTML entities in a string so extracted URLs are usable.
     * Handles numeric references (&#58; &#47; &#46; etc.) and named entities (&amp; &lt; etc.).
     */
    protected static String decodeHtmlEntities(String s) {
        if (s == null) return null;
        String result = s;
        StringBuilder sb = new StringBuilder(result.length());
        int i = 0;
        while (i < result.length()) {
            if (result.charAt(i) == '&' && i + 1 < result.length() && result.charAt(i + 1) == '#') {
                int semi = result.indexOf(';', i + 2);
                if (semi > i + 2) {
                    String entity = result.substring(i + 2, semi);
                    try {
                        int codePoint;
                        if (entity.toLowerCase().startsWith("x")) {
                            codePoint = Integer.parseInt(entity.substring(1), 16);
                        } else {
                            codePoint = Integer.parseInt(entity);
                        }
                        sb.appendCodePoint(codePoint);
                        i = semi + 1;
                        continue;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            sb.append(result.charAt(i));
            i++;
        }
        result = sb.toString();
        result = result.replace("&amp;", "&");
        result = result.replace("&lt;", "<");
        result = result.replace("&gt;", ">");
        result = result.replace("&quot;", "\"");
        result = result.replace("&apos;", "'");
        return result;
    }

    /**
     * Returns the vendor's product/support page URL for this driver.
     * Subclasses should override to provide the correct page URL.
     */
    protected String getVendorPageUrl(InstalledDriver driver) {
        return switch (vendor) {
            case INTEL -> "https://downloadcenter.intel.com";
            case NVIDIA -> "https://www.nvidia.com/Download/index.aspx";
            case AMD -> "https://www.amd.com/en/support";
            case REALTEK -> OemRealtekCatalogProvider.REALTEK_DOWNLOAD_HUB;
            case BROADCOM -> "https://www.broadcom.com/support/download-search";
            case QUALCOMM -> OemQualcommCatalogProvider.QUALCOMM_WIFI_SUPPORT;
            default -> "https://www." + vendor.label().toLowerCase() + ".com/support";
        };
    }

    /**
     * Attempts to resolve a direct download URL for the driver.
     * Default: scrapes the vendor page for links matching common driver file extensions.
     * Subclasses should override for vendor-specific resolution logic.
     *
     * @return direct download URL, or null if unable to resolve
     */
    protected String resolveDirectDownloadUrl(InstalledDriver driver, String vendorPageUrl) {
        // Generic support-page scraping cannot prove package-to-device identity -- manual only.
        return null;
    }

    /**
     * True when the URL host belongs to this provider's vendor (CDNs included).
     * Mirrors {@code DriverInstallService.isTrustedSource} for the vendor side;
     * the install gate re-validates before any download.
     */
    protected boolean isVendorHost(String url) {
        return com.sbtools.drivers.DriverInstallTrust.isTrustedHttpsUrl(url, vendorSourceId());
    }

    private String vendorSourceId() {
        return switch (vendor) {
            case NVIDIA -> "Nvidia";
            case AMD -> "AMD";
            case INTEL -> "Intel";
            case REALTEK -> "Realtek";
            case BROADCOM -> "Broadcom";
            case QUALCOMM -> "Qualcomm";
            case SYNAPTICS -> "Synaptics";
            case LENOVO -> "Lenovo";
            case DELL -> "Dell";
            case HP -> "HP";
            case ASUS -> "ASUS";
        };
    }

    protected String findFirstMatchingLink(String html, Pattern pattern) {
        if (html == null) return null;
        Matcher m = pattern.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    protected boolean isLikelyStable(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        // Digit-glued tags (rc1, beta2, preview3) have no word boundary
        // before the digit and previously tested "stable".
        return !lower.matches(".*\\b(alpha|beta|rc|preview|test)\\d*\\b.*");
    }

    private static String deviceKey(InstalledDriver d) {
        if (d == null || d.deviceId() == null) return "unknown";
        return d.deviceId().replaceAll("[^a-zA-Z0-9]", "_");
    }

    static boolean isPlausibleVersion(String v) {
        if (v == null || v.isBlank()) return false;
        String t = v.trim();
        // Require at least major.minor numeric form; rejects page numbers,
        // years, single integers and HTML artefacts.
        if (!t.matches("(?i).*\\d+\\.\\d+.*")) return false;
        if (t.length() > 64) return false;
        return true;
    }

    private static String sanitize(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /**
     * True when the URL points directly at an installer/archive file.
     * Catalog landing pages have no file extension and must go through
     * provider resolution or manual flow. Realtek ToDownload endpoints
     * serve the installer after agree/direct redirect.
     */
    static boolean hasDirectFile(String url) {
        if (url == null || url.isBlank()) return false;
        String lower = url.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("realtek.com") && lower.contains("/download/todownload") && lower.contains("downloadid=")) {
            return true;
        }
        String path = url.split("[?#]", 2)[0];
        return path.matches("(?i).*\\.(exe|zip|msi|inf|cab)$");
    }
}
