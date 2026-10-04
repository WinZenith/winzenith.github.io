package com.sbtools.browserext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbtools.util.JsonMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Chromium {@code Secure Preferences} HMAC-SHA256 (hex, upper case), matching
 * {@code PrefHashCalculator} message layout: {@code deviceId + path + valueJson}.
 */
final class ChromiumPrefMac {

    /** Widely used Chromium resources.pak seed (see CANS 2020 / Chrome 85 research). */
    static final byte[] DEFAULT_SEED = new byte[]{
            (byte) 0xe7, 0x48, (byte) 0xf3, 0x36, (byte) 0xd8, 0x5e, (byte) 0xa5, (byte) 0xf9,
            (byte) 0xdc, (byte) 0xdf, 0x25, (byte) 0xd8, (byte) 0xf3, 0x47, (byte) 0xa6, 0x5b,
            0x4c, (byte) 0xdf, 0x66, 0x76, 0x00, (byte) 0xf0, 0x2d, (byte) 0xf6, 0x72, 0x4a,
            0x2a, (byte) 0xf1, (byte) 0x8a, 0x21, 0x2d, 0x26, (byte) 0xb7, (byte) 0x88, (byte) 0xa2,
            0x50, (byte) 0x86, (byte) 0x91, 0x0c, (byte) 0xf3, (byte) 0xa9, 0x03, 0x13, 0x69, 0x68,
            0x71, (byte) 0xf3, (byte) 0xdc, 0x05, (byte) 0x82, 0x70, (byte) 0xc9, 0x1d, (byte) 0xf8,
            (byte) 0xba, 0x5c, (byte) 0x4f, (byte) 0xd9, (byte) 0xc8, (byte) 0x84, (byte) 0xb5, 0x05,
            (byte) 0xa8
    };

    private static final ObjectMapper MAPPER = JsonMapper.mapper().copy();

    private ChromiumPrefMac() {
    }

    static String extensionSettingsPath(String extensionId) {
        return "extensions.settings." + extensionId;
    }

    static String storedExtensionMac(ObjectNode secureRoot, String extensionId) {
        if (secureRoot == null) return null;
        JsonNode n = secureRoot.path("protection").path("macs").path("extensions").path("settings").get(extensionId);
        return n != null && n.isTextual() ? n.asText() : null;
    }

    static boolean validates(byte[] seed, String deviceId, String path, JsonNode valueNode, String digestHex) {
        if (digestHex == null || digestHex.isBlank()) return false;
        String expected = calculate(seed, deviceId, path, valueNode);
        if (digestHex.equalsIgnoreCase(expected)) return true;
        String weak = calculateWeak(seed, valueNode);
        return digestHex.equalsIgnoreCase(weak);
    }

    static String calculate(byte[] seed, String deviceId, String path, JsonNode valueNode) {
        String valueJson = canonicalValueJson(valueNode);
        String device = deviceId != null ? deviceId : "";
        String prefPath = path != null ? path : "";
        return hmacHex(seed, device + prefPath + valueJson);
    }

    /** Legacy {@code VALID_WEAK_LEGACY}: HMAC(seed, valueJson) only. */
    static String calculateWeak(byte[] seed, JsonNode valueNode) {
        return hmacHex(seed, canonicalValueJson(valueNode));
    }

    static void writeExtensionMac(ObjectNode secureRoot, String extensionId, byte[] seed, String deviceId) {
        if (secureRoot == null) return;
        JsonNode ext = secureRoot.path("extensions").path("settings").get(extensionId);
        if (ext == null || !ext.isObject()) return;
        String path = extensionSettingsPath(extensionId);
        String mac = calculate(seed, deviceId, path, ext);
        ObjectNode protection = ensureObject(secureRoot, "protection");
        ObjectNode macs = ensureObject(protection, "macs");
        ObjectNode extensions = ensureObject(macs, "extensions");
        ObjectNode settings = ensureObject(extensions, "settings");
        settings.put(extensionId, mac);
    }

    static void writeSuperMac(ObjectNode secureRoot, byte[] seed, String deviceId) {
        if (secureRoot == null) return;
        JsonNode macs = secureRoot.path("protection").path("macs");
        if (!macs.isObject()) return;
        String mac = calculate(seed, deviceId, "", macs);
        ObjectNode protection = ensureObject(secureRoot, "protection");
        protection.put("super_mac", mac);
    }

    static ChromiumMacContext resolveMacContext(ObjectNode secureRoot, String extensionId) {
        return resolveMacContext(secureRoot, extensionId, null);
    }

