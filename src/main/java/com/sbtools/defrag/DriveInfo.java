package com.sbtools.defrag;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class DriveInfo {

    private final StringProperty driveLetter = new SimpleStringProperty("");
    private final StringProperty volumeLabel = new SimpleStringProperty("");
    private final StringProperty mediaType = new SimpleStringProperty("Unknown");
    private final StringProperty fileSystem = new SimpleStringProperty("");
    private final LongProperty sizeBytes = new SimpleLongProperty(0);
    private final LongProperty freeBytes = new SimpleLongProperty(0);
    private final LongProperty fragmentsFound = new SimpleLongProperty(0);
    private final LongProperty fragmentationPercent = new SimpleLongProperty(0);

    /* ── Extended analysis fields ── */
    private final LongProperty fragmentedFileCount = new SimpleLongProperty(0);
    private final LongProperty totalFileCount = new SimpleLongProperty(0);
    private final DoubleProperty averageFragmentsPerFile = new SimpleDoubleProperty(0);
    private final LongProperty mftSizeBytes = new SimpleLongProperty(0);
    private final LongProperty pageFileSizeBytes = new SimpleLongProperty(0);
    private final LongProperty hiberFileSizeBytes = new SimpleLongProperty(0);
    private final LongProperty swapFileSizeBytes = new SimpleLongProperty(0);
    private final LongProperty totalDirectories = new SimpleLongProperty(0);

    private final BooleanProperty summaryRow = new SimpleBooleanProperty(false);
    private final StringProperty summaryLabel = new SimpleStringProperty("");

    public DriveInfo() {}

    /* ── Standard fields ── */

    @JsonProperty("driveLetter")
    public String getDriveLetter() { return driveLetter.get(); }
    public StringProperty driveLetterProperty() { return driveLetter; }
    public void setDriveLetter(String v) { driveLetter.set(v); }

    @JsonProperty("volumeLabel")
    public String getVolumeLabel() { return volumeLabel.get(); }
    public StringProperty volumeLabelProperty() { return volumeLabel; }
    public void setVolumeLabel(String v) { volumeLabel.set(v); }

    @JsonProperty("mediaType")
    public String getMediaType() { return mediaType.get(); }
    public StringProperty mediaTypeProperty() { return mediaType; }
    public void setMediaType(String v) { mediaType.set(v); }

    @JsonProperty("fileSystem")
    public String getFileSystem() { return fileSystem.get(); }
    public StringProperty fileSystemProperty() { return fileSystem; }
    public void setFileSystem(String v) { fileSystem.set(v); }

    @JsonProperty("sizeBytes")
    public long getSizeBytes() { return sizeBytes.get(); }
    public LongProperty sizeBytesProperty() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes.set(v); }

    @JsonProperty("freeBytes")
    public long getFreeBytes() { return freeBytes.get(); }
    public LongProperty freeBytesProperty() { return freeBytes; }
    public void setFreeBytes(long v) { freeBytes.set(v); }

    public long getFragmentsFound() { return fragmentsFound.get(); }
    public LongProperty fragmentsFoundProperty() { return fragmentsFound; }
    public void setFragmentsFound(long v) { fragmentsFound.set(v); }

    public long getFragmentationPercent() { return fragmentationPercent.get(); }
    public LongProperty fragmentationPercentProperty() { return fragmentationPercent; }
    public void setFragmentationPercent(long v) { fragmentationPercent.set(v); }

    public boolean isSsd() { return "SSD".equalsIgnoreCase(mediaType.get()); }
    public boolean isHdd() { return "HDD".equalsIgnoreCase(mediaType.get()); }

    public long getUsedBytes() { return sizeBytes.get() - freeBytes.get(); }

    /* ── Extended analysis fields ── */

    @JsonProperty("fragmentedFileCount")
    public long getFragmentedFileCount() { return fragmentedFileCount.get(); }
    public LongProperty fragmentedFileCountProperty() { return fragmentedFileCount; }
    public void setFragmentedFileCount(long v) { fragmentedFileCount.set(v); }

    @JsonProperty("totalFileCount")
    public long getTotalFileCount() { return totalFileCount.get(); }
    public LongProperty totalFileCountProperty() { return totalFileCount; }
    public void setTotalFileCount(long v) { totalFileCount.set(v); }

    @JsonProperty("averageFragmentsPerFile")
    public double getAverageFragmentsPerFile() { return averageFragmentsPerFile.get(); }
    public DoubleProperty averageFragmentsPerFileProperty() { return averageFragmentsPerFile; }
    public void setAverageFragmentsPerFile(double v) { averageFragmentsPerFile.set(v); }

    @JsonProperty("mftSizeBytes")
    public long getMftSizeBytes() { return mftSizeBytes.get(); }
    public LongProperty mftSizeBytesProperty() { return mftSizeBytes; }
    public void setMftSizeBytes(long v) { mftSizeBytes.set(v); }

    @JsonProperty("pageFileSizeBytes")
    public long getPageFileSizeBytes() { return pageFileSizeBytes.get(); }
    public LongProperty pageFileSizeBytesProperty() { return pageFileSizeBytes; }
    public void setPageFileSizeBytes(long v) { pageFileSizeBytes.set(v); }

    @JsonProperty("hiberFileSizeBytes")
    public long getHiberFileSizeBytes() { return hiberFileSizeBytes.get(); }
    public LongProperty hiberFileSizeBytesProperty() { return hiberFileSizeBytes; }
    public void setHiberFileSizeBytes(long v) { hiberFileSizeBytes.set(v); }

    @JsonProperty("swapFileSizeBytes")
    public long getSwapFileSizeBytes() { return swapFileSizeBytes.get(); }
    public LongProperty swapFileSizeBytesProperty() { return swapFileSizeBytes; }
    public void setSwapFileSizeBytes(long v) { swapFileSizeBytes.set(v); }

    @JsonProperty("totalDirectories")
    public long getTotalDirectories() { return totalDirectories.get(); }
    public LongProperty totalDirectoriesProperty() { return totalDirectories; }
    public void setTotalDirectories(long v) { totalDirectories.set(v); }

    /* ── Summary row fields ── */

    @JsonIgnore
    public boolean isSummaryRow() { return summaryRow.get(); }
    public BooleanProperty summaryRowProperty() { return summaryRow; }
    public void setSummaryRow(boolean v) { summaryRow.set(v); }

    @JsonIgnore
    public String getSummaryLabel() { return summaryLabel.get(); }
    public StringProperty summaryLabelProperty() { return summaryLabel; }
    public void setSummaryLabel(String v) { summaryLabel.set(v); }

    /* ── Formatting utilities ── */

    public String getSizeFormatted() {
        return formatBytes(sizeBytes.get());
    }

    public String getFreeFormatted() {
        return formatBytes(freeBytes.get());
    }

    public String getFragmentsFormatted() {
        long f = fragmentsFound.get();
        return formatFragmentSize(f);
    }

    public static String formatFragmentSize(long f) {
        if (f < 1024) return f + " B";
        if (f < 1024 * 1024) return (f / 1024) + " KB";
        if (f < 1024L * 1024 * 1024) return (f / (1024 * 1024)) + " MB";
        return (f / (1024L * 1024 * 1024)) + " GB";
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

    /* ── Summary row factory ── */

    private static DriveInfo createSummaryRow(String label, String mediaTypeFilter, List<DriveInfo> drives) {
        DriveInfo summary = new DriveInfo();
        summary.setSummaryRow(true);
        summary.setSummaryLabel(label);
        summary.setDriveLetter(label);
        summary.setMediaType(mediaTypeFilter);

        long totalSize = 0;
        long totalFree = 0;
        long totalFragments = 0;
        long totalFragPct = 0;
        long totalFragFiles = 0;
        long totalFiles = 0;
        double totalAvgFrag = 0;
        long totalMft = 0;
        long totalPage = 0;
        long totalHiber = 0;
        long totalSwap = 0;
        long totalDirs = 0;
        int count = 0;

        for (DriveInfo d : drives) {
            if (!d.isSummaryRow() && d.getMediaType().equalsIgnoreCase(mediaTypeFilter)) {
                totalSize += d.getSizeBytes();
                totalFree += d.getFreeBytes();
                totalFragments += d.getFragmentsFound();
                totalFragPct += d.getFragmentationPercent();
                totalFragFiles += d.getFragmentedFileCount();
                totalFiles += d.getTotalFileCount();
                totalAvgFrag += d.getAverageFragmentsPerFile();
                totalMft += d.getMftSizeBytes();
                totalPage += d.getPageFileSizeBytes();
                totalHiber += d.getHiberFileSizeBytes();
                totalSwap += d.getSwapFileSizeBytes();
                totalDirs += d.getTotalDirectories();
                count++;
            }
        }

        if (count == 0) return null;

        summary.setSizeBytes(totalSize);
        summary.setFreeBytes(totalFree);
        summary.setFragmentsFound(totalFragments);
        summary.setFragmentationPercent(count > 0 ? totalFragPct / count : 0);
        summary.setFragmentedFileCount(totalFragFiles);
        summary.setTotalFileCount(totalFiles);
        summary.setAverageFragmentsPerFile(count > 0 ? totalAvgFrag / count : 0);
        summary.setMftSizeBytes(totalMft);
        summary.setPageFileSizeBytes(totalPage);
        summary.setHiberFileSizeBytes(totalHiber);
        summary.setSwapFileSizeBytes(totalSwap);
        summary.setTotalDirectories(totalDirs);
        summary.setVolumeLabel(count + " drive(s)");

        return summary;
    }

    public static DriveInfo createHddSummary(List<DriveInfo> drives) {
        DriveInfo s = createSummaryRow("HDD Summary", "HDD", drives);
        if (s != null) s.setMediaType("HDD");
        return s;
    }

    public static DriveInfo createSsdSummary(List<DriveInfo> drives) {
        DriveInfo s = createSummaryRow("SSD Summary", "SSD", drives);
        if (s != null) s.setMediaType("SSD");
        return s;
    }
}
