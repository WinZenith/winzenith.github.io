package com.sbtools.drivers.catalog;

import com.sbtools.drivers.DriverPostInstallVerifier;
import com.sbtools.drivers.DriverScanService;
import com.sbtools.drivers.DriverVersionVerifier;
import com.sbtools.drivers.model.InstalledDriver;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Checks the two Drivers-tab critical fixes. Run as a main class. */
public final class DriversCriticalFixCheck {

    public static void main(String[] args) throws Exception {
        checkHardwareIds();
        checkCancelledVerificationKeepsVerdict();
        System.out.println("DriversCriticalFixCheck ok");
    }

    private static void checkHardwareIds() {
        InstalledDriver gpu = device(
                "PCI\\VEN_10DE&DEV_1C82&SUBSYS_00000000",
                "PCI\\VEN_10DE&DEV_1C82&SUBSYS_11111111;PCI\\VEN_10DE&DEV_1C82");
        check(!DriverCatalogDatabase.hardwareCompatible(gpu, "*"), "star must not match");
        check(!DriverCatalogDatabase.hardwareCompatible(gpu, "---"), "dashes must not match");
        check(!DriverCatalogDatabase.hardwareCompatible(gpu, "PCI\\VEN_10DE"), "vendor-only must not match");
        check(DriverCatalogDatabase.hardwareCompatible(gpu, "PCI\\VEN_10DE&DEV_1C82"), "VEN+DEV must match");
        check(DriverCatalogDatabase.hardwareCompatible(gpu, "PCI\\VEN_10DE&DEV_1C82&SUBSYS_11111111"),
                "longer SUBSYS id must match");
        check(!DriverCatalogDatabase.hardwareCompatible(gpu, "PCI\\VEN_10DE&DEV_1C83"), "other DEV must not match");

        InstalledDriver near = device("PCI\\VEN_8086&DEV_27231", "PCI\\VEN_8086&DEV_27231");
        check(!DriverCatalogDatabase.hardwareCompatible(near, "PCI\\VEN_8086&DEV_2723"),
                "DEV_2723 must not match DEV_27231");
        InstalledDriver exact = device("PCI\\VEN_8086&DEV_2723", "PCI\\VEN_8086&DEV_2723&SUBSYS_12345678");
        check(DriverCatalogDatabase.hardwareCompatible(exact, "PCI\\VEN_8086&DEV_2723"),
                "DEV prefix with SUBSYS must match");

        InstalledDriver codec = device(
                "HDAUDIO\\FUNC_01&VEN_10EC&DEV_0888",
                "HDAUDIO\\FUNC_01&VEN_10EC&DEV_0888");
        check(DriverCatalogDatabase.hardwareCompatible(codec, "PCI\\VEN_10EC&DEV_0888"),
                "same codec on another bus must match");
        check(!DriverCatalogDatabase.hardwareCompatible(codec, "PCI\\VEN_10EC"),
                "codec vendor-only must not match");

        check(!DriverCatalogDatabase.isValidRefreshedEntry(entry(List.of("PCI\\VEN_10DE"))),
                "refreshed vendor-only entry must be rejected");
        check(!DriverCatalogDatabase.isValidRefreshedEntry(entry(List.of("*"))),
                "refreshed empty-normalized entry must be rejected");
        check(DriverCatalogDatabase.isValidRefreshedEntry(entry(List.of("PCI\\VEN_10DE&DEV_1C82"))),
                "refreshed VEN+DEV entry must be accepted");
    }

    private static void checkCancelledVerificationKeepsVerdict() throws InterruptedException {
        String deviceId = "PCI\\VEN_10DE&DEV_1C82&SUBSYS_00000000";
        String key = DriverScanService.normalizeDeviceKey(deviceId);
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicInteger scans = new AtomicInteger();
        InstalledDriver fresh = new InstalledDriver(
                deviceId, "Test Device", "PCI\\VEN_10DE&DEV_1C82", "NVIDIA",
                "2.0.0.0", "oem1.inf", "", "OK", null, Boolean.TRUE);
        Map<String, DriverPostInstallVerifier.Outcome> outcomes = DriverPostInstallVerifier.verifyAll(
                List.of(new DriverPostInstallVerifier.VerifyRequest(deviceId, "2.0.0.0", "1.0.0.0", false)),
                () -> {
                    int n = scans.incrementAndGet();
                    if (n == 1) {
                        cancel.set(true);
                        return Map.of();
                    }
                    return Map.of(key, fresh);
                },
                cancel,
                ms -> {
                    throw new InterruptedException("cancelled");
                },
                null);
        DriverPostInstallVerifier.Outcome outcome = outcomes.get(key);
        check(outcome != null, "cancelled verify must still return an outcome");
        check(outcome.verdict() == DriverVersionVerifier.Verdict.VERIFIED,
                "closing scan must keep a successful install, got " + outcome.verdict());
    }

    private static CatalogEntry entry(List<String> hardwareIds) {
        CatalogEntry e = new CatalogEntry(
                "check", "NVIDIA", "gpu", hardwareIds,
                CatalogEntry.MatchMethod.HARDWARE_ID, "", "32.0.15.1", null);
        e.setLastVerified(Instant.now());
        e.setConfidence(0.9);
        return e;
    }

    private static InstalledDriver device(String deviceId, String hardwareIds) {
        return new InstalledDriver(
                deviceId, "Test Device", hardwareIds, "NVIDIA",
                "1.0.0.0", "oem1.inf", "", "OK", null, Boolean.TRUE);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private DriversCriticalFixCheck() {
    }
}
