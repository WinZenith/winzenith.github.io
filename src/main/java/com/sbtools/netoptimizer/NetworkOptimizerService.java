package com.sbtools.netoptimizer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class NetworkOptimizerService {

    private final ObjectMapper mapper = new ObjectMapper();
    private final NetworkChangeLog changeLog = new NetworkChangeLog();
    private final NetworkSnapshotStore snapshotStore = new NetworkSnapshotStore();
    private final NetworkStateStore stateStore = new NetworkStateStore();
    // SAFE_NAME: allow Unicode letters/numbers for localized adapter names (e.g., Réseau, Łącze) plus common symbols; block injection chars "'\"`;|&<>\$%!+=\n
    private static final Pattern SAFE_NAME = Pattern.compile("^[\\p{L}\\p{N} _\\-().*#]+$");
    // SAFE_HOST: hostname, IPv4 or IPv6. Allow alnum, dot, hyphen, underscore, colon for IPv6.
    private static final Pattern SAFE_HOST = Pattern.compile("^[a-zA-Z0-9._\\-:]+$");
    private static final Pattern IPV4_PATTERN = Pattern.compile("^((25[0-5]|2[0-4]\\d|[01]?\\d\\d?)\\.){3}(25[0-5]|2[0-4]\\d|[01]?\\d\\d?)$");

    private static List<String> powershellCommand(String command) {
        return List.of("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", command);
    }

    private String sanitizeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Adapter name is required");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("Adapter name too long");
        }
        // Block obvious injection characters that could escape psQuote context
        if (trimmed.contains(";") || trimmed.contains("|") || trimmed.contains("&") || trimmed.contains("`") || trimmed.contains("$") || trimmed.contains("\n") || trimmed.contains("\r")) {
            throw new IllegalArgumentException("Invalid adapter name: " + name);
        }
        if (!SAFE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid adapter name: " + name);
        }
        return name;
    }

    public List<NetworkAdapterRow> listAdapters() {
        List<NetworkAdapterRow> adapters = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("net-adapter-info.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            String stderr = pr.stderr() != null ? pr.stderr().trim() : "";
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                try {
                    List<Map<String, Object>> raw = mapper.readValue(stdout,
                            new TypeReference<List<Map<String, Object>>>() {});
                    for (Map<String, Object> entry : raw) {
                        try {
                            String name = str(entry, "Name");
                            if (name.isBlank()) continue;
                            String desc = str(entry, "InterfaceDescription");
                            String status = str(entry, "Status");
                            String speed = str(entry, "LinkSpeed");
                            String mac = str(entry, "MacAddress");
                            String ip = str(entry, "IPAddress");
                            String adminStatus = str(entry, "AdminStatus");
                            // enabled = administrative state, not operational Up status
                            boolean adminEnabled = "Up".equalsIgnoreCase(adminStatus) || "Enabled".equalsIgnoreCase(adminStatus);
                            // status reflects operational: Up/Disconnected/Disabled etc.
                            boolean enabled = adminEnabled;
                            String dhcp = str(entry, "Dhcp");
                            String gateway = str(entry, "Gateway");
                            String dns = str(entry, "DnsServers");
                            adapters.add(new NetworkAdapterRow(name, desc, status, speed, mac, ip, enabled, dhcp, gateway, dns));
                        } catch (Exception e) {
                            AppLogger.warning("Failed to parse adapter entry: " + e.getMessage());
                        }
                    }
                } catch (Exception je) {
                    AppLogger.warning("Failed to parse adapter JSON: " + je.getMessage() + " stdout=" + stdout + " stderr=" + stderr);
                }
            } else if (!stderr.isEmpty()) {
                AppLogger.warning("List adapters stderr: " + stderr);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to list adapters: " + e.getMessage());
        }
        return adapters;
    }

    public OperationResult applyOptimization(OptimizationPreset preset) {
        try {
            Path script = PowerShellScripts.resolve("net-optimize.ps1");
            String presetArg = preset.getScriptName();
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString(), "-Preset", presetArg));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            String combined = pr.combinedOutput();
            String formatted = formatOptimizeResults(stdout);
            if (pr.exitCode() != 0) {
                // Partial application: some settings may already be in effect.
                // Report per-setting detail (not raw JSON) and log it so history matches reality.
                String details = formatted != null ? formatted : (!stdout.isEmpty() ? stdout : combined);
                // Detect access denied hints
                if (combined.toLowerCase().contains("access") && combined.toLowerCase().contains("denied")) {
                    details += "\n\nTip: Run WinZenith as Administrator.";
                }
                logChange("Apply Optimization", preset.getDisplayName(), details, false);
                return OperationResult.fail(
                        "Optimization did not fully apply (exit code " + pr.exitCode() + "). Settings marked OK below are already in effect.",
                        details);
            }
            logChange("Apply Optimization", preset.getDisplayName(), preset.getDescription(), true);
            return OperationResult.ok(preset.getDisplayName() + " applied successfully.",
                    formatted != null ? formatted : stdout);
        } catch (Exception e) {
            AppLogger.warning("Failed to apply optimization: " + e.getMessage());
            return OperationResult.fail("Failed to apply optimization: " + e.getMessage());
        }
    }

    /**
     * Formats the net-optimize.ps1 per-setting JSON array
     * ({Key, Value, Success} entries) into human-readable lines.
     * Returns null when the output is not in that shape.
     */
    private String formatOptimizeResults(String stdout) {
        if (stdout == null || stdout.isBlank()) return null;
        try {
            List<Map<String, Object>> raw = mapper.readValue(stdout,
                    new TypeReference<List<Map<String, Object>>>() {});
            if (raw == null || raw.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> entry : raw) {
                String key = firstPresent(entry, "Key", "key", "name", "Name");
                String value = firstPresent(entry, "Value", "value");
                Object okObj = firstPresentObj(entry, "Success", "success", "ok", "Ok");
                boolean ok = okObj instanceof Boolean b ? b : false;
                if (key.isEmpty() && value.isEmpty()) continue;
                if (sb.length() > 0) sb.append('\n');
                sb.append(key.isEmpty() ? "(unnamed setting)" : key).append(": ")
                        .append(value.isEmpty() ? "-" : value)
                        .append(ok ? " — OK" : " — FAILED");
            }
            return sb.length() > 0 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String firstPresent(Map<String, Object> map, String... keys) {
        Object v = firstPresentObj(map, keys);
        return v != null ? v.toString() : "";
    }

    private static Object firstPresentObj(Map<String, Object> map, String... keys) {
        for (String k : keys) {
            if (map.containsKey(k) && map.get(k) != null) return map.get(k);
        }
        return null;
    }

    public OperationResult flushDnsCache() {
        try {
            Path script = PowerShellScripts.resolve("net-dns-flush.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(result, "message");
                if (ok) logChange("Flush DNS Cache", "All", msg, true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Flush DNS Cache", "All", "DNS cache flushed.", true);
            return ok
                    ? OperationResult.ok("DNS cache flushed.")
                    : OperationResult.fail("Flush failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to flush DNS: " + e.getMessage());
            return OperationResult.fail("Failed to flush DNS: " + e.getMessage());
        }
    }

    public OperationResult resetNetworkStack() {
        try {
            Path script = PowerShellScripts.resolve("net-reset.ps1");
            ProcessResult pr = new ProcessRunner(60).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> result = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = result.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                if (ok) logChange("Reset Network Stack", "TCP/IP + Winsock", "Reboot required.", true);
                return ok
                        ? OperationResult.ok("Network stack reset. Reboot required.", stdout)
                        : OperationResult.fail("Network stack reset failed.", stdout);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Reset Network Stack", "TCP/IP + Winsock", "Reboot required.", true);
            return ok
                    ? OperationResult.ok("Network stack reset. Reboot required.")
                    : OperationResult.fail("Reset failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to reset network stack: " + e.getMessage());
            return OperationResult.fail("Failed to reset network stack: " + e.getMessage());
        }
    }

    public OperationResult resetWinsock() {
        try {
            ProcessResult pr = new ProcessRunner(60).run(powershellCommand("netsh winsock reset"));
            boolean ok = pr.exitCode() == 0;
            String out = pr.combinedOutput();
            if (out != null && out.toLowerCase().contains("access") && out.toLowerCase().contains("denied")) {
                return OperationResult.fail("Winsock reset requires Administrator privileges.", out);
            }
            if (ok) logChange("Reset Winsock", "Winsock catalog", "Reboot recommended.", true);
            return ok
                    ? OperationResult.ok("Winsock reset. Reboot recommended.", out)
                    : OperationResult.fail("Winsock reset failed.", out);
        } catch (Exception e) {
            AppLogger.warning("Failed to reset winsock: " + e.getMessage());
            return OperationResult.fail("Failed to reset winsock: " + e.getMessage());
        }
    }

    public OperationResult renewIp(String adapterName) {
        try {
            sanitizeName(adapterName);
            // Call ipconfig directly without cmd.exe to avoid cmd metachar issues; ProcessBuilder handles spaces via quoting
            ProcessResult release = new ProcessRunner(30).run(
                    List.of("ipconfig", "/release", adapterName));
            if (release.exitCode() != 0) {
                AppLogger.warning("ipconfig /release failed: " + release.combinedOutput());
                // Don't abort if adapter uses static IP; still try renew
                String relOut = release.combinedOutput();
                if (relOut != null && relOut.toLowerCase().contains("no operation can be performed")) {
                    // Adapter not DHCP - inform but continue
                }
            }
            ProcessResult renew = new ProcessRunner(30).run(
                    List.of("ipconfig", "/renew", adapterName));
            if (renew.exitCode() != 0) {
                return OperationResult.fail("IP renewal failed.",
                        "release: " + release.combinedOutput() + "\nrenew: " + renew.combinedOutput());
            }
            logChange("Renew IP", adapterName, "ipconfig /release + /renew", true);
            return OperationResult.ok("IP address renewed for " + adapterName + ".", renew.stdout());
        } catch (Exception e) {
            AppLogger.warning("Failed to renew IP: " + e.getMessage());
            return OperationResult.fail("Failed to renew IP: " + e.getMessage());
        }
    }

    public OperationResult setAdapterState(String adapterName, boolean enable) {
        try {
            sanitizeName(adapterName);
            String cmd = enable ? "Enable-NetAdapter" : "Disable-NetAdapter";
            // Use powershellScript approach with proper quoting via psQuote
            String psCmd = cmd + " -Name " + ProcessRunner.psQuote(adapterName) + " -Confirm:$false";
            ProcessResult pr = new ProcessRunner(30).run(powershellCommand(psCmd));
            String out = pr.combinedOutput();
            if (pr.exitCode() != 0) {
                if (out != null && out.toLowerCase().contains("access") && out.toLowerCase().contains("denied")) {
                    return OperationResult.fail("Requires Administrator: failed to " + (enable ? "enable" : "disable") + " adapter.", out);
                }
                return OperationResult.fail("Failed to " + (enable ? "enable" : "disable") + " adapter.", out);
            }
            logChange(enable ? "Enable Adapter" : "Disable Adapter", adapterName, "", true);
            return OperationResult.ok((enable ? "Enabled" : "Disabled") + " " + adapterName + ".", out);
        } catch (Exception e) {
            AppLogger.warning("Failed to set adapter state: " + e.getMessage());
            return OperationResult.fail("Failed to set adapter state: " + e.getMessage());
        }
    }

    public String getIpConfigAll() {
        try {
            // Use chcp 65001 to force UTF-8 output so ProcessRunner UTF-8 decoding is correct for localized systems (OEM CP850/866).
            // Single command string after /c is required for cmd.exe.
            ProcessResult pr = new ProcessRunner(30).run(
                    List.of("cmd.exe", "/c", "chcp 65001 >nul & ipconfig /all"));
            if (pr.exitCode() == 0) {
                String out = pr.stdout();
                // cmd may prefix with Active code page: 65001 line - strip it
                if (out != null) {
                    out = out.replaceFirst("(?m)^Active code page:.*\\R", "");
                }
                if (out != null && !out.isBlank()) return out;
                // Fallback to direct ipconfig if cmd wrapper produced empty
                ProcessResult pr2 = new ProcessRunner(30).run(List.of("ipconfig", "/all"));
                if (pr2.exitCode() == 0 && pr2.stdout() != null && !pr2.stdout().isBlank()) return pr2.stdout();
                return "No network information returned.";
            }
            // Fallback to direct ipconfig on cmd failure
            try {
                ProcessResult pr2 = new ProcessRunner(30).run(List.of("ipconfig", "/all"));
                if (pr2.exitCode() == 0 && pr2.stdout() != null && !pr2.stdout().isBlank()) return pr2.stdout();
            } catch (Exception ignored) {}
            String err = pr.stderr() != null ? pr.stderr() : pr.combinedOutput();
            return "Failed to retrieve network information:\n" + err;
        } catch (Exception e) {
            AppLogger.warning("Failed to get ipconfig: " + e.getMessage());
            return "Failed to retrieve network information: " + e.getMessage();
        }
    }

    public TcpSettings getCurrentTcpSettings() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(powershellCommand("netsh int tcp show global"));
            String out = pr.stdout();
            if (out == null || out.isBlank()) {
                String combined = pr.combinedOutput();
                if (combined != null && combined.toLowerCase().contains("access") && combined.toLowerCase().contains("denied")) {
                    AppLogger.warning("netsh requires admin: " + combined);
                }
                return new TcpSettings(Map.of());
            }
            return TcpSettings.parse(out);
        } catch (Exception e) {
            AppLogger.warning("Failed to get TCP settings: " + e.getMessage());
            return new TcpSettings(Map.of());
        }
    }

    public List<String> getCurrentDnsServers(String adapterName) {
        try {
            sanitizeName(adapterName);
            Path script = PowerShellScripts.resolve("net-dns-get.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-AdapterName", adapterName));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object dnsObj = data.get("dnsServers");
                if (dnsObj instanceof List<?> list) {
                    return list.stream().map(Object::toString).toList();
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get DNS servers: " + e.getMessage());
        }
        return List.of();
    }

    public OperationResult setDnsServers(String adapterName, String primaryDns, String secondaryDns) {
        try {
            sanitizeName(adapterName);
            String p1 = primaryDns != null ? primaryDns.trim() : "";
            String p2 = secondaryDns != null ? secondaryDns.trim() : "";
            if (!p1.isEmpty() && !isValidIpAddress(p1)) {
                return OperationResult.fail("Invalid primary DNS: " + p1);
            }
            if (!p2.isEmpty() && !isValidIpAddress(p2)) {
                return OperationResult.fail("Invalid secondary DNS: " + p2);
            }
            if (p1.isEmpty() && !p2.isEmpty()) {
                return OperationResult.fail("Primary DNS must be set if secondary is provided.");
            }
            Path script = PowerShellScripts.resolve("net-dns-set.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(),
                            "-AdapterName", adapterName,
                            "-PrimaryDNS", p1,
                            "-SecondaryDNS", p2));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) {
                    String target = !p1.isEmpty() ? p1 + (!p2.isEmpty() ? ", " + p2 : "") : "DHCP";
                    logChange("Set DNS", adapterName, target, true);
                }
                return ok ? OperationResult.ok(msg, stdout) : OperationResult.fail(msg, stdout);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Set DNS", adapterName, !p1.isEmpty() ? p1 : "DHCP", true);
            return ok
                    ? OperationResult.ok("DNS servers updated.")
                    : OperationResult.fail("DNS update failed with exit code " + pr.exitCode(), pr.combinedOutput());
        } catch (Exception e) {
            AppLogger.warning("Failed to set DNS servers: " + e.getMessage());
            return OperationResult.fail("Failed to set DNS servers: " + e.getMessage());
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private void logChange(String operation, String target, String details, boolean success) {
        changeLog.append(new NetworkChangeEntry(
                Instant.now().toString(), operation, target, details, success));
    }

    public List<NetworkChangeEntry> getChangeLog() {
        return changeLog.load();
    }

    public void clearChangeLog() {
        changeLog.clear();
    }

    public AdapterProperties getAdapterProperties(String adapterName) {
        try {
            sanitizeName(adapterName);
            Path script = PowerShellScripts.resolve("net-adapter-properties.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-AdapterName", adapterName));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("error")) {
                    AppLogger.warning("Adapter properties error: " + data.get("error"));
                    return new AdapterProperties(adapterName, Map.of());
                }
                Object propsObj = data.get("properties");
                if (propsObj instanceof List<?> list) {
                    Map<String, String> props = new LinkedHashMap<>();
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> m) {
                            String name = m.get("Name") != null ? m.get("Name").toString() : "";
                            String value = m.get("Value") != null ? m.get("Value").toString() : "";
                            props.put(name, value);
                        }
                    }
                    return new AdapterProperties(adapterName, props);
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get adapter properties: " + e.getMessage());
        }
        return new AdapterProperties(adapterName, Map.of());
    }

    public WiFiInfo getCurrentWifiInfo() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-info.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                if (data.containsKey("error")) {
                    return null;
                }
                return new WiFiInfo(
                        str(data, "ssid"),
                        str(data, "state"),
                        data.get("signalPercent") instanceof Number n ? n.intValue() : 0,
                        str(data, "radioType"),
                        str(data, "channel"),
                        str(data, "receiveRate"),
                        str(data, "transmitRate"));
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get Wi-Fi info: " + e.getMessage());
        }
        return null;
    }

    public List<String> getWifiProfiles() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-profiles.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                return mapper.readValue(stdout, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to get Wi-Fi profiles: " + e.getMessage());
        }
        return List.of();
    }

    public OperationResult disconnectWifi() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-disconnect.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) logChange("Disconnect Wi-Fi", "Wi-Fi", "", true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Disconnect Wi-Fi", "Wi-Fi", "", true);
            return ok
                    ? OperationResult.ok("Wi-Fi disconnected.")
                    : OperationResult.fail("Disconnect failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to disconnect Wi-Fi: " + e.getMessage());
            return OperationResult.fail("Failed to disconnect Wi-Fi: " + e.getMessage());
        }
    }

    public OperationResult forgetWifiProfile(String ssid) {
        if (ssid == null || ssid.isBlank()) {
            return OperationResult.fail("SSID is required.");
        }
        if (ssid.length() > 32) {
            return OperationResult.fail("SSID must be 32 characters or less.");
        }
        try {
            Path script = PowerShellScripts.resolve("net-wifi-forget.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-SSID", ssid));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object success = data.get("success");
                boolean ok = success instanceof Boolean && (Boolean) success;
                String msg = str(data, "message");
                if (ok) logChange("Forget Wi-Fi Profile", ssid, "", true);
                return ok ? OperationResult.ok(msg) : OperationResult.fail(msg);
            }
            boolean ok = pr.exitCode() == 0;
            if (ok) logChange("Forget Wi-Fi Profile", ssid, "", true);
            return ok
                    ? OperationResult.ok("Profile '" + ssid + "' forgotten.")
                    : OperationResult.fail("Forget failed with exit code " + pr.exitCode());
        } catch (Exception e) {
            AppLogger.warning("Failed to forget Wi-Fi profile: " + e.getMessage());
            return OperationResult.fail("Failed to forget Wi-Fi profile: " + e.getMessage());
        }
    }

    private String sanitizeHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Host is required");
        }
        String trimmed = host.trim();
        if (trimmed.length() > 253) {
            throw new IllegalArgumentException("Host too long");
        }
        // Allow percent-encoded? No, strict but include underscore/colon for IPv6
        if (!SAFE_HOST.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("Invalid host: " + host + " (allowed: letters, digits, dot, hyphen, underscore, colon)");
        }
        // Disallow leading hyphen/dot/colon
        if (trimmed.startsWith("-") || trimmed.startsWith(".") || trimmed.startsWith(":")) {
            throw new IllegalArgumentException("Invalid host: " + host);
        }
        return trimmed;
    }

    public static boolean isValidHost(String host) {
        if (host == null || host.isBlank()) return false;
        String t = host.trim();
        if (t.length() > 253) return false;
        if (!SAFE_HOST.matcher(t).matches()) return false;
        if (t.startsWith("-") || t.startsWith(".") || t.startsWith(":")) return false;
        return true;
    }

    public static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isBlank()) return false;
        String t = ip.trim();
        // IPv4 strict
        if (IPV4_PATTERN.matcher(t).matches()) return true;
        // IPv6 loose check: hex, colons, at least 2 colons, no DNS lookup to avoid blocking
        if (t.contains(":")) {
            String ipv6 = t.toLowerCase();
            // Must be hex digits and colons only, allow :: compression
            if (ipv6.matches("^[0-9a-f:]+$") && ipv6.chars().filter(ch -> ch == ':').count() >= 2) {
                // Disallow triple colon and invalid chars already filtered
                if (ipv6.contains(":::")) return false;
                // Basic segment length check (each segment <=4 hex chars)
                String[] parts = ipv6.split(":", -1);
                for (String part : parts) {
                    if (part.length() > 4) return false;
                    if (!part.isEmpty() && !part.matches("^[0-9a-f]{1,4}$")) return false;
                }
                return true;
            }
        }
        return false;
    }

    public PingResult ping(String host, int count) {
        try {
            sanitizeHost(host);
            if (count < 1 || count > 50) throw new IllegalArgumentException("Count must be 1-50");
            Path script = PowerShellScripts.resolve("net-ping.ps1");
            ProcessResult pr = new ProcessRunner(30 + (long) count * 5).run(
                    ProcessRunner.powershellScript(script.toString(), "-TargetHost", host, "-Count", String.valueOf(count)));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            if (!stdout.isEmpty()) {
                try {
                    Map<String, Object> data = mapper.readValue(stdout,
                            new TypeReference<Map<String, Object>>() {});
                    if (data.containsKey("error")) {
                        return PingResult.fail(host, str(data, "error"));
                    }
                    if (data.containsKey("host")) {
                        return new PingResult(
                                host,
                                data.get("packetsSent") instanceof Number n ? n.intValue() : 0,
                                data.get("packetsReceived") instanceof Number n ? n.intValue() : 0,
                                data.get("packetLossPercent") instanceof Number n ? n.intValue() : 100,
                                data.get("minMs") instanceof Number n ? n.doubleValue() : 0,
                                data.get("maxMs") instanceof Number n ? n.doubleValue() : 0,
                                data.get("avgMs") instanceof Number n ? n.doubleValue() : 0,
                                str(data, "rawOutput"));
                    }
                } catch (Exception je) {
                    AppLogger.warning("Failed to parse ping JSON: " + je.getMessage() + " stdout=" + stdout);
                }
            }
            String err = pr.stderr() != null && !pr.stderr().isBlank() ? pr.stderr() : stdout;
            return PingResult.fail(host, err.isEmpty() ? "No output from ping." : err);
        } catch (IllegalArgumentException e) {
            return PingResult.fail(host, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Operation cancelled");
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.warning("Failed to ping: " + e.getMessage());
            return PingResult.fail(host, "Error: " + e.getMessage());
        }
    }

    public List<TracerouteHop> traceroute(String host, int maxHops) {
        try {
            sanitizeHost(host);
            if (maxHops < 1 || maxHops > 30) maxHops = 30;
            Path script = PowerShellScripts.resolve("net-traceroute.ps1");
            ProcessResult pr = new ProcessRunner(60 + (long) maxHops * 5).run(
                    ProcessRunner.powershellScript(script.toString(), "-TargetHost", host, "-MaxHops", String.valueOf(maxHops)));
            String stdout = pr.stdout().trim();
            if (!stdout.isEmpty() && !"[]".equals(stdout)) {
                List<Map<String, Object>> raw = mapper.readValue(stdout,
                        new TypeReference<List<Map<String, Object>>>() {});
                List<TracerouteHop> hops = new ArrayList<>();
                for (Map<String, Object> entry : raw) {
                    try {
                        hops.add(new TracerouteHop(
                                entry.get("hopNumber") instanceof Number n ? n.intValue() : 0,
                                str(entry, "address"),
                                str(entry, "latency1"),
                                str(entry, "latency2"),
                                str(entry, "latency3")));
                    } catch (Exception e) {
                        AppLogger.warning("Failed to parse traceroute hop: " + e.getMessage());
                    }
                }
                return hops;
            }
        } catch (IllegalArgumentException e) {
            AppLogger.warning("Invalid host for traceroute: " + e.getMessage());
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Operation cancelled");
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (Exception e) {
            AppLogger.warning("Failed to traceroute: " + e.getMessage());
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // PR1: snapshots (read-only capture), preview diff, reboot state
    // ------------------------------------------------------------------

    /**
     * Captures current TCP global settings + registry tuning values without
     * performing any writes. Never throws — returns best-effort snapshot.
     */
    public NetworkSnapshot captureSnapshot(String reason) {
        Map<String, String> tcp = Map.of();
        String ack = null;
        String noDelay = null;
        try {
            tcp = new LinkedHashMap<>(getCurrentTcpSettings().settings());
        } catch (Exception e) {
            AppLogger.warning("Snapshot TCP read failed: " + e.getMessage());
        }
        try {
            Path script = PowerShellScripts.resolve("net-tcp-snapshot.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            if (!stdout.isEmpty()) {
                Map<String, Object> data = mapper.readValue(stdout,
                        new TypeReference<Map<String, Object>>() {});
                Object a = data.get("TcpAckFrequency");
                Object n = data.get("TCPNoDelay");
                ack = a != null ? a.toString() : null;
                noDelay = n != null ? n.toString() : null;
            }
        } catch (Exception e) {
            AppLogger.warning("Snapshot registry read failed: " + e.getMessage());
        }
        NetworkSnapshot snap = new NetworkSnapshot(
                java.util.UUID.randomUUID().toString(),
                Instant.now().toString(),
                reason != null ? reason : "manual",
                tcp, ack, noDelay);
        try {
            snapshotStore.append(snap);
        } catch (Exception e) {
            AppLogger.warning("Failed to persist snapshot: " + e.getMessage());
        }
        return snap;
    }

    public List<NetworkSnapshot> listSnapshots() {
        try {
            return snapshotStore.load();
        } catch (Exception e) {
            AppLogger.warning("Failed to list snapshots: " + e.getMessage());
            return List.of();
        }
    }

    public java.util.Optional<NetworkSnapshot> latestSnapshot() {
        List<NetworkSnapshot> all = listSnapshots();
        return all.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(all.get(0));
    }

    /**
     * Builds a preview diff for a preset against live TCP state.
     * Registry current values are rendered as "default (absent)" when null.
     */
    public List<PresetExpectations.PreviewRow> buildPreview(OptimizationPreset preset) {
        Map<String, String> expected = PresetExpectations.expectedFor(preset);
        NetworkSnapshot current = null;
        try {
            // Lightweight live read without persisting
            Map<String, String> tcp = new LinkedHashMap<>(getCurrentTcpSettings().settings());
            String ack = null;
            String noDelay = null;
            try {
                Path script = PowerShellScripts.resolve("net-tcp-snapshot.ps1");
                ProcessResult pr = new ProcessRunner(30).run(
                        ProcessRunner.powershellScript(script.toString()));
                String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
                if (!stdout.isEmpty()) {
                    Map<String, Object> data = mapper.readValue(stdout,
                            new TypeReference<Map<String, Object>>() {});
                    Object a = data.get("TcpAckFrequency");
                    Object n = data.get("TCPNoDelay");
                    ack = a != null ? a.toString() : null;
                    noDelay = n != null ? n.toString() : null;
                }
            } catch (Exception ignored) {}
            current = new NetworkSnapshot("", Instant.now().toString(), "preview", tcp, ack, noDelay);
        } catch (Exception e) {
            AppLogger.warning("Preview read failed: " + e.getMessage());
        }
        List<PresetExpectations.PreviewRow> rows = new ArrayList<>();
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String key = e.getKey();
            String willSet = e.getValue();
            String cur = resolveCurrentForPreview(key, current);
            boolean changes = !normalizePreviewValue(cur).equalsIgnoreCase(normalizePreviewValue(willSet));
            rows.add(new PresetExpectations.PreviewRow(key, cur, willSet, changes));
        }
        return rows;
    }

    private static String resolveCurrentForPreview(String key, NetworkSnapshot current) {
        if (current == null) return "(unknown — run as Administrator?)";
        if ("TCP Ack Frequency".equalsIgnoreCase(key)) {
            return current.tcpAckFrequency() == null ? "default (absent)" : current.tcpAckFrequency();
        }
        if ("TCP No Delay".equalsIgnoreCase(key)) {
            return current.tcpNoDelay() == null ? "default (absent)" : current.tcpNoDelay();
        }
        // TCP global keys: netsh uses names like "Receive-Side Scaling State", "ECN Capability", etc.
        // Map our short names to likely netsh keys case-insensitively.
        Map<String, String> tcp = current.tcpSettings() != null ? current.tcpSettings() : Map.of();
        if (tcp.isEmpty()) return "(no data — not Windows or access denied)";
        String lookup = switch (key) {
            case "TCP AutoTuning" -> findTcpKey(tcp, "auto-tuning", "autotuning");
            case "RSS" -> findTcpKey(tcp, "receive-side scaling", "rss");
            case "RSC" -> findTcpKey(tcp, "receive segment coalescing", "rsc");
            case "ECN" -> findTcpKey(tcp, "ecn capability", "ecn");
            default -> null;
        };
        return lookup != null ? lookup : "(not reported by netsh)";
    }

    private static String findTcpKey(Map<String, String> tcp, String... hints) {
        for (Map.Entry<String, String> e : tcp.entrySet()) {
            String k = e.getKey() != null ? e.getKey().toLowerCase() : "";
            for (String h : hints) {
                if (k.contains(h)) return e.getValue();
            }
        }
        return null;
    }

    private static String normalizePreviewValue(String v) {
        if (v == null) return "";
        return v.trim().toLowerCase();
    }

    /**
     * Guided restore info for a snapshot. Read-only+ constraint: we do NOT
     * re-apply arbitrary registry values — we point at the existing
     * Default preset (which removes custom keys) and show snapshot detail
     * for manual verification.
     */
    public OperationResult describeRestore(NetworkSnapshot snap) {
        if (snap == null) return OperationResult.fail("No snapshot selected.");
        StringBuilder sb = new StringBuilder();
        sb.append("Snapshot: ").append(snap.summary()).append("\n\n");
        if (snap.tcpSettings() != null && !snap.tcpSettings().isEmpty()) {
            sb.append("TCP settings at capture:\n");
            snap.tcpSettings().forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append("\n"));
            sb.append("\n");
        }
        sb.append("Registry at capture:\n");
        sb.append("  TcpAckFrequency: ").append(snap.tcpAckFrequency() == null ? "absent (default)" : snap.tcpAckFrequency()).append("\n");
        sb.append("  TCPNoDelay: ").append(snap.tcpNoDelay() == null ? "absent (default)" : snap.tcpNoDelay()).append("\n\n");
        sb.append("Restore guidance (read-only+ mode):\n");
        sb.append("- Click 'Reset to Defaults' to remove custom registry keys via the existing optimizer.\n");
        sb.append("- netsh values return to Windows defaults (autotuning=normal, RSS/ECN/RSC=default).\n");
        sb.append("- If the snapshot shows non-default custom values outside this set, re-apply them manually.\n");
        sb.append("- A reboot may be required for TCP changes to fully take effect.");
        return OperationResult.ok("Snapshot details ready.", sb.toString());
    }

    public boolean isRebootRequired() {
        try {
            return stateStore.isRebootRequired();
        } catch (Exception e) {
            return false;
        }
    }

    public String rebootReason() {
        try {
            return stateStore.rebootReason();
        } catch (Exception e) {
            return "";
        }
    }

    public void markRebootRequired(String reason) {
        try {
            stateStore.setRebootRequired(true, reason != null ? reason : "");
        } catch (Exception e) {
            AppLogger.warning("Failed to mark reboot required: " + e.getMessage());
        }
    }

    public void clearRebootRequired() {
        try {
            stateStore.clearRebootRequired();
        } catch (Exception e) {
            AppLogger.warning("Failed to clear reboot flag: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // PR2: read-only network detail (route/arp/netstat/public-ip/export)
    // ------------------------------------------------------------------

    public String getRouteTable() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(
                    List.of("cmd.exe", "/c", "chcp 65001 >nul & route print"));
            if (pr.exitCode() == 0 && pr.stdout() != null && !pr.stdout().isBlank()) {
                return pr.stdout().replaceFirst("(?m)^Active code page:.*\\R", "");
            }
            return "Failed to retrieve route table:\n" + pr.combinedOutput();
        } catch (Exception e) {
            AppLogger.warning("Failed to get route table: " + e.getMessage());
            return "Failed to retrieve route table: " + e.getMessage();
        }
    }

    public String getArpTable() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(List.of("arp", "-a"));
            if (pr.exitCode() == 0 && pr.stdout() != null && !pr.stdout().isBlank()) return pr.stdout();
            return "Failed to retrieve ARP table:\n" + pr.combinedOutput();
        } catch (Exception e) {
            AppLogger.warning("Failed to get ARP table: " + e.getMessage());
            return "Failed to retrieve ARP table: " + e.getMessage();
        }
    }

    public String getNetstatSummary() {
        try {
            ProcessResult pr = new ProcessRunner(30).run(List.of("cmd.exe", "/c", "chcp 65001 >nul & netstat -ano"));
            if (pr.exitCode() == 0 && pr.stdout() != null && !pr.stdout().isBlank()) {
                String out = pr.stdout().replaceFirst("(?m)^Active code page:.*\\R", "");
                // Cap output to avoid huge UI freeze (first 400 lines)
                String[] lines = out.split("\\R");
                if (lines.length > 400) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < 400; i++) sb.append(lines[i]).append(System.lineSeparator());
                    sb.append("... truncated (").append(lines.length - 400).append(" more lines) ...");
                    return sb.toString();
                }
                return out;
            }
            return "Failed to retrieve netstat:\n" + pr.combinedOutput();
        } catch (Exception e) {
            AppLogger.warning("Failed to get netstat: " + e.getMessage());
            return "Failed to retrieve netstat: " + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // PR3: DNS benchmark + MTU probe + speed test (opt-in internet)
    // ------------------------------------------------------------------

    public record DnsBenchmarkRow(String provider, String servers, double avgMs, double minMs, double maxMs, boolean ok, String note) {
    }

    public List<DnsBenchmarkRow> benchmarkDnsServers() {
        // Opt-in only — caller shows explicit consent. Uses Resolve-DnsName timing (read-only).
        List<String[]> targets = List.of(
                new String[]{"Current (DHCP)", ""},
                new String[]{"Google", "8.8.8.8"},
                new String[]{"Cloudflare", "1.1.1.1"},
                new String[]{"Quad9", "9.9.9.9"},
                new String[]{"OpenDNS", "208.67.222.222"});
        List<DnsBenchmarkRow> rows = new ArrayList<>();
        for (String[] t : targets) {
            String name = t[0];
            String server = t[1];
            try {
                Path script = PowerShellScripts.resolve("net-dns-benchmark.ps1");
                java.util.List<String> cmd;
                if (server.isEmpty()) {
                    cmd = ProcessRunner.powershellScript(script.toString(), "-Server", "");
                } else {
                    cmd = ProcessRunner.powershellScript(script.toString(), "-Server", server);
                }
                ProcessResult pr = new ProcessRunner(45).run(cmd);
                String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
                if (!stdout.isEmpty()) {
                    try {
                        Map<String, Object> data = mapper.readValue(stdout,
                                new TypeReference<Map<String, Object>>() {});
                        double avg = toDouble(data.get("avgMs"));
                        double min = toDouble(data.get("minMs"));
                        double max = toDouble(data.get("maxMs"));
                        boolean ok = Boolean.TRUE.equals(data.get("success"));
                        String note = data.get("note") != null ? data.get("note").toString() : "";
                        rows.add(new DnsBenchmarkRow(name, server.isEmpty() ? "(system)" : server, avg, min, max, ok, note));
                        continue;
                    } catch (Exception je) {
                        AppLogger.warning("DNS benchmark parse failed for " + name + ": " + je.getMessage());
                    }
                }
                rows.add(new DnsBenchmarkRow(name, server, -1, -1, -1, false, "No result"));
            } catch (Exception e) {
                AppLogger.warning("DNS benchmark failed for " + name + ": " + e.getMessage());
                rows.add(new DnsBenchmarkRow(name, server, -1, -1, -1, false, e.getMessage()));
            }
        }
        return rows;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        try {
            return o != null ? Double.parseDouble(o.toString()) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    public record MtuProbeResult(boolean success, int optimalMtu, String details) {
    }

    public MtuProbeResult probeMtu(String targetHost, java.util.concurrent.atomic.AtomicBoolean cancelled) {
        try {
            sanitizeHost(targetHost);
        } catch (IllegalArgumentException e) {
            return new MtuProbeResult(false, -1, "Invalid host: " + e.getMessage());
        }
        try {
            Path script = PowerShellScripts.resolve("net-mtu-probe.ps1");
            ProcessResult pr = new ProcessRunner(120).run(
                    ProcessRunner.powershellScript(script.toString(), "-TargetHost", targetHost),
                    cancelled);
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            if (!stdout.isEmpty()) {
                try {
                    Map<String, Object> data = mapper.readValue(stdout,
                            new TypeReference<Map<String, Object>>() {});
                    boolean ok = Boolean.TRUE.equals(data.get("success"));
                    int mtu = data.get("optimalMtu") instanceof Number n ? n.intValue() : -1;
                    String details = data.get("details") != null ? data.get("details").toString() : stdout;
                    return new MtuProbeResult(ok, mtu, details);
                } catch (Exception je) {
                    return new MtuProbeResult(false, -1, stdout);
                }
            }
            return new MtuProbeResult(false, -1, "No output from MTU probe.");
        } catch (java.util.concurrent.CancellationException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.util.concurrent.CancellationException("Operation cancelled");
        } catch (Exception e) {
            AppLogger.warning("MTU probe failed: " + e.getMessage());
            return new MtuProbeResult(false, -1, "Error: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // PR4: Wi-Fi survey (read-only netsh scan)
    // ------------------------------------------------------------------

    public record WifiNetwork(String ssid, String bssid, int signalPercent, String auth, String channel, String radio) {
    }

    public List<WifiNetwork> scanWifiNetworks() {
        try {
            Path script = PowerShellScripts.resolve("net-wifi-scan.ps1");
            ProcessResult pr = new ProcessRunner(45).run(
                    ProcessRunner.powershellScript(script.toString()));
            String stdout = pr.stdout() != null ? pr.stdout().trim() : "";
            if (stdout.isEmpty() || "[]".equals(stdout)) return List.of();
            List<Map<String, Object>> raw = mapper.readValue(stdout,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<WifiNetwork> out = new ArrayList<>();
            for (Map<String, Object> e : raw) {
                try {
                    out.add(new WifiNetwork(
                            str(e, "ssid"), str(e, "bssid"),
                            e.get("signalPercent") instanceof Number n ? n.intValue() : 0,
                            str(e, "auth"), str(e, "channel"), str(e, "radio")));
                } catch (Exception ex) {
                    AppLogger.warning("Failed to parse wifi network: " + ex.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            AppLogger.warning("Wi-Fi scan failed: " + e.getMessage());
            return List.of();
        }
    }

}
