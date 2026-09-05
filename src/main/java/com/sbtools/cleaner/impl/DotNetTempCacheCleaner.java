package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * .NET temporary compilation cache (Temporary ASP.NET Files).
 * Regenerated on demand. Admin required (WINDIR).
 */
public class DotNetTempCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DOTNET_TEMP_CACHE; }

    @Override
    public boolean requiresAdmin() { return true; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of(
                "%WINDIR%\\Microsoft.NET\\Framework*\\*\\Temporary ASP.NET Files");
    }

    @Override
    public void scan(CleanupRow row) {
        CleanerUtils.scanDirectorySizes(row, getDirs(), CleanerUtils.DEFAULT_SCAN_MAX_DEPTH);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull) {
        return clean(backupRootOrNull, com.sbtools.util.CancellationToken.NONE);
    }

    @Override
    public long clean(java.nio.file.Path backupRootOrNull, com.sbtools.util.CancellationToken token) {
        if (token != null && token.isCancelled()) return 0L;
        return CleanerUtils.cleanDirectoryPattern(getDirs(), token);
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        String windir = CleanerUtils.safeEnv("WINDIR");
        if (windir != null) {
            // Enumerate Framework + Framework64 roots if present; addEnvPath checks existence.
            for (String frameworkRoot : new String[]{"Microsoft.NET\\Framework", "Microsoft.NET\\Framework64"}) {
                java.nio.file.Path root = java.nio.file.Paths.get(windir, frameworkRoot);
                if (Files.isDirectory(root)) {
                    try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                        for (Path versionDir : ds) {
                            Path tmp = versionDir.resolve("Temporary ASP.NET Files");
                            if (Files.isDirectory(tmp)) dirs.add(tmp);
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
