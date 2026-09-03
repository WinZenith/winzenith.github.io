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
        // NVIDIA interop: installed drivers report Windows DCH versions
        // ("32.0.15.8157" == public 581.57) while catalogs/scrapers report
        // public versions ("566.36"). A naive numeric compare reads 32 < 566
        // and offers a DOWNGRADE. Normalize mixed pairs onto one scale.
        String aT = a.trim();
        String bT = b.trim();
        boolean aDch = isNvidiaDch(aT);
        boolean bDch = isNvidiaDch(bT);
        boolean aPub = !aDch && isNvidiaPublic(aT);
        boolean bPub = !bDch && isNvidiaPublic(bT);
        if ((aDch && bPub) || (bDch && aPub)) {
            return compareNvidiaMixed(aT, bT);
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

    /**
     * True for NVIDIA Windows DCH driver versions such as {@code 32.0.15.8157}
     * (= public 581.57), {@code 31.0.15.3623} (= 536.23), {@code 30.0.14.7212}
     * (= 472.12). Other vendors do not use the {@code NN.N.1N.} shape, so this
     * pattern is NVIDIA-specific in practice.
     */
    static boolean isNvidiaDch(String v) {
        return v != null && v.matches("\\d{2}\\.\\d{1,2}\\.1\\d\\.\\d{1,5}");
    }

    /** True for NVIDIA public versions such as {@code 566.36}. */
    static boolean isNvidiaPublic(String v) {
        return v != null && v.matches("\\d{3}\\.\\d{1,2}");
    }

    /**
     * Compares a mixed NVIDIA DCH / public pair on one scale. The public
     * version's digit string ends with the DCH build number (public 566.36 →
     * digits "56636" → DCH "...6636"; 581.57 → "...8157"), so the last four
     * digits are directly comparable once both sides are placed in the same
     * release era. Era order decides across eras; the tail decides within an
     * era. Never inverts: unparseable input abstains (0 = no update).
     */
    static int compareNvidiaMixed(String a, String b) {
        try {
            boolean aDch = isNvidiaDch(a.trim());
            String dch = aDch ? a.trim() : b.trim();
            String pub = aDch ? b.trim() : a.trim();
            String[] dchParts = dch.split("\\.");
            long dchEra = Long.parseLong(dchParts[0].replaceAll("[^0-9]", ""));
            long dchTail = Long.parseLong(dchParts[dchParts.length - 1].replaceAll("[^0-9]", ""));
            String pubDigits = pub.replaceAll("[^0-9]", "");
            if (pubDigits.length() < 5) return 0;
            long pubMajor = Long.parseLong(pubDigits.substring(0, pubDigits.length() - 2));
            long pubTail = Long.parseLong(pubDigits.substring(pubDigits.length() - 4));
            // Best-effort era mapping (DCH major per public branch). Wrong
            // guesses near a boundary can only miss an update, never invert:
            // the installed DCH major is fact, and tails increase with
            // releases, so a cross-era decision always follows release order
            // except exactly at a misguessed boundary — where equal-era tail
            // comparison still matches the true digit order.
            long pubEra = pubMajor >= 560 ? 32 : pubMajor >= 520 ? 31 : pubMajor >= 470 ? 30 : 27;
            long keyA;
            long keyB;
            long keyDch = dchEra * 1_000_000L + dchTail;
            long keyPub = pubEra * 1_000_000L + pubTail;
            if (aDch) {
                keyA = keyDch;
                keyB = keyPub;
            } else {
                keyA = keyPub;
                keyB = keyDch;
            }
            return Long.compare(keyA, keyB);
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static long parsePart(String part) {        String digits = part.replaceAll("[^0-9]", "");
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
