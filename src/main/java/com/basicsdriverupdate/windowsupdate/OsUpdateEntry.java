package com.basicsdriverupdate.windowsupdate;

public record OsUpdateEntry(
        String updateId,
        String title,
        String kbArticle,
        long sizeBytes,
        String importance,
        boolean rebootRequired,
        boolean selected
) {
    public String sizeDisplay() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.1f KB", sizeBytes / 1024.0);
        }
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024.0));
    }
}
