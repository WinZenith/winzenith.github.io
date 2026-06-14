package com.sbtools.license;

import com.sbtools.util.AppLogger;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

public class LicenseValidator {

    private final LicenseStore store = new LicenseStore();
    private volatile LicenseStatus cachedStatus = LicenseStatus.UNKNOWN;

    public LicenseStatus getStatus() {
        return cachedStatus;
    }

    public LicenseStatus check() {
        LicenseStore.LicenseData data = store.load();
        if (data.isEmpty()) {
            cachedStatus = LicenseStatus.NO_LICENSE;
            return cachedStatus;
        }
        LicenseCode.ValidationResult result = LicenseCode.validate(data.licenseKey());
        if (result.valid()) {
            long daysRemaining = ChronoUnit.DAYS.between(LocalDate.now(), result.expiryDate());
            cachedStatus = LicenseStatus.active(result.expiryDate(), daysRemaining);
        } else if (result.expired()) {
            cachedStatus = LicenseStatus.expired(result.expiryDate());
        } else {
            AppLogger.warning("Invalid license: " + result.errorMessage());
            cachedStatus = LicenseStatus.invalid(result.errorMessage());
        }
        return cachedStatus;
    }

    public boolean isLicenseActive() {
        if (cachedStatus == LicenseStatus.UNKNOWN) {
            check();
        }
        return cachedStatus.isActive();
    }

    public long getDaysRemaining() {
        if (cachedStatus == LicenseStatus.UNKNOWN) {
            check();
        }
        return cachedStatus.daysRemaining();
    }

    public LocalDate getExpiryDate() {
        if (cachedStatus == LicenseStatus.UNKNOWN) {
            check();
        }
        return cachedStatus.expiryDate();
    }

    public void activate(String licenseKey) throws Exception {
        AppLogger.info("Activating license: " + licenseKey);
        store.save(licenseKey);
        LicenseStatus newStatus = check();
        AppLogger.info("License activation result: " + newStatus.type());
    }

    public void deactivate() throws Exception {
        store.clear();
        cachedStatus = LicenseStatus.NO_LICENSE;
    }

    public record LicenseStatus(
            StatusType type,
            LocalDate expiryDate,
            long daysRemaining,
            String errorMessage
    ) {
        static final LicenseStatus UNKNOWN = new LicenseStatus(StatusType.UNKNOWN, null, 0, null);
        static final LicenseStatus NO_LICENSE = new LicenseStatus(StatusType.NO_LICENSE, null, 0, null);

        static LicenseStatus active(LocalDate expiryDate, long daysRemaining) {
            return new LicenseStatus(StatusType.ACTIVE, expiryDate, daysRemaining, null);
        }

        static LicenseStatus expired(LocalDate expiryDate) {
            return new LicenseStatus(StatusType.EXPIRED, expiryDate, 0, null);
        }

        static LicenseStatus invalid(String message) {
            return new LicenseStatus(StatusType.INVALID, null, 0, message);
        }

        boolean isActive() {
            return type == StatusType.ACTIVE;
        }

        public enum StatusType {
            UNKNOWN, NO_LICENSE, ACTIVE, EXPIRED, INVALID
        }
    }
}
