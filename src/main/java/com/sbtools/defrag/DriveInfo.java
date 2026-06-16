package com.sbtools.defrag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

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

    private static String formatFragmentSize(long f) {
        if (f < 1024) return f + " B";
        if (f < 1024 * 1024) return String.format("%.1f KB", f / 1024.0);
        if (f < 1024L * 1024 * 1024) return String.format("%.1f MB", f / (1024.0 * 1024));
        return String.format("%.1f GB", f / (1024.0 * 1024 * 1024));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        if (bytes < 1024L * 1024 * 1024 * 1024) return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
        return String.format("%.1f TB", bytes / (1024.0 * 1024 * 1024 * 1024));
    }

}
