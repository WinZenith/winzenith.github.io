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

        java.util.Map<String, Object> tcp = new java.util.LinkedHashMap<>();
        tcp.put("Key", "TCP/IP Reset");
        tcp.put("Value", "failed");
        tcp.put("Detail", "Resetting Ethernet, FAILED");
        java.util.Map<String, Object> winsock = new java.util.LinkedHashMap<>();
        winsock.put("Key", "Winsock Reset");
        winsock.put("Value", "completed");
        java.util.Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("results", java.util.List.of(tcp, winsock));
        String formatted = NetworkOptimizerService.formatStackResetDetails(data, null);
        check(formatted.contains("TCP/IP Reset: failed — FAILED"),
                "failed marker");
        check(formatted.contains("Resetting Ethernet, FAILED"),
                "netsh excerpt");
        check(formatted.contains("Winsock Reset: completed — OK"),
                "winsock ok line");

        String absentOnly = "TCP AutoTuning: normal (timed out) — FAILED\n"
                + "TCP Ack Frequency: already absent — OK";
        check(!NetworkOptimizerService.isPartialOptimizeApply(absentOnly),
                "already-absent OK is not a partial apply");
        String removed = "TCP AutoTuning: normal (timed out) — FAILED\n"
                + "TCP Ack Frequency: removed (registry default) — OK";
        check(NetworkOptimizerService.isPartialOptimizeApply(removed),
                "a real registry delete plus a failure is partial");
        OperationResult absentResult = OperationResult.fail("Optimization did not fully apply.", absentOnly);
        check(!absentResult.partialApply() && !absentResult.rebootRequired(),
                "already-absent result must not save the preset or require reboot");
        OperationResult removedResult = OperationResult.fail("Optimization did not fully apply.", removed);
        check(removedResult.partialApply(),
                "removed result is partial");
        System.out.println("NetworkOptimizerCriticalFixCheck ok");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
