package com.sbtools.drivers;

import com.sbtools.drivers.model.InstalledDriver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Bounded post-install version reads so PnP can catch up after vendor bootstrappers exit.
 */
public final class DriverPostInstallVerifier {

    private static final long[] RETRY_DELAYS_MS = {5_000, 10_000, 20_000};
    private static final long OVERALL_DEADLINE_MS = 60_000;

    public record VerifyRequest(
            String deviceId,
            String expectedVersion,
            String previousCurrent,
            boolean rebootPending) {
    }

    public record Outcome(
            InstalledDriver fresh,
            DriverVersionVerifier.Verdict verdict,
            int readAttempts,
            String diagnostic) {
    }

    private DriverPostInstallVerifier() {
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /**
     * One full-scan supplier per read round; retries only unresolved verdicts.
     */
    public static Map<String, Outcome> verifyAll(
            List<VerifyRequest> requests,
            Supplier<Map<String, InstalledDriver>> scanByDeviceKey,
            AtomicBoolean cancelled,
            Sleeper sleeper,
            IntConsumer attemptNotifier) throws InterruptedException {
        if (requests == null || requests.isEmpty()) {
            return Map.of();
        }
        long deadline = System.currentTimeMillis() + OVERALL_DEADLINE_MS;
        Map<String, Outcome> done = new HashMap<>();
        List<VerifyRequest> pending = new ArrayList<>(requests);
        int attempt = 0;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            if (cancelled != null && cancelled.get()) {
                break;
            }
            attempt++;
            if (attemptNotifier != null) {
                attemptNotifier.accept(attempt);
            }
            Map<String, InstalledDriver> freshMap = scanByDeviceKey.get();
            List<VerifyRequest> stillPending = new ArrayList<>();
            for (VerifyRequest req : pending) {
                if (cancelled != null && cancelled.get()) {
                    stillPending.add(req);
                    continue;
                }
                String key = DriverScanService.normalizeDeviceKey(req.deviceId());
                InstalledDriver fresh = freshMap.get(key);
                DriverVersionVerifier.Verdict verdict = evaluateVerdict(req, fresh);
                if (isResolvedSuccess(verdict)) {
                    done.put(key, new Outcome(fresh, verdict, attempt, ""));
                } else if (attempt > RETRY_DELAYS_MS.length) {
                    String diag = "Windows did not report expected version after " + attempt + " read(s)";
                    done.put(key, new Outcome(fresh, verdict, attempt, diag));
                } else {
                    stillPending.add(req);
                }
            }
            pending = stillPending;
            if (pending.isEmpty()) {
                break;
            }
            if (attempt > RETRY_DELAYS_MS.length) {
                break;
            }
            long delay = RETRY_DELAYS_MS[attempt - 1];
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                break;
            }
            long sleepMs = Math.min(delay, remaining);
            if (sleeper != null && sleepMs > 0) {
                sleeper.sleep(sleepMs);
            }
        }
        for (VerifyRequest req : pending) {
            String key = DriverScanService.normalizeDeviceKey(req.deviceId());
            if (done.containsKey(key)) {
                continue;
            }
            Map<String, InstalledDriver> freshMap = scanByDeviceKey.get();
            InstalledDriver fresh = freshMap.get(key);
            DriverVersionVerifier.Verdict verdict = evaluateVerdict(req, fresh);
            String diag = cancelled != null && cancelled.get()
                    ? "verification cancelled"
                    : "verification deadline elapsed after " + attempt + " read(s)";
            done.put(key, new Outcome(fresh, verdict, attempt, diag));
        }
        return done;
    }

    private static DriverVersionVerifier.Verdict evaluateVerdict(VerifyRequest req, InstalledDriver fresh) {
        String freshVer = fresh == null || fresh.driverVersion() == null ? "" : fresh.driverVersion().trim();
        return DriverVersionVerifier.evaluate(
                req.expectedVersion(),
                req.previousCurrent(),
                freshVer,
                req.rebootPending());
    }

    private static boolean isResolvedSuccess(DriverVersionVerifier.Verdict verdict) {
        return verdict == DriverVersionVerifier.Verdict.VERIFIED
                || verdict == DriverVersionVerifier.Verdict.NEEDS_REBOOT;
    }
}
