package com.sbtools.drivers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Guards and classifies Windows Installer (msiexec) invocations. */
public final class WindowsInstallerInvoke {

    public static final String INVALID_INVOCATION_MESSAGE =
            "Windows Installer was started with invalid arguments (likely wrong package type).";

    public static final String INTEL_BLUETOOTH_INVALID_CMD_MESSAGE =
            "Intel Bluetooth installer rejected its command line; installation was not started.";

    private WindowsInstallerInvoke() {
    }

    public static boolean isUsageHelpOutput(String stdout, String stderr) {
        return isUsageHelpOutput(joinOutput(stdout, stderr));
    }

    public static boolean isUsageHelpOutput(String combinedOutput) {
        if (combinedOutput == null || combinedOutput.isBlank()) {
            return false;
        }
        String lower = combinedOutput.toLowerCase(Locale.ROOT);
        return lower.contains("windows") && lower.contains("installer")
                && lower.contains("install options")
                && (lower.contains("</package | /i>") || lower.contains("/package | /i>"))
                && lower.contains("setting public properties");
    }

    static String joinOutput(String stdout, String stderr) {
        String out = stdout == null ? "" : stdout;
        String err = stderr == null ? "" : stderr;
        if (out.isBlank()) {
            return err;
        }
        if (err.isBlank()) {
            return out;
        }
        return out + System.lineSeparator() + err;
    }

    /**
     * @return null if the path looks like a non-empty OLE compound document (MSI container)
     */
    public static String validateMsiPackage(Path file) {
        if (file == null) {
            return "MSI path is missing.";
        }
        try {
            if (!Files.isRegularFile(file)) {
                return "MSI package not found: " + file;
            }
            long size = Files.size(file);
            if (size <= 0) {
                return "MSI package is empty: " + file;
            }
            if (!hasOleCompoundHeader(file)) {
                return "File is not a valid MSI package (missing OLE header): " + file.getFileName();
            }
        } catch (IOException e) {
            return "Could not read MSI package: " + e.getMessage();
        }
        return null;
    }

    static boolean hasOleCompoundHeader(Path file) throws IOException {
        byte[] header = new byte[8];
        try (var in = Files.newInputStream(file)) {
            if (in.read(header) != 8) {
                return false;
            }
        }
        return header[0] == (byte) 0xD0 && header[1] == (byte) 0xCF
                && header[2] == (byte) 0x11 && header[3] == (byte) 0xE0
                && header[4] == (byte) 0xA1 && header[5] == (byte) 0xB1
                && header[6] == (byte) 0x1A && header[7] == (byte) 0xE1;
    }
}
