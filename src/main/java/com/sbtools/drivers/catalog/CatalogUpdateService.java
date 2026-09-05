package com.sbtools.drivers.catalog;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;

/**
 * Online refresh for the driver catalog.
 *
 * <p>Behavior (fail-safe, additive):</p>
 * <ul>
 *   <li>Bundled {@code /catalog/driver-catalog.json} is always the fallback.</li>
 *   <li>When a refresh URL is configured (system property
 *       {@code winzenith.catalog.url} or env {@code WINZENITH_CATALOG_URL}),
 *       downloads the JSON, verifies SHA-256 when a {@code .sha256} sidecar
 *       is available, validates entries, and atomically stores it at
 *       {@code <portableBase|localAppData>/catalog/driver-catalog.json}.</li>
 *   <li>Any failure (offline, bad hash, invalid schema) keeps the current
 *       catalog untouched and returns {@code refreshed=false}.</li>
 * </ul>
 */
public final class CatalogUpdateService {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(30);
    /** Minimum sane catalog: refuse to replace a healthy catalog with a tiny file. */
    private static final int MIN_ENTRY_COUNT = 5;

    private CatalogUpdateService() {
    }

    public record RefreshResult(boolean refreshed, String message, int entryCount, String source) {
    }

    public static String configuredCatalogUrl() {
        String sys = System.getProperty("winzenith.catalog.url");
        if (sys != null && !sys.isBlank()) return sys.trim();
        String env = System.getenv("WINZENITH_CATALOG_URL");
        if (env != null && !env.isBlank()) return env.trim();
        return "";
    }

    public static Path refreshedCatalogPath() {
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) {
                Path p = portable.resolve("catalog").resolve("driver-catalog.json");
                // Prefer portable when writable or already present.
                if (Files.exists(p)) return p;
                try {
                    Files.createDirectories(p.getParent());
                    if (Files.isWritable(p.getParent())) return p;
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return AppPaths.localAppData().resolve("catalog").resolve("driver-catalog.json");
    }

    public static Instant refreshedCatalogTime() {
        try {
            Path p = refreshedCatalogPath();
            if (Files.exists(p)) return Files.getLastModifiedTime(p).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Refreshes the catalog from the given URL (or the configured URL when blank).
     * Returns quickly with {@code refreshed=false} when offline/unconfigured.
     */
    public static RefreshResult refresh(String urlOverride) {
        String url = urlOverride != null && !urlOverride.isBlank() ? urlOverride.trim() : configuredCatalogUrl();
        if (url.isBlank()) {
            return new RefreshResult(false, "No catalog URL configured (set -Dwinzenith.catalog.url=... to enable).", 0, "");
        }
        if (!url.toLowerCase().startsWith("https://")) {
            return new RefreshResult(false, "Refusing non-https catalog URL.", 0, "");
        }
        try {
            AppLogger.info("CatalogUpdateService: Refreshing catalog from " + url);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WinZenith")
                    .header("Accept", "application/json")
                    .GET().build();
            HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new RefreshResult(false, "Catalog download failed: HTTP " + resp.statusCode(), 0, "");
            }
            byte[] data = resp.body();
            if (data == null || data.length < 1024) {
                return new RefreshResult(false, "Catalog download too small (" + (data == null ? 0 : data.length) + " bytes) — ignored.", 0, "");
            }

            // Optional .sha256 sidecar verification (best-effort, non-fatal when absent).
            String expectedHash = fetchSidecarHash(url);
            if (expectedHash != null && !expectedHash.isBlank()) {
                String actual = sha256Hex(data);
                if (!actual.equalsIgnoreCase(expectedHash.trim())) {
                    AppLogger.warning("CatalogUpdateService: SHA-256 mismatch (expected=" + expectedHash + " actual=" + actual + ") — refusing update");
                    return new RefreshResult(false, "Catalog hash mismatch — kept current catalog.", 0, "");
                }
                AppLogger.info("CatalogUpdateService: SHA-256 verified for refreshed catalog");
            }

            // Validate schema before replacing anything on disk.
            List<CatalogEntry> entries;
            try {
                String json = new String(data, StandardCharsets.UTF_8);
                JsonNode root = JsonMapper.parseTree(json);
                if (!root.isArray() || root.size() < MIN_ENTRY_COUNT) {
                    return new RefreshResult(false, "Downloaded catalog invalid (need JSON array with >= " + MIN_ENTRY_COUNT + " entries).", 0, "");
                }
                entries = JsonMapper.mapper().readValue(data,
                        JsonMapper.mapper().getTypeFactory().constructCollectionType(List.class, CatalogEntry.class));
            } catch (Exception ex) {
                return new RefreshResult(false, "Downloaded catalog failed validation: " + ex.getMessage(), 0, "");
            }
            long valid = entries.stream().filter(DriverCatalogDatabase::isValidRefreshedEntry).count();
            if (valid < MIN_ENTRY_COUNT) {
                return new RefreshResult(false, "Downloaded catalog has too few valid entries (" + valid + ") — ignored.", 0, "");
            }

            Path target = refreshedCatalogPath();
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling("." + target.getFileName().toString() + ".tmp");
            Files.write(tmp, data);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
            // Bust provider fingerprint caches so the next scan uses the new catalog.
            // Fingerprint already includes bundled catalog hash; refreshed content is picked up
            // on next load() via merge. Clear disk cache to be safe (cheap, non-destructive).
            try {
                new ProviderCache().clearAll();
            } catch (Exception ignored) {
            }
            String msg = "Catalog refreshed: " + valid + " valid entries from " + url;
            AppLogger.info("CatalogUpdateService: " + msg);
            return new RefreshResult(true, msg, (int) valid, url);
        } catch (Exception ex) {
            AppLogger.warning("CatalogUpdateService: Refresh failed: " + ex.getMessage());
            return new RefreshResult(false, "Catalog refresh failed (" + ex.getMessage() + ") — kept current catalog.", 0, "");
        }
    }

    private static String fetchSidecarHash(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url + ".sha256"))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) WinZenith")
                    .GET().build();
            HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) return null;
            String body = resp.body();
            if (body == null || body.isBlank()) return null;
            // Format is either "<hash>" or "<hash>  <filename>".
            String first = body.trim().split("\\s+")[0];
            if (first.matches("(?i)[0-9a-f]{64}")) return first.toLowerCase();
            return null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
