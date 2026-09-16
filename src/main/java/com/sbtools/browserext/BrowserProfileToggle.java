package com.sbtools.browserext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sbtools.util.AppLogger;
import com.sbtools.util.JsonMapper;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Surgical Jackson edit of Chromium Preferences / Secure Preferences and
 * Firefox {@code extensions.json}. Avoids PowerShell {@code ConvertTo-Json}
 * (single-element array collapse, number coercion) and restores the backup
 * when verify-after-write fails.
 */
public final class BrowserProfileToggle {

    static final Pattern ILLEGAL_FILENAME = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final DateTimeFormatter BAK_TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final ObjectMapper MAPPER = JsonMapper.mapper().copy();
    private static final AtomicBoolean MUTATING = new AtomicBoolean(false);
    private static final int LOCK_RETRIES = 3;

    private BrowserProfileToggle() {
    }

    /** True when this tab or another tab already holds a mutating op. */
    public static boolean shouldRefuseNewUiOp(boolean localBusy, boolean globalBusy) {
        return localBusy || globalBusy;
    }

    /**
     * After a confirm dialog: if we still hold local busy we own the slot;
     * otherwise refuse when another tab took global busy meanwhile.
     */
    public static boolean shouldRefuseAfterDialog(boolean localBusy, boolean globalBusy) {
        if (localBusy) return false;
        return globalBusy;
    }

    public static boolean beginMutation() {
        return MUTATING.compareAndSet(false, true);
    }

    public static void endMutation() {
        MUTATING.set(false);
    }

    public static boolean toggle(Path profileDir, String extensionId, boolean enable,
                                 AtomicBoolean cancelled) {
        if (profileDir == null || !Files.isDirectory(profileDir)
                || extensionId == null || extensionId.isBlank()) return false;
        if (cancelled != null && cancelled.get()) return false;
        if (!beginMutation()) {
            AppLogger.warning("Refusing toggle: another browser-profile write is in progress");
            return false;
        }
        try {
            Path prefs = profileDir.resolve("Preferences");
            Path secure = profileDir.resolve("Secure Preferences");
            Path extJson = profileDir.resolve("extensions.json");
            // Chromium profiles always have Preferences / Secure Preferences.
            // Prefer that over a stray extensions.json so we never edit the
            // wrong format (Firefox addons vs Chromium settings).
            if (Files.isRegularFile(prefs) || Files.isRegularFile(secure)) {
                return toggleChromium(profileDir, extensionId, enable, cancelled);
            }
            if (Files.isRegularFile(extJson)) {
                return toggleFirefox(profileDir, extJson, extensionId, enable, cancelled);
            }
            AppLogger.warning("No Preferences/extensions.json in " + profileDir);
            return false;
        } catch (Exception e) {
            AppLogger.warning("Failed to toggle extension: " + e.getMessage());
            return false;
        } finally {
            endMutation();
        }
    }

    static boolean toggleChromium(Path profileDir, String extensionId, boolean enable,
                                  AtomicBoolean cancelled) throws Exception {
        List<Path> candidates = new ArrayList<>();
        Path secure = profileDir.resolve("Secure Preferences");
        Path prefs = profileDir.resolve("Preferences");
        if (Files.isRegularFile(secure)) candidates.add(secure);
        if (Files.isRegularFile(prefs)) candidates.add(prefs);
        if (candidates.isEmpty()) {
            AppLogger.warning("Preferences file not found in " + profileDir);
            return false;
        }
        List<Path> committedBaks = new ArrayList<>();
        boolean anyUpdated = false;
        boolean overallSuccess = true;
        try {
            for (Path target : candidates) {
                if (cancelled != null && cancelled.get()) {
                    overallSuccess = false;
                    break;
                }
                if (isLocked(target)) {
                    AppLogger.warning("File is locked (browser may be running): " + target);
                    overallSuccess = false;
                    break;
                }
                byte[] raw = Files.readAllBytes(target);
                JsonNode root = MAPPER.readTree(raw);
                if (!applyChromiumEdit(root, extensionId, enable, isSecurePreferences(target))) {
                    continue;
                }
                Path bak = backupPath(target);
                if (!writeAndVerify(target, root, bak, node -> chromiumStateMatches(node, extensionId, enable))) {
                    overallSuccess = false;
                    break;
                }
                committedBaks.add(bak);
                anyUpdated = true;
            }
            if (!anyUpdated) {
                AppLogger.warning("Extension " + extensionId + " not found in Secure Preferences nor Preferences");
                return false;
            }
            if (!overallSuccess) {
                AppLogger.warning("Partial toggle failure for " + extensionId + " — restoring already-written files");
                rollbackCommitted(committedBaks);
                return false;
            }
            Boolean verified = readChromiumEnabled(profileDir, extensionId);
            if (verified == null || verified != enable) {
                AppLogger.warning("Toggle verification failed for " + extensionId
                        + " (expected enabled=" + enable + ", read enabled=" + verified + ")");
                rollbackCommitted(committedBaks);
                return false;
            }
            pruneBackups(profileDir, "Secure Preferences.bak.");
            pruneBackups(profileDir, "Preferences.bak.");
            return true;
        } catch (Exception e) {
            rollbackCommitted(committedBaks);
            throw e;
        }
    }

