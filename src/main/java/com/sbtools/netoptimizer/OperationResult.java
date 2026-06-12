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
}
