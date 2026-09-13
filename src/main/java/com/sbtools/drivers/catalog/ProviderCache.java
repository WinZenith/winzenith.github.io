package com.sbtools.drivers.catalog;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    private static final ObjectMapper MAPPER = new ObjectMapper()
            // JavaTimeModule is mandatory: cached candidates embed InstalledDriver
            // records with LocalDate release dates. Without it every write of a
            // non-empty result threw InvalidDefinitionException, so positive
            // caching never worked and each scan repaid the full provider cost.
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final long DEFAULT_TTL_SECONDS = 6 * 60 * 60L; // 6 hours
    private static final long WINDOWS_UPDATE_TTL_SECONDS = 5 * 60L; // 5 minutes — WU offer sets flip outside the app (Settings/auto-install) while the fingerprint stays identical, which wedged rescans on stale offers until expiry
    private static final long EMPTY_RESULT_TTL_SECONDS = 15 * 60L; // 15 min negative cache for empty/transient results
    private static final ConcurrentHashMap<String, ReentrantLock> LOCKS = new ConcurrentHashMap<>();
    // Serializes clearAll against itself: per-provider locks are taken in map
    // order, so two concurrent clearAlls could ABBA-deadlock. Writes take only
    // their provider lock (never this one), so no lock-order cycle exists.
    private static final ReentrantLock CLEAR_LOCK = new ReentrantLock();

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
                // Stale format or changed device set: drop the file so disk
                // never accumulates misleading results (e.g. pre-fix entries
                // that fail the current matching rules).
                try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                return Optional.empty();
            }
            long age = Instant.now().getEpochSecond() - cached.savedAtEpochSecond;
            boolean isEmpty = cached.candidates == null || cached.candidates.isEmpty();
            if (isEmpty) {
                // Negative cache: transient failures (e.g. scrape 403, WU timeout)
                // are cached briefly to avoid hammering, but expire quickly
                // so the next scan retries the network.
                if (age > EMPTY_RESULT_TTL_SECONDS) {
                    try { Files.deleteIfExists(file); } catch (Exception ignored) {}
                    return Optional.empty();
                }
                AppLogger.debug("ProviderCache: negative-cache hit for " + providerId + " (age " + age + "s)");
                return Optional.of(List.of());
            }
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
            // Cache empty results briefly (negative cache) instead of deleting:
            // avoids hammering a failing provider on every rescan while still
            // retrying after EMPTY_RESULT_TTL_SECONDS. See read().
            List<DriverUpdateCandidate> toStore = candidates == null ? List.of() : candidates;
            if (toStore.isEmpty()) {
                AppLogger.debug("ProviderCache: Caching empty result for " + providerId + " (negative cache, 15m)");
            }
            Files.createDirectories(cacheDir);
            CacheFile cached = new CacheFile();
            cached.providerId = providerId;
            cached.fingerprint = fingerprint(installed);
            cached.savedAtEpochSecond = Instant.now().getEpochSecond();
            cached.candidates = toStore;
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(cached);
            Path target = pathFor(providerId);
            // Unique tmp: fixed sibling names let concurrent instances truncate
            // each other's file (intra-process locks don't cross processes).
            Path tmp = Files.createTempFile(target.getParent(), "." + target.getFileName().toString() + ".", ".tmp");
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
        CLEAR_LOCK.lock();
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
            CLEAR_LOCK.unlock();
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
            if (installed == null) return "";
            String catalogVersion = catalogFingerprint();
            md.update(("catalog:" + catalogVersion + ";").getBytes(StandardCharsets.UTF_8));
            installed.stream()
                    // Stable identity only: friendlyName/provider/infName churn
                    // on innocent renames (Windows re-enumeration, oemNNN.inf
                    // rollover) and thrashed the cache into repaying full
                    // WU/OEM cost every rescan. deviceId+version+HW+key fully
                    // determine provider results.
                    .map(d -> (d == null ? "" : "")
                            + (d == null || d.deviceId() == null ? "" : d.deviceId())
                            + "@" + (d == null || d.driverVersion() == null ? "" : d.driverVersion())
                            + "#" + (d == null || d.hardwareIds() == null ? "" : d.hardwareIds())
                            // driverKey selects PACKAGE_ID catalog matches: same
                            // id/version with a different package must not reuse
                            // cached candidates within the TTL.
                            + "#" + (d == null || d.driverKey() == null ? "" : d.driverKey()))
                    .sorted()
                    .forEach(s -> md.update(s.getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            // Never return a constant here: "" would collide with every other
            // failed hash and serve stale cross-device results from cache.
            return "err-" + System.nanoTime();
        }
    }

    private static String catalogFingerprint() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            boolean any = false;
            try (var is = ProviderCache.class.getResourceAsStream("/catalog/driver-catalog.json")) {
                if (is != null) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) md.update(buf, 0, n);
                    any = true;
                }
            } catch (Exception ignored) {
            }
            // Refreshed catalogs merge over bundled entries in load(): a changed
            // refreshed file must bust the cache too, otherwise scans keep
            // serving candidates computed from the previous catalog content.
            for (Path p : refreshedCatalogPaths()) {
                try {
                    if (p != null && Files.exists(p) && Files.size(p) > 0) {
                        byte[] buf = new byte[8192];
                        try (var in = Files.newInputStream(p)) {
                            int n;
                            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
                        }
                        any = true;
                    }
                } catch (Exception ignored) {
                }
            }
            if (!any) return "no-catalog";
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) {
            return "catalog-err";
        }
    }

    private static List<Path> refreshedCatalogPaths() {
        List<Path> out = new java.util.ArrayList<>(2);
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) out.add(portable.resolve("catalog").resolve("driver-catalog.json"));
        } catch (Exception ignored) {
        }
        try {
            out.add(AppPaths.localAppData().resolve("catalog").resolve("driver-catalog.json"));
        } catch (Exception ignored) {
        }
        return out;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static final class CacheFile {
        public String providerId;
        public String fingerprint;
        public long savedAtEpochSecond;
        public List<DriverUpdateCandidate> candidates;
    }
}
