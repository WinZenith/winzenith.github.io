package com.sbtools.drivers.model;

public enum InstallStatus {
    SUCCESS,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
    INSTALL_FAILED,
    BLOCKED_PRE_RELEASE,
    BLOCKED_UNTRUSTED,
    BLOCKED_CANCELLED,
    NO_DOWNLOAD_URL,
    UNKNOWN_ERROR;

    public boolean isSuccess() {
        return this == SUCCESS;
    }

    public boolean isBlocked() {
        return this == BLOCKED_PRE_RELEASE || this == BLOCKED_UNTRUSTED || this == BLOCKED_CANCELLED;
    }
}
