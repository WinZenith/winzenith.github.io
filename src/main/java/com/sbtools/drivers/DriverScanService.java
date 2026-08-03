package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.JsonMapper;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessResult;
import com.sbtools.util.ProcessRunner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DriverScanService {

    private static final long ENUMERATE_TIMEOUT_SECONDS = 90;

    private final ProcessRunner processRunner = new ProcessRunner(ENUMERATE_TIMEOUT_SECONDS);

    public List<InstalledDriver> scanInstalled() throws IOException, InterruptedException {
        if (!com.sbtools.util.AppPaths.isWindows()) {
            return List.of();
        }
        Path script = PowerShellScripts.resolve("enumerate-devices.ps1");
        ProcessResult result = processRunner.run(ProcessRunner.powershellScript(script.toString()));
        if (!result.success()) {
            throw new IOException("Driver enumeration failed: " + result.combinedOutput());
        }
        return parseDrivers(result.stdout());
    }

    /**
     * Re-scans the system and returns the updated driver entry for the given device ID,
     * or {@code null} if the device is no longer found.
     */
    public InstalledDriver scanSingleDriver(String deviceId) throws IOException, InterruptedException {
        List<InstalledDriver> all = scanInstalled();
        for (InstalledDriver d : all) {
            if (d.deviceId().equals(deviceId)) {
                return d;
            }
        }
        return null;
    }

    public static List<InstalledDriver> parseDrivers(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonMapper.parseTree(json);
        Map<String, InstalledDriver> byDeviceId = new LinkedHashMap<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                InstalledDriver d = nodeToDriver(n);
                if (d != null) {
                    byDeviceId.put(d.deviceId(), d);
                }
            }
        } else if (root.isObject()) {
            InstalledDriver d = nodeToDriver(root);
            if (d != null) {
                byDeviceId.put(d.deviceId(), d);
            }
        }
        return new ArrayList<>(byDeviceId.values());
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
                text(n, "status"),
                parseDate(text(n, "releaseDate"))
        );
    }

    private static LocalDate parseDate(String dateStr) {
        if (dateStr == null || dateStr.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(dateStr);
        } catch (DateTimeParseException ignored) {
        }
        try {
            String cleaned = dateStr.contains("T") ? dateStr.substring(0, dateStr.indexOf('T')) : dateStr;
            String[] parts = cleaned.split("[/\\-]");
            if (parts.length == 3) {
                int a = Integer.parseInt(parts[0]);
                int b = Integer.parseInt(parts[1]);
                int c = Integer.parseInt(parts[2]);
                if (c > 0 && c < 100) {
                    c += (c > 50) ? 1900 : 2000;
                }
                if (a > 12 && b <= 12 && c <= 31) {
                    return LocalDate.of(a, b, c);
                } else if (b > 12 && a <= 12 && c <= 31) {
                    return LocalDate.of(c, a, b);
                } else if (c > 31 && a <= 12 && b <= 12) {
                    return LocalDate.of(c, a, b);
                } else if (a <= 12 && b <= 12 && c > 31) {
                    return LocalDate.of(c, a, b);
                } else if (a <= 12 && b <= 12 && c > 100) {
                    return LocalDate.of(c, a, b);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v != null && !v.isNull() ? v.asText("") : "";
    }
}
