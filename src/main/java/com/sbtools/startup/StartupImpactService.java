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
        String startType = item.getServiceStartType();
        if ("Disabled".equalsIgnoreCase(startType)) {
            return 0;
        }
        // Manual services do not start at boot unless triggered – minimal impact
        if ("Manual".equalsIgnoreCase(startType)) {
            return 15.0;
        }
        if ("Automatic (Delayed Start)".equalsIgnoreCase(startType)) {
            // Delayed start has reduced boot impact
            double score = 30.0;
            String nameLower = lower(item.getName());
            if (containsHeavy(nameLower, item.getName())) score += 120;
            if (item.getDependencies() != null) score += Math.min(item.getDependencies().size() * 30, 150);
            return Math.min(score, 800);
        }
        // Automatic
        double score = 100.0;
        if ("Automatic".equalsIgnoreCase(startType)) {
            // keep 100 base for automatic
        }

        String nameLower = lower(item.getName());
        if (containsHeavyExact(nameLower)) {
            score += 300;
        } else if (containsHeavy(nameLower, item.getName())) {
            score += 200;
        }

        // Dependency awareness: each dependency adds latency but capped
        if (item.getDependencies() != null) {
            score += Math.min(item.getDependencies().size() * 50, 300);
        }

        return Math.min(score, 3000);
    }

    private static boolean containsHeavyExact(String nameLower) {
        for (String h : HEAVY_SERVICE_NAMES) {
            if (h.equalsIgnoreCase(nameLower)) return true;
        }
        return false;
    }

    private static boolean containsHeavy(String nameLower, String original) {
        for (String heavy : HEAVY_SERVICE_NAMES) {
            if (nameLower.contains(heavy.toLowerCase())) return true;
            if (original != null && original.equalsIgnoreCase(heavy)) return true;
        }
        return false;
    }

    public static String formatImpact(double ms) {
        if (ms < 1000) return String.format("%.0f ms", ms);
        return String.format("%.1f s", ms / 1000.0);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase();
    }
}
