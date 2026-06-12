package com.sbtools.duplicates;

import com.sbtools.util.AppLogger;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI;
import com.sun.jna.platform.win32.ShellAPI.SHFILEOPSTRUCT;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class DuplicateFinderService {

    /**
     * Scans for duplicate files under {@code root}.
     *
     * @param progress  callback(progress, total) — during Phase 1 total is -1 (indeterminate);
     *                  during Phase 2/3 total is the combined hash count.
     * @param phaseLabel called with "Phase 1/3 — Enumerating…", "Phase 2/3 — Quick hashing…", etc.
     * @param cancelled checked frequently to allow early abort
     */
    public List<DuplicateFileRow> scan(Path root, BiConsumer<Integer, Integer> progress,
                                       java.util.function.Consumer<String> phaseLabel,
                                       AtomicBoolean cancelled) {
        List<DuplicateFileRow> result = new ArrayList<>();
        ExecutorService executor = null;

        try {
            // ── Phase 1: walk file tree, bucket by size ────────────────────
            if (phaseLabel != null) phaseLabel.accept("Phase 1/3 — Enumerating files…");

            Map<Long, List<Path>> bySize = new HashMap<>();
            Set<Object> seenFileKeys = new HashSet<>();
            long[] fileCount = {0};

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

            if (cancelled.get()) return result;

            // Collect files that have at least one same-size sibling
            List<Path> toHash = new ArrayList<>();
            for (List<Path> paths : bySize.values()) {
                if (paths.size() >= 2) {
                    toHash.addAll(paths);
                }
            }
            if (toHash.isEmpty()) return result;

            // Release Phase 1 data — no longer needed
            bySize.clear();

            MessageDigest md;
            try {
                md = MessageDigest.getInstance("MD5");
            } catch (Exception e) {
                AppLogger.warning("MD5 not available: " + e.getMessage());
                return result;
            }

            HexFormat hex = HexFormat.of();
            byte[] buf = new byte[8192];
            int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            executor = Executors.newFixedThreadPool(threadCount);

            // ── Phase 2: quick-hash first 8KB (parallel) ───────────────────
            if (phaseLabel != null) phaseLabel.accept("Phase 2/3 — Quick hashing…");

            int quickTotal = toHash.size();
            List<Future<Map.Entry<String, Path>>> quickFutures = new ArrayList<>(quickTotal);

            for (Path p : toHash) {
                if (cancelled.get()) return result;
                quickFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    byte[] localBuf = new byte[8192];
                    MessageDigest localMd = MessageDigest.getInstance("MD5");
                    try (InputStream is = Files.newInputStream(p)) {
                        int read = is.read(localBuf);
                        localMd.update(localBuf, 0, Math.max(read, 0));
                        String key = Files.size(p) + ":" + hex.formatHex(localMd.digest());
                        return Map.entry(key, p);
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }

            Map<String, List<Path>> quickGroups = new HashMap<>();
            int quickProcessed = 0;
            for (Future<Map.Entry<String, Path>> future : quickFutures) {
                if (cancelled.get()) return result;
                try {
                    Map.Entry<String, Path> entry = future.get();
                    if (entry != null) {
                        quickGroups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                .add(entry.getValue());
                    }
                } catch (Exception ignored) {}
                quickProcessed++;
                if (progress != null) progress.accept(quickProcessed, quickTotal);
            }

            if (cancelled.get()) return result;

            // ── Phase 3: full MD5 for groups still having 2+ files (parallel) ──
            if (phaseLabel != null) phaseLabel.accept("Phase 3/3 — Full hashing…");

            List<Path> toFullHash = new ArrayList<>();
            for (List<Path> group : quickGroups.values()) {
                if (group.size() >= 2) {
                    toFullHash.addAll(group);
                }
            }

            // Release Phase 2 data
            quickGroups.clear();

            int fullTotal = toFullHash.size();
            int combinedTotal = quickTotal + fullTotal;
            List<Future<Map.Entry<String, Path>>> fullFutures = new ArrayList<>(fullTotal);

            for (Path p : toFullHash) {
                if (cancelled.get()) return result;
                fullFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    byte[] localBuf = new byte[8192];
                    MessageDigest localMd = MessageDigest.getInstance("MD5");
                    try (InputStream is = Files.newInputStream(p)) {
                        int read;
                        while ((read = is.read(localBuf)) != -1) {
                            localMd.update(localBuf, 0, read);
                        }
                        return Map.entry(hex.formatHex(localMd.digest()), p);
                    } catch (Exception e) {
                        return null;
                    }
                }));
            }

            Map<String, List<Path>> hashGroups = new HashMap<>();
            int combinedProcessed = quickTotal;
            for (Future<Map.Entry<String, Path>> future : fullFutures) {
                if (cancelled.get()) return result;
                try {
                    Map.Entry<String, Path> entry = future.get();
                    if (entry != null) {
                        hashGroups.computeIfAbsent(entry.getKey(), k -> new ArrayList<>())
                                .add(entry.getValue());
                    }
                } catch (Exception ignored) {}
                combinedProcessed++;
                if (progress != null) {
                    progress.accept(combinedProcessed, combinedTotal);
                }
            }

            if (cancelled.get()) return result;

            // ── Build result rows — keep newest file per group ─────────────
            for (Map.Entry<String, List<Path>> entry : hashGroups.entrySet()) {
                List<Path> group = entry.getValue();
                String hash = entry.getKey();
                if (group.size() < 2) continue;

                Path keeper = group.get(0);
                FileTime newestTime;
                try {
                    newestTime = Files.getLastModifiedTime(keeper);
                } catch (Exception e) {
                    continue;
                }
                for (int i = 1; i < group.size(); i++) {
                    Path p = group.get(i);
                    try {
                        FileTime ft = Files.getLastModifiedTime(p);
                        if (ft.compareTo(newestTime) > 0) {
                            newestTime = ft;
                            keeper = p;
                        }
                    } catch (Exception ignored) {}
                }

                List<String> deletablePaths = new ArrayList<>();
                for (Path p : group) {
                    if (!p.equals(keeper)) {
                        deletablePaths.add(p.toAbsolutePath().toString());
                    }
                }

                try {
                    long size = Files.size(keeper);
                    result.add(new DuplicateFileRow(
                            keeper.getFileName().toString(),
                            keeper.toAbsolutePath().toString(),
                            size,
                            hash,
                            group.size(),
                            deletablePaths
                    ));
                } catch (Exception ignored) {}
            }

            return result;
        } catch (Exception e) {
            AppLogger.warning("Duplicate scan failed: " + e.getMessage());
            return result;
        } finally {
            if (executor != null) {
                executor.shutdownNow();
            }
        }
    }

    public CleanResult clean(List<DuplicateFileRow> selectedRows) {
        return clean(selectedRows, false);
    }

    public CleanResult clean(List<DuplicateFileRow> selectedRows, boolean useRecycleBin) {
        int deleted = 0;
        int failed = 0;

        for (DuplicateFileRow row : selectedRows) {
            if (!row.isSelected() || row.getDeletablePaths() == null) continue;
            for (String path : row.getDeletablePaths()) {
                if (useRecycleBin) {
                    if (moveToRecycleBin(Collections.singletonList(path)) > 0) {
                        deleted++;
                    } else {
                        failed++;
                    }
                } else {
                    try {
                        Files.deleteIfExists(Paths.get(path));
                        deleted++;
                    } catch (Exception e) {
                        AppLogger.warning("Failed to delete duplicate: " + path + " — " + e.getMessage());
                        failed++;
                    }
                }
            }
        }
        return new CleanResult(deleted, failed);
    }

    private int moveToRecycleBin(List<String> paths) {
        if (paths.isEmpty()) return 0;
        StringBuilder sb = new StringBuilder();
        for (String p : paths) {
            sb.append(p).append('\0');
        }
        sb.append('\0');

        SHFILEOPSTRUCT op = new SHFILEOPSTRUCT();
        op.wFunc = 3; // FO_DELETE
        op.pFrom = sb.toString();
        op.fFlags = 0x40 | 0x10 | 0x400; // FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI

        int result = Shell32.INSTANCE.SHFileOperation(op);
        if (result == 0) return paths.size();

        AppLogger.warning("SHFileOperationW returned " + result);
        return 0;
    }

    public static class CleanResult {
        private final int deleted;
        private final int failed;

        public CleanResult(int deleted, int failed) {
            this.deleted = deleted;
            this.failed = failed;
        }

        public int getDeleted() { return deleted; }
        public int getFailed() { return failed; }
    }
}
