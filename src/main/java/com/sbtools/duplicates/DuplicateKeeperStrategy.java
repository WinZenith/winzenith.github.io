package com.sbtools.duplicates;

/**
 * Keeper selection strategy for duplicate groups.
 * <p>
 * The {@link DuplicateSafety#keeperRank(Path)} (non-system drive preferred)
 * always stays primary for safety. The strategy only breaks ties within the
 * same rank, then lexicographic path order is the final deterministic tie-break.
 */
public enum DuplicateKeeperStrategy {
    NEWEST("Newest (default)"),
    OLDEST("Oldest"),
    SHORTEST_PATH("Shortest path");

    private final String displayName;

    DuplicateKeeperStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static DuplicateKeeperStrategy fromString(String s) {
        if (s == null || s.isBlank()) return NEWEST;
        String t = s.trim();
        for (DuplicateKeeperStrategy v : values()) {
            if (v.name().equalsIgnoreCase(t)) return v;
        }
        // Accept display-name prefixes for persisted UI values.
        String lower = t.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("oldest")) return OLDEST;
        if (lower.startsWith("shortest")) return SHORTEST_PATH;
        return NEWEST;
    }
}
