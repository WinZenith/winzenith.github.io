package com.basicsdriverupdate.util;

public record ProcessResult(int exitCode, String stdout, String stderr) {

    public boolean success() {
        return exitCode == 0;
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