    static boolean toggleFirefox(Path profileDir, Path extJson, String extensionId, boolean enable,
                                 AtomicBoolean cancelled) throws Exception {
        if (cancelled != null && cancelled.get()) return false;
        if (isLocked(extJson)) {
            AppLogger.warning("extensions.json is locked (Firefox may be running): " + extJson);
            return false;
        }
        JsonNode root = MAPPER.readTree(Files.readAllBytes(extJson));
        if (firefoxIsManaged(root, extensionId)) {
            AppLogger.warning("Extension " + extensionId + " is a system/built-in add-on and cannot be toggled.");
            return false;
        }
        if (!applyFirefoxEdit(root, extensionId, enable)) {
            AppLogger.warning("Extension " + extensionId + " not found in extensions.json");
            return false;
        }
        Path bak = backupPath(extJson);
        if (!writeAndVerify(extJson, root, bak, node -> firefoxStateMatches(node, extensionId, enable))) {
            return false;
        }
        pruneBackups(extJson.getParent(), "extensions.json.bak.");
        try {
            Files.deleteIfExists(profileDir.resolve("addonStartup.json.lz4"));
            Files.deleteIfExists(profileDir.resolve("addonStartup.json"));
        } catch (Exception ignored) {
        }
        return true;
    }

    static boolean applyChromiumEdit(JsonNode root, String extensionId, boolean enable, boolean secureFile) {
        if (root == null || !root.isObject()) return false;
        JsonNode settings = root.path("extensions").path("settings");
        if (!settings.isObject()) return false;
        JsonNode extNode = settings.get(extensionId);
        if (extNode == null || !extNode.isObject()) return false;
        if (extNode.path("was_installed_by_default").asBoolean(false)) return false;
        ObjectNode ext = (ObjectNode) extNode;
        int newState = enable ? 1 : 0;
        ext.put("state", newState);
        applyDisableReasons(ext, (ObjectNode) settings, enable);
        if (secureFile) {
            JsonNode macs = root.path("protection").path("macs").path("extensions").path("settings");
            if (macs.isObject()) {
                ((ObjectNode) macs).remove(extensionId);
            }
            JsonNode protection = root.get("protection");
            if (protection instanceof ObjectNode p) {
                p.remove("super_mac");
                p.remove("superMac");
            }
        }
        return true;
    }

    static boolean applyFirefoxEdit(JsonNode root, String extensionId, boolean enable) {
        JsonNode addons = root == null ? null : root.get("addons");
        if (addons == null || !addons.isArray()) return false;
        String want = sanitizeExtId(extensionId);
        for (JsonNode addon : addons) {
            if (addon == null || !addon.isObject()) continue;
            String id = addon.path("id").asText("");
            if (id.isEmpty() || !sanitizeExtId(id).equals(want)) continue;
            ObjectNode obj = (ObjectNode) addon;
            if (enable) {
                obj.put("disabled", false);
                obj.put("appDisabled", false);
                if (obj.has("userDisabled")) obj.put("userDisabled", false);
                if (obj.has("softDisabled")) obj.put("softDisabled", false);
                if (obj.has("embedderDisabled")) obj.put("embedderDisabled", false);
                if (obj.has("visible")) obj.put("visible", true);
                if (obj.has("active")) obj.put("active", true);
            } else {
                obj.put("disabled", true);
                if (obj.has("userDisabled")) obj.put("userDisabled", true);
                if (obj.has("active")) obj.put("active", false);
            }
            return true;
        }
        return false;
    }

