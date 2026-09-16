package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class MavenCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.MAVEN_CACHE; }

    @Override
    public void scan(CleanupRow row) {
        scan(row, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public void scan(CleanupRow row, com.sbtools.util.CancellationToken token) {
        Path repo = repoDir();
        if (repo == null) {
            row.setTotalBytes(0);
            row.setItemCount(0);
            row.setSizeOrCountText("No snapshot artifacts found");
            return;
        }
        CleanerUtils.scanFilesMatching(row, List.of(repo), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH,
                MavenCacheCleaner::isSnapshotArtifact, token);
        if (row.getItemCount() == 0) {
            row.setSizeOrCountText("No snapshot artifacts found");
        } else {
            row.setSizeOrCountText(CleanerUtils.formatBytes(row.getTotalBytes())
                    + " (" + row.getItemCount() + " snapshot files)");
        }
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        Path repo = repoDir();
        if (repo == null || !CleanerUtils.isSafeToCleanDirectory(repo)) return 0;
        return CleanerUtils.deleteFilesMatching(repo, CleanerUtils.DEFAULT_SCAN_MAX_DEPTH, token,
                MavenCacheCleaner::isSnapshotArtifact);
    }

    private static final java.util.regex.Pattern TIMESTAMPED_SNAPSHOT =
            java.util.regex.Pattern.compile("-\\d{8}\\.\\d{6}-\\d+\\.");

    /**
     * Maven layout: version folder {@code 1.0-SNAPSHOT/} holds both
     * {@code -SNAPSHOT.jar} and timestamped unique versions
     * ({@code foo-1.0-20240101.120000-1.jar}).
     */
    static boolean isSnapshotArtifact(Path p) {
        if (p == null) return false;
        Path fileName = p.getFileName();
        if (fileName == null) return false;
        String lower = fileName.toString().toLowerCase();
        if (lower.contains("-snapshot.")) return true;
        if (TIMESTAMPED_SNAPSHOT.matcher(lower).find()) return true;
        for (Path part : p) {
            String s = part.toString();
            if (s.length() >= 9 && s.regionMatches(true, s.length() - 9, "-SNAPSHOT", 0, 9)) {
                return true;
            }
        }
        return false;
    }

    private Path repoDir() {
        String userHome = CleanerUtils.safeEnv("USERPROFILE");
        if (userHome == null) return null;
        Path repo = Paths.get(userHome, ".m2", "repository");
        return Files.isDirectory(repo) ? repo : null;
    }
}
