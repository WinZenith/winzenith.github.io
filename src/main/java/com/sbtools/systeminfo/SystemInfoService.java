package com.sbtools.systeminfo;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BiConsumer;

public class SystemInfoService {

    private static final long TIMEOUT_SECONDS = 120;
    private static final long CACHE_TTL_MS = 5 * 60 * 1000;

    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);

    private volatile SystemInfoData cachedData;
    private volatile long cacheTimestamp;

    public SystemInfoData gatherSystemInfo() throws IOException, InterruptedException {
        return gatherSystemInfo(null);
    }

    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback) throws IOException, InterruptedException {
        return gatherSystemInfo(progressCallback, false);
    }

    public SystemInfoData gatherSystemInfo(BiConsumer<String, Double> progressCallback, boolean forceRefresh) throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("System information is only available on Windows.");
        }

        if (!forceRefresh && cachedData != null && (System.currentTimeMillis() - cacheTimestamp) < CACHE_TTL_MS) {
            if (progressCallback != null) {
                progressCallback.accept("cached", 1.0);
            }
            return cachedData;
        }

        if (progressCallback != null) {
            progressCallback.accept("CPU", 0.1);
        }
        Path script = PowerShellScripts.resolve("system-info.ps1");
        if (progressCallback != null) {
            progressCallback.accept("hardware", 0.2);
        }
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (progressCallback != null) {
            progressCallback.accept("parsing", 0.9);
        }
        if (!result.success()) {
            throw new IOException("System info query failed: " + result.combinedOutput());
        }
        String json = result.stdout();
        if (json == null || json.isBlank()) {
            throw new IOException("System info query returned empty output.");
        }
        try {
            SystemInfoData data = JsonMapper.mapper().readValue(json.trim(), SystemInfoData.class);
            cachedData = data;
            cacheTimestamp = System.currentTimeMillis();
            if (progressCallback != null) {
                progressCallback.accept("done", 1.0);
            }
            return data;
        } catch (Exception e) {
            AppLogger.error("Failed to parse system info JSON", e);
            throw new IOException("Failed to parse system info: " + e.getMessage(), e);
        }
    }

    public void invalidateCache() {
        cachedData = null;
        cacheTimestamp = 0;
    }
}
