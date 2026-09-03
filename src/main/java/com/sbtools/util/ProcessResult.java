package com.sbtools.util;

public record ProcessResult(int exitCode, String stdout, String stderr) {

    /** Standard MSI success codes that require a reboot. */
    public static final int MSI_SUCCESS_REBOOT_REQUIRED = 3010;
    public static final int MSI_SUCCESS_REBOOT_INITIATED = 1641;

    public boolean success() {
        return exitCode == 0;
    }

    /**
     * True when the process reported success, including the standard MSI
     * "success + reboot required" codes (3010, 1641). Uninstallers commonly
     * return these on success; treating them as failure misleads users.
     */
    public boolean isRebootRequired() {
        return exitCode == MSI_SUCCESS_REBOOT_REQUIRED || exitCode == MSI_SUCCESS_REBOOT_INITIATED;
    }

    /**
     * Success including reboot-required outcomes. Prefer this over
     * {@link #success()} for installer/uninstaller exit codes.
     */
    public boolean succeeded() {
        return success() || isRebootRequired();
    }

    public String combinedOutput() {
        if (stderr == null || stderr.isBlank()) {
            return stdout == null ? "" : stdout;
        }
        if (stdout == null || stdout.isBlank()) {
            return stderr;
        }
        return stdout + System.lineSeparator() + stderr;
    }
}
