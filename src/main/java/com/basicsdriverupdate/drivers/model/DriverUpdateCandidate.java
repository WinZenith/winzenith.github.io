package com.basicsdriverupdate.drivers.model;

public record DriverUpdateCandidate(
        InstalledDriver installed,
        String availableVersion,
        String source,
        String packageId,
        String title,
        String description,
        UpdateSeverity severity
) {
}
