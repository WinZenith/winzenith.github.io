package com.sbtools.drivers.model;

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
}