    static ChromiumMacContext resolveMacContext(ObjectNode secureRoot, String extensionId, Path profileDir) {
        if (secureRoot == null || extensionId == null || extensionId.isBlank()) return null;
        JsonNode ext = secureRoot.path("extensions").path("settings").get(extensionId);
        if (ext == null || !ext.isObject()) return null;
        String stored = storedExtensionMac(secureRoot, extensionId);
        if (stored == null || stored.isBlank()) return null;
        String path = extensionSettingsPath(extensionId);
        List<byte[]> seeds = profileDir != null
                ? ChromiumPakSeed.candidatesNearProfile(profileDir)
                : List.of(DEFAULT_SEED);
        List<String> devices = ChromiumDeviceIds.candidates();
        for (byte[] seed : seeds) {
            for (String device : devices) {
                if (validates(seed, device, path, ext, stored)) {
                    return new ChromiumMacContext(seed, device);
                }
            }
        }
        return null;
    }

    private static ObjectNode ensureObject(ObjectNode parent, String field) {
        JsonNode child = parent.get(field);
        if (child instanceof ObjectNode obj) return obj;
        ObjectNode created = MAPPER.createObjectNode();
        parent.set(field, created);
        return created;
    }

    static String canonicalValueJson(JsonNode valueNode) {
        JsonNode canonical = withoutEmptyChildren(valueNode);
        if (canonical == null || canonical.isNull()) return "";
        try {
            String json = MAPPER.writeValueAsString(canonical);
            return json.replace("<", "\\u003C");
        } catch (Exception e) {
            return "";
        }
    }

    static JsonNode withoutEmptyChildren(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            node.fields().forEachRemaining(e -> {
                JsonNode cleaned = withoutEmptyChildren(e.getValue());
                if (!isEmptyValue(cleaned)) out.set(e.getKey(), cleaned);
            });
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode item : node) {
                JsonNode cleaned = withoutEmptyChildren(item);
                if (!isEmptyValue(cleaned)) out.add(cleaned);
            }
            return out;
        }
        return node;
    }

    private static boolean isEmptyValue(JsonNode n) {
        if (n == null || n.isNull()) return true;
        if (n.isObject() && n.isEmpty()) return true;
        if (n.isArray() && n.isEmpty()) return true;
        return false;
    }

    private static String hmacHex(byte[] seed, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(seed, "HmacSHA256"));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02X", b & 0xFF));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    record ChromiumMacContext(byte[] seed, String deviceId) {
    }

    /** Device id candidates for MAC validation (Windows SID + legacy empty). */
    static final class ChromiumDeviceIds {
        private ChromiumDeviceIds() {
        }

        static List<String> candidates() {
            List<String> out = new ArrayList<>();
            String sid = currentUserSid();
            if (sid != null && !sid.isBlank()) {
                out.add(sid);
                String legacy = legacyMetricsDeviceId(sid);
                if (!legacy.isBlank() && !out.contains(legacy)) out.add(legacy);
            }
            if (!out.contains("")) out.add("");
            return out;
        }

        private static String legacyMetricsDeviceId(String sid) {
            if (sid == null) return "";
            int dash = sid.lastIndexOf('-');
            if (dash <= 0 || dash >= sid.length() - 1) return sid;
            return sid.substring(0, dash);
        }

        private static String currentUserSid() {
            try {
                com.sun.jna.platform.win32.WinNT.HANDLEByReference token =
                        new com.sun.jna.platform.win32.WinNT.HANDLEByReference();
                if (com.sun.jna.platform.win32.Advapi32.INSTANCE.OpenProcessToken(
                        com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentProcess(),
                        com.sun.jna.platform.win32.WinNT.TOKEN_QUERY, token)) {
                    try {
                        com.sun.jna.platform.win32.Advapi32Util.Account acct =
                                com.sun.jna.platform.win32.Advapi32Util.getTokenAccount(token.getValue());
                        if (acct != null && acct.sidString != null && !acct.sidString.isBlank()) {
                            return acct.sidString;
                        }
                    } finally {
                        com.sun.jna.platform.win32.Kernel32.INSTANCE.CloseHandle(token.getValue());
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                String domain = System.getenv("USERDOMAIN");
                String user = System.getenv("USERNAME");
                String name = (domain != null && !domain.isBlank() && user != null && !user.isBlank())
                        ? domain + "\\" + user
                        : com.sun.jna.platform.win32.Advapi32Util.getUserName();
                com.sun.jna.platform.win32.Advapi32Util.Account acct =
                        com.sun.jna.platform.win32.Advapi32Util.getAccountByName(name);
                if (acct != null && acct.sidString != null && !acct.sidString.isBlank()) {
                    return acct.sidString;
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
    }
}
