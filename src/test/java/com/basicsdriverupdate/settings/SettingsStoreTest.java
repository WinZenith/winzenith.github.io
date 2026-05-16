package com.basicsdriverupdate.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SettingsStoreTest {

    @TempDir
    Path tempHome;

    @Test
    void saveAndLoad() throws Exception {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", tempHome.toString());
            SettingsStore store = new SettingsStore();
            store.save(new AppSettings(true, true, true));
            AppSettings loaded = store.load();
            assertTrue(loaded.autoBackupDrivers());
            assertTrue(loaded.createSystemRestorePoint());
            assertTrue(loaded.eulaAccepted());
        } finally {
            if (original != null) {
                System.setProperty("user.home", original);
            }
        }
    }

    @Test
    void defaultsWhenMissing(@TempDir Path emptyHome) {
        String original = System.getProperty("user.home");
        try {
            System.setProperty("user.home", emptyHome.toString());
            AppSettings d = new SettingsStore().load();
            assertTrue(d.autoBackupDrivers());
            assertFalse(d.eulaAccepted());
        } finally {
            if (original != null) {
                System.setProperty("user.home", original);
            }
        }
    }
}
