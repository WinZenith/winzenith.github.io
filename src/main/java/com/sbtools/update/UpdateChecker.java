package com.sbtools.update;

import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.VersionCompare;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + AppInfo.GITHUB_REPO + "/releases/latest";

    private volatile UpdateResult cachedResult = UpdateResult.UNKNOWN;

    public UpdateResult checkForUpdate() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_URL))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion())
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                AppLogger.debug("Update check returned status " + response.statusCode());
                cachedResult = UpdateResult.UNKNOWN;
                return cachedResult;
            }

            String body = response.body();
            String tagName = extractJsonString(body, "tag_name");
            String downloadUrl = extractAssetDownloadUrl(body);

            if (tagName == null || tagName.isEmpty()) {
                cachedResult = UpdateResult.UNKNOWN;
                return cachedResult;
            }

            String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
            if (VersionCompare.isNewer(latestVersion, AppInfo.getVersion())) {
                cachedResult = UpdateResult.updateAvailable(latestVersion, downloadUrl);
            } else {
                cachedResult = UpdateResult.upToDate();
            }
        } catch (Exception e) {
            AppLogger.debug("Update check failed: " + e.getMessage());
            cachedResult = UpdateResult.UNKNOWN;
        }
        return cachedResult;
    }

    public void checkForUpdateAsync(Runnable onResult) {
        Thread thread = new Thread(() -> {
            checkForUpdate();
            Platform.runLater(onResult);
        }, "UpdateChecker");
        thread.setDaemon(true);
        thread.start();
    }

    public UpdateResult getCachedResult() {
        return cachedResult;
    }

    private String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colonIdx = json.indexOf(':', idx + search.length());
        if (colonIdx < 0) return null;
        int startQuote = json.indexOf('"', colonIdx + 1);
        if (startQuote < 0) return null;
        int endQuote = json.indexOf('"', startQuote + 1);
        if (endQuote < 0) return null;
        return json.substring(startQuote + 1, endQuote);
    }

    private String extractAssetDownloadUrl(String json) {
        int assetsIdx = json.indexOf("\"browser_download_url\"");
        if (assetsIdx < 0) return "https://github.com/" + AppInfo.GITHUB_REPO + "/releases/latest";
        int colonIdx = json.indexOf(':', assetsIdx + 22);
        int startQuote = json.indexOf('"', colonIdx + 1);
        int endQuote = json.indexOf('"', startQuote + 1);
        if (startQuote < 0 || endQuote < 0) return "https://github.com/" + AppInfo.GITHUB_REPO + "/releases/latest";
        return json.substring(startQuote + 1, endQuote);
    }

    public record UpdateResult(
            Status status,
            String latestVersion,
            String downloadUrl
    ) {
        static final UpdateResult UNKNOWN = new UpdateResult(Status.UNKNOWN, null, null);

        static UpdateResult upToDate() {
            return new UpdateResult(Status.UP_TO_DATE, null, null);
        }

        static UpdateResult updateAvailable(String version, String url) {
            return new UpdateResult(Status.UPDATE_AVAILABLE, version, url);
        }

        public boolean isUpdateAvailable() {
            return status == Status.UPDATE_AVAILABLE;
        }

        enum Status {
            UNKNOWN, UP_TO_DATE, UPDATE_AVAILABLE
        }
    }
}
