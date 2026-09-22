package com.sbtools.startup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Checks the two Startup-tab critical fixes. Run as a main class. */
public final class StartupCriticalFixCheck {

    public static void main(String[] args) throws Exception {
        checkMissingFileKeepsOriginalStartType();
        checkDeleteRemovesOnlyTheScannedFile();
        System.out.println("StartupCriticalFixCheck ok");
    }

    private static void checkMissingFileKeepsOriginalStartType() {
        StartupService.acceptOriginalStartTypesLoad(true, Map.of("Spooler", "Automatic (Delayed Start)"));
        StartupService.acceptOriginalStartTypesLoad(false, Map.of());
        check("Automatic (Delayed Start)".equals(
                        StartupService.rememberedStartType("Spooler", "Manual")),
                "missing file must not replace a known start type with Manual");
        check("Automatic (Delayed Start)".equals(
                        new StartupService().getOriginalServiceStartType("Spooler", "Disabled")),
                "disabled service must re-enable to the remembered start type");
        StartupService.acceptOriginalStartTypesLoad(true, Map.of());
        check("Manual".equals(StartupService.rememberedStartType("Spooler", "Manual")),
                "unknown disabled service still falls back to Manual");
    }

    private static void checkDeleteRemovesOnlyTheScannedFile() throws Exception {
        Path dir = Files.createTempDirectory("wz-startup-folder");
        try {
            Path live = dir.resolve("Foo.lnk");
            Path disabled = dir.resolve("Foo.lnk.disabled");
            Files.writeString(live, "live");
            Files.writeString(disabled, "disabled");
            check(StartupService.startupFolderEnabledPath(disabled).getFileName().toString().equals("Foo.lnk"),
                    "enabled path strips one .disabled suffix");
            check(StartupService.startupFolderEnabledPath(live).equals(live),
                    "live path stays unchanged");
            StartupService.deleteOnlyThisStartupFile(disabled);
            check(Files.exists(live), "deleting the .disabled row must leave the live shortcut");
            check(!Files.exists(disabled), ".disabled file must be removed");
        } finally {
            Files.deleteIfExists(dir.resolve("Foo.lnk"));
            Files.deleteIfExists(dir.resolve("Foo.lnk.disabled"));
            Files.deleteIfExists(dir);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
