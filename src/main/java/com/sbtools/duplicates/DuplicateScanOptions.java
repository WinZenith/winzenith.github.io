package com.sbtools.duplicates;

import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Scan scope options for duplicate detection.
 * <p>
 * Defaults preserve the historical behaviour: no size threshold beyond
 * skipping 0-byte files, no extension filter, newest keeper.
 */
public record DuplicateScanOptions(
        long minSizeBytes,
        Set<String> includeExtensions,
        DuplicateKeeperStrategy keeperStrategy
) {
    public static DuplicateScanOptions defaults() {
        return new DuplicateScanOptions(1L, Collections.emptySet(), DuplicateKeeperStrategy.NEWEST);
    }

    public static DuplicateScanOptions of(long minSizeBytes, Set<String> includeExtensions,
                                          DuplicateKeeperStrategy keeperStrategy) {
        long min = Math.max(1L, minSizeBytes);
        Set<String> ext = includeExtensions == null
                ? Collections.emptySet()
                : Collections.unmodifiableSet(new HashSet<>(includeExtensions));
        return new DuplicateScanOptions(min, ext, keeperStrategy == null ? DuplicateKeeperStrategy.NEWEST : keeperStrategy);
    }

    /**
     * Parses a user-typed filter like "*.jpg, png;*.mp4" into lowercase
     * extensions without dots/dots-wildcards. Empty/blank yields empty set (= all files).
     */
    public static Set<String> parseExtensionFilter(String text) {
        if (text == null || text.isBlank()) return Collections.emptySet();
        Set<String> out = new HashSet<>();
        for (String token : text.split("[,;\\s]+")) {
            String t = token.trim().toLowerCase(Locale.ROOT);
            if (t.isEmpty()) continue;
            // Strip leading "*." / "*"/"."
            while (t.startsWith("*")) t = t.substring(1);
            while (t.startsWith(".")) t = t.substring(1);
            if (t.isEmpty() || t.equals("*")) continue;
            // Keep only the extension part after last dot ("archive.tar.gz" -> "gz").
            int dot = t.lastIndexOf('.');
            if (dot >= 0) t = t.substring(dot + 1);
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /**
     * Formats an extension set back to a user-editable "*.ext, ..." string.
     */
    public static String formatExtensionFilter(Set<String> extensions) {
        if (extensions == null || extensions.isEmpty()) return "";
        java.util.List<String> sorted = new java.util.ArrayList<>(extensions);
        Collections.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for (String e : sorted) {
            if (sb.length() > 0) sb.append(", ");
            sb.append("*.").append(e);
        }
        return sb.toString();
    }

    public boolean matchesExtension(String fileName) {
        if (includeExtensions == null || includeExtensions.isEmpty()) return true;
        if (fileName == null) return false;
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) return false;
        return includeExtensions.contains(lower.substring(dot + 1));
    }
}
