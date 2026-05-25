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
                String downloadUrl = getDownloadUrl(driver);
                out.add(new DriverUpdateCandidate(
                        driver,
                        latest,
                        vendor.label(),
                        vendor.name() + ":" + sanitize(deviceKey(driver)),
                        vendor.label() + " driver update available",
                        "Check " + vendor.label() + " support site for certified package.",
                        UpdateSeverity.RECOMMENDED,
                        downloadUrl
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
                    .header("User-Agent", "SBasicDriverUpdater/1.0")
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

    protected String getDownloadUrl(InstalledDriver driver) {
        // Subclasses may override to provide precise direct-download URLs.
        // Default: try a small set of vendor support pages and look for the first
        // anchor that links to a common driver package file extension.
        String[] pages;
        switch (vendor) {
            case INTEL -> pages = new String[]{
                    "https://downloadcenter.intel.com",
                    "https://www.intel.com/content/www/us/en/download-center/home.html"
            };
            case NVIDIA -> pages = new String[]{
                    "https://www.nvidia.com/Download/index.aspx",
                    "https://www.nvidia.com/Download/Find.aspx"
            };
            case AMD -> pages = new String[]{
                    "https://www.amd.com/en/support"
            };
            case REALTEK -> pages = new String[]{
                    "https://www.realtek.com/en/downloads"
            };
            case BROADCOM -> pages = new String[]{
                    "https://www.broadcom.com/support/download-search"
            };
            case QUALCOMM -> pages = new String[]{
                    "https://www.qualcomm.com/support"
            };
            default -> pages = new String[]{"https://www." + vendor.label().toLowerCase() + ".com/support"};
        }

        Pattern linkPattern = Pattern.compile("href\\s*=\\s*\"(https?://[^\"]+\\.(?:exe|zip|msi|inf|cab))\"",
                Pattern.CASE_INSENSITIVE);

        for (String p : pages) {
            String body = httpGet(p);
            String found = findFirstMatchingLink(body, linkPattern);
            if (found != null && isLikelyStable(found)) {
                AppLogger.debug(vendor.label() + ": Found candidate download URL: " + found);
                return found;
            }
        }

        return null;
    }

    private String findFirstMatchingLink(String html, Pattern pattern) {
        if (html == null) return null;
        Matcher m = pattern.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    private boolean isLikelyStable(String url) {
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
