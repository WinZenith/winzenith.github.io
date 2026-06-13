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
        String na = normalizeVersion(a);
        String nb = normalizeVersion(b);
        String[] pa = na.split("\\.");
        String[] pb = nb.split("\\.");
        int len = Math.max(pa.length, pb.length);
        for (int i = 0; i < len; i++) {
            long va = parsePart(i < pa.length ? pa[i] : "0");
            long vb = parsePart(i < pb.length ? pb[i] : "0");
            if (va != vb) {
                return Long.compare(va, vb);
            }
        }
        return na.compareToIgnoreCase(nb);
    }

    private static String normalizeVersion(String version) {
        String v = version.replace(',', '.');
        int dashIdx = v.indexOf('-');
        if (dashIdx > 0) {
            v = v.substring(0, dashIdx);
        }
        return v;
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
}
