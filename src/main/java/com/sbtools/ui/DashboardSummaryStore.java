package com.sbtools.ui;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sbtools.util.AppLogger;
import com.sbtools.util.AppPaths;
import com.sbtools.util.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists the last successful Dashboard scan snapshot so the Dashboard can
 * show meaningful numbers immediately on startup (portable-aware).
 *
 * <p>Read-only overview data only: counts, sizes, per-category rows and up to
 * 5 detail lines per row. Never stores credentials, paths beyond display text,
 * or anything needed for mutation. Failures are silent (warning log) so a
 * corrupt snapshot can never break the Dashboard.
 */
public final class DashboardSummaryStore {

    private static final String FILE_NAME = "dashboard-last.json";
    private static final int MAX_ROWS = 60;
    private static final int MAX_DETAILS_PER_ROW = 5;

    private DashboardSummaryStore() {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IssueSnapshot(
            String category,
            String countText,
            String sizeText,
            String source,
            long sizeBytes,
            int count,
            boolean error,
            List<String> details) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(
            long scannedEpochMilli,
            List<IssueSnapshot> issues) {
    }

    public static Path path() {
        try {
            Path portable = AppPaths.portableBaseDir();
            if (portable != null) return portable.resolve(FILE_NAME);
        } catch (Exception ignored) {
        }
        try {
            return AppPaths.localAppData().resolve(FILE_NAME);
        } catch (Exception ignored) {
        }
        return Path.of(System.getProperty("user.home"), ".winzenith", FILE_NAME);
    }

    public static void save(Instant scannedAt, List<DashboardTabView.IssueCategory> issues) {
        if (scannedAt == null || issues == null) return;
        try {
            List<IssueSnapshot> rows = new ArrayList<>();
            int n = Math.min(issues.size(), MAX_ROWS);
            for (int i = 0; i < n; i++) {
                DashboardTabView.IssueCategory ic = issues.get(i);
                if (ic == null) continue;
                List<String> details = ic.getDetails();
                List<String> copy = details == null ? List.of()
                        : details.stream()
                                .filter(s -> s != null && !s.isBlank())
                                .limit(MAX_DETAILS_PER_ROW)
                                .toList();
                rows.add(new IssueSnapshot(
                        safe(ic.categoryProperty().get()),
                        safe(ic.countTextProperty().get()),
                        safe(ic.sizeTextProperty().get()),
                        safe(ic.sourceProperty().get()),
                        ic.getSizeBytes(),
                        ic.getCount(),
                        ic.isError(),
                        copy));
            }
            Snapshot snap = new Snapshot(scannedAt.toEpochMilli(), rows);
            Path p = path();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling("." + p.getFileName() + ".tmp");
            JsonMapper.mapper().writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), snap);
            try {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException ex) {
                Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                try {
                    Files.deleteIfExists(tmp);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            AppLogger.warning("Dashboard snapshot save failed: " + e.getMessage());
        }
    }

    public static Snapshot load() {
        try {
            Path p = path();
            if (p == null || !Files.isRegularFile(p)) return null;
            if (Files.size(p) <= 0 || Files.size(p) > 512 * 1024) return null;
            Snapshot snap = JsonMapper.mapper().readValue(p.toFile(), Snapshot.class);
            if (snap == null || snap.issues() == null) return null;
            return snap;
        } catch (Exception e) {
            AppLogger.warning("Dashboard snapshot load failed: " + e.getMessage());
            return null;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
