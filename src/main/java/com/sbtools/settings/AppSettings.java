package com.sbtools.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AppSettings(
        boolean autoBackupDrivers,
        boolean createSystemRestorePoint,
        boolean eulaAccepted,
        List<String> excludedDriverIds,
        List<String> skippedSoftwareIds,
        String networkOptimizationPreset
) {
    public static AppSettings defaults() {
        return new AppSettings(true, true, false, Collections.emptyList(), Collections.emptyList(), "DEFAULT");
    }
}
