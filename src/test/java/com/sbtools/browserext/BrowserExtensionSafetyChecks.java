package com.sbtools.browserext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Plain assertions for extension toggle safety. Run as a main, no test library. */
public final class BrowserExtensionSafetyChecks {

    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("wz-bext");
        try {
            securePreferencesAreNotRewritten(root.resolve("secure"));
            unreadableSecurePreferencesBlockToggle(root.resolve("bad-secure"));
            preferencesOnlyKeepsIntegrityKeys(root.resolve("prefs"));
            firefoxBlockIsNotCleared(root.resolve("ff-block"));
            firefoxUserEnableLeavesBlockFlags(root.resolve("ff-user"));
            restoreSnapshotRestoresBothFiles(root.resolve("restore"));
            restoreDoesNotPullADifferentSnapshot(root.resolve("restore-other"));
            System.out.println("ok");
        } finally {
            deleteTree(root);
        }
    }

    private static void securePreferencesAreNotRewritten(Path dir) throws Exception {
        Files.createDirectories(dir);
        String secure = "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1,\"disable_reasons\":0}}},"
                + "\"protection\":{\"super_mac\":\"KEEP\",\"macs\":{\"extensions\":{\"settings\":{\"extone\":\"MAC\"}}}}}";
        String prefs = "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1,\"disable_reasons\":0}}}}";
        Path securePath = dir.resolve("Secure Preferences");
        Path prefsPath = dir.resolve("Preferences");
        Files.writeString(securePath, secure);
        Files.writeString(prefsPath, prefs);
        byte[] secureBefore = Files.readAllBytes(securePath);
        byte[] prefsBefore = Files.readAllBytes(prefsPath);
        check(!BrowserProfileToggle.toggle(dir, "extone", false, null), "secure profile toggle must fail");
        check(java.util.Arrays.equals(secureBefore, Files.readAllBytes(securePath)), "Secure Preferences bytes changed");
        check(java.util.Arrays.equals(prefsBefore, Files.readAllBytes(prefsPath)), "Preferences written while Secure Preferences holds the extension");
        try (var names = Files.list(dir)) {
            check(names.noneMatch(p -> p.getFileName().toString().contains(".bak.")), "backup created for a refused toggle");
        }
    }

    private static void unreadableSecurePreferencesBlockToggle(Path dir) throws Exception {
        Files.createDirectories(dir);
        String prefs = "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1,\"disable_reasons\":0}}}}";
        Path prefsPath = dir.resolve("Preferences");
        Files.writeString(prefsPath, prefs);
        Files.writeString(dir.resolve("Secure Preferences"), "not-json");
        byte[] before = Files.readAllBytes(prefsPath);
        check(!BrowserProfileToggle.toggle(dir, "extone", false, null), "unreadable Secure Preferences must block");
        check(java.util.Arrays.equals(before, Files.readAllBytes(prefsPath)), "Preferences changed when Secure Preferences was unreadable");
    }

    private static void preferencesOnlyKeepsIntegrityKeys(Path dir) throws Exception {
        Files.createDirectories(dir);
        String prefs = "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1,\"disable_reasons\":0}}},"
                + "\"protection\":{\"super_mac\":\"KEEP\"}}";
        Path prefsPath = dir.resolve("Preferences");
        Files.writeString(prefsPath, prefs);
        check(BrowserProfileToggle.toggle(dir, "extone", false, null), "Preferences-only toggle should succeed");
        String written = Files.readString(prefsPath);
        check(written.contains("\"super_mac\":\"KEEP\""), "super_mac removed from Preferences: " + written);
        check(written.contains("\"state\":0"), "extension was not disabled: " + written);
        check(!written.contains("\"state\":1"), "enabled state left behind: " + written);
    }

    private static void firefoxBlockIsNotCleared(Path dir) throws Exception {
        Files.createDirectories(dir);
        String ext = "{\"addons\":[{\"id\":\"a@b.c\",\"userDisabled\":true,\"disabled\":true,\"appDisabled\":true}]}";
        Path extJson = dir.resolve("extensions.json");
        Files.writeString(extJson, ext);
        byte[] before = Files.readAllBytes(extJson);
        check(!BrowserProfileToggle.toggle(dir, "a@b.c", true, null), "appDisabled addon must not enable");
        check(java.util.Arrays.equals(before, Files.readAllBytes(extJson)), "appDisabled addon file was modified");

        String soft = "{\"addons\":[{\"id\":\"a@b.c\",\"userDisabled\":false,\"disabled\":false,\"softDisabled\":true}]}";
        Files.writeString(extJson, soft);
        byte[] softBefore = Files.readAllBytes(extJson);
        check(!BrowserProfileToggle.toggle(dir, "a@b.c", true, null), "softDisabled addon must not enable");
        check(java.util.Arrays.equals(softBefore, Files.readAllBytes(extJson)), "softDisabled addon file was modified");
    }

    private static void firefoxUserEnableLeavesBlockFlags(Path dir) throws Exception {
        Files.createDirectories(dir);
        String ext = "{\"addons\":[{\"id\":\"a@b.c\",\"userDisabled\":true,\"disabled\":true,\"appDisabled\":false}]}";
        Path extJson = dir.resolve("extensions.json");
        Files.writeString(extJson, ext);
        check(BrowserProfileToggle.toggle(dir, "a@b.c", true, null), "user-disabled addon should enable");
        String written = Files.readString(extJson);
        check(written.contains("\"userDisabled\":false"), "userDisabled not cleared: " + written);
        check(written.contains("\"appDisabled\":false"), "appDisabled changed: " + written);
        check(!written.contains("softDisabled"), "softDisabled was introduced: " + written);
    }

    private static void restoreSnapshotRestoresBothFiles(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Preferences"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1}}}}");
        Files.writeString(dir.resolve("Secure Preferences"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1}}},\"protection\":{\"super_mac\":\"LIVE\"}}");
        String stamp = "20260922-120000-001";
        Files.writeString(dir.resolve("Preferences.bak." + stamp),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":0}}}}");
        Files.writeString(dir.resolve("Secure Preferences.bak." + stamp),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":0}}},\"protection\":{\"super_mac\":\"BAK\"}}");
        check(BrowserExtensionService.restoreProfileBackup(dir.resolve("Preferences.bak." + stamp)),
                "paired restore failed");
        check(Files.readString(dir.resolve("Preferences")).contains("\"state\":0"), "Preferences not restored");
        String secure = Files.readString(dir.resolve("Secure Preferences"));
        check(secure.contains("\"super_mac\":\"BAK\""), "Secure Preferences left without the snapshot mac: " + secure);
        check(secure.contains("\"state\":0"), "Secure Preferences extension state not restored: " + secure);
    }

    private static void restoreDoesNotPullADifferentSnapshot(Path dir) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Preferences"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1}}}}");
        Files.writeString(dir.resolve("Secure Preferences"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":1}}},\"protection\":{\"super_mac\":\"LIVE\"}}");
        Files.writeString(dir.resolve("Preferences.bak.SNAP"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":0}}}}");
        Files.writeString(dir.resolve("Secure Preferences.bak.OTHER"),
                "{\"extensions\":{\"settings\":{\"extone\":{\"state\":0}}},\"protection\":{\"super_mac\":\"OTHER\"}}");
        check(BrowserExtensionService.restoreProfileBackup(dir.resolve("Preferences.bak.SNAP")),
                "single-file restore failed");
        check(Files.readString(dir.resolve("Preferences")).contains("\"state\":0"), "Preferences snapshot not restored");
        String secure = Files.readString(dir.resolve("Secure Preferences"));
        check(secure.contains("\"super_mac\":\"LIVE\""), "different snapshot was restored: " + secure);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
