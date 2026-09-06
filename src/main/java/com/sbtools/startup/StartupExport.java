package com.sbtools.startup;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * CSV export helper for startup tables. Pure logic (no JavaFX, no I/O) so it
 * can be unit-tested. I/O (file chooser + write) stays in the UI layer.
 */
public final class StartupExport {

    private StartupExport() {
    }

    public static String toCsv(List<StartupItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Publisher,Location,Command,Status,Boot Impact (ms),Type,Service Start Type,Service State\n");
        if (items == null) {
            return sb.toString();
        }
        for (StartupItem it : items) {
            if (it == null) {
                continue;
            }
            StringJoiner row = new StringJoiner(",");
            row.add(csv(it.getName()));
            row.add(csv(it.getPublisher()));
            row.add(csv(it.getLocation()));
            row.add(csv(it.getPath()));
            row.add(csv(it.isEnabled() ? "Enabled" : "Disabled"));
            row.add(csv(formatMs(it.getEstimatedBootImpactMs())));
            row.add(csv(it.getType() == null ? "" : it.getType().name()));
            row.add(csv(it.getServiceStartType() == null ? "" : it.getServiceStartType()));
            row.add(csv(it.getServiceState() == null ? "" : it.getServiceState()));
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    public static String toCsvBackups(List<StartupService.StartupBackupEntry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("Name,Type,Backup Time,Original Location,Command,Enabled\n");
        if (entries == null) {
            return sb.toString();
        }
        java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (StartupService.StartupBackupEntry e : entries) {
            if (e == null) {
                continue;
            }
            StringJoiner row = new StringJoiner(",");
            row.add(csv(e.getName()));
            row.add(csv(e.getType()));
            row.add(csv(df.format(new java.util.Date(e.getBackupTime()))));
            row.add(csv(e.getLocation()));
            row.add(csv(e.getCommand()));
            row.add(csv(e.isEnabled() ? "Enabled" : "Disabled"));
            sb.append(row).append('\n');
        }
        return sb.toString();
    }

    /**
     * Case-insensitive substring match across the searchable fields.
     * Extracted from UI so filtering logic is unit-testable.
     */
    public static boolean matchesSearch(StartupItem item, String filter) {
        if (item == null) {
            return false;
        }
        if (filter == null || filter.isBlank()) {
            return true;
        }
        String lower = filter.toLowerCase(Locale.ROOT);
        return contains(item.getName(), lower)
                || contains(item.getPublisher(), lower)
                || contains(item.getPath(), lower)
                || contains(item.getLocation(), lower);
    }

    public static boolean matchesStatus(StartupItem item, StatusFilter filter) {
        if (filter == null || filter == StatusFilter.ALL) {
            return true;
        }
        if (item == null) {
            return false;
        }
        return filter == StatusFilter.ENABLED ? item.isEnabled() : !item.isEnabled();
    }

    public static boolean matchesImpact(StartupItem item, ImpactFilter filter) {
        if (filter == null || filter == ImpactFilter.ALL) {
            return true;
        }
        if (item == null) {
            return false;
        }
        double ms = item.getEstimatedBootImpactMs();
        String level = ms < 100 ? "LOW" : ms <= 300 ? "MEDIUM" : "HIGH";
        return filter.name().equals(level);
    }

    public enum StatusFilter {
        ALL, ENABLED, DISABLED
    }

    public enum ImpactFilter {
        ALL, HIGH, MEDIUM, LOW
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private static String formatMs(double ms) {
        if (Double.isNaN(ms) || Double.isInfinite(ms)) {
            return "0";
        }
        return String.valueOf(Math.round(ms));
    }

    private static String csv(String value) {
        if (value == null) {
            return "\"\"";
        }
        String escaped = value.replace("\"", "\"\"");
        // Flatten newlines so one item is always one CSV row.
        escaped = escaped.replace("\r", " ").replace("\n", " ");
        return "\"" + escaped + "\"";
    }
}
