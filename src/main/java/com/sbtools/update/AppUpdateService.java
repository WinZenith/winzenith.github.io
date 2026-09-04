package com.sbtools.update;

import com.sbtools.util.AppInfo;
import com.sbtools.util.AppLogger;
import javafx.concurrent.Task;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Downloads an application update in the background.
 *
 * <p>Runs off the JavaFX Application Thread as a {@link Task} so progress can be
 * bound directly to the UI ({@code progressProperty}/{@code messageProperty})
 * without manual {@code Platform.runLater} flooding. Returns the final file in
 * the target download directory on success.</p>
 */
public class AppUpdateService extends Task<Path> {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final String latestVersion;
    private final String downloadUrl;
    private final Path targetDir;

    public AppUpdateService(String latestVersion, String downloadUrl, Path targetDir) {
        this.latestVersion = latestVersion;
        this.downloadUrl = downloadUrl;
        this.targetDir = targetDir;
    }

    @Override
    protected Path call() throws Exception {
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IOException("Download URL unavailable.");
        }

        URI uri;
        try {
            uri = URI.create(downloadUrl.trim());
        } catch (Exception e) {
            throw new IOException("Invalid download URL: " + e.getMessage(), e);
        }

        updateMessage("Connecting...");
        updateProgress(-1, 1);

        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.ALWAYS)
                .connectTimeout(Duration.ofSeconds(15))
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("User-Agent", AppInfo.DISPLAY_NAME + "/" + AppInfo.getVersion())
                .header("Accept", "*/*")
                .timeout(Duration.ofMinutes(10))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        }

        if (isCancelled()) {
            try { response.body().close(); } catch (Exception ignored) {
            }
            throw new InterruptedException("Download cancelled.");
        }

        if (response.statusCode() != 200) {
            try { response.body().close(); } catch (Exception ignored) {
            }
            throw new IOException("Download failed (HTTP " + response.statusCode() + ").");
        }

        OptionalLong lenHeader = response.headers().firstValueAsLong("Content-Length");
        long contentLength = lenHeader.orElse(-1L);

        String filename = resolveFilename(response, uri);
        Path tempDir = Files.createTempDirectory("WinZenith-update-");
        Path tempFile = tempDir.resolve(filename);

        AppLogger.info("Downloading update v" + latestVersion + " from " + uri);

        try (InputStream in = response.body();
             OutputStream out = Files.newOutputStream(tempFile,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[32 * 1024];
            long total = 0;
            long lastUiUpdate = 0;
            // Show indeterminate until first bytes arrive when length is unknown.
            updateProgress(-1, 1);
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (isCancelled()) {
                    throw new InterruptedException("Download cancelled.");
                }
                out.write(buffer, 0, read);
                total += read;
                long now = System.currentTimeMillis();
                if (now - lastUiUpdate >= 120) {
                    lastUiUpdate = now;
                    if (contentLength > 0) {
                        updateProgress(total, contentLength);
                        updateMessage(String.format("Downloading... %.0f%% (%.1f / %.1f MB)",
                                Math.min(100.0, total * 100.0 / contentLength),
                                total / 1_000_000.0, contentLength / 1_000_000.0));
                    } else {
                        updateProgress(-1, 1);
                        updateMessage(String.format("Downloading... %.1f MB", total / 1_000_000.0));
                    }
                }
            }
            out.flush();

            if (isCancelled()) {
                throw new InterruptedException("Download cancelled.");
            }
            if (contentLength > 0 && total != contentLength) {
                throw new IOException("Download incomplete: expected " + contentLength
                        + " bytes but received " + total + " bytes.");
            }
            if (total == 0) {
                throw new IOException("Download failed: received empty file.");
            }

            updateMessage("Saving...");
            Files.createDirectories(targetDir);
            Path destination = resolveDestination(targetDir, latestVersion);
            // Temp dir is usually on another volume (%TEMP% vs Downloads),
            // so copy instead of atomic move for reliability.
            Files.copy(tempFile, destination);
            AppLogger.info("Update v" + latestVersion + " saved to " + destination + " (" + total + " bytes)");

            updateProgress(1, 1);
            updateMessage("Done");
            return destination;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private static String resolveFilename(HttpResponse<?> response, URI uri) {
        // 1. Prefer Content-Disposition filename if present.
        try {
            Optional<String> cd = response.headers().firstValue("Content-Disposition");
            if (cd.isPresent()) {
                String parsed = parseContentDispositionFilename(cd.get());
                if (parsed != null && !parsed.isBlank()) {
                    return sanitizeFilename(parsed);
                }
            }
        } catch (Exception ignored) {
        }
        // 2. Last path segment of the (possibly redirected) URI.
        try {
            URI effective = response.uri() != null ? response.uri() : uri;
            String path = effective.getPath();
            if (path != null && !path.isBlank()) {
                int slash = path.lastIndexOf('/');
                String name = slash >= 0 ? path.substring(slash + 1) : path;
                // Strip query leftovers just in case.
                int q = name.indexOf('?');
                if (q >= 0) {
                    name = name.substring(0, q);
                }
                if (!name.isBlank()) {
                    return sanitizeFilename(name);
                }
            }
        } catch (Exception ignored) {
        }
        return "WinZenith-update.zip";
    }

    private static String parseContentDispositionFilename(String header) {
        // Handles: attachment; filename="foo.zip" and filename*=UTF-8''foo.zip
        String lower = header.toLowerCase();
        int starIdx = lower.indexOf("filename*=");
        if (starIdx >= 0) {
            String v = header.substring(starIdx + "filename*=".length()).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) {
                v = v.substring(0, semi).trim();
            }
            // Strip charset'' prefix (e.g. UTF-8''name.zip)
            int ticks = v.indexOf("''");
            if (ticks >= 0) {
                v = v.substring(ticks + 2);
            }
            v = stripQuotes(v);
            try {
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {
            }
            if (!v.isBlank()) {
                return v;
            }
        }
        int idx = lower.indexOf("filename=");
        if (idx >= 0) {
            String v = header.substring(idx + "filename=".length()).trim();
            int semi = v.indexOf(';');
            if (semi >= 0) {
                v = v.substring(0, semi).trim();
            }
            v = stripQuotes(v);
            if (!v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String stripQuotes(String v) {
        v = v.trim();
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    private static String sanitizeFilename(String name) {
        String clean = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (clean.isBlank()) {
            return "WinZenith-update.zip";
        }
        // Guard against path traversal segments.
        if (clean.equals(".") || clean.equals("..")) {
            return "WinZenith-update.zip";
        }
        return clean;
    }

    private static Path resolveDestination(Path dir, String version) throws IOException {
        String base = "WinZenith-v" + (version != null && !version.isBlank() ? version : "update") + ".zip";
        Path candidate = dir.resolve(sanitizeFilename(base));
        if (!Files.exists(candidate)) {
            return candidate;
        }
        String ts = LocalDateTime.now().format(TS);
        String stamped = "WinZenith-v" + version + "-" + ts + ".zip";
        return dir.resolve(sanitizeFilename(stamped));
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            if (!Files.exists(dir)) {
                return;
            }
            try (var walk = Files.walk(dir)) {
                var sorted = walk.sorted((a, b) -> b.compareTo(a)).toList();
                for (Path p : sorted) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
