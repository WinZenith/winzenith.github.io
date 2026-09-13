package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;
import com.sbtools.util.AppLogger;
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
        ProcessResult result = processRunner.run(ProcessRunner.powershellScriptNonInteractive(script.toString()));
        if (!result.success()) {
            // Truncate: full WMI/PS dumps can be multi-MB and would freeze the
            // FX thread inside setStatus + Alert (other paths cap at 1500).
            String out = result.combinedOutput();
            if (out != null && out.length() > 1500) {
                out = out.substring(0, 1500) + "… [truncated, see app.log]";
            }
            AppLogger.warning("Driver enumeration failed: " + result.combinedOutput());
            throw new IOException("Driver enumeration failed: " + out);
        }
        List<InstalledDriver> parsed;
        try {
            parsed = parseDrivers(result.stdout());
        } catch (Exception parseEx) {
            AppLogger.warning("Driver enumeration output unparseable: " + parseEx.getMessage());
            throw new IOException("Driver enumeration returned unparseable output. Retry the scan."
                    + (parseEx.getMessage() == null ? "" : " (" + parseEx.getMessage() + ")"));
        }
        // Fail closed: a healthy machine always enumerates devices. An empty
        // list means CIM filtering went wrong, and reporting "0 devices, all
        // up to date" would hide the failure behind a healthy-looking scan.
        if (parsed.isEmpty()) {
            AppLogger.warning("Driver enumeration returned no devices.");
            throw new IOException("Driver enumeration returned no devices. The device query may have failed — retry the scan.");
        }
        return parsed;
    }

    /**
     * Re-scans the system and returns the updated driver entry for the given device ID,
     * or {@code null} if the device is no longer found.
     */
    public InstalledDriver scanSingleDriver(String deviceId) throws IOException, InterruptedException {
        List<InstalledDriver> all = scanInstalled();
        // Case-insensitive: parseDrivers dedups case-insensitively, so a
        // case-variant re-enumeration must still resolve (else false
        // "device no longer found" and skipped verification).
        String want = normalizeDeviceKey(deviceId);
        for (InstalledDriver d : all) {
            if (normalizeDeviceKey(d.deviceId()).equals(want)) {
                return d;
            }
        }
        return null;
    }

    public static List<InstalledDriver> parseDrivers(String json) throws com.fasterxml.jackson.core.JsonProcessingException {
        JsonNode root = JsonMapper.parseTree(json);
        // First/rich wins, keyed case-insensitively: the PnP source comes first
        // with provider/driverKey populated, and sparse Display/Video fallback
        // rows (or case-variant duplicates the PS $seen missed) must not
        // clobber it and destroy PACKAGE_ID/INF evidence.
        Map<String, InstalledDriver> byDeviceId = new LinkedHashMap<>();
        if (root.isArray()) {
            for (JsonNode n : root) {
                InstalledDriver d = nodeToDriver(n);
                if (d != null) {
                    byDeviceId.putIfAbsent(normalizeDeviceKey(d.deviceId()), d);
                }
            }
        } else if (root.isObject()) {
            InstalledDriver d = nodeToDriver(root);
            if (d != null) {
                byDeviceId.putIfAbsent(normalizeDeviceKey(d.deviceId()), d);
            }
        }
        return new ArrayList<>(byDeviceId.values());
    }

    public static String normalizeDeviceKey(String deviceId) {
        return deviceId == null ? "" : deviceId.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static InstalledDriver nodeToDriver(JsonNode n) {
        String deviceId = text(n, "deviceId");
        if (deviceId.isBlank()) {
            return null;
        }
        Boolean signed = null;
        if (n.has("isSigned") && !n.get("isSigned").isNull()) {
            signed = n.get("isSigned").asBoolean();
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
                parseDate(text(n, "releaseDate")),
                signed
        );
    }

    /**
     * Best-effort fallback for non-ISO dates (e.g. {@code dd/MM/yyyy},
     * {@code MM/dd/yyyy} with 2- or 4-digit years). The primary script always
     * emits ISO {@code yyyy-MM-dd}, handled by {@link LocalDate#parse} above;
     * this path must never throw and returns {@code null} when ambiguous input
     * cannot be resolved safely.
     */
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
            int space = cleaned.indexOf(' ');
            if (space > 0) cleaned = cleaned.substring(0, space);
            cleaned = cleaned.trim();
            String[] parts = cleaned.split("[/\\-.]");
            if (parts.length == 3) {
                String p0 = parts[0].trim();
                String p1 = parts[1].trim();
                String p2 = parts[2].trim();
                int a = Integer.parseInt(p0);
                int b = Integer.parseInt(p1);
                int c = Integer.parseInt(p2);
                // Leading 4-digit year: yyyy-MM-dd with non-dash separators.
                if (p0.length() == 4 && a > 31) {
                    return LocalDate.of(a, b, c);
                }
                int year = c;
                if (year >= 0 && year < 100) {
                    year += (year > 50) ? 1900 : 2000;
                }
                if (a > 12 && b >= 1 && b <= 12) {
                    // dd/MM/yyyy (day cannot be a month)
                    return LocalDate.of(year, b, a);
                }
                if (b > 12 && a >= 1 && a <= 12) {
                    // MM/dd/yyyy (day cannot be a month)
                    return LocalDate.of(year, a, b);
                }
                if (a >= 1 && a <= 12 && b >= 1 && b <= 12) {
                    // Ambiguous (e.g. 05/06/2023): default to MM/dd/yyyy.
                    return LocalDate.of(year, a, b);
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
