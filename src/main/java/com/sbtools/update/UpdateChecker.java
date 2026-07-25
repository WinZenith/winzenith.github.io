package com.sbtools.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.VersionCompare;
import javafx.application.Platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

public class UpdateChecker {

    private static final String RELEASES_URL =
            "https://api.github.com/repos/" + AppInfo.GITHUB_REPO + "/releases/latest";

    private volatile UpdateResult cachedResult = UpdateResult.UNKNOWN;
    private volatile String lastEtag;

    public UpdateResult checkForUpdate() {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(RELEASES_URL))
                    .header("Accept", "application/vnd.github.v3+json")
                    .header("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion())
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            if (lastEtag != null) {
                requestBuilder.header("If-None-Match", lastEtag);
            }

            HttpResponse<String> response = client.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 304) {
                AppLogger.debug("Update check: 304 Not Modified, using cached result");
                return cachedResult;
            }

            if (response.statusCode() == 403) {
                AppLogger.warning("Update check rate-limited (HTTP 403)");
                String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
                if (retryAfter != null) {
                    AppLogger.warning("Rate limit retry-after: " + retryAfter + "s");
                }
                cachedResult = UpdateResult.UNKNOWN;
                return cachedResult;
            }

            if (response.statusCode() != 200) {
                AppLogger.debug("Update check returned status " + response.statusCode());
                cachedResult = UpdateResult.UNKNOWN;
                return cachedResult;
            }

            response.headers().firstValue("ETag").ifPresent(etag -> lastEtag = etag);

            String body = response.body();
            JsonNode root = JsonMapper.parseTree(body);

            String tagName = root.has("tag_name") ? root.get("tag_name").asText(null) : null;
            String downloadUrl = extractAssetDownloadUrl(root);

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

    public void checkForUpdateAsync(Consumer<UpdateResult> onResult) {
        Thread thread = new Thread(() -> {
            try {
                UpdateResult result = checkForUpdate();
                Platform.runLater(() -> onResult.accept(result));
            } catch (Exception e) {
                AppLogger.debug("Update check thread failed: " + e.getMessage());
                try {
                    Platform.runLater(() -> onResult.accept(UpdateResult.UNKNOWN));
                } catch (Exception ignored) {
                }
            }
        }, "UpdateChecker");
        thread.setDaemon(true);
        thread.start();
    }

    public UpdateResult getCachedResult() {
        return cachedResult;
    }

    private String extractAssetDownloadUrl(JsonNode root) {
        JsonNode assets = root.get("assets");
        if (assets != null && assets.isArray() && !assets.isEmpty()) {
            JsonNode firstAsset = assets.get(0);
            JsonNode urlNode = firstAsset.get("browser_download_url");
            if (urlNode != null && urlNode.isTextual()) {
                String url = urlNode.asText();
                if (!url.isBlank()) {
                    return url;
                }
            }
        }
        return null;
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

        public boolean isUpToDate() {
            return status == Status.UP_TO_DATE;
        }

        public boolean isUnknown() {
            return status == Status.UNKNOWN;
        }

        enum Status {
            UNKNOWN, UP_TO_DATE, UPDATE_AVAILABLE
        }
    }
}
