package com.sbtools.duplicates;

import com.sbtools.util.AppLogger;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHFILEOPSTRUCT;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.zip.CRC32;

public class DuplicateFinderService {

    private static final int IO_BUFFER_SIZE = 65536;
    private static final int QUICK_HASH_BYTES = 8192;
    private static final int SAMPLE_CHUNK_BYTES = 65536;
    private static final long SAMPLE_SKIP_BELOW_BYTES = 256L * 1024L;

    private static final ThreadLocal<byte[]> IO_BUF = ThreadLocal.withInitial(() -> new byte[IO_BUFFER_SIZE]);
    private static final ThreadLocal<byte[]> QUICK_BUF = ThreadLocal.withInitial(() -> new byte[QUICK_HASH_BYTES]);

    /** Mutable per-scan counters, converted to {@link ScanResult} on return. */
    private static final class ScanStats {
        long enumeratedFiles;
        long skippedProtected;
        long skippedFiltered;
    }

    public List<DuplicateFileRow> scan(Path root, BiConsumer<Integer, Integer> progress,
                                       java.util.function.Consumer<String> phaseLabel,
                                       AtomicBoolean cancelled) {
        return scan(Collections.singletonList(root), progress, phaseLabel, cancelled);
    }

    public ScanResult scanSingleWithStats(Path root, DuplicateScanOptions options,
                                          BiConsumer<Integer, Integer> progress,
                                          java.util.function.Consumer<String> phaseLabel,
                                          AtomicBoolean cancelled) {
        return scanWithStats(Collections.singletonList(root), options, progress, phaseLabel, cancelled);
    }

    public List<DuplicateFileRow> scan(List<Path> roots, BiConsumer<Integer, Integer> progress,
                                       java.util.function.Consumer<String> phaseLabel,
                                       AtomicBoolean cancelled) {
        return scanWithStats(roots, DuplicateScanOptions.defaults(), progress, phaseLabel, cancelled).getRows();
    }

