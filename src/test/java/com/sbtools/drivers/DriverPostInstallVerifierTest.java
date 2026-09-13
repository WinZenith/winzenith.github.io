package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DriverPostInstallVerifierTest {

    private static InstalledDriver driver(String version) {
        return new InstalledDriver("USB\\VID_8087&PID_0AAA\\1", "BT", "", "Intel", version,
                "", "", "OK", LocalDate.now(), true);
    }

    @Test
    void verifiedOnLaterRead() throws InterruptedException {
        String deviceId = "USB\\VID_8087&PID_0AAA\\1";
        String key = DriverScanService.normalizeDeviceKey(deviceId);
        AtomicInteger scans = new AtomicInteger();
        Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                List.of(new DriverPostInstallVerifier.VerifyRequest(deviceId, "24.70.0.3", "24.40.0.3", false)),
                () -> {
                    int n = scans.incrementAndGet();
                    String ver = n < 2 ? "24.40.0.3" : "24.70.0.3";
                    return Map.of(key, driver(ver));
                },
                new AtomicBoolean(false),
                ms -> { /* no delay in tests */ },
                null);
        DriverPostInstallVerifier.Outcome o = outcomes.get(key);
        assertNotNull(o);
        assertEquals(DriverVersionVerifier.Verdict.VERIFIED, o.verdict());
        assertEquals(2, o.readAttempts());
    }

    @Test
    void unchangedAfterAllReads() throws InterruptedException {
        String deviceId = "USB\\VID_8087&PID_0AAA\\1";
        String key = DriverScanService.normalizeDeviceKey(deviceId);
        Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                List.of(new DriverPostInstallVerifier.VerifyRequest(deviceId, "24.70.0.3", "24.40.0.3", false)),
                () -> Map.of(key, driver("24.40.0.3")),
                new AtomicBoolean(false),
                ms -> { /* no delay in tests */ },
                null);
        DriverPostInstallVerifier.Outcome o = outcomes.get(key);
        assertNotNull(o);
        assertEquals(DriverVersionVerifier.Verdict.UNCHANGED, o.verdict());
        assertTrue(o.readAttempts() >= 4);
        assertFalse(o.diagnostic().isBlank());
    }

    @Test
    void needsRebootImmediate() throws InterruptedException {
        String deviceId = "dev1";
        String key = DriverScanService.normalizeDeviceKey(deviceId);
        Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                List.of(new DriverPostInstallVerifier.VerifyRequest(deviceId, "2.0", "1.0", true)),
                () -> Map.of(key, driver("1.0")),
                new AtomicBoolean(false),
                ms -> { /* no delay in tests */ },
                null);
        assertEquals(DriverVersionVerifier.Verdict.NEEDS_REBOOT, outcomes.get(key).verdict());
        assertEquals(1, outcomes.get(key).readAttempts());
    }

    @Test
    void cancellationStopsRetries() throws InterruptedException {
        String deviceId = "dev1";
        String key = DriverScanService.normalizeDeviceKey(deviceId);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                List.of(new DriverPostInstallVerifier.VerifyRequest(deviceId, "2.0", "1.0", false)),
                () -> {
                    cancelled.set(true);
                    return new HashMap<>();
                },
                cancelled,
                ms -> { /* no delay in tests */ },
                null);
        assertEquals(DriverVersionVerifier.Verdict.INCONCLUSIVE, outcomes.get(key).verdict());
    }
}
