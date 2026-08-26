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
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * On-disk cache of catalog-provider results. Each provider's last response is
 * keyed by a hash of the (deviceId, driverVersion) tuples it was queried with,
 * so a re-scan over an unchanged device set short-circuits the network call.
 * Entries also carry a TTL to bound staleness.
 */
public final class ProviderCache {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final long DEFAULT_TTL_SECONDS = 6 * 60 * 60L; // 6 hours
    private static final long WINDOWS_UPDATE_TTL_SECONDS = 30 * 60L; // 30 minutes — WU state changes frequently
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();

    private final Path cacheDir;
    private final long ttlSeconds;

    public ProviderCache() {
        this(resolveDefaultCacheDir(), DEFAULT_TTL_SECONDS);
    }

    private static Path resolveDefaultCacheDir() {
        Path portable = AppPaths.portableBaseDir();
        if (portable != null) {
            try {
                Path portableCache = portable.resolve("catalog-cache");
                Files.createDirectories(portableCache);
                if (Files.isWritable(portableCache)) {
                    return portableCache;
                }
            } catch (Exception ignored) {}
        }
        return AppPaths.localAppData().resolve("catalog-cache");
    }

    public ProviderCache(Path cacheDir, long ttlSeconds) {
        this.cacheDir = cacheDir;
        this.ttlSeconds = ttlSeconds;
    }

    private static ReentrantLock lockFor(String providerId) {
        return LOCKS.computeIfAbsent(providerId, k -> new ReentrantLock());
    }

    private long ttlForProvider(String providerId) {
        if ("WindowsUpdate".equals(providerId)) {
            return WINDOWS_UPDATE_TTL_SECONDS;
        }
        return ttlSeconds;
    }

    public Optional<List<DriverUpdateCandidate>> read(String providerId, List<InstalledDriver> installed) {
        ReentrantLock lock = lockFor(providerId);
        lock.lock();
        try {
            Path file = pathFor(providerId);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            String json;
            // Try channel lock for inter-process safety, fallback to plain read
            try (var channel = java.nio.channels.FileChannel.open(file, java.nio.file.StandardOpenOption.READ);
                 var fl = channel.tryLock(0L, Long.MAX_VALUE, true)) {
                json = Files.readString(file, StandardCharsets.UTF_8);
            } catch (Exception lockEx) {
                json = Files.readString(file, StandardCharsets.UTF_8);
            }
            CacheFile cached = MAPPER.readValue(json, CacheFile.class);
            if (cached == null || cached.fingerprint == null) {
                return Optional.empty();
            }
            if (!cached.fingerprint.equals(fingerprint(installed))) {
                return Optional.empty();
            }
            long age = Instant.now().getEpochSecond() - cached.savedAtEpochSecond;
            if (age > ttlForProvider(providerId)) {
                return Optional.empty();
            }
            return Optional.ofNullable(cached.candidates);
        } catch (Exception e) {
            AppLogger.warning("ProviderCache read failed for " + providerId + ": " + e.getMessage());
            return Optional.empty();
        } finally {
            lock.unlock();
        }
    }

    public void write(String providerId, List<InstalledDriver> installed, List<DriverUpdateCandidate> candidates) {
        ReentrantLock lock = lockFor(providerId);
        lock.lock();
        try {
            // Do not cache empty results – they are typically transient failures (e.g. AMD scrape 403)
            // and would block re-scan for up to 6h.
            if (candidates == null || candidates.isEmpty()) {
                AppLogger.debug("ProviderCache: Skipping cache write for " + providerId + " (empty result)");
                // Also clear any stale file so next scan retries network
                try { Files.deleteIfExists(pathFor(providerId)); } catch (Exception ignored) {}
                return;
            }
            Files.createDirectories(cacheDir);
            CacheFile cached = new CacheFile();
            cached.providerId = providerId;
            cached.fingerprint = fingerprint(installed);
            cached.savedAtEpochSecond = Instant.now().getEpochSecond();
            cached.candidates = candidates;
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cached);
            Path target = pathFor(providerId);
            Path tmp = target.resolveSibling("." + target.getFileName().toString() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try { Files.deleteIfExists(tmp); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            AppLogger.warning("ProviderCache write failed for " + providerId + ": " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    public void clearAll() {
        // Acquire all provider locks to avoid racing with concurrent writes
        var locks = LOCKS.values().stream().toList();
        locks.forEach(ReentrantLock::lock);
        try {
            if (Files.exists(cacheDir)) {
                try (var walk = Files.walk(cacheDir)) {
                    walk.filter(Files::isRegularFile)
                            .filter(p -> p.toString().endsWith(".json"))
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            }
            AppLogger.info("ProviderCache: Cleared all cached results");
        } catch (Exception e) {
            AppLogger.warning("ProviderCache: Failed to clear cache: " + e.getMessage());
        } finally {
            locks.forEach(ReentrantLock::unlock);
        }
    }

    public void clear(String providerId) {
        ReentrantLock lock = lockFor(providerId);
        lock.lock();
        try {
            Path file = pathFor(providerId);
            Files.deleteIfExists(file);
            AppLogger.info("ProviderCache: Cleared cache for " + providerId);
        } catch (Exception e) {
            AppLogger.warning("ProviderCache: Failed to clear cache for " + providerId + ": " + e.getMessage());
        } finally {
            lock.unlock();
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
