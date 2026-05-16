package com.basicsdriverupdate.drivers.catalog;

import com.basicsdriverupdate.drivers.model.DriverUpdateCandidate;
import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.drivers.model.UpdateSeverity;
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
        String latest = null;
        for (InstalledDriver driver : installed) {
            if (OemVendorHelper.detect(driver) != vendor) {
                continue;
            }
            if (latest == null) {
                latest = fetchLatestVersion(driver);
            }
            if (latest != null && VersionCompare.isOlder(driver.driverVersion(), latest)) {
                out.add(new DriverUpdateCandidate(
                        driver,
                        latest,
                        vendor.label(),
                        vendor.name() + ":" + sanitize(deviceKey(driver)),
                        vendor.label() + " driver update available",
                        "Check " + vendor.label() + " support site for certified package.",
                        UpdateSeverity.RECOMMENDED
                ));
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

    private static String deviceKey(InstalledDriver d) {
        return d.deviceId().replaceAll("[^a-zA-Z0-9]", "_");
    }

    private static String sanitize(String s) {
        return s.length() > 64 ? s.substring(0, 64) : s;
    }
}