    public ScanResult scanWithStats(List<Path> roots, DuplicateScanOptions options,
                                    BiConsumer<Integer, Integer> progress,
                                    java.util.function.Consumer<String> phaseLabel,
                                    AtomicBoolean cancelled) {
        DuplicateScanOptions effectiveOptions = options == null ? DuplicateScanOptions.defaults() : options;
        DuplicateKeeperStrategy keeperStrategy = effectiveOptions.keeperStrategy() == null
                ? DuplicateKeeperStrategy.NEWEST : effectiveOptions.keeperStrategy();
        ScanStats stats = new ScanStats();
        List<DuplicateFileRow> result = new ArrayList<>();
        ExecutorService executor = null;

        try {
            // Phase 1: walk file tree, bucket by size (skip 0-byte files + option filters)
            if (phaseLabel != null) phaseLabel.accept("Phase 1/4 — Enumerating files...");

            Map<Long, List<Path>> bySize = new HashMap<>();
            Set<String> seenFileKeys = new HashSet<>();
            long[] fileCount = {0};
            final long minSize = Math.max(1L, effectiveOptions.minSizeBytes());
            final DuplicateScanOptions filterOptions = effectiveOptions;

            for (Path root : roots) {
                if (cancelled.get()) break;
                // Validate scan root at walk time — guard against race where root was added before safety check
                if (DuplicateSafety.isProtected(root)) {
                    AppLogger.warning("Skipping protected scan root: " + root);
                    continue;
                }
                // Additional real-path check: block junction/symlink at root that points to protected location
                try {
                    Path realRoot = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!realRoot.equals(root.toAbsolutePath().normalize()) && DuplicateSafety.isProtected(realRoot)) {
                        AppLogger.warning("Skipping protected scan root (real path): " + root + " -> " + realRoot);
                        continue;
                    }
                } catch (Exception ignored) {}
                Path effectiveRoot = toLongPathForced(root);
                Files.walkFileTree(effectiveRoot, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancelled.get()) return FileVisitResult.TERMINATE;
                        // Block any protected directory on any drive (C:\Windows, WindowsApps, System Volume Information, etc.)
                        if (DuplicateSafety.isProtected(dir)) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        // Block junction/symlink that resolves to protected location
                        try {
                            Path real = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
                            if (!real.equals(dir.toAbsolutePath().normalize()) && DuplicateSafety.isProtected(real)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        } catch (Exception ignored) {}
                        // Skip reparse/junction/symlink directories (including root) to avoid traversing outside scope
                        try {
                            if (attrs.isSymbolicLink()) return FileVisitResult.SKIP_SUBTREE;
                        } catch (Exception ignored) {}
                        try {
                            if (Files.isSymbolicLink(dir)) return FileVisitResult.SKIP_SUBTREE;
                        } catch (Exception ignored) {}
                        try {
                            if ((Boolean) Files.getAttribute(dir, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        } catch (Exception ignored) {}
                        if (dir.getFileName() != null) {
                            String name = dir.getFileName().toString().toLowerCase();
                            // Skip hidden/cache directories, but allow root regardless of name
                            if (!dir.equals(effectiveRoot) && (name.startsWith(".") || name.equals("node_modules")
                                    || name.equals("__pycache__"))) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (cancelled.get()) return FileVisitResult.TERMINATE;
                        if (DuplicateSafety.isProtected(file)) {
                            stats.skippedProtected++;
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                            if (!real.equals(file.toAbsolutePath().normalize()) && DuplicateSafety.isProtected(real)) {
                                stats.skippedProtected++;
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (Exception ignored) {}
                        // Skip symlink/reparse files — target content should not be hashed via link
                        try {
                            if (Files.isSymbolicLink(file)) return FileVisitResult.CONTINUE;
                        } catch (Exception ignored) {}
                        try {
                            if ((Boolean) Files.getAttribute(file, "dos:isReparsePoint", LinkOption.NOFOLLOW_LINKS)) {
                                return FileVisitResult.CONTINUE;
                            }
                        } catch (Exception ignored) {}
                        if (file.getFileName() == null) return FileVisitResult.CONTINUE;
                        String fileName = file.getFileName().toString().toLowerCase();
                        if (fileName.equals("ntuser.dat") || fileName.startsWith("ntuser.dat.")
                                || fileName.equals("usrclass.dat") || fileName.startsWith("usrclass.dat.")
                                || fileName.equals("desktop.ini")) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (attrs.size() > 0) {
                            if (attrs.size() < minSize) {
                                stats.skippedFiltered++;
                                return FileVisitResult.CONTINUE;
                            }
                            if (!filterOptions.matchesExtension(file.getFileName().toString())) {
                                stats.skippedFiltered++;
                                return FileVisitResult.CONTINUE;
                            }
                            Object fk = attrs.fileKey();
                            if (fk != null) {
                                String compositeKey;
                                try {
                                    String storeKey = "";
                                    try { storeKey = Files.getFileStore(file).name() + ":" + Files.getFileStore(file).type(); } catch (Exception ignored) {}
                                    Path rootPart = file.toAbsolutePath().getRoot() != null ? file.toAbsolutePath().getRoot() : Paths.get("");
                                    compositeKey = rootPart.toString().toLowerCase() + "|" + storeKey + "|" + fk.toString();
                                } catch (Exception e) {
                                    compositeKey = fk.toString();
                                }
                                if (!seenFileKeys.add(compositeKey)) {
                                    return FileVisitResult.CONTINUE;
                                }
                            }
                            // Store stripped path for UI (remove \\?\ prefix for display)
                            Path stored = stripLongPrefix(file);
                            bySize.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(stored);
                            fileCount[0]++;
                            if (fileCount[0] % 500 == 0 && progress != null) {
                                progress.accept((int) fileCount[0], -1);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            if (cancelled.get()) return ScanResult.cancelled(result, stats);

            List<Path> toHash = new ArrayList<>();
            for (List<Path> paths : bySize.values()) {
                if (paths.size() >= 2) toHash.addAll(paths);
            }
            if (toHash.isEmpty()) return ScanResult.completed(result, stats);
            bySize.clear();

            int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            // FIX: use daemon fixed pool for blocking I/O — ForkJoinPool (workStealing) starves on blocking hash reads
            executor = Executors.newFixedThreadPool(threadCount, r -> {
                Thread t = new Thread(r, "duplicate-hash");
                t.setDaemon(true);
                return t;
            });

            // Phase 2: quick-hash first 8KB using CRC32 (parallel)
            if (phaseLabel != null) phaseLabel.accept("Phase 2/4 — Quick hashing (CRC32)...");

            int quickTotal = toHash.size();
            List<Future<Map.Entry<String, Path>>> quickFutures = new ArrayList<>(quickTotal);

            for (Path p : toHash) {
                if (cancelled.get()) return ScanResult.cancelled(result, stats);
                final Path pathForTask = p;
                quickFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    if (DuplicateSafety.isProtected(pathForTask)) return null;
                    byte[] localBuf = QUICK_BUF.get();
                    CRC32 crc = new CRC32();
                    Path effective = toLongPath(pathForTask);
                    try (InputStream is = new BufferedInputStream(Files.newInputStream(effective))) {
                        int read = is.read(localBuf);
                        crc.update(localBuf, 0, Math.max(read, 0));
                        long size = Files.size(effective);
                        String key = size + ":" + Long.toHexString(crc.getValue());
                        return Map.entry(key, pathForTask);
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }

            Map<String, List<Path>> quickGroups = new HashMap<>();
            int quickProcessed = 0;
            for (int i = 0; i < quickFutures.size(); i++) {
                if (cancelled.get()) {
                    for (int j = i; j < quickFutures.size(); j++) quickFutures.get(j).cancel(true);
                    return ScanResult.cancelled(result, stats);
                }
                try {
                    Map.Entry<String, Path> entry = quickFutures.get(i).get();
                    if (entry != null) {
                        quickGroups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
                    }
                } catch (Exception ignored) {}
                quickProcessed++;
                if (progress != null) progress.accept(quickProcessed, quickTotal);
            }

            if (cancelled.get()) return ScanResult.cancelled(result, stats);

            // Phase 3: sample-hash (head/middle/tail 64KB) to prune same-size
            // collisions without reading multi-GB files in full (parallel).
            // Files below the sample threshold go straight to full hashing.
            if (phaseLabel != null) phaseLabel.accept("Phase 3/4 — Sample hashing...");

            List<Path> smallDirect = new ArrayList<>();
            List<Path> toSample = new ArrayList<>();
            for (List<Path> group : quickGroups.values()) {
                if (group.size() < 2) continue;
                for (Path p : group) {
                    try {
                        long sz = Files.size(toLongPath(p));
                        if (sz < SAMPLE_SKIP_BELOW_BYTES) smallDirect.add(p);
                        else toSample.add(p);
                    } catch (Exception e) {
                        toSample.add(p);
                    }
                }
            }
            quickGroups.clear();

            int middleTotal = toSample.size();
            Map<String, List<Path>> sampleGroups = new HashMap<>();
            if (!toSample.isEmpty()) {
                List<Future<Map.Entry<String, Path>>> sampleFutures = new ArrayList<>(middleTotal);
                for (Path p : toSample) {
                    if (cancelled.get()) return ScanResult.cancelled(result, stats);
                    final Path pathForTask = p;
                    sampleFutures.add(executor.submit(() -> {
                        if (cancelled.get()) return null;
                        if (DuplicateSafety.isProtected(pathForTask)) return null;
                        try {
                            String sample = sampleHashSha256(pathForTask, cancelled);
                            if (sample == null) return null;
                            long size = Files.size(toLongPath(pathForTask));
                            return Map.entry(size + ":" + sample, pathForTask);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return null;
                        } catch (Exception e) {
                            return null;
                        }
                    }));
                }
                int sampleProcessed = 0;
                int sampleCombinedTotal = quickTotal + middleTotal;
                for (int i = 0; i < sampleFutures.size(); i++) {
                    if (cancelled.get()) {
                        for (int j = i; j < sampleFutures.size(); j++) sampleFutures.get(j).cancel(true);
                        return ScanResult.cancelled(result, stats);
                    }
                    try {
                        Map.Entry<String, Path> entry = sampleFutures.get(i).get();
                        if (entry != null) {
                            sampleGroups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
                        }
                    } catch (Exception ignored) {}
                    sampleProcessed++;
                    if (progress != null) progress.accept(quickTotal + sampleProcessed, sampleCombinedTotal);
                }
            }

            if (cancelled.get()) return ScanResult.cancelled(result, stats);

            List<Path> toFullHash = new ArrayList<>(smallDirect);
            for (List<Path> group : sampleGroups.values()) {
                if (group.size() >= 2) toFullHash.addAll(group);
            }
            sampleGroups.clear();

            // Phase 4: full SHA-256 for surviving candidates (parallel)
            if (phaseLabel != null) phaseLabel.accept("Phase 4/4 — Full hashing...");

            int fullTotal = toFullHash.size();
            int combinedTotal = quickTotal + middleTotal + fullTotal;
            List<Future<Map.Entry<String, Path>>> fullFutures = new ArrayList<>(fullTotal);

            for (Path p : toFullHash) {
                if (cancelled.get()) return ScanResult.cancelled(result, stats);
                final Path pathForTask = p;
                fullFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    // Extra guard: file may have become protected between enumeration and hashing
                    if (DuplicateSafety.isProtected(pathForTask)) return null;
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] localBuf = IO_BUF.get();
                        Path effective = toLongPath(pathForTask);
                        try (InputStream is = new BufferedInputStream(Files.newInputStream(effective))) {
                            int read;
                            while ((read = is.read(localBuf)) != -1) {
                                md.update(localBuf, 0, read);
                            }
                        }
                        return Map.entry(HexFormat.of().formatHex(md.digest()), pathForTask);
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }

            Map<String, List<Path>> hashGroups = new LinkedHashMap<>();
            int combinedProcessed = quickTotal + middleTotal;
            for (int i = 0; i < fullFutures.size(); i++) {
                if (cancelled.get()) {
                    for (int j = i; j < fullFutures.size(); j++) fullFutures.get(j).cancel(true);
                    return ScanResult.cancelled(result, stats);
                }
                try {
                    Map.Entry<String, Path> entry = fullFutures.get(i).get();
                    if (entry != null) {
                        hashGroups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>()).add(entry.getValue());
                    }
                } catch (Exception ignored) {}
                combinedProcessed++;
                if (progress != null) progress.accept(combinedProcessed, combinedTotal);
            }

            if (cancelled.get()) return ScanResult.cancelled(result, stats);

            for (Map.Entry<String, List<Path>> entry : hashGroups.entrySet()) {
                List<Path> group = entry.getValue();
                if (group.size() < 2) continue;

                // Safety: if any member is protected, skip the entire group — scanning should have excluded them,
                // but this guards against root race / symlinked protected content.
                boolean hasProtected = false;
                for (Path p : group) {
                    if (DuplicateSafety.isProtected(p)) { hasProtected = true; break; }
                }
                if (hasProtected) {
                    AppLogger.warning("Skipping duplicate group containing protected path: " + group.get(0) + " hash=" + entry.getKey());
                    continue;
                }

                // Keeper selection: keeperRank (non-system drive > system drive) stays
                // primary for safety; the strategy only breaks ties within the same rank.
                Path keeper = selectKeeper(group, keeperStrategy);
                if (keeper == null) continue;

                List<String> deletablePaths = new ArrayList<>();
                for (Path p : group) {
                    if (!p.equals(keeper)) {
                        // Double-guard: never add protected path to deletable set
                        if (DuplicateSafety.isProtected(p)) continue;
                        deletablePaths.add(p.toAbsolutePath().toString());
                    }
                }
                // If after filtering no deletable remains, skip group
                if (deletablePaths.isEmpty()) continue;

                try {
                    long size = Files.size(toLongPath(keeper));
                    List<String> allMembers = new ArrayList<>(group.size());
                    for (Path p : group) {
                        try {
                            allMembers.add(stripLongPrefix(p).toAbsolutePath().toString());
                        } catch (Exception e) {
                            allMembers.add(p.toString());
                        }
                    }
                    stats.enumeratedFiles = fileCount[0];
                    result.add(new DuplicateFileRow(
                            stripLongPrefix(keeper).getFileName().toString(),
                            stripLongPrefix(keeper).toAbsolutePath().toString(),
                            size,
                            entry.getKey(),
                            group.size(),
                            deletablePaths,
                            allMembers
                    ));
                } catch (Exception ignored) {}
            }

            stats.enumeratedFiles = fileCount[0];
            return ScanResult.completed(result, stats);
        } catch (Exception e) {
            AppLogger.error("Duplicate scan failed", e);
            throw new RuntimeException("Duplicate scan failed: " + e.getMessage(), e);
        } finally {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) executor.shutdownNow();
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Selects the keeper for a duplicate group.
     * {@link DuplicateSafety#keeperRank(Path)} stays primary (non-system drive
     * preferred, protected never). The strategy breaks rank ties; the final
     * tie-break is always lexicographic for determinism.
     * Metadata failures never exclude a file — unreadable timestamps are
     * treated as oldest (epoch 0).
     */
    public static Path selectKeeper(List<Path> group, DuplicateKeeperStrategy strategy) {
        if (group == null || group.isEmpty()) return null;
        DuplicateKeeperStrategy effective = strategy == null ? DuplicateKeeperStrategy.NEWEST : strategy;
        Path keeper = null;
        int bestRank = -1;
        FileTime bestTime = null;
        String bestPathStr = null;
        int bestLen = Integer.MAX_VALUE;
        for (Path p : group) {
            int rank;
            try {
                rank = DuplicateSafety.keeperRank(p);
            } catch (Exception e) {
                AppLogger.warning("keeperRank failed for " + p + ": " + e.getMessage());
                rank = 10;
            }
            FileTime ft;
            try {
                ft = Files.getLastModifiedTime(toLongPath(p));
            } catch (Exception e) {
                AppLogger.warning("Could not read lastModified for keeper selection: " + p + " — " + e.getMessage() + " (treating as oldest)");
                ft = FileTime.fromMillis(0);
            }
            String pathStr;
            try {
                pathStr = stripLongPrefix(p).toAbsolutePath().toString();
            } catch (Exception e) {
                pathStr = p.toString();
            }
            boolean better = false;
            if (keeper == null) better = true;
            else if (rank > bestRank) better = true;
            else if (rank == bestRank) {
                switch (effective) {
                    case OLDEST -> {
                        int cmp = ft.compareTo(bestTime);
                        if (cmp < 0) better = true;
                        else if (cmp == 0 && bestPathStr != null && pathStr.compareTo(bestPathStr) < 0) better = true;
                    }
                    case SHORTEST_PATH -> {
                        int len = pathStr.length();
                        if (len < bestLen) better = true;
                        else if (len == bestLen) {
                            if (bestTime == null || ft.compareTo(bestTime) > 0) better = true;
                            else if (ft.equals(bestTime) && bestPathStr != null && pathStr.compareTo(bestPathStr) < 0) better = true;
                        }
                    }
                    default -> { // NEWEST
                        if (bestTime == null || ft.compareTo(bestTime) > 0) better = true;
                        else if (ft.equals(bestTime) && bestPathStr != null && pathStr.compareTo(bestPathStr) < 0) better = true;
                    }
                }
            }
            if (better) {
                keeper = p;
                bestRank = rank;
                bestTime = ft;
                bestPathStr = pathStr;
                bestLen = pathStr.length();
            }
        }
        return keeper;
    }

    /**
     * Recomputes a scanned row's keeper under a different strategy without
     * rescanning. Returns true if the keeper changed (row mutated in place).
     */
    public static boolean recomputeKeeper(DuplicateFileRow row, DuplicateKeeperStrategy strategy) {
        if (row == null) return false;
        List<String> members = row.getAllMemberPaths();
        if (members == null || members.size() < 2) return false;
        List<Path> paths = new ArrayList<>(members.size());
        for (String s : members) {
            try {
                paths.add(Paths.get(s));
            } catch (Exception ignored) {}
        }
        if (paths.size() < 2) return false;
        Path newKeeper = selectKeeper(paths, strategy);
        if (newKeeper == null) return false;
        String newKeeperStr;
        try {
            newKeeperStr = stripLongPrefix(newKeeper).toAbsolutePath().toString();
        } catch (Exception e) {
            newKeeperStr = newKeeper.toString();
        }
        if (newKeeperStr.equals(row.getFullPath())) return false;
        return reassignKeeper(row, newKeeperStr);
    }

    /**
     * Makes the given member path the keeper of the row. The previous keeper
     * becomes deletable. Returns false if the path is not a group member.
     */
    public static boolean reassignKeeper(DuplicateFileRow row, String newKeeperPath) {
        if (row == null || newKeeperPath == null) return false;
        List<String> members = new ArrayList<>(row.getAllMemberPaths());
        if (!members.contains(newKeeperPath)) return false;
        List<String> deletables = new ArrayList<>(members.size() - 1);
        for (String m : members) {
            if (!m.equals(newKeeperPath)) deletables.add(m);
        }
        row.setFullPath(newKeeperPath);
        try {
            Path keeperPath = Paths.get(newKeeperPath);
            String name = keeperPath.getFileName() != null ? keeperPath.getFileName().toString() : row.getFileName();
            row.setFileName(name);
        } catch (Exception ignored) {}
        row.setDeletablePaths(deletables);
        row.setTotalDuplicates(members.size());
        return true;
    }

    public CleanResult clean(List<DuplicateFileRow> selectedRows) {
        return clean(selectedRows, false, null, null);
    }

    public CleanResult clean(List<DuplicateFileRow> selectedRows, boolean useRecycleBin) {
        return clean(selectedRows, useRecycleBin, null, null);
    }

    /**
     * Cancellable clean with progress.
     *
     * @param progress  accepts (processedFiles, totalFiles), may be null; total is best-effort
     * @param cancelled when set, stops before the next file/row; already-deleted files stay deleted,
     *                  remaining files are left untouched and reported via {@link CleanResult#getFailed()}
     */
    public CleanResult clean(List<DuplicateFileRow> selectedRows, boolean useRecycleBin,
                             BiConsumer<Integer, Integer> progress, AtomicBoolean cancelled) {
        int deleted = 0;
        int failed = 0;
        List<DuplicateFileRow> fullyCleanedRows = new ArrayList<>();

        int total = 0;
        if (selectedRows != null) {
            for (DuplicateFileRow r : selectedRows) {
                if (r != null && r.isSelected() && r.getDeletablePaths() != null) {
                    total += r.getDeletablePaths().size();
                }
            }
        }
        int processed = 0;
        boolean wasCancelled = false;

        if (selectedRows == null) return new CleanResult(0, 0, fullyCleanedRows, false);

        for (DuplicateFileRow row : selectedRows) {
            if (isCancelled(cancelled)) { wasCancelled = true; break; }
            if (row == null || !row.isSelected() || row.getDeletablePaths() == null) continue;

            // CRITICAL: keeper re-validation (TOCTOU guard). If the keeper is gone,
            // protected, or no longer matches the scanned checksum, deleting the
            // remaining copies would destroy the last copy — skip the whole group.
            if (!isKeeperStillValid(row, cancelled)) {
                if (isCancelled(cancelled)) { wasCancelled = true; break; }
                int skipped = row.getDeletablePaths().size();
                failed += skipped;
                processed += skipped;
                if (progress != null) {
                    try { progress.accept(processed, total); } catch (Exception ignored) {}
                }
                continue;
            }

            // Filter deletable paths through safety gate and re-hash validation
            List<String> safeDeletables = new ArrayList<>();
            for (String pathStr : row.getDeletablePaths()) {
                if (isCancelled(cancelled)) { wasCancelled = true; break; }
                try {
                    Path p = Paths.get(pathStr);
                    if (DuplicateSafety.isProtected(p)) {
                        AppLogger.warning("Blocked protected delete (safety gate): " + pathStr);
                        failed++;
                        processed++;
                        if (progress != null) {
                            try { progress.accept(processed, total); } catch (Exception ignored) {}
                        }
                        continue;
                    }
                    if (!Files.exists(toLongPath(p))) {
                        AppLogger.info("File already absent during clean: " + pathStr);
                        processed++;
                        if (progress != null) {
                            try { progress.accept(processed, total); } catch (Exception ignored) {}
                        }
                        continue;
                    }
                    // Re-hash check: ensure file still matches the scanned checksum; if changed, skip
                    if (row.getChecksumSha256() != null && !row.getChecksumSha256().isBlank()) {
                        try {
                            String current = hashFileSha256(p, cancelled);
                            if (isCancelled(cancelled)) { wasCancelled = true; break; }
                            if (!row.getChecksumSha256().equalsIgnoreCase(current)) {
                                AppLogger.warning("Skipped changed file (hash mismatch) : " + pathStr);
                                failed++;
                                processed++;
                                if (progress != null) {
                                    try { progress.accept(processed, total); } catch (Exception ignored) {}
                                }
                                continue;
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            wasCancelled = true;
                            break;
                        } catch (Exception he) {
                            AppLogger.warning("Hash check failed for " + pathStr + ": " + he.getMessage() + " — skipping unverified file");
                            failed++;
                            processed++;
                            if (progress != null) {
                                try { progress.accept(processed, total); } catch (Exception ignored) {}
                            }
                            continue;
                        }
                    }
                    safeDeletables.add(pathStr);
                } catch (Exception e) {
                    AppLogger.warning("Invalid deletable path skipped: " + pathStr + " — " + e.getMessage());
                    failed++;
                    processed++;
                    if (progress != null) {
                        try { progress.accept(processed, total); } catch (Exception ignored) {}
                    }
                }
            }
            if (wasCancelled) break;
            if (safeDeletables.isEmpty()) {
                // No safe files to delete in this row — do not count as fully cleaned
                continue;
            }

            if (useRecycleBin) {
                int recycled = moveToRecycleBin(safeDeletables, cancelled);
                if (isCancelled(cancelled)) wasCancelled = true;
                deleted += recycled;
                processed += safeDeletables.size();
                if (progress != null) {
                    try { progress.accept(processed, total); } catch (Exception ignored) {}
                }
                int rowFailed = safeDeletables.size() - recycled;
                failed += rowFailed;
                // Only verified successes count (moveToRecycleBin verifies via Files.exists).
                if (recycled == safeDeletables.size()) fullyCleanedRows.add(row);
            } else {
                boolean rowFullyCleaned = true;
                for (String path : safeDeletables) {
                    if (isCancelled(cancelled)) { wasCancelled = true; rowFullyCleaned = false; break; }
                    try {
                        Path p = Paths.get(path);
                        if (DuplicateSafety.isProtected(p)) {
                            AppLogger.warning("Blocked protected permanent delete: " + path);
                            failed++;
                            processed++;
                            if (progress != null) {
                                try { progress.accept(processed, total); } catch (Exception ignored) {}
                            }
                            rowFullyCleaned = false;
                            continue;
                        }
                        if (Files.deleteIfExists(toLongPath(p))) deleted++;
                        else AppLogger.info("File already absent during clean: " + path);
                        processed++;
                        if (progress != null) {
                            try { progress.accept(processed, total); } catch (Exception ignored) {}
                        }
                    } catch (Exception e) {
                        AppLogger.warning("Failed to delete duplicate: " + path + " — " + e.getMessage());
                        failed++;
                        processed++;
                        if (progress != null) {
                            try { progress.accept(processed, total); } catch (Exception ignored) {}
                        }
                        rowFullyCleaned = false;
                    }
                }
                if (rowFullyCleaned && !isCancelled(cancelled)) fullyCleanedRows.add(row);
                if (isCancelled(cancelled)) wasCancelled = true;
            }
        }
        if (progress != null) {
            try { progress.accept(processed, total); } catch (Exception ignored) {}
        }
        return new CleanResult(deleted, failed, fullyCleanedRows, wasCancelled);
    }

    private static boolean isCancelled(AtomicBoolean cancelled) {
        return cancelled != null && cancelled.get();
    }

    /**
     * Keeper TOCTOU guard: the keeper must still exist, be unprotected, be a
     * regular file, and still hash to the scanned checksum. Otherwise deleting
     * the other copies could destroy the last remaining copy.
     */
    private static boolean isKeeperStillValid(DuplicateFileRow row, AtomicBoolean cancelled) {
        if (row == null || row.getFullPath() == null || row.getFullPath().isBlank()) {
            AppLogger.warning("Skipping group with missing keeper path");
            return false;
        }
        Path keeper;
        try {
            keeper = Paths.get(row.getFullPath());
        } catch (Exception e) {
            AppLogger.warning("Skipping group with invalid keeper path: " + row.getFullPath());
            return false;
        }
        if (DuplicateSafety.isProtected(keeper)) {
            AppLogger.warning("Skipping group: keeper became protected: " + row.getFullPath());
            return false;
        }
        Path effective = toLongPath(keeper);
        try {
            if (!Files.exists(effective) || !Files.isRegularFile(effective, LinkOption.NOFOLLOW_LINKS)) {
                AppLogger.warning("Skipping group: keeper missing (would delete last copy): " + row.getFullPath());
                return false;
            }
        } catch (Exception e) {
            AppLogger.warning("Skipping group: cannot stat keeper " + row.getFullPath() + " — " + e.getMessage());
            return false;
        }
        String expected = row.getChecksumSha256();
        if (expected != null && !expected.isBlank()) {
            try {
                String current = hashFileSha256(keeper, cancelled);
                if (!expected.equalsIgnoreCase(current)) {
                    AppLogger.warning("Skipping group: keeper changed since scan (hash mismatch): " + row.getFullPath());
                    return false;
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            } catch (Exception e) {
                AppLogger.warning("Skipping group: keeper hash check failed for " + row.getFullPath() + " — " + e.getMessage());
                return false;
            }
        }
        return true;
    }

    private static String hashFileSha256(Path p) throws Exception {
        return hashFileSha256(p, null);
    }

    private static String hashFileSha256(Path p, AtomicBoolean cancelled) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = IO_BUF.get();
        Path effective = toLongPath(p);
        try (InputStream is = new BufferedInputStream(Files.newInputStream(effective))) {
            int r;
            while ((r = is.read(buf)) != -1) {
                if (isCancelled(cancelled)) throw new InterruptedException("Cancelled during hash: " + p);
                md.update(buf, 0, r);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static Path toLongPath(Path p) {
        try {
            String s = p.toAbsolutePath().toString();
            if (s.startsWith("\\\\?\\")) return p;
            if (s.length() >= 240) {
                if (s.length() >= 2 && s.charAt(1) == ':') {
                    return Paths.get("\\\\?\\" + s);
                }
                if (s.startsWith("\\\\")) {
                    return Paths.get("\\\\?\\UNC\\" + s.substring(2));
                }
            }
        } catch (Exception ignored) {}
        return p;
    }

    private static Path toLongPathForced(Path p) {
        try {
            String s = p.toAbsolutePath().toString();
            if (s.startsWith("\\\\?\\")) return p;
            if (s.length() >= 2 && s.charAt(1) == ':') {
                return Paths.get("\\\\?\\" + s);
            }
            if (s.startsWith("\\\\")) {
                return Paths.get("\\\\?\\UNC\\" + s.substring(2));
            }
        } catch (Exception ignored) {}
        return p;
    }

    public static Path stripLongPrefix(Path p) {
        try {
            String s = p.toString();
            if (s.startsWith("\\\\?\\UNC\\")) return Paths.get("\\\\" + s.substring(8));
            if (s.startsWith("\\\\?\\")) return Paths.get(s.substring(4));
        } catch (Exception ignored) {}
        return p;
    }

    public static String stripLongPrefix(String s) {
        if (s == null) return null;
        if (s.startsWith("\\\\?\\UNC\\")) return "\\\\" + s.substring(8);
        if (s.startsWith("\\\\?\\")) return s.substring(4);
        return s;
    }

    public int moveToRecycleBin(List<String> paths) {
        return moveToRecycleBin(paths, null);
    }

    /**
     * Moves files to the Recycle Bin. The returned count is VERIFIED via
     * {@link Files#exists} — never trust the SH return code alone because
     * FOF_NOERRORUI suppresses per-file errors and bulk multi-string handling
     * may process only a subset.
     */
    public int moveToRecycleBin(List<String> paths, AtomicBoolean cancelled) {
        if (paths == null || paths.isEmpty()) return 0;
        List<String> validPaths = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (isCancelled(cancelled)) break;
            try {
                Path pathObj = Paths.get(p);
                if (DuplicateSafety.isProtected(pathObj)) {
                    AppLogger.warning("Blocked protected recycle (safety gate): " + p);
                    continue;
                }
            } catch (Exception e) {
                AppLogger.warning("Invalid recycle path skipped: " + p);
                continue;
            }
            // SHFileOperationW does NOT support long \\?\ paths — do not prefix.
            // Keep original path for SH; long paths will be handled via PowerShell fallback.
            validPaths.add(p);
        }
        if (validPaths.isEmpty()) return 0;
        if (isCancelled(cancelled)) return 0;

        boolean hasLongPath = validPaths.stream().anyMatch(p -> p.length() >= 240);

        StringBuilder sb = new StringBuilder();
        for (String p : validPaths) sb.append(p).append('\0');
        sb.append('\0');

        SHFILEOPSTRUCT op = new SHFILEOPSTRUCT();
        op.wFunc = 3; // FO_DELETE
        op.pFrom = sb.toString();
        op.fFlags = 0x40 | 0x10 | 0x400; // FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI

        int result = Shell32.INSTANCE.SHFileOperation(op);
        if (result == 0 && !op.fAnyOperationsAborted && !hasLongPath) {
            // Verify every file — SH may report success while skipping locked files silently.
            int verified = countAbsent(validPaths);
            if (verified < validPaths.size()) {
                AppLogger.warning("SHFileOperation reported success but only "
                        + verified + "/" + validPaths.size() + " files are gone — trying PowerShell fallback for the rest");
                List<String> remaining = listExisting(validPaths);
                if (!remaining.isEmpty() && !isCancelled(cancelled)) {
                    verified += recycleViaPowerShell(remaining, cancelled);
                }
            }
            return verified;
        }
        if (result == 0 && op.fAnyOperationsAborted) {
            // User/system aborted partway — count what actually disappeared.
            return countAbsent(validPaths);
        }
        AppLogger.warning("SHFileOperationW returned " + result + (hasLongPath ? " (contains long path >=240, trying PowerShell fallback)" : ""));

        // Fallback for long paths or SH failure — use PowerShell Microsoft.VisualBasic.FileIO (supports recycle via IFileOperation)
        if (!isCancelled(cancelled) && (hasLongPath || result != 0)) {
            int fb = recycleViaPowerShell(validPaths, cancelled);
            if (fb > 0) {
                AppLogger.info("PowerShell recycle fallback succeeded for " + fb + "/" + validPaths.size() + " files");
                return fb;
            }
        }
        // Even if SH "succeeded" with long paths, verify — anything still present counts as failure.
        if (result == 0) return countAbsent(validPaths);
        return 0;
    }

    private static int countAbsent(List<String> paths) {
        int n = 0;
        for (String p : paths) {
            try {
                String check = stripLongPrefix(p);
                if (!Files.exists(toLongPath(Paths.get(check)))) n++;
            } catch (Exception ignored) {}
        }
        return n;
    }

    private static List<String> listExisting(List<String> paths) {
        List<String> out = new ArrayList<>();
        for (String p : paths) {
            try {
                String check = stripLongPrefix(p);
                if (Files.exists(toLongPath(Paths.get(check)))) out.add(p);
            } catch (Exception ignored) {}
        }
        return out;
    }

    private int recycleViaPowerShell(List<String> paths, AtomicBoolean cancelled) {
        int success = 0;
        for (String p : paths) {
            if (isCancelled(cancelled)) break;
            try {
                Path pathObj = Paths.get(p);
                Path effective = toLongPath(pathObj);
                boolean isDir = Files.isDirectory(effective);
                // PowerShell single-quote escaping: '' for '
                String escaped = p.replace("'", "''");
                String psCmd;
                if (isDir) {
                    psCmd = "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('"
                            + escaped + "','OnlyErrorDialogs','SendToRecycleBin')";
                } else {
                    psCmd = "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('"
                            + escaped + "','OnlyErrorDialogs','SendToRecycleBin')";
                }
                if (runRecycleCommand(psCmd, effective, cancelled)) {
                    success++;
                    continue;
                }
                if (isCancelled(cancelled)) break;
                // Try with \\?\ prefix for long path if first attempt failed
                if (p.length() >= 240 && !p.startsWith("\\\\?\\")) {
                    try {
                        String longP = p.length() >= 2 && p.charAt(1) == ':' ? "\\\\?\\" + p
                                : p.startsWith("\\\\") ? "\\\\?\\UNC\\" + p.substring(2) : p;
                        String escapedLong = longP.replace("'", "''");
                        String psCmdLong = isDir
                                ? "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteDirectory('"
                                + escapedLong + "','OnlyErrorDialogs','SendToRecycleBin')"
                                : "Add-Type -AssemblyName Microsoft.VisualBasic; [Microsoft.VisualBasic.FileIO.FileSystem]::DeleteFile('"
                                + escapedLong + "','OnlyErrorDialogs','SendToRecycleBin')";
                        if (runRecycleCommand(psCmdLong, effective, cancelled)) {
                            success++;
                        }
                    } catch (Exception ignored2) {}
                }
            } catch (Exception e) {
                AppLogger.warning("PowerShell recycle exception for " + p + ": " + e.getMessage());
            }
        }
        return success;
    }

    /**
     * Runs one PowerShell recycle command with a cancellable wait.
     * Polls so Stop can abort between files and mid-file (destroys the process).
     */
    private boolean runRecycleCommand(String psCmd, Path effective, AtomicBoolean cancelled) {
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCmd);
            pb.redirectErrorStream(true);
            proc = pb.start();
            // Cancellable wait: 30s total, 100ms slices.
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                if (isCancelled(cancelled)) {
                    proc.destroyForcibly();
                    return false;
                }
                try {
                    boolean done = proc.waitFor(100, TimeUnit.MILLISECONDS);
                    if (done) break;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    proc.destroyForcibly();
                    return false;
                }
            }
            if (proc.isAlive()) {
                proc.destroyForcibly();
                AppLogger.warning("PowerShell recycle timed out");
                return false;
            }
            String out = "";
            try {
                out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            if (proc.exitValue() == 0 && !Files.exists(effective)) {
                return true;
            }
            AppLogger.warning("PowerShell recycle failed exit=" + proc.exitValue() + " out=" + out.trim());
            return false;
        } catch (Exception e) {
            AppLogger.warning("PowerShell recycle exception: " + e.getMessage());
            return false;
        } finally {
            if (proc != null && proc.isAlive()) proc.destroyForcibly();
        }
    }

    /**
     * Sample hash for the pruning stage: SHA-256 over head/middle/tail
     * 64KB chunks plus the file size. Reads at most 192KB per file, so
     * multi-GB candidates that differ outside the quick-hash window are
     * rejected without a full read. Small files skip this stage upstream.
     */
    private static String sampleHashSha256(Path p, AtomicBoolean cancelled) throws Exception {
        Path effective = toLongPath(p);
        long size = Files.size(effective);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = IO_BUF.get();
        ByteBuffer wrapped = ByteBuffer.wrap(buf);
        long middle = Math.max(0, size / 2 - SAMPLE_CHUNK_BYTES / 2L);
        long tail = Math.max(0, size - SAMPLE_CHUNK_BYTES);
        try (FileChannel ch = FileChannel.open(effective, StandardOpenOption.READ)) {
            readChunkInto(ch, 0, wrapped, SAMPLE_CHUNK_BYTES, md, cancelled, p);
            if (isCancelled(cancelled)) throw new InterruptedException("Cancelled during sample hash: " + p);
            if (middle != 0) readChunkInto(ch, middle, wrapped, SAMPLE_CHUNK_BYTES, md, cancelled, p);
            if (isCancelled(cancelled)) throw new InterruptedException("Cancelled during sample hash: " + p);
            if (tail != 0 && tail != middle) readChunkInto(ch, tail, wrapped, SAMPLE_CHUNK_BYTES, md, cancelled, p);
        }
        // Fold the size into the digest so equal samples of different sizes never collide.
        for (int i = 7; i >= 0; i--) md.update((byte) ((size >>> (i * 8)) & 0xFF));
        return HexFormat.of().formatHex(md.digest());
    }

    private static void readChunkInto(FileChannel ch, long position, ByteBuffer wrapped,
                                      int chunkLen, MessageDigest md,
                                      AtomicBoolean cancelled, Path p) throws Exception {
        ch.position(position);
        int remaining = chunkLen;
        while (remaining > 0) {
            if (isCancelled(cancelled)) throw new InterruptedException("Cancelled during sample hash: " + p);
            wrapped.clear();
            wrapped.limit(Math.min(remaining, wrapped.capacity()));
            int read = ch.read(wrapped);
            if (read <= 0) break;
            md.update(wrapped.array(), 0, read);
            remaining -= read;
        }
    }

    /**
     * Scan outcome: duplicate groups plus enumeration counters.
     * {@link #getRows()} is the same list shape the legacy
     * {@link #scan(List, BiConsumer, java.util.function.Consumer, AtomicBoolean)}
     * returns, so existing callers keep working.
     */
    public static class ScanResult {
        private final List<DuplicateFileRow> rows;
        private final long enumeratedFiles;
        private final long skippedProtected;
        private final long skippedFiltered;
        private final boolean cancelled;

        private ScanResult(List<DuplicateFileRow> rows, ScanStats stats, boolean cancelled) {
            this.rows = rows != null ? rows : new ArrayList<>();
            this.enumeratedFiles = stats != null ? stats.enumeratedFiles : 0;
            this.skippedProtected = stats != null ? stats.skippedProtected : 0;
            this.skippedFiltered = stats != null ? stats.skippedFiltered : 0;
            this.cancelled = cancelled;
        }

        static ScanResult completed(List<DuplicateFileRow> rows, ScanStats stats) {
            return new ScanResult(rows, stats, false);
        }

        static ScanResult cancelled(List<DuplicateFileRow> rows, ScanStats stats) {
            return new ScanResult(rows, stats, true);
        }

        public List<DuplicateFileRow> getRows() { return rows; }
        public long getEnumeratedFiles() { return enumeratedFiles; }
        public long getSkippedProtected() { return skippedProtected; }
        public long getSkippedFiltered() { return skippedFiltered; }
        public boolean isCancelled() { return cancelled; }

        public long getReclaimableBytes() {
            long total = 0;
            for (DuplicateFileRow r : rows) {
                if (r != null) total += (long) (r.getTotalDuplicates() - 1) * r.getFileSize();
            }
            return total;
        }
    }

    public static class CleanResult {
        private final int deleted;
        private final int failed;
        private final List<DuplicateFileRow> fullyCleanedRows;
        private final boolean cancelled;

        public CleanResult(int deleted, int failed, List<DuplicateFileRow> fullyCleanedRows) {
            this(deleted, failed, fullyCleanedRows, false);
        }

        public CleanResult(int deleted, int failed, List<DuplicateFileRow> fullyCleanedRows, boolean cancelled) {
            this.deleted = deleted;
            this.failed = failed;
            this.fullyCleanedRows = fullyCleanedRows != null ? fullyCleanedRows : new ArrayList<>();
            this.cancelled = cancelled;
        }

        public int getDeleted() { return deleted; }
        public int getFailed() { return failed; }
        public List<DuplicateFileRow> getFullyCleanedRows() { return fullyCleanedRows; }
        public boolean isCancelled() { return cancelled; }
    }
}
