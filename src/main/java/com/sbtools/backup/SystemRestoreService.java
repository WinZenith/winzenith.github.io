package com.sbtools.backup;

import com.sbtools.util.AppLogger;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.PowerShellScripts;

import java.io.IOException;
import java.nio.file.Path;

public class SystemRestoreService {

    private final ProcessRunner runner = new ProcessRunner(300);

    public boolean createRestorePoint(String description) {
        try {
            Path script = PowerShellScripts.resolve("checkpoint-restore.ps1");
            ProcessResult r = runner.run(ProcessRunner.powershellScript(script.toString(), description));
            return r.success();
        } catch (Exception e) {
            AppLogger.warning("Failed to create restore point: " + e.getMessage());
            return false;
        }
    }
}
