package com.basicsdriverupdate.drivers.model;

public record InstalledDriver(
        String deviceId,
        String friendlyName,
        String hardwareIds,
        String provider,
        String driverVersion,
        String infName,
        String driverKey,
        String status
) {
    public boolean isHealthy() {
        return status == null || status.isBlank() || "OK".equalsIgnoreCase(status);
    }

    public boolean matchesHardware(String pattern) {
        if (hardwareIds == null || pattern == null) {
            return false;
        }
        return hardwareIds.toUpperCase().contains(pattern.toUpperCase());
    }
}
