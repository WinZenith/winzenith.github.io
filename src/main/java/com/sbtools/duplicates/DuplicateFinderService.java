package com.sbtools.duplicates;

import com.sbtools.util.AppLogger;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHFILEOPSTRUCT;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
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
            Set<String> seenFileKeys = new HashSet<>();
            long[] fileCount = {0};

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
                            return FileVisitResult.CONTINUE;
                        }
                        try {
                            Path real = file.toRealPath(LinkOption.NOFOLLOW_LINKS);
                            if (!real.equals(file.toAbsolutePath().normalize()) && DuplicateSafety.isProtected(real)) {
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

            if (cancelled.get()) return result;

            List<Path> toHash = new ArrayList<>();
            for (List<Path> paths : bySize.values()) {
                if (paths.size() >= 2) toHash.addAll(paths);
            }
            if (toHash.isEmpty()) return result;
            bySize.clear();

            int threadCount = Math.min(Runtime.getRuntime().availableProcessors(), 8);
            // FIX: use daemon fixed pool for blocking I/O — ForkJoinPool (workStealing) starves on blocking hash reads
            executor = Executors.newFixedThreadPool(threadCount, r -> {
                Thread t = new Thread(r, "duplicate-hash");
                t.setDaemon(true);
                return t;
            });

            // Phase 2: quick-hash first 8KB using CRC32 (parallel)
            if (phaseLabel != null) phaseLabel.accept("Phase 2/3 — Quick hashing (CRC32)...");

            int quickTotal = toHash.size();
            List<Future<Map.Entry<String, Path>>> quickFutures = new ArrayList<>(quickTotal);

            for (Path p : toHash) {
                if (cancelled.get()) return result;
                final Path pathForTask = p;
                quickFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    if (DuplicateSafety.isProtected(pathForTask)) return null;
                    byte[] localBuf = new byte[8192];
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
                final Path pathForTask = p;
                fullFutures.add(executor.submit(() -> {
                    if (cancelled.get()) return null;
                    // Extra guard: file may have become protected between enumeration and hashing
                    if (DuplicateSafety.isProtected(pathForTask)) return null;
                    try {
                        MessageDigest md = MessageDigest.getInstance("SHA-256");
                        byte[] localBuf = new byte[8192];
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

                // Keeper selection: prefer keeperRank (non-system drive > system drive), then newest, then lexicographic for determinism
                // FIX: granular metadata handling — failure to read lastModified must not silently exclude file from keeper candidacy
                Path keeper = null;
                int bestRank = -1;
                FileTime bestTime = null;
                String bestPathStr = null;
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
                        if (bestTime == null || ft.compareTo(bestTime) > 0) better = true;
                        else if (ft.equals(bestTime) && bestPathStr != null && pathStr.compareTo(bestPathStr) < 0) better = true;
                    }
                    if (better) {
                        keeper = p;
                        bestRank = rank;
                        bestTime = ft;
                        bestPathStr = pathStr;
                    }
                }
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
                    result.add(new DuplicateFileRow(
                            stripLongPrefix(keeper).getFileName().toString(),
                            stripLongPrefix(keeper).toAbsolutePath().toString(),
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
            // Filter deletable paths through safety gate and re-hash validation
            List<String> safeDeletables = new ArrayList<>();
            for (String pathStr : row.getDeletablePaths()) {
                try {
                    Path p = Paths.get(pathStr);
                    if (DuplicateSafety.isProtected(p)) {
                        AppLogger.warning("Blocked protected delete (safety gate): " + pathStr);
                        failed++;
                        continue;
                    }
                    if (!Files.exists(toLongPath(p))) {
                        AppLogger.info("File already absent during clean: " + pathStr);
                        continue;
                    }
                    // Re-hash check: ensure file still matches the scanned checksum; if changed, skip
                    if (row.getChecksumSha256() != null && !row.getChecksumSha256().isBlank()) {
                        try {
                            String current = hashFileSha256(p);
                            if (!row.getChecksumSha256().equalsIgnoreCase(current)) {
                                AppLogger.warning("Skipped changed file (hash mismatch) : " + pathStr);
                                failed++;
                                continue;
                            }
                        } catch (Exception he) {
                            AppLogger.warning("Hash check failed for " + pathStr + ": " + he.getMessage() + " — skipping unverified file");
                            failed++;
                            continue;
                        }
                    }
                    // Also block system/hidden flagged non-protected? Currently only protected gate blocks; system files inside allowed folders are still allowed
                    safeDeletables.add(pathStr);
                } catch (Exception e) {
                    AppLogger.warning("Invalid deletable path skipped: " + pathStr + " — " + e.getMessage());
                    failed++;
                }
            }
            if (safeDeletables.isEmpty()) {
                // No safe files to delete in this row — do not count as fully cleaned
                continue;
            }

            if (useRecycleBin) {
                int recycled = moveToRecycleBin(safeDeletables);
                deleted += recycled;
                int rowFailed = safeDeletables.size() - recycled;
                failed += rowFailed;
                // Original row considered fully cleaned only if every original deletable was either already absent or successfully recycled
                // For safety we base on safeDeletables set
                if (recycled == safeDeletables.size()) fullyCleanedRows.add(row);
            } else {
                boolean rowFullyCleaned = true;
                for (String path : safeDeletables) {
                    try {
                        Path p = Paths.get(path);
                        if (DuplicateSafety.isProtected(p)) {
                            AppLogger.warning("Blocked protected permanent delete: " + path);
                            failed++;
                            rowFullyCleaned = false;
                            continue;
                        }
                        if (Files.deleteIfExists(toLongPath(p))) deleted++;
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

    private static String hashFileSha256(Path p) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] buf = new byte[8192];
        Path effective = toLongPath(p);
        try (InputStream is = new BufferedInputStream(Files.newInputStream(effective))) {
            int r;
            while ((r = is.read(buf)) != -1) md.update(buf, 0, r);
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
        if (paths.isEmpty()) return 0;
        List<String> validPaths = new ArrayList<>(paths.size());
        for (String p : paths) {
            Path pathObj = Paths.get(p);
            if (DuplicateSafety.isProtected(pathObj)) {
                AppLogger.warning("Blocked protected recycle (safety gate): " + p);
                continue;
            }
            // SHFileOperationW does NOT support long \\?\ paths — do not prefix.
            // Keep original path for SH; long paths will be handled via PowerShell fallback.
            validPaths.add(p);
        }
        if (validPaths.isEmpty()) return 0;

        boolean hasLongPath = validPaths.stream().anyMatch(p -> p.length() >= 240);

        StringBuilder sb = new StringBuilder();
        for (String p : validPaths) sb.append(p).append('\0');
        sb.append('\0');

        SHFILEOPSTRUCT op = new SHFILEOPSTRUCT();
        op.wFunc = 3; // FO_DELETE
        op.pFrom = sb.toString();
        op.fFlags = 0x40 | 0x10 | 0x400; // FOF_ALLOWUNDO | FOF_NOCONFIRMATION | FOF_NOERRORUI

        int result = Shell32.INSTANCE.SHFileOperation(op);
        if (result == 0) {
            if (op.fAnyOperationsAborted) {
                int successCount = 0;
                for (String p : paths) {
                    String check = stripLongPrefix(p);
                    if (!Files.exists(toLongPath(Paths.get(check)))) successCount++;
                }
                return successCount;
            }
            return validPaths.size();
        }
        AppLogger.warning("SHFileOperationW returned " + result + (hasLongPath ? " (contains long path >=240, trying PowerShell fallback)" : ""));

        // Fallback for long paths or SH failure — use PowerShell Microsoft.VisualBasic.FileIO (supports recycle via IFileOperation)
        if (hasLongPath || result != 0) {
            int fb = recycleViaPowerShell(validPaths);
            if (fb > 0) {
                AppLogger.info("PowerShell recycle fallback succeeded for " + fb + "/" + validPaths.size() + " files");
                return fb;
            }
        }
        return 0;
    }

    private int recycleViaPowerShell(List<String> paths) {
        int success = 0;
        for (String p : paths) {
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
                ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
                if (!finished) {
                    proc.destroyForcibly();
                    AppLogger.warning("PowerShell recycle timed out for: " + p);
                    continue;
                }
                String out = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                if (proc.exitValue() == 0 && !Files.exists(effective)) {
                    success++;
                } else {
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
                            ProcessBuilder pb2 = new ProcessBuilder("powershell", "-NoProfile", "-Command", psCmdLong);
                            pb2.redirectErrorStream(true);
                            Process proc2 = pb2.start();
                            boolean fin2 = proc2.waitFor(30, TimeUnit.SECONDS);
                            if (fin2 && proc2.exitValue() == 0 && !Files.exists(effective)) {
                                success++;
                                continue;
                            }
                        } catch (Exception ignored2) {}
                    }
                    AppLogger.warning("PowerShell recycle failed for " + p + " exit=" + proc.exitValue() + " out=" + out.trim());
                }
            } catch (Exception e) {
                AppLogger.warning("PowerShell recycle exception for " + p + ": " + e.getMessage());
            }
        }
        return success;
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
