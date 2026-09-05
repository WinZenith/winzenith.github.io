package com.sbtools.cleaner.impl;

import com.sbtools.cleaner.CleanupCategory;
import com.sbtools.cleaner.CleanupRow;
import com.sbtools.cleaner.CleanerExtension;
import com.sbtools.cleaner.CleanerUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ShaderCacheCleaner implements CleanerExtension {

    @Override
    public CleanupCategory getCategory() { return CleanupCategory.NVIDIA_SHADER_CACHE; }

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
        long cleaned = 0;
        for (Path dir : getDirs()) {
            if (token != null && token.isCancelled()) break;
            if (dir != null && Files.isDirectory(dir)) cleaned += CleanerUtils.deleteDirectoryContents(dir, token);
        }
        return cleaned;
    }

    private List<Path> getDirs() {
        List<Path> dirs = new ArrayList<>();
        String localAppData = CleanerUtils.safeEnv("LOCALAPPDATA");
        if (localAppData != null) {
            CleanerUtils.addPath(dirs, localAppData + "\\NVIDIA\\DXCache");
            CleanerUtils.addPath(dirs, localAppData + "\\NVIDIA\\GLCache");
            CleanerUtils.addPath(dirs, localAppData + "\\NVIDIA\\NvShaderCache");
            CleanerUtils.addPath(dirs, localAppData + "\\AMD\\D3DSCache");
            CleanerUtils.addPath(dirs, localAppData + "\\AMD\\VkCache");
        }
        return dirs;
    }
}
