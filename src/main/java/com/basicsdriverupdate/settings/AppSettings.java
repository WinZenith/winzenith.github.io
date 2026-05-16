package com.basicsdriverupdate.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSettings(
        boolean autoBackupDrivers,
        boolean createSystemRestorePoint,
        boolean eulaAccepted
) {
    public static AppSettings defaults() {
        return new AppSettings(true, false, false);
    }
}
