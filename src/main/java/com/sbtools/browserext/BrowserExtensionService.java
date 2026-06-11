package com.sbtools.browserext;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sbtools.util.AppLogger;
import com.sbtools.util.PowerShellScripts;
import com.sbtools.util.ProcessRunner;
import com.sbtools.util.ProcessResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class BrowserExtensionService {

    private final ObjectMapper mapper = new ObjectMapper();

    public List<BrowserExtensionRow> scanAllBrowsers(Runnable onProgress) {
        List<BrowserExtensionRow> all = new ArrayList<>();
        all.addAll(scanBrowser("All", onProgress));
        return all;
    }

    public List<BrowserExtensionRow> scanBrowser(String browser, Runnable onProgress) {
        List<BrowserExtensionRow> results = new ArrayList<>();
        try {
            Path script = PowerShellScripts.resolve("browser-extensions.ps1");
            ProcessResult pr = new ProcessRunner(30).run(
                    ProcessRunner.powershellScript(script.toString(), "-Browser", browser));
            int exitCode = pr.exitCode();
            String stdout = pr.stdout();
            String stderr = pr.stderr().trim();
            if (!stderr.isEmpty()) {
                AppLogger.warning("Script stderr for " + browser + ": " + stderr);
            }
            if (exitCode != 0 || stderr.contains("error")) {
                System.err.println("[BrowserExtensionService] Exit=" + exitCode + " stderr=" + stderr);
            }
            String trimmed = stdout.trim();
            if (trimmed.isEmpty() || "[]".equals(trimmed)) {
                System.err.println("[BrowserExtensionService] EMPTY stdout for " + browser + " (exit=" + exitCode + ") raw_hex=" + bytesToHex(stdout.getBytes(StandardCharsets.UTF_8)));
                if (onProgress != null) onProgress.run();
                return results;
            }
            stdout = trimmed;
            List<Map<String, Object>> raw = mapper.readValue(stdout,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> entry : raw) {
                try {
                    String id = str(entry, "id");
                    String name = str(entry, "name");
                    String version = str(entry, "version");
                    String description = str(entry, "description");
                    String extBrowser = str(entry, "browser");
                    String extPath = str(entry, "path");
                    String installTime = str(entry, "installTime");
                    String permissions = str(entry, "permissions");
                    boolean enabled = true;
                    Object en = entry.get("enabled");
                    if (en instanceof Boolean) enabled = (Boolean) en;
                    results.add(new BrowserExtensionRow(extBrowser, id, name, version,
                            description, enabled, extPath, installTime, permissions));
                } catch (Exception e) {
                    AppLogger.warning("Failed to parse extension entry: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to scan browser " + browser + ": " + e.getMessage());
        }
        if (onProgress != null) onProgress.run();
        return results;
    }

    public boolean toggleExtension(BrowserExtensionRow ext, boolean enable) {
        try {
            Path extPath = Paths.get(ext.getPath());
            if (!Files.exists(extPath)) return false;
            String browser = ext.getBrowser();
            if ("Chrome".equals(browser) || "Edge".equals(browser) || "Brave".equals(browser) || "Opera".equals(browser) || "Vivaldi".equals(browser)) {
                Path extFolder = extPath.resolve(ext.getExtensionId());
                Path versionDir = Files.list(extFolder)
                        .filter(Files::isDirectory)
                        .findFirst().orElse(null);
                if (versionDir == null) return false;
                Path disabledMarker = versionDir.resolve("Disabled");
                if (enable) {
                    Files.deleteIfExists(disabledMarker);
                } else {
                    Files.createFile(disabledMarker);
                }
                ext.setEnabled(enable);
                return true;
            } else if ("Firefox".equals(browser)) {
                Path extFilePath = extPath.resolve(ext.getExtensionId() + ".xpi");
                if (!Files.exists(extFilePath)) {
                    extFilePath = extPath.resolve(ext.getExtensionId() + ".json");
                }
                if (enable) {
                    Path disabledExt = extPath.resolve(ext.getExtensionId() + ".xpi.disabled");
                    if (Files.exists(disabledExt)) {
                        Files.move(disabledExt, disabledExt.resolveSibling(ext.getExtensionId() + ".xpi"));
                    }
                } else {
                    if (Files.exists(extFilePath) && extFilePath.toString().endsWith(".xpi")) {
                        Path disabledPath = extPath.resolve(ext.getExtensionId() + ".xpi.disabled");
                        if (!Files.exists(disabledPath)) {
                            Files.move(extFilePath, disabledPath);
                        }
                    }
                }
                ext.setEnabled(enable);
                return true;
            }
            return false;
        } catch (Exception e) {
            AppLogger.warning("Failed to toggle extension: " + e.getMessage());
            return false;
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v != null ? v.toString() : "";
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
