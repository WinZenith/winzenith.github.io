package com.sbtools.util;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Parses the final JSON object from {@code wu-install.ps1} stdout (fail-closed).
 */
public final class WindowsUpdateInstallResult {

    public record Parsed(boolean success, boolean rebootRequired, String diagnostic) {
    }

    private WindowsUpdateInstallResult() {
    }

    public static Parsed parse(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return new Parsed(false, false, "empty Windows Update output");
        }
        String jsonPart = stdout;
        int brace = jsonPart.lastIndexOf('{');
        if (brace < 0) {
            return new Parsed(false, false, "no JSON object in Windows Update output");
        }
        jsonPart = jsonPart.substring(brace);
        try {
            JsonNode root = JsonMapper.parseTree(jsonPart);
            int overall = root.path("resultCode").asInt(-1);
            int perUpdate = root.path("installed").asInt(-1);
            if (overall != 2 || perUpdate != 2) {
                return new Parsed(false, false,
                        "resultCode=" + overall + " updateResult=" + perUpdate);
            }
            boolean reboot = root.path("rebootRequired").asBoolean(false);
            return new Parsed(true, reboot, "");
        } catch (Exception e) {
            return new Parsed(false, false, "parse error: " + e.getMessage());
        }
    }
}
