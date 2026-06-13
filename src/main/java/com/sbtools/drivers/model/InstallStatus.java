package com.sbtools.drivers.model;

public enum InstallStatus {
    SUCCESS,
    DOWNLOAD_FAILED,
    VERIFICATION_FAILED,
    INSTALL_FAILED,
    BLOCKED_PRE_RELEASE,
    BLOCKED_UNTRUSTED,
    NO_DOWNLOAD_URL,
    UNKNOWN_ERROR;

    public boolean isSuccess() {
        return this == SUCCESS;
    }
}
