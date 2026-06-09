package com.sbtools.startup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class StartupImpactService {

    public static double estimateBootImpactMs(StartupItem item) {
        switch (item.getType()) {
            case REGISTRY:
                return estimateRegistryImpact(item);
            case TASK:
                return estimateTaskImpact(item);
            case SERVICE:
                return estimateServiceImpact(item);
            default:
                return 50;
        }
    }

    private static double estimateRegistryImpact(StartupItem item) {
        double base = 50.0;
        if (item.getPath() != null && !item.getPath().isEmpty()) {
            try {
                Path p = Paths.get(item.getPath());
                if (Files.exists(p)) {
                    long size = Files.size(p);
                    base += (size / (1024.0 * 1024.0)) * 10;
                    base = Math.min(base, 500);
                }
            } catch (Exception ignored) {}
        }
        return base;
    }

    private static double estimateTaskImpact(StartupItem item) {
        double base = 100.0;
        if (item.getPath() != null && !item.getPath().isEmpty()) {
            try {
                Path p = Paths.get(item.getPath());
                if (Files.exists(p)) {
                    long size = Files.size(p);
                    base += (size / (1024.0 * 1024.0)) * 15;
                    base = Math.min(base, 2000);
                }
            } catch (Exception ignored) {}
        }
        return base;
    }

    private static double estimateServiceImpact(StartupItem item) {
        double base = 200.0;
        if ("Automatic".equalsIgnoreCase(item.getServiceStartType())) {
            base += 100;
        }
        if (item.getPath() != null && !item.getPath().isEmpty()) {
            try {
                Path p = Paths.get(item.getPath());
                if (Files.exists(p)) {
                    long size = Files.size(p);
                    base += (size / (1024.0 * 1024.0)) * 20;
                    base = Math.min(base, 5000);
                }
            } catch (Exception ignored) {}
        }
        return base;
    }

    public static String formatImpact(double ms) {
        if (ms < 1000) return String.format("%.0f ms", ms);
        return String.format("%.1f s", ms / 1000.0);
    }
}
