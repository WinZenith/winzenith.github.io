package com.sbtools.ui;

import com.sbtools.defrag.DriveInfo;

import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public class DefragVisualization {

    public static final int GRID_COLS = 50;
    public static final int GRID_ROWS = 16;
    public static final int GRID_TOTAL_CELLS = GRID_COLS * GRID_ROWS;

    private static final Color COL_SYS    = Color.rgb(139, 233, 253);
    private static final Color COL_DIR    = Color.rgb(189, 147, 249);
    private static final Color COL_FREQ   = Color.rgb(241, 250, 140);
    private static final Color COL_NORMAL = Color.rgb(80, 250, 123);
    private static final Color COL_FRAG   = Color.rgb(255, 85, 85);
    private static final Color COL_PAGE   = Color.rgb(255, 184, 108);
    private static final Color COL_FREE   = Color.rgb(68, 71, 90);
    private static final Color COL_BG     = Color.rgb(40, 42, 54);

    public record RenderResult(
            String analysisText,
            String fragCountText,
            String fragPercentText,
            boolean fragIsHigh
    ) {}

    public static RenderResult render(Canvas canvas, DriveInfo drive) {
        double canvasW = canvas.getWidth();
        double canvasH = canvas.getHeight();
        if (canvasW <= 0 || canvasH <= 0) {
            return new RenderResult("", "", "", false);
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvasW, canvasH);

        long used = drive.getUsedBytes();
        long total = drive.getSizeBytes();
        double usedRatio = total > 0 ? (double) used / total : 0;

        long mftBytes = drive.getMftSizeBytes();
        long pageBytes = drive.getPageFileSizeBytes() + drive.getHiberFileSizeBytes() + drive.getSwapFileSizeBytes();
        long fragBytes = drive.getFragmentsFound();
        long dirBytes = drive.getTotalDirectories() * 4096L;

        long knownBytes = mftBytes + pageBytes + dirBytes + fragBytes;
        long remainingUsed = Math.max(0, used - knownBytes);
        long frequentlyUsedBytes = (long) (remainingUsed * 0.10);
        long normalBytes = Math.max(0, remainingUsed - frequentlyUsedBytes);

        int totalCells = GRID_TOTAL_CELLS;
        int usedCells = (int) Math.round(totalCells * usedRatio);

        int mftCells  = bytesToCells(mftBytes, total, totalCells);
        int pageCells = bytesToCells(pageBytes, total, totalCells);
        int dirCells  = bytesToCells(dirBytes, total, totalCells);
        int fragCells = bytesToCells(fragBytes, total, totalCells);
        int freqCells = bytesToCells(frequentlyUsedBytes, total, totalCells);

        int totalSpecial = mftCells + pageCells + dirCells + fragCells + freqCells;
        if (totalSpecial > usedCells && totalSpecial > 0) {
            double scale = (double) usedCells / totalSpecial;
            mftCells  = Math.max(0, (int) (mftCells * scale));
            pageCells = Math.max(0, (int) (pageCells * scale));
            dirCells  = Math.max(0, (int) (dirCells * scale));
            fragCells = Math.max(0, (int) (fragCells * scale));
            freqCells = Math.max(0, (int) (freqCells * scale));
        }
        int normalCells = Math.max(0, usedCells - (mftCells + pageCells + dirCells + fragCells + freqCells));

        Color[][] grid = buildGrid(mftCells, dirCells, pageCells, fragCells, freqCells, normalCells,
                drive.getDriveLetter());

        double cellW = canvasW / GRID_COLS;
        double cellH = canvasH / GRID_ROWS;

        gc.setStroke(COL_BG);
        gc.setLineWidth(1);
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                gc.setFill(grid[r][c]);
                gc.fillRect(c * cellW + 1, r * cellH + 1, cellW - 2, cellH - 2);
            }
        }

        renderSummaryBar(gc, canvasW, canvasH, mftCells, dirCells, pageCells, freqCells, normalCells, fragCells);

        String analysisText = "Analysis: " + drive.getDriveLetter() + " (" + drive.getVolumeLabel() + ")"
                + "  \u2014  " + drive.getSizeFormatted() + " total, "
                + formatBytes(used) + " used (" + (total > 0 ? (int)(used * 100 / total) : 0) + "%)";

        String fragCountText = "Fragments: " + drive.getFragmentsFormatted()
                + "  |  Fragmented files: " + drive.getFragmentedFileCount()
                + "  |  Total files: " + drive.getTotalFileCount();

        String fragPercentText = "Fragmentation: " + drive.getFragmentationPercent() + "%"
                + "  |  Avg fragments/file: " + String.format("%.1f", drive.getAverageFragmentsPerFile());

        boolean fragIsHigh = drive.getFragmentationPercent() > 20;

        return new RenderResult(analysisText, fragCountText, fragPercentText, fragIsHigh);
    }

    private static Color[][] buildGrid(int mftCells, int dirCells, int pageCells,
                                       int fragCells, int freqCells, int normalCells,
                                       String driveLetter) {
        Color[][] grid = new Color[GRID_ROWS][GRID_COLS];
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                grid[r][c] = COL_FREE;
            }
        }

        Random rng = new Random(driveLetter.hashCode());

        int placed = 0;
        for (int i = 0; i < mftCells && placed < GRID_TOTAL_CELLS; i++, placed++) {
            int r = placed % GRID_ROWS;
            int c = placed / GRID_ROWS;
            if (c < GRID_COLS) grid[r][c] = COL_SYS;
        }

        for (int i = 0; i < dirCells; i++) {
            int idx = placed + i;
            int r = idx % GRID_ROWS;
            int c = idx / GRID_ROWS;
            if (c < GRID_COLS && grid[r][c] == COL_FREE) grid[r][c] = COL_DIR;
        }
        placed += dirCells;

        for (int i = 0; i < pageCells; i++) {
            int idx = placed + i;
            int r = idx % GRID_ROWS;
            int c = idx / GRID_ROWS;
            if (c < GRID_COLS && grid[r][c] == COL_FREE) grid[r][c] = COL_PAGE;
        }
        placed += pageCells;

        for (int i = 0; i < normalCells; i++) {
            int idx = placed + i;
            int r = idx % GRID_ROWS;
            int c = idx / GRID_ROWS;
            if (c < GRID_COLS && grid[r][c] == COL_FREE) grid[r][c] = COL_NORMAL;
        }
        placed += normalCells;

        List<int[]> freePositions = new ArrayList<>();
        for (int r = 0; r < GRID_ROWS; r++) {
            for (int c = 0; c < GRID_COLS; c++) {
                if (grid[r][c] == COL_FREE) {
                    freePositions.add(new int[]{r, c});
                }
            }
        }
        Collections.shuffle(freePositions, rng);

        int freeIdx = 0;
        for (int i = 0; i < fragCells && freeIdx < freePositions.size(); i++) {
            int[] pos = freePositions.get(freeIdx++);
            grid[pos[0]][pos[1]] = COL_FRAG;
        }

        for (int i = 0; i < freqCells && freeIdx < freePositions.size(); i++) {
            int[] pos = freePositions.get(freeIdx++);
            grid[pos[0]][pos[1]] = COL_FREQ;
        }

        return grid;
    }

    private static void renderSummaryBar(GraphicsContext gc, double canvasW, double canvasH,
                                          int mftCells, int dirCells, int pageCells,
                                          int freqCells, int normalCells, int fragCells) {
        int barY = GRID_ROWS * (int) (canvasH / GRID_ROWS) + 8;
        int barH = 10;
        int barW = (int) canvasW;
        int barX = 0;

        int barSysW   = cellCountToBarWidth(mftCells, barW);
        int barDirW   = cellCountToBarWidth(dirCells, barW);
        int barPageW  = cellCountToBarWidth(pageCells, barW);
        int barFreqW  = cellCountToBarWidth(freqCells, barW);
        int barNormW  = cellCountToBarWidth(normalCells, barW);
        int barFragW  = cellCountToBarWidth(fragCells, barW);
        int barFreeW  = barW - barFragW - barFreqW - barSysW - barDirW - barPageW - barNormW;

        int barOffset = 0;
        gc.setFill(COL_SYS);  gc.fillRect(barX + barOffset, barY, Math.max(1, barSysW), barH);  barOffset += barSysW;
        gc.setFill(COL_DIR);  gc.fillRect(barX + barOffset, barY, Math.max(1, barDirW), barH);  barOffset += barDirW;
        gc.setFill(COL_PAGE); gc.fillRect(barX + barOffset, barY, Math.max(1, barPageW), barH); barOffset += barPageW;
        gc.setFill(COL_FREQ); gc.fillRect(barX + barOffset, barY, Math.max(1, barFreqW), barH); barOffset += barFreqW;
        gc.setFill(COL_NORMAL); gc.fillRect(barX + barOffset, barY, Math.max(1, barNormW), barH); barOffset += barNormW;
        gc.setFill(COL_FRAG); gc.fillRect(barX + barOffset, barY, Math.max(1, barFragW), barH); barOffset += barFragW;
        gc.setFill(COL_FREE); gc.fillRect(barX + barOffset, barY, Math.max(1, barFreeW), barH);

        gc.setStroke(COL_BG);
        gc.setLineWidth(1);
        gc.strokeRect(barX, barY, barW, barH);
    }

    private static int cellCountToBarWidth(int cells, int barWidth) {
        return (int) (barWidth * (double) cells / GRID_TOTAL_CELLS);
    }

    private static int bytesToCells(long bytes, long totalBytes, int totalCells) {
        if (totalBytes <= 0 || bytes <= 0) return 0;
        return Math.max(1, (int) Math.round((double) bytes / totalBytes * totalCells));
    }

    public static HBox createLegendItem(Color color, String label) {
        Label colorBox = new Label("  ");
        colorBox.setStyle(String.format("-fx-background-color: #%02x%02x%02x; "
                + "-fx-min-width: 12px; -fx-min-height: 12px; "
                + "-fx-max-width: 12px; -fx-max-height: 12px; "
                + "-fx-border-color: #6272a4; -fx-border-width: 1;",
                (int)(color.getRed()*255), (int)(color.getGreen()*255), (int)(color.getBlue()*255)));
        Label text = new Label(label);
        text.setStyle("-fx-text-fill: #f8f8f2; -fx-font-size: 11px;");
        HBox item = new HBox(4, colorBox, text);
        item.setAlignment(Pos.CENTER);
        return item;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }
}
