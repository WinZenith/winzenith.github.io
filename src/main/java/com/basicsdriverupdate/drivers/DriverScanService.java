package com.basicsdriverupdate.drivers;

import com.basicsdriverupdate.drivers.model.InstalledDriver;
import com.basicsdriverupdate.util.JsonMapper;
import com.basicsdriverupdate.util.PowerShellScripts;
import com.basicsdriverupdate.util.ProcessResult;
import com.basicsdriverupdate.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class DriverScanService {

    private static final long ENUMERATE_TIMEOUT_SECONDS = 90;

    private final ProcessRunner processRunner = new ProcessRunner(ENUMERATE_TIMEOUT_SECONDS);

    public List<InstalledDriver> scanInstalled() throws IOException, InterruptedException {
        if (!com.basicsdriverupdate.util.AppPaths.isWindows()) {
            return List.of();
        }
        Path script = PowerShellScripts.resolve("enumerate-devices.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Driver enumeration failed: " + result.combinedOutput());
        }
        return parseDrivers(result.stdout());
    }

    public static List<InstalledDriver> parseDrivers(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonMapper.parseTree(json);
        List<InstalledDriver> list = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                InstalledDriver d = nodeToDriver(n);
                if (d != null && d.isHealthy()) {
                    list.add(d);
                }
            }
        } else if (root.isObject()) {
            InstalledDriver d = nodeToDriver(root);
            if (d != null && d.isHealthy()) {
                list.add(d);
            }
        }
        return list;
    }

    private static InstalledDriver nodeToDriver(JsonNode n) {
        String deviceId = text(n, "deviceId");
        if (deviceId.isBlank()) {
            return null;
        }
        return new InstalledDriver(
                deviceId,
                text(n, "friendlyName"),
                text(n, "hardwareIds"),
                text(n, "provider"),
                text(n, "driverVersion"),
                text(n, "infName"),
                text(n, "driverKey"),
                text(n, "status")
        );
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }
}
