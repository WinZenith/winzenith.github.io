package com.sbtools.systeminfo;

import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;

import java.io.IOException;
import java.nio.file.Path;

public class SystemInfoService {

    private static final long TIMEOUT_SECONDS = 120;
    private final ProcessRunner processRunner = new ProcessRunner(TIMEOUT_SECONDS);

    public SystemInfoData gatherSystemInfo() throws IOException, InterruptedException {
        if (!AppPaths.isWindows()) {
            throw new UnsupportedOperationException("System information is only available on Windows.");
        }
        Path script = PowerShellScripts.resolve("system-info.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("System info query failed: " + result.combinedOutput());
        }
        String json = result.stdout();
        if (json == null || json.isBlank()) {
            throw new IOException("System info query returned empty output.");
        }
        try {
            return JsonMapper.mapper().readValue(json.trim(), SystemInfoData.class);
        } catch (Exception e) {
            AppLogger.error("Failed to parse system info JSON", e);
            throw new IOException("Failed to parse system info: " + e.getMessage(), e);
        }
    }
}
