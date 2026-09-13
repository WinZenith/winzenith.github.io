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

            // Try catalog database first (metadata-first matching)
            if (catalogDatabase != null) {
                List<CatalogEntry> catalogMatches = catalogDatabase.findMatchingEntries(driver);
                CatalogEntry bestCatalogMatch = catalogMatches.stream()
                        .filter(e -> vendor.label().equalsIgnoreCase(e.provider()))
                        .findFirst()
                        .orElse(null);

                if (bestCatalogMatch != null) {
                    AppLogger.info(vendor.label() + ": Found catalog entry for " + driver.friendlyName()
                            + " (version: " + bestCatalogMatch.latestVersion()
                            + ", confidence: " + String.format("%.0f", bestCatalogMatch.confidence() * 100) + "%)");
                    DriverUpdateCandidate candidate = DriverCatalogDatabase.toCandidate(bestCatalogMatch, driver);

                    // Landing-page catalog URLs (no installer file extension) must not
                    // become downloadUrl: the installer would download an HTML page.
                    // Resolve a direct file URL instead; fall back to manual flow.
                    if (!hasDirectFile(candidate.downloadUrl())) {
                        AppLogger.info(vendor.label() + ": Catalog entry has no direct file URL, resolving via provider");
                        String vendorPageUrl = candidate.vendorPageUrl();
                        if (vendorPageUrl == null || vendorPageUrl.isBlank()) {
                            vendorPageUrl = getVendorPageUrl(driver);
                        }
                        String resolvedUrl = resolveDirectDownloadUrl(driver, vendorPageUrl);
                        if (resolvedUrl != null && !resolvedUrl.isBlank()) {
                            candidate = new DriverUpdateCandidate(
                                    candidate.installed(),
                                    candidate.availableVersion(),
                                    candidate.source(),
                                    candidate.packageId(),
                                    candidate.title(),
                                    candidate.description(),
                                    candidate.severity(),
                                    resolvedUrl,
                                    candidate.vendorPageUrl()
                            );
                        } else {
                            // Keep the HW-matched catalog candidate as a manual-download
                            // flow (empty URL + vendor page) instead of dropping a
                            // genuine update. The UI routes empty-URL candidates to
                            // the vendor website; nothing installs silently.
                            // The catalog version stays authoritative: do NOT fall
                            // through to generic web scraping, whose guessed
                            // version could displace this HW-matched one.
                            AppLogger.info(vendor.label() + ": No direct download for "
                                    + driver.friendlyName() + " — offering manual update to "
                                    + candidate.availableVersion());
                            out.add(new DriverUpdateCandidate(
                                    candidate.installed(),
                                    candidate.availableVersion(),
                                    candidate.source(),
                                    candidate.packageId(),
                                    candidate.title(),
                                    candidate.description(),
                                    candidate.severity(),
                                    "",
                                    candidate.vendorPageUrl()));
                            continue;
                        }
                    }

                    if (candidate != null) {
                        out.add(candidate);
                        continue;
                    }
                }
            }

            // Fall back to web scraping (legacy path)
            String latest = fetchLatestVersion(driver);
            if (!isPlausibleVersion(latest)) {
                if (latest != null) {
                    AppLogger.warning(vendor.label() + ": Rejecting implausible scraped version '" + latest
                            + "' for " + driver.friendlyName());
                }
            } else if (VersionCompare.isOlder(driver.driverVersion(), latest)) {
                AppLogger.debug(vendor.label() + ": Update available for " + driver.friendlyName() + " (current: " + driver.driverVersion() + ", latest: " + latest + ")");
                String vendorPageUrl = getVendorPageUrl(driver);
                String downloadUrl = resolveDirectDownloadUrl(driver, vendorPageUrl);
                if (downloadUrl == null) {
                    downloadUrl = "";
                }
                if (vendorPageUrl == null) {
                    vendorPageUrl = "";
                }
                out.add(new DriverUpdateCandidate(
                        driver,
                        latest,
                        vendor.label(),
                        vendor.name() + ":" + sanitize(deviceKey(driver)),
                        vendor.label() + " driver update available",
                        "Check " + vendor.label() + " support site for certified package.",
                        UpdateSeverity.RECOMMENDED,
                        downloadUrl,
                        vendorPageUrl
                ));
            } else if (latest != null) {
                AppLogger.debug(vendor.label() + ": Driver " + driver.friendlyName() + " is up to date (current: " + driver.driverVersion() + ", latest: " + latest + ")");
            }
            } catch (Exception ex) {
                AppLogger.warning(vendor.label() + ": Skipping driver due to error: " + ex.getMessage());
            }
        }
        return out;
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
                    AppLogger.warning(vendor.label() + ": HTTP " + resp.statusCode() + " on " + url + " (attempt " + attempt + "/" + HTTP_MAX_RETRIES + "), retrying…");
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
            case REALTEK -> "https://www.realtek.com/en/downloads";
            case BROADCOM -> "https://www.broadcom.com/support/download-search";
            case QUALCOMM -> "https://www.qualcomm.com/support";
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
        if (vendorPageUrl == null || vendorPageUrl.isBlank()) {
            return null;
        }
        // Quote- and query-tolerant: minified pages use single quotes and CDN
        // links carry ?download=1 tails (which the old "...exe" pattern missed).
        // The query stays INSIDE the capture: signed CDN links 403 without it.
        Pattern linkPattern = Pattern.compile("(?:href|data-href)\\s*=\\s*[\"'](https?://[^\"']+\\.(?:exe|zip|msi|inf|cab)(?:[?#][^\"']*)?)[\"']",
                Pattern.CASE_INSENSITIVE);
        String body = httpGet(vendorPageUrl);
        String found = findFirstMatchingLink(body, linkPattern);
        // Vendor-host gate: the first exe on a support page can be an ad or a
        // third-party tool. Only same-vendor hosts are accepted (mirrors the
        // install-time trusted-source list); anything else falls to manual flow.
        if (found != null && isLikelyStable(found) && isVendorHost(found)) {
            AppLogger.debug(vendor.label() + ": Resolved direct download URL: " + found);
            return found;
        }
        return null;
    }

    /**
     * True when the URL host belongs to this provider's vendor (CDNs included).
     * Mirrors {@code DriverInstallService.isTrustedSource} for the vendor side;
     * the install gate re-validates before any download.
     */
    protected boolean isVendorHost(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = new URI(url).getHost();
            if (host == null || host.isBlank()) return false;
            host = host.toLowerCase();
            return switch (vendor) {
                case NVIDIA -> host.contains("nvidia.com") || host.contains("geforce.com") || host.contains("nvdlcdn.com");
                case AMD -> host.contains("amd.com");
                case INTEL -> host.contains("intel.com");
                case REALTEK -> host.contains("realtek.com");
                case BROADCOM -> host.contains("broadcom.com");
                case QUALCOMM -> host.contains("qualcomm.com");
                case SYNAPTICS -> host.contains("synaptics.com") || host.contains("hp.com") || host.contains("lenovo.com");
                case LENOVO -> host.contains("lenovo.com") || host.contains("lenovo-images.com") || host.contains("lenovo.net");
                case DELL -> host.contains("dell.com") || host.contains("dellcdn.com") || host.contains("dell-cdn.com");
                case HP -> host.contains("hp.com") || host.contains("hpe.com");
                case ASUS -> host.contains("asus.com") || host.contains("asusnet.net");
            };
        } catch (Exception e) {
            return false;
        }
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
     * Catalog landing pages (e.g. realtek.com/en/downloads) have no file
     * extension and must go through provider resolution or manual flow.
     */
    static boolean hasDirectFile(String url) {
        if (url == null || url.isBlank()) return false;
        String path = url.split("[?#]", 2)[0];
        return path.matches("(?i).*\\.(exe|zip|msi|inf|cab)$");
    }
}
