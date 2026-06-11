package com.sbtools.startup;

import java.util.Set;

public class StartupImpactService {

    private static final Set<String> HEAVY_SERVICE_NAMES = Set.of(
            "wuauserv",      // Windows Update
            "WinDefend",     // Windows Defender
            "Spooler",       // Print Spooler
            "bits",          // Background Intelligent Transfer
            "Schedule",      // Task Scheduler
            "TrustedInstaller",
            "cryptsvc",
            "EventLog",
            "Dhcp",
            "Dnscache"
    );

    private static final String[] HEAVY_NAME_PATTERNS = {
            "antivirus", "avast", "avg", "norton", "defender", "mcafee", "kaspersky", "bitdefender",
            "onedrive", "dropbox", "google drive", "icloud", "sync",
            "update", "auto_update", "autoupdate"
    };

    public static double estimateBootImpactMs(StartupItem item) {
        return switch (item.getType()) {
            case REGISTRY -> estimateRegistryImpact(item);
            case TASK -> estimateTaskImpact(item);
            case SERVICE -> estimateServiceImpact(item);
            default -> 50;
        };
    }

    private static double estimateRegistryImpact(StartupItem item) {
        double score = 30.0;
        String nameLower = lower(item.getName());
        String pathLower = lower(item.getPath());

        for (String pattern : HEAVY_NAME_PATTERNS) {
            if (nameLower.contains(pattern) || pathLower.contains(pattern)) {
                if (pattern.contains("antivirus") || pattern.contains("avast")
                        || pattern.contains("avg") || pattern.contains("norton")
                        || pattern.contains("defender") || pattern.contains("mcafee")
                        || pattern.contains("kaspersky") || pattern.contains("bitdefender")) {
                    score += 200;
                } else if (pattern.contains("onedrive") || pattern.contains("dropbox")
                        || pattern.contains("google drive") || pattern.contains("icloud")
                        || pattern.contains("sync")) {
                    score += 150;
                } else {
                    score += 100;
                }
                break;
            }
        }

        if ("Unknown".equals(item.getPublisher())) {
            score += 30;
        }

        return Math.min(score, 500);
    }

    private static double estimateTaskImpact(StartupItem item) {
        double score = 60.0;
        String nameLower = lower(item.getName());
        String pathLower = lower(item.getPath());

        for (String pattern : HEAVY_NAME_PATTERNS) {
            if (nameLower.contains(pattern) || pathLower.contains(pattern)) {
                score += 100;
                break;
            }
        }

        if (pathLower.contains("system32") || pathLower.contains("syswow64")) {
            score += 50;
        }

        return Math.min(score, 1500);
    }

    private static double estimateServiceImpact(StartupItem item) {
        double score = 50.0;

        String startType = item.getServiceStartType();
        if ("Automatic".equalsIgnoreCase(startType)) {
            score *= 2;
        } else if ("Disabled".equalsIgnoreCase(startType)) {
            return 0;
        }

        String nameLower = lower(item.getName());
        if (HEAVY_SERVICE_NAMES.contains(item.getName())) {
            score += 300;
        } else {
            for (String heavy : HEAVY_SERVICE_NAMES) {
                if (nameLower.contains(heavy.toLowerCase())) {
                    score += 200;
                    break;
                }
            }
        }

        // Dependency awareness: each dependency adds latency
        if (item.getDependencies() != null) {
            score += item.getDependencies().size() * 100;
        }

        return Math.min(score, 3000);
    }

    public static String formatImpact(double ms) {
        if (ms < 1000) return String.format("%.0f ms", ms);
        return String.format("%.1f s", ms / 1000.0);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
