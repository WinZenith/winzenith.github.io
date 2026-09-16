package com.sbtools.netoptimizer;

public record OperationResult(boolean success, String message, String details) {

    public static OperationResult ok(String message) {
        return new OperationResult(true, message, null);
    }

    public static OperationResult ok(String message, String details) {
        return new OperationResult(true, message, details);
    }

    public static OperationResult fail(String message) {
        return new OperationResult(false, message, null);
    }

    public static OperationResult fail(String message, String details) {
        return new OperationResult(false, message, details);
    }

    /** True when apply reported per-setting OK and FAILED lines (mixed TCP state). */
    public boolean partialApply() {
        return !success && details != null && details.contains(" — OK") && details.contains(" — FAILED");
    }

    /** Stack/Winsock paths put this phrase in the message when a reboot is required. */
    public boolean rebootRequired() {
        if (partialApply()) return true;
        return message != null && message.toLowerCase().contains("reboot required");
    }
}
