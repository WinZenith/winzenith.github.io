package com.sbtools.software;

import com.sbtools.util.ProcessResult;

/**
 * Focused checks for winget success vs reboot-phrasing (C1).
 * JDK-only (no JUnit): {@code mvn -q test-compile} then run this class.
 */
public final class SoftwareUpdateServiceTest {

    public static void main(String[] args) {
        failureWithPleaseRestartIsNotSuccess();
        msiRebootExitIsSuccess();
        exitZeroWithRestartPhraseIsSuccessAndReboot();
        System.out.println("SoftwareUpdateServiceTest: ok");
    }

    static void failureWithPleaseRestartIsNotSuccess() {
        ProcessResult failed = new ProcessResult(1603, "",
                "Installer failed. Please restart your computer and try again.");
        require(!SoftwareUpdateService.isWingetInstallSuccess(failed),
                "failure + please restart must not count as installed");
        require(!SoftwareUpdateService.isWingetRebootRequired(failed),
                "failure + please restart must not abort the batch as reboot-success");
        require(SoftwareUpdateService.isRebootRequired(failed),
                "phrasing is still detected for diagnostics");
    }

    static void msiRebootExitIsSuccess() {
        ProcessResult reboot = new ProcessResult(ProcessResult.MSI_SUCCESS_REBOOT_REQUIRED,
                "ERROR_SUCCESS_REBOOT_REQUIRED", "");
        require(SoftwareUpdateService.isWingetInstallSuccess(reboot), "3010 must count as installed");
        require(SoftwareUpdateService.isWingetRebootRequired(reboot), "3010 must abort remaining batch");
        ProcessResult initiated = new ProcessResult(ProcessResult.MSI_SUCCESS_REBOOT_INITIATED, "", "");
        require(SoftwareUpdateService.isWingetInstallSuccess(initiated), "1641 must count as installed");
    }

    static void exitZeroWithRestartPhraseIsSuccessAndReboot() {
        ProcessResult ok = new ProcessResult(0, "Please restart to finish installation.", "");
        require(SoftwareUpdateService.isWingetInstallSuccess(ok), "exit 0 is installed");
        require(SoftwareUpdateService.isWingetRebootRequired(ok), "exit 0 + restart phrase aborts batch");
    }

    private static void require(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }
}
