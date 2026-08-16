package com.sbtools.duplicates;

import com.sbtools.util.AppLogger;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHFILEOPSTRUCT;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
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

    private static final long MMAP_THRESHOLD = 64 * 1024 * 1024L;

    public List<DuplicateFileRow> scan(Path root, BiConsumer<Integer, Integer> progress,
                                       java.util.function.Consumer<String> phaseLabel,
                                       AtomicBoolean cancelled) {
        return scan(Collections.singletonList(root), progress, phaseLabel, cancelled);
    }

    public List<DuplicateFileRow> scan(List<Path> roots, BiConsumer<Integer, Integer> progress,
                                       java.util.function.Consumer<String> phaseLabel,
                                       AtomicBoolean cancelled) {
        List<DuplicateFileRow> result = new ArrayList<>();
        ExecutorService executor = null;

        try {
            // Phase 1: walk file tree, bucket by size (skip 0-byte files)
            if (phaseLabel != null) phaseLabel.accept("Phase 1/3 — Enumerating files...");

            Map<Long, List<Path>> bySize = new HashMap<>();
            Set<Object> seenFileKeys = new HashSet<>();
            long[] fileCount = {0};

            for (Path root : roots) {
                if (cancelled.get()) break;
                Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        if (cancelled.get()) return FileVisitResult.TERMINATE;
                        if (dir != root) {
                            String name = dir.getFileName().toString().toLowerCase();
                            if (name.startsWith(".") || name.equals("node_modules")
                                    || name.equals("__pycache__")) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            try {
                                if ((Boolean) Files.getAttribute(dir, "dos:isReparsePoint")) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                            } catch (Exception ignored) {}
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (cancelled.get()) return FileVisitResult.TERMINATE;
                        String fileName = file.getFileName().toString().toLowerCase();
                        if (fileName.equals("ntuser.dat") || fileName.startsWith("ntuser.dat.")
                                || fileName.equals("usrclass.dat") || fileName.startsWith("usrclass.dat.")
                                || fileName.equals("desktop.ini")) {
                            return FileVisitResult.CONTINUE;
                        }
                        if (attrs.size() > 0) {
                            Object fk = attrs.fileKey();
                            if (fk != null && !seenFileKeys.add(fk)) {
                                return FileVisitResult.CONTINUE;
                            }
                            bySize.computeIfAbsent(attrs.size(), k -> new ArrayList<>()).add(file);
                            fileCount[0]++;
                            if (fileCount[0] % 500 == 0 && progress != null) {
                                progress.accept((int) fileCount[0], -1);
                            }
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                });
            }

            if (cancelled.get()) return result;

            List<Path> toHash = new ArrayList<>();
            for (List<Path> paths : bySize.values()) {
                if (paths.size() >= 2) toHash.addAll(paths);
            }
            if (toHash.isEmpty()) return result;
            bySize.clear();

            int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            executor = Executors.newWorkStealingPool(threadCount);

            // Phase 2: quick-hash first 8KB using CRC32 (parallel)
            if (phaseLabel != null) phaseLabel.accept("Phase 2/3 — Quick hashing (CRC32)...");

            int quickTotal = toHash.size();
            List<Future<Map.Entry<String, Path>>> quickFutures = new ArrayList<>(quickTotal);

            for (Path p : toHash) {
                if (cancelled.get()) return result;
                quickFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    byte[] localBuf = new byte[8192];
                    CRC32 crc = new CRC32();
                    try (InputStream is = new BufferedInputStream(Files.newInputStream(p))) {
                        int read = is.read(localBuf);
                        crc.update(localBuf, 0, Math.max(read, 0));
                        long size = Files.size(p);
                        String key = size + ":" + Long.toHexString(crc.getValue());
                        return Map.entry(key, p);
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
                    return result;
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

            if (cancelled.get()) return result;

            // Phase 3: full SHA-256 for groups with 2+ files, with short-circuit & mmap (parallel)
            if (phaseLabel != null) phaseLabel.accept("Phase 3/3 — Full hashing...");

            List<Path> toFullHash = new ArrayList<>();
            for (List<Path> group : quickGroups.values()) {
                if (group.size() >= 2) toFullHash.addAll(group);
            }
            quickGroups.clear();

            int fullTotal = toFullHash.size();
            int combinedTotal = quickTotal + fullTotal;
            List<Future<Map.Entry<String, Path>>> fullFutures = new ArrayList<>(fullTotal);

            for (Path p : toFullHash) {
                if (cancelled.get()) return result;
                fullFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    try {
                        long fileSize = Files.size(p);
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        if (fileSize > MMAP_THRESHOLD) {
                            try (FileChannel fc = FileChannel.open(p, StandardOpenOption.READ)) {
                                MappedByteBuffer mapped = fc.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
                                md.update(mapped);
                            }
                        } else {
                            byte[] localBuf = new byte[8192];
                            try (InputStream is = new BufferedInputStream(Files.newInputStream(p))) {
                                int read;
                                while ((read = is.read(localBuf)) != -1) {
                                    md.update(localBuf, 0, read);
                                }
                            }
                        }
                        return Map.entry(HexFormat.of().formatHex(md.digest()), p);
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }

            Map<String, List<Path>> hashGroups = new LinkedHashMap<>();
            int combinedProcessed = quickTotal;
            for (int i = 0; i < fullFutures.size(); i++) {
                if (cancelled.get()) {
                    for (int j = i; j < fullFutures.size(); j++) fullFutures.get(j).cancel(true);
                    return result;
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

            if (cancelled.get()) return result;

            for (Map.Entry<String, List<Path>> entry : hashGroups.entrySet()) {
                List<Path> group = entry.getValue();
                if (group.size() < 2) continue;

                Path keeper = null;
                FileTime newestTime = null;
                for (Path p : group) {
                    try {
                        FileTime ft = Files.getLastModifiedTime(p);
                        if (newestTime == null || ft.compareTo(newestTime) > 0) {
                            newestTime = ft;
                            keeper = p;
                        }
                    } catch (Exception ignored) {}
                }
                if (keeper == null) continue;

                List<String> deletablePaths = new ArrayList<>();
                for (Path p : group) {
                    if (!p.equals(keeper)) deletablePaths.add(p.toAbsolutePath().toString());
                }

                try {
                    long size = Files.size(keeper);
                    result.add(new DuplicateFileRow(
                            keeper.getFileName().toString(),
                            keeper.toAbsolutePath().toString(),
                            size,
                            entry.getKey(),
                            group.size(),
                            deletablePaths
                    ));
                } catch (Exception ignored) {}
            }

            return result;
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

    public CleanResult clean(List<DuplicateFileRow> selectedRows) {
        return clean(selectedRows, false);
    }

    public CleanResult clean(List<DuplicateFileRow> selectedRows, boolean useRecycleBin) {
        int deleted = 0;
        int failed = 0;
        List<DuplicateFileRow> fullyCleanedRows = new ArrayList<>();

        for (DuplicateFileRow row : selectedRows) {
            if (!row.isSelected() || row.getDeletablePaths() == null) continue;

            if (useRecycleBin) {
                int recycled = moveToRecycleBin(row.getDeletablePaths());
                deleted += recycled;
                failed += row.getDeletablePaths().size() - recycled;
                if (recycled == row.getDeletablePaths().size()) fullyCleanedRows.add(row);
            } else {
                boolean rowFullyCleaned = true;
                for (String path : row.getDeletablePaths()) {
                    try {
                        if (Files.deleteIfExists(Paths.get(path))) deleted++;
                        else AppLogger.info("File already absent during clean: " + path);
                    } catch (Exception e) {
                        AppLogger.warning("Failed to delete duplicate: " + path + " — " + e.getMessage());
                        failed++;
                        rowFullyCleaned = false;
                    }
                }
                if (rowFullyCleaned) fullyCleanedRows.add(row);
            }
        }
        return new CleanResult(deleted, failed, fullyCleanedRows);
    }

    public int moveToRecycleBin(List<String> paths) {
        if (paths.isEmpty()) return 0;
        List<String> validPaths = new ArrayList<>(paths.size());
        for (String p : paths) {
            if (p.length() < 260) validPaths.add(p);
            else AppLogger.warning("Path exceeds MAX_PATH (260), cannot recycle: " + p);
        }
        if (validPaths.isEmpty()) return 0;

        StringBuilder sb = new StringBuilder();
        for (String p : validPaths) sb.append(p).append('\0');
        sb.append('\0');

        SHFILEOPSTRUCT op = new SHFILEOPSTRUCT();
        op.wFunc = 3;
        op.pFrom = sb.toString();
        op.fFlags = 0x40 | 0x10 | 0x400;

        int result = Shell32.INSTANCE.SHFileOperation(op);
        if (result == 0) {
            if (op.fAnyOperationsAborted) {
                int successCount = 0;
                for (String p : validPaths) {
                    if (!Files.exists(Paths.get(p))) successCount++;
                }
                return successCount;
            }
            return validPaths.size();
        }
        AppLogger.warning("SHFileOperationW returned " + result);
        return 0;
    }

    public static class CleanResult {
        private final int deleted;
        private final int failed;
        private final List<DuplicateFileRow> fullyCleanedRows;

        public CleanResult(int deleted, int failed, List<DuplicateFileRow> fullyCleanedRows) {
            this.deleted = deleted;
            this.failed = failed;
            this.fullyCleanedRows = fullyCleanedRows;
        }

        public int getDeleted() { return deleted; }
        public int getFailed() { return failed; }
        public List<DuplicateFileRow> getFullyCleanedRows() { return fullyCleanedRows; }
    }
}
