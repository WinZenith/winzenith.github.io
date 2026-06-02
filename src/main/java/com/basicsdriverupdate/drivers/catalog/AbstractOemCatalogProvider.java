package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.drivers.model.UpdateSeverity;
import com.basicsdriverupdate.util.AppLogger;
import com.basicsdriverupdate.util.VersionCompare;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

abstract class AbstractOemCatalogProvider implements DriverCatalogProvider {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private final OemVendorHelper vendor;

    protected AbstractOemCatalogProvider(OemVendorHelper vendor) {
        this.vendor = vendor;
    }

    @Override
    public List<DriverUpdateCandidate> findUpdates(List<InstalledDriver> installed) {
        List<DriverUpdateCandidate> out = new ArrayList<>();
        for (InstalledDriver driver : installed) {
            if (OemVendorHelper.detect(driver) != vendor) {
                continue;
            }
            AppLogger.debug(vendor.label() + ": Matched driver " + driver.friendlyName() + " (current version: " + driver.driverVersion() + ")");
            String latest = fetchLatestVersion(driver);
            if (latest != null && VersionCompare.isOlder(driver.driverVersion(), latest)) {
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
        }
        return out;
    }

    protected abstract String fetchLatestVersion(InstalledDriver driver);

    protected String httpGet(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36")
                    .GET()
                    .build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                return resp.body();
            }
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
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
        // Numeric character references: &#58; → :  &#47; → /  &#46; → .  etc.
        StringBuilder sb = new StringBuilder(result.length());
        int i = 0;
        while (i < result.length()) {
            if (result.charAt(i) == '&' && i + 1 < result.length() && result.charAt(i + 1) == '#') {
                int semi = result.indexOf(';', i + 2);
                if (semi > i + 2) {
                    String entity = result.substring(i + 2, semi);
                    try {
                        int codePoint = Integer.parseInt(entity);
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
        // Named entities
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
        Pattern linkPattern = Pattern.compile("(?:href|data-href)\\s*=\\s*\"(https?://[^\"]+\\.(?:exe|zip|msi|inf|cab))\"",
                Pattern.CASE_INSENSITIVE);
        String body = httpGet(vendorPageUrl);
        String found = findFirstMatchingLink(body, linkPattern);
        if (found != null && isLikelyStable(found)) {
            AppLogger.debug(vendor.label() + ": Resolved direct download URL: " + found);
            return found;
        }
        return null;
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
        String lower = url.toLowerCase();
        return !lower.matches(".*\\b(alpha|beta|rc|preview|test)\\b.*");
    }

    private static String deviceKey(InstalledDriver d) {
        return d.deviceId().replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static String sanitize(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
