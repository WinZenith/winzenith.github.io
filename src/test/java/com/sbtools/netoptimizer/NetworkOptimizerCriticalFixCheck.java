package com.sbtools.netoptimizer;

/** Timeout after netsh int ip reset must still demand a reboot. */
public final class NetworkOptimizerCriticalFixCheck {

    public static void main(String[] args) {
        OperationResult timed = NetworkOptimizerService.stackResetProcessFailure(
                "Process timed out after 60s", "details");
        check(!timed.success() && timed.rebootRequired(),
                "timeout must require reboot");
        OperationResult other = NetworkOptimizerService.stackResetProcessFailure(
                "script missing", "details");
        check(!other.success() && !other.rebootRequired(),
                "non-timeout failure must not claim reboot");
        System.out.println("NetworkOptimizerCriticalFixCheck ok");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
