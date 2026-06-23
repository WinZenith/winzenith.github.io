package com.sbtools.drivers.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.drivers.model.DriverUpdateCandidate;
import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * On-disk cache of catalog-provider results. Each provider's last response is
 * keyed by a hash of the (deviceId, driverVersion) tuples it was queried with,
 * so a re-scan over an unchanged device set short-circuits the network call.
 * Entries also carry a TTL to bound staleness.
 */
public final class ProviderCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TTL_SECONDS = 6 * 60 * 60L; // 6 hours

    private final Path cacheDir;
    private final long ttlSeconds;

    public ProviderCache() {
        this(AppPaths.localAppData().resolve("catalog-cache"), DEFAULT_TTL_SECONDS);
    }

    public ProviderCache(Path cacheDir, long ttlSeconds) {
        this.cacheDir = cacheDir;
        this.ttlSeconds = ttlSeconds;
    }

    public Optional<List<DriverUpdateCandidate>> read(String providerId, List<InstalledDriver> installed) {
        try {
            Path file = pathFor(providerId);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            CacheFile cached = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8), CacheFile.class);
            if (cached == null || cached.fingerprint == null) {
                return Optional.empty();
            }
            if (!cached.fingerprint.equals(fingerprint(installed))) {
                return Optional.empty();
            }
            long age = Instant.now().getEpochSecond() - cached.savedAtEpochSecond;
            if (age > ttlSeconds) {
                return Optional.empty();
            }
            return Optional.ofNullable(cached.candidates);
        } catch (Exception e) {
            AppLogger.warning("ProviderCache read failed for " + providerId + ": " + e.getMessage());
            return Optional.empty();
        }
    }

    public void write(String providerId, List<InstalledDriver> installed, List<DriverUpdateCandidate> candidates) {
        try {
            Files.createDirectories(cacheDir);
            CacheFile cached = new CacheFile();
            cached.providerId = providerId;
            cached.fingerprint = fingerprint(installed);
            cached.savedAtEpochSecond = Instant.now().getEpochSecond();
            cached.candidates = candidates;
            Files.writeString(pathFor(providerId),
                    MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cached),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            AppLogger.warning("ProviderCache write failed for " + providerId + ": " + e.getMessage());
        }
    }

    private Path pathFor(String providerId) {
        String safe = providerId.replaceAll("[^A-Za-z0-9_.-]", "_");
        return cacheDir.resolve(safe + ".json");
    }

    private static String fingerprint(List<InstalledDriver> installed) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            installed.stream()
                    .map(d -> (d.deviceId() == null ? "" : d.deviceId())
                            + "@" + (d.driverVersion() == null ? "" : d.driverVersion()))
                    .sorted()
                    .forEach(s -> md.update(s.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "";
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CacheFile {
        public String providerId;
        public String fingerprint;
        public long savedAtEpochSecond;
        public List<DriverUpdateCandidate> candidates;
    }
}
