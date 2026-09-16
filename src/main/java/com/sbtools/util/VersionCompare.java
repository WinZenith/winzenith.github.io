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
        boolean aIntelDch = isIntelDch(aT);
        boolean bIntelDch = isIntelDch(bT);
        boolean aIntelPub = !aIntelDch && isIntelPublic(aT);
        boolean bIntelPub = !bIntelDch && isIntelPublic(bT);
        if ((aIntelDch && bIntelPub) || (bIntelDch && aIntelPub)) {
            return compareIntelMixed(aT, bT);
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
        // Numeric parts and prerelease state tie: versions are equal even if
        // spelled differently ("23.70.0" vs "23.70.0.0"). A raw lexicographic
        // fallback here reported phantom Outdated and needless reinstalls.
        // Trailing zero groups are stripped before comparing so exotic
        // lettered revisions ("1.0a" vs "1.0b") still order correctly.
        String normA = baseA.replaceAll("(\\.0+)+$", "");
        String normB = baseB.replaceAll("(\\.0+)+$", "");
        return normA.compareToIgnoreCase(normB);
    }

    private static String extractBaseVersion(String version) {
        String v = version.replace(',', '.');
        int dashIdx = v.indexOf('-');
        if (dashIdx > 0) {
            v = v.substring(0, dashIdx);
        }
        // ROOT locale: tr-TR turns "PREVIEW" into "prevIew"(dotless), hiding tags.
        String lower = v.toLowerCase(java.util.Locale.ROOT);
        for (String suffix : new String[]{"alpha", "beta", "rc", "preview", "test", "dev"}) {
            int idx = lower.indexOf(suffix);
            if (idx > 0) {
                char before = lower.charAt(idx - 1);
                // Attached forms ("1.0beta2", "2.0rc1") count too: only a
                // preceding LETTER vetoes ("latest" is not a prerelease tag).
                // A trailing digit run belongs to the tag ("beta2").
                if (!Character.isLetter(before)) {
                    int endIdx = idx + suffix.length();
                    while (endIdx < lower.length() && Character.isDigit(lower.charAt(endIdx))) {
                        endIdx++;
                    }
                    boolean afterIsWord = endIdx < lower.length() && Character.isLetter(lower.charAt(endIdx));
                    if (!afterIsWord) {
                        v = v.substring(0, idx);
                        // Also drop a dangling separator left behind ("1.0-").
                        while (v.endsWith("-") || v.endsWith(".") || v.endsWith("_")) {
                            v = v.substring(0, v.length() - 1);
                        }
                        break;
                    }
                }
            }
        }
        return v;
    }

    private static boolean hasPrereleaseSuffix(String version) {
        if (version == null) return false;
        String v = version.replace(',', '.').toLowerCase(java.util.Locale.ROOT);
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
        String v = version.replace(',', '.').toLowerCase(java.util.Locale.ROOT);
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
        Long keyA = nvidiaNormalizedKey(a.trim());
        Long keyB = nvidiaNormalizedKey(b.trim());
        if (keyA == null || keyB == null) {
            return 0;
        }
        return Long.compare(keyA, keyB);
    }

    /**
     * Canonical NVIDIA build key: DCH {@code NN.N.1X.YYYY} → digit {@code X} + zero-padded
     * {@code YYYY}; public {@code AAA.BB} → {@code AAA * 100 + BB}.
     */
    static Long nvidiaNormalizedKey(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        if (isNvidiaDch(t)) {
            String[] parts = t.split("\\.");
            if (parts.length != 4) {
                return null;
            }
            try {
                char eraCh = parts[2].charAt(parts[2].length() - 1);
                int eraDigit = Character.digit(eraCh, 10);
                if (eraDigit < 0) {
                    return null;
                }
                long tail = Long.parseLong(parts[3].replaceAll("[^0-9]", ""));
                if (tail < 0 || tail > 9999) {
                    return null;
                }
                return eraDigit * 10_000L + tail;
            } catch (Exception e) {
                return null;
            }
        }
        if (isNvidiaPublic(t)) {
            String[] parts = t.split("\\.");
            if (parts.length != 2) {
                return null;
            }
            try {
                long major = Long.parseLong(parts[0].replaceAll("[^0-9]", ""));
                String minorRaw = parts[1].replaceAll("[^0-9]", "");
                if (minorRaw.length() < 1 || minorRaw.length() > 2) {
                    return null;
                }
                if (minorRaw.length() == 1) {
                    minorRaw = minorRaw + "0";
                }
                long minor = Long.parseLong(minorRaw);
                if (minor < 0 || minor > 99) {
                    return null;
                }
                return major * 100L + minor;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /**
     * True for Intel Windows graphics versions such as {@code 31.0.101.5768}.
     * The public Arc/Xe build is the {@code 101.xxxx} tail.
     */
    static boolean isIntelDch(String v) {
        return v != null && v.matches("\\d{2}\\.\\d{1,2}\\.101\\.\\d{3,5}");
    }

    /** True for Intel public graphics versions such as {@code 101.5768}. */
    static boolean isIntelPublic(String v) {
        return v != null && v.matches("101\\.\\d{3,5}");
    }

    static int compareIntelMixed(String a, String b) {
        Long keyA = intelNormalizedKey(a.trim());
        Long keyB = intelNormalizedKey(b.trim());
        if (keyA == null || keyB == null) {
            return 0;
        }
        return Long.compare(keyA, keyB);
    }

    /** Intel GFX build key: DCH {@code NN.N.101.XXXX} and public {@code 101.XXXX} → {@code XXXX}. */
    static Long intelNormalizedKey(String v) {
        if (v == null || v.isBlank()) {
            return null;
        }
        String t = v.trim();
        try {
            if (isIntelDch(t) || isIntelPublic(t)) {
                String[] parts = t.split("\\.");
                return Long.parseLong(parts[parts.length - 1].replaceAll("[^0-9]", ""));
            }
        } catch (Exception e) {
            return null;
        }
        return null;
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
