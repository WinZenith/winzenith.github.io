package com.sbtools.drivers.model;

public record DriverUpdateCandidate(
        InstalledDriver installed,
        String availableVersion,
        String source,
        String packageId,
        String title,
        String description,
        UpdateSeverity severity,
        String downloadUrl,
        String vendorPageUrl
) {
}
