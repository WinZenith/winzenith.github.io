package com.sbtools.drivers;

import com.sbtools.drivers.model.DriverUpdateCandidate;

import java.nio.file.Path;
import java.util.regex.Pattern;

/** Vendor-documented silent-install argument lists for driver EXE packages. */
public final class DriverSilentInstallerArgs {

    private static final Pattern INTEL_BT_CONSUMER_EXE = Pattern.compile(
            "(?i)BT-\\d+(?:\\.\\d+)*-64UWD-Win10-Win11\\.exe");

    public enum IntelPackageFamily {
        BLUETOOTH_CONSUMER
    }

    private DriverSilentInstallerArgs() {
    }

    /**
     * @return detected Intel package family, or null when source is not Intel
     */
    public static IntelPackageFamily detectIntelPackageFamily(Path driverFile, DriverUpdateCandidate candidate) {
        if (candidate == null || !"Intel".equals(candidate.source())) {
            return null;
        }
        if (driverFile != null && driverFile.getFileName() != null) {
            String fn = driverFile.getFileName().toString();
            if (INTEL_BT_CONSUMER_EXE.matcher(fn).matches()) {
                return IntelPackageFamily.BLUETOOTH_CONSUMER;
            }
        }
        return null;
    }

    public static boolean usesIntelSingleShotSilent(IntelPackageFamily family) {
        return family == IntelPackageFamily.BLUETOOTH_CONSUMER;
    }

    public static String[] exeArgsFor(IntelPackageFamily family) {
        if (family == IntelPackageFamily.BLUETOOTH_CONSUMER) {
            return new String[]{"/quiet"};
        }
        return null;
    }
}
