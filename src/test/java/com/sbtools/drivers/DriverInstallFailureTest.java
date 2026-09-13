package com.sbtools.drivers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverInstallFailureTest {

    @Test
    void nullInterruptedExceptionIsActionable() {
        String msg = DriverInstallFailure.userMessage("download", new InterruptedException(), false);
        assertFalse(msg.contains("null"));
        assertTrue(msg.contains("interrupted"));
    }

    @Test
    void nestedCauseSuppliesMessage() {
        Throwable t = new RuntimeException(new IOException("connection reset"));
        assertEquals("connection reset", DriverInstallFailure.deepestMessage(t));
        assertTrue(DriverInstallFailure.userMessage("download", t, false).contains("connection reset"));
    }

    @Test
    void messagelessExceptionUsesClassName() {
        assertEquals("IOException", DriverInstallFailure.deepestMessage(new IOException()));
    }

    @Test
    void cancelRequestedMapsToCancelled() {
        assertEquals(DriverInstallFailure.CANCELLED_MESSAGE,
                DriverInstallFailure.userMessage("installer wait", new InterruptedException(), true));
        assertEquals(DriverInstallFailure.CANCELLED_MESSAGE,
                DriverInstallFailure.userMessage("download", new CancellationException(), true));
    }

    @Test
    void unexpectedInterruptionIsRetryable() {
        String msg = DriverInstallFailure.userMessage("installer wait", new InterruptedException(), false);
        assertTrue(msg.contains("Retry"));
        assertFalse(msg.contains("null"));
    }

    @Test
    void intelLaunchFailureNamesVendorAndFile() {
        Path p = Path.of("IntelBluetooth.exe");
        String msg = DriverInstallFailure.intelInstallerLaunchFailure(
                "Intel", p, new String[]{"-q", "-s"}, new IOException("Access is denied"));
        assertTrue(msg.contains("Intel"));
        assertTrue(msg.contains("IntelBluetooth.exe"));
        assertTrue(msg.contains("-q -s"));
        assertTrue(msg.contains("Access is denied"));
    }

    @Test
    void intelWaitInterruptionNeverNull() {
        String msg = DriverInstallFailure.intelInstallerWaitInterrupted("Intel");
        assertFalse(msg.contains("null"));
        assertTrue(msg.contains("Intel"));
    }

    @Test
    void sanitizeRejectsLegacyNullStrings() {
        assertTrue(DriverInstallFailure.isInvalidUserMessage("Error: null"));
        assertTrue(DriverInstallFailure.isInvalidUserMessage("Install failed:\nnull"));
        String safe = DriverInstallFailure.sanitizeUserMessage("Error: null", "Bluetooth");
        assertFalse(safe.contains("null"));
        assertTrue(safe.contains("Bluetooth"));
        assertTrue(safe.contains("app.log"));
    }
}
