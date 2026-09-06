package com.sbtools.netoptimizer;

import com.sbtools.util.AppLogger;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleConsumer;

/**
 * Opt-in download speed test using stdlib {@code java.net.http} only.
 * No background traffic — only runs when the user clicks the button.
 * Default URL is Cloudflare's speed-test endpoint with configurable override.
 */
public class SpeedTestService {

    public record SpeedResult(boolean success, double mbps, long bytes, long millis, String note) {
    }

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public SpeedResult runDownload(String url, AtomicBoolean cancelled, DoubleConsumer onProgress) {
        String target = (url == null || url.isBlank())
                ? "https://speed.cloudflare.com/__down?bytes=10000000"
                : url.trim();
        long start = System.nanoTime();
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(target))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new SpeedResult(false, 0, 0, 0, "HTTP " + resp.statusCode());
            }
            long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1L);
            byte[] buf = new byte[64 * 1024];
            long read = 0;
            int n;
            try (var in = resp.body()) {
                while ((n = in.read(buf)) != -1) {
                    if (cancelled != null && cancelled.get()) {
                        return new SpeedResult(false, 0, read, elapsed(start), "Cancelled by user.");
                    }
                    read += n;
                    if (onProgress != null && total > 0) {
                        try {
                            onProgress.accept(Math.min(1.0, (double) read / total));
                        } catch (Exception ignored) {}
                    }
                }
            }
            long ms = elapsed(start);
            double seconds = Math.max(0.001, ms / 1000.0);
            double mbps = (read * 8.0) / 1_000_000.0 / seconds;
            return new SpeedResult(true, Math.round(mbps * 10.0) / 10.0, read, ms,
                    String.format("%,d bytes in %,d ms", read, ms));
        } catch (IllegalArgumentException e) {
            return new SpeedResult(false, 0, 0, elapsed(start), "Invalid URL: " + e.getMessage());
        } catch (Exception e) {
            AppLogger.warning("Speed test failed: " + e.getMessage());
            return new SpeedResult(false, 0, 0, elapsed(start), "Error: " + e.getMessage());
        }
    }

    private static long elapsed(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
