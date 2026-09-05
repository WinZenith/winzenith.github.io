package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Direct3D shader cache at %LOCALAPPDATA%\D3DSCache.
 * Distinct from vendor NVIDIA/AMD caches (covered by NVIDIA_SHADER_CACHE).
 * Regenerated on demand, safe LOW risk.
 */
public class DirectXShaderCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.DIRECTX_SHADER_CACHE; }

    @Override
    public java.util.List<String> describeTargets() {
        return java.util.List.of("%LOCALAPPDATA%\\D3DSCache");
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
        CleanerUtils.addEnvPath(dirs, "LOCALAPPDATA", "D3DSCache");
        return CleanerUtils.deduplicatePaths(dirs);
    }
}
