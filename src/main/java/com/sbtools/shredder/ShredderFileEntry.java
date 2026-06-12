package com.sbtools.shredder;

import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class ShredderFileEntry {

    public enum Status {
        PENDING,
        DELETED,
        SCHEDULED_FOR_REBOOT,
        FAILED
    }

    private final StringProperty filePath = new SimpleStringProperty("");
    private final LongProperty sizeBytes = new SimpleLongProperty(0);
    private final StringProperty status = new SimpleStringProperty(Status.PENDING.name());

    public ShredderFileEntry() {}

    public ShredderFileEntry(String filePath, long sizeBytes) {
        this.filePath.set(filePath);
        this.sizeBytes.set(sizeBytes);
    }

    public String getFilePath() { return filePath.get(); }
    public StringProperty filePathProperty() { return filePath; }
    public void setFilePath(String v) { filePath.set(v); }

    public long getSizeBytes() { return sizeBytes.get(); }
    public LongProperty sizeBytesProperty() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes.set(v); }

    public String getStatus() { return status.get(); }
    public StringProperty statusProperty() { return status; }
    public void setStatus(String v) { status.set(v); }

    public Status getStatusEnum() {
        try {
            return Status.valueOf(status.get());
        } catch (Exception e) {
            return Status.PENDING;
        }
    }

    public void setStatusEnum(Status s) { status.set(s.name()); }

    public String getSizeFormatted() {
        long bytes = sizeBytes.get();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        if (bytes < 1024L * 1024 * 1024) return (bytes / (1024 * 1024)) + " MB";
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
