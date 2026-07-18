package com.sbtools.util;

public final class VersionCompare {

    private VersionCompare() {
    }

    /** Returns negative if a &lt; b, zero if equal, positive if a &gt; b. */
    public static int compare(String a, String b) {
        if (a == null || a.isBlank()) {
            return b == null || b.isBlank() ? 0 : -1;
        }
        if (b == null || b.isBlank()) {
            return 1;
        }
        String baseA = extractBaseVersion(a);
        String baseB = extractBaseVersion(b);
        String[] pa = baseA.split("\\.");
        String[] pb = baseB.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long va = parsePart(i < pa.length ? pa[i] : "0");
            long vb = parsePart(i < pb.length ? pb[i] : "0");
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        boolean hasPrereleaseA = hasPrereleaseSuffix(a);
        boolean hasPrereleaseB = hasPrereleaseSuffix(b);
        if (hasPrereleaseA != hasPrereleaseB) {
            return hasPrereleaseA ? -1 : 1;
        }
        if (hasPrereleaseA && hasPrereleaseB) {
            int cmp = baseA.compareToIgnoreCase(baseB);
            if (cmp != 0) {
                return cmp;
            }
            return Integer.compare(prereleaseWeight(a), prereleaseWeight(b));
        }
        return baseA.compareToIgnoreCase(baseB);
    }

    private static String extractBaseVersion(String version) {
        String v = version.replace(',', '.');
        int dashIdx = v.indexOf('-');
        if (dashIdx > 0) {
            v = v.substring(0, dashIdx);
        }
        String lower = v.toLowerCase();
        for (String suffix : new String[]{"alpha", "beta", "rc", "preview", "test", "dev"}) {
            int idx = lower.indexOf(suffix);
            if (idx > 0) {
                char before = lower.charAt(idx - 1);
                int endIdx = idx + suffix.length();
                boolean afterIsWord = endIdx < lower.length() && Character.isLetterOrDigit(lower.charAt(endIdx));
                if (!Character.isLetterOrDigit(before) && !afterIsWord) {
                    v = v.substring(0, idx);
                    break;
                }
            }
        }
        return v;
    }

    private static boolean hasPrereleaseSuffix(String version) {
        if (version == null) return false;
        String v = version.replace(',', '.').toLowerCase();
        int dashIdx = v.indexOf('-');
        if (dashIdx > 0) {
            String suffix = v.substring(dashIdx + 1);
            if (suffix.contains("alpha") || suffix.contains("beta")
                    || suffix.contains("rc") || suffix.contains("preview")
                    || suffix.contains("test") || suffix.contains("dev")) {
                return true;
            }
        }
        String base = extractBaseVersion(version);
        if (base.length() < v.length()) {
            return true;
        }
        return false;
    }

    private static int prereleaseWeight(String version) {
        if (version == null) return 0;
        String v = version.replace(',', '.').toLowerCase();
        if (v.contains("alpha") || v.contains("dev")) return 1;
        if (v.contains("beta")) return 2;
        if (v.contains("rc") || v.contains("preview")) return 3;
        if (v.contains("test")) return 4;
        return 0;
    }

    private static long parsePart(String part) {
        String digits = part.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(digits.length() > 18 ? digits.substring(0, 18) : digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static boolean isOlder(String installed, String available) {
        return compare(installed, available) < 0;
    }

    public static boolean isNewer(String candidate, String current) {
        return compare(candidate, current) > 0;
    }
}
