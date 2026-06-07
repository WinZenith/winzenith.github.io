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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

public class DuplicateFinderService {

    public List<DuplicateFileRow> scan(Path root, BiConsumer<Integer, Integer> progress,
                                       AtomicBoolean cancelled) {
        List<DuplicateFileRow> result = new ArrayList<>();

        // Phase 1: walk file tree via FileVisitor — skip junk dirs, cancellable
        Map<Long, List<Path>> bySize = new HashMap<>();
        Set<Object> seenFileKeys = new HashSet<>();
        long[] fileCount = {0};
        try {
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
                            progress.accept((int) fileCount[0], 0);
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
            });
        } catch (Exception e) {
            AppLogger.warning("Duplicate scan enumeration failed: " + e.getMessage());
            return result;
        }

        if (cancelled.get()) return result;

        // Collect files that have at least one same-size sibling
        List<Path> toHash = new ArrayList<>();
        for (List<Path> paths : bySize.values()) {
            if (paths.size() >= 2) {
                toHash.addAll(paths);
            }
        }
        if (toHash.isEmpty()) return result;

        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            AppLogger.warning("MD5 not available: " + e.getMessage());
            return result;
        }

        HexFormat hex = HexFormat.of();
        byte[] buf = new byte[8192];

        // Phase 2: quick-hash first 8KB of every candidate file
        // key = size + ":" + hex(md5(first 8KB))
        Map<String, List<Path>> quickGroups = new HashMap<>();
        int quickTotal = toHash.size();
        int[] quickProcessed = {0};

        for (Path p : toHash) {
            if (cancelled.get()) return result;
            try (InputStream is = Files.newInputStream(p)) {
                int read = is.read(buf);
                md.reset();
                md.update(buf, 0, Math.max(read, 0));
                String key = Files.size(p) + ":" + hex.formatHex(md.digest());
                quickGroups.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
            } catch (Exception ignored) {}
            quickProcessed[0]++;
            if (progress != null) progress.accept(quickProcessed[0], quickTotal);
        }

        if (cancelled.get()) return result;

        // Phase 3: full MD5 for groups still having 2+ files
        List<Path> toFullHash = new ArrayList<>();
        for (List<Path> group : quickGroups.values()) {
            if (group.size() >= 2) {
                toFullHash.addAll(group);
            }
        }
        int fullTotal = toFullHash.size();
        int combinedTotal = quickTotal + fullTotal;
        int[] combinedProcessed = {quickTotal};
        Map<String, List<Path>> hashGroups = new HashMap<>();

        for (Path p : toFullHash) {
            if (cancelled.get()) return result;
            try {
                md.reset();
                try (InputStream is = Files.newInputStream(p)) {
                    int read;
                    while ((read = is.read(buf)) != -1) {
                        md.update(buf, 0, read);
                    }
                }
                hashGroups.computeIfAbsent(hex.formatHex(md.digest()), k -> new ArrayList<>()).add(p);
            } catch (Exception ignored) {}
            combinedProcessed[0]++;
            if (progress != null) {
                progress.accept(combinedProcessed[0], combinedTotal);
            }
        }

        if (cancelled.get()) return result;

        // Build one row per group — keep the newest file, mark others for deletion
        for (Map.Entry<String, List<Path>> entry : hashGroups.entrySet()) {
            List<Path> group = entry.getValue();
            String hash = entry.getKey();
            if (group.size() < 2) continue;

            // Find the newest file by last modified time
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