    static Boolean readChromiumEnabled(Path profileDir, String extensionId) {
        Path[] files = {
                profileDir.resolve("Secure Preferences"),
                profileDir.resolve("Preferences")
        };
        for (Path f : files) {
            if (!Files.isRegularFile(f)) continue;
            try {
                JsonNode root = MAPPER.readTree(Files.readAllBytes(f));
                Boolean v = chromiumEnabledFrom(root, extensionId);
                if (v != null) return v;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    static Boolean chromiumEnabledFrom(JsonNode root, String extensionId) {
        JsonNode val = root.path("extensions").path("settings").get(extensionId);
        if (val == null || !val.isObject()) return null;
        JsonNode disableReasons = val.get("disable_reasons");
        if (disableReasons != null) {
            if (disableReasons.isArray() || disableReasons.isObject()) {
                if (disableReasons.size() > 0) return false;
            } else if (disableReasons.isNumber() || disableReasons.isTextual()) {
                try {
                    if (Integer.parseInt(disableReasons.asText()) != 0) return false;
                } catch (NumberFormatException ignored) {
                }
            }
        }
        JsonNode state = val.get("state");
        if (state != null && !state.isNull()) {
            return state.asInt(-1) == 1 || "1".equals(state.asText()) || state.asBoolean(false);
        }
        if (disableReasons != null) {
            if (disableReasons.isArray() && disableReasons.isEmpty()) return true;
            try {
                if (Integer.parseInt(disableReasons.asText()) == 0) return true;
            } catch (Exception ignored) {
            }
        }
        return true;
    }

    static boolean chromiumStateMatches(JsonNode root, String extensionId, boolean enable) {
        Boolean v = chromiumEnabledFrom(root, extensionId);
        return v != null && v == enable;
    }

    static boolean firefoxStateMatches(JsonNode root, String extensionId, boolean enable) {
        JsonNode addons = root.get("addons");
        if (addons == null || !addons.isArray()) return false;
        String want = sanitizeExtId(extensionId);
        for (JsonNode addon : addons) {
            String id = addon.path("id").asText("");
            if (id.isEmpty() || !sanitizeExtId(id).equals(want)) continue;
            boolean disabled = addon.path("disabled").asBoolean(false)
                    || addon.path("userDisabled").asBoolean(false);
            return enable != disabled;
        }
        return false;
    }

    static String sanitizeExtId(String id) {
        return id == null ? "" : ILLEGAL_FILENAME.matcher(id).replaceAll("_");
    }

    static boolean firefoxIsManaged(JsonNode root, String extensionId) {
        JsonNode addons = root.get("addons");
        if (addons == null || !addons.isArray()) return false;
        String want = sanitizeExtId(extensionId);
        for (JsonNode addon : addons) {
            String id = addon.path("id").asText("");
            if (id.isEmpty() || !sanitizeExtId(id).equals(want)) continue;
            if (addon.path("isSystem").asBoolean(false) || addon.path("isBuiltin").asBoolean(false)) {
                return true;
            }
            String rootUri = addon.path("rootURI").asText("");
            return rootUri.regionMatches(true, 0, "resource://", 0, 11)
                    || rootUri.regionMatches(true, 0, "chrome://", 0, 9);
        }
        return false;
    }

    public static boolean isAllowedLiveName(String liveName) {
        return "Preferences".equals(liveName)
                || "Secure Preferences".equals(liveName)
                || "extensions.json".equals(liveName);
    }

    private static void applyDisableReasons(ObjectNode ext, ObjectNode settings, boolean enable) {
        JsonNode dr = ext.get("disable_reasons");
        if (enable) {
            if (dr != null && dr.isArray()) {
                ArrayNode next = MAPPER.createArrayNode();
                for (JsonNode n : dr) {
                    if (!isUserDisableReason(n)) next.add(n);
                }
                ext.set("disable_reasons", next);
            } else if (dr != null && (dr.isNumber() || dr.isTextual())) {
                try {
                    ext.put("disable_reasons", Integer.parseInt(dr.asText()) & ~1);
                } catch (NumberFormatException ignored) {
                }
            }
            return;
        }
        if (dr != null && dr.isArray()) {
            boolean found = false;
            for (JsonNode n : dr) {
                if (isUserDisableReason(n)) {
                    found = true;
                    break;
                }
            }
            if (!found) ((ArrayNode) dr).add(1);
        } else if (dr != null && (dr.isNumber() || dr.isTextual())) {
            try {
                ext.put("disable_reasons", Integer.parseInt(dr.asText()) | 1);
            } catch (NumberFormatException ignored) {
            }
        } else if (majorityDisableReasonsAreArrays(settings)) {
            ext.set("disable_reasons", MAPPER.createArrayNode().add(1));
        } else {
            ext.put("disable_reasons", 1);
        }
    }

    private static boolean majorityDisableReasonsAreArrays(ObjectNode settings) {
        int arrays = 0;
        int ints = 0;
        var it = settings.fields();
        while (it.hasNext()) {
            JsonNode dr = it.next().getValue().get("disable_reasons");
            if (dr == null) continue;
            if (dr.isArray()) arrays++;
            else ints++;
        }
        return arrays > ints;
    }

    private static boolean isUserDisableReason(JsonNode n) {
        return n != null && (n.asInt(0) == 1 || "1".equals(n.asText()));
    }

    static boolean writeAndVerify(Path live, JsonNode root, Path bak, java.util.function.Predicate<JsonNode> verify) {
        try {
            Files.copy(live, bak, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            AppLogger.warning("Failed to backup " + live + ": " + e.getMessage());
            return false;
        }
        Path tmp = live.resolveSibling(live.getFileName() + ".tmp");
        try {
            byte[] json = MAPPER.writeValueAsBytes(root);
            Files.write(tmp, json);
            try {
                Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to write " + live + ": " + e.getMessage());
            restoreBak(bak, live);
            return false;
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
        try {
            JsonNode written = MAPPER.readTree(Files.readAllBytes(live));
            if (!verify.test(written)) {
                throw new IllegalStateException("state mismatch after write");
            }
        } catch (Exception e) {
            AppLogger.warning("Write verification failed for " + live + ": " + e.getMessage() + ". Restoring backup.");
            restoreBak(bak, live);
            return false;
        }
        return true;
    }

    static void rollbackCommitted(List<Path> baks) {
        if (baks == null) return;
        for (int i = baks.size() - 1; i >= 0; i--) {
            Path bak = baks.get(i);
            if (bak == null) continue;
            Path live = livePathFromBak(bak);
            if (live != null) restoreBak(bak, live);
        }
    }

    static Path livePathFromBak(Path bak) {
        if (bak == null) return null;
        String name = bak.getFileName().toString();
        int idx = name.indexOf(".bak.");
        if (idx <= 0) return null;
        String liveName = name.substring(0, idx);
        if (!isAllowedLiveName(liveName)) return null;
        Path parent = bak.getParent();
        return parent == null ? null : parent.resolve(liveName);
    }

    static boolean backupLooksValid(Path bak, String liveName) {
        if (bak == null || !Files.isRegularFile(bak) || liveName == null) return false;
        try {
            JsonNode n = MAPPER.readTree(Files.readAllBytes(bak));
            if (n == null || !n.isObject() || n.isEmpty()) return false;
            if ("extensions.json".equals(liveName)) {
                JsonNode addons = n.get("addons");
                return addons != null && addons.isArray();
            }
            return n.path("extensions").isObject();
        } catch (Exception e) {
            return false;
        }
    }

    private static void restoreBak(Path bak, Path live) {
        if (bak == null || live == null || !Files.exists(bak)) return;
        Path tmp = live.resolveSibling(live.getFileName() + ".restore.tmp");
        try {
            Files.copy(bak, tmp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, live, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            AppLogger.warning("Failed to restore backup " + bak + ": " + e.getMessage());
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }

    private static Path backupPath(Path live) {
        return live.resolveSibling(live.getFileName() + ".bak." + LocalDateTime.now().format(BAK_TS));
    }

    private static void pruneBackups(Path dir, String prefix) {
        if (dir == null || prefix == null) return;
        try {
            List<Path> baks = new ArrayList<>();
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path p : stream) {
                    if (Files.isRegularFile(p) && p.getFileName().toString().startsWith(prefix)) {
                        baks.add(p);
                    }
                }
            }
            baks.sort(Comparator.comparingLong((Path p) -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (Exception e) {
                    return 0L;
                }
            }).reversed());
            for (int i = 3; i < baks.size(); i++) {
                try {
                    Files.deleteIfExists(baks.get(i));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isSecurePreferences(Path target) {
        String n = target.getFileName().toString();
        return n.equalsIgnoreCase("Secure Preferences");
    }

    static boolean isLocked(Path path) {
        if (path == null || !Files.isRegularFile(path)) return true;
        for (int i = 0; i < LOCK_RETRIES; i++) {
            try (FileChannel ch = FileChannel.open(path, StandardOpenOption.WRITE);
                 FileLock lock = ch.tryLock()) {
                if (lock != null) return false;
            } catch (Exception ignored) {
                if (i == LOCK_RETRIES - 1) return true;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return true;
    }
}
