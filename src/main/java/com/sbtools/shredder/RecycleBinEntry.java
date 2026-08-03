package com.sbtools.shredder;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RecycleBinEntry {

    private final StringProperty name = new SimpleStringProperty("");
    private final StringProperty originalPath = new SimpleStringProperty("");
    private final StringProperty recyclePath = new SimpleStringProperty("");
    private final LongProperty sizeBytes = new SimpleLongProperty(0);
    private final StringProperty deleteDate = new SimpleStringProperty("");

    public RecycleBinEntry() {}

    @JsonProperty("name")
    public String getName() { return name.get(); }
    public StringProperty nameProperty() { return name; }
    public void setName(String v) { name.set(v); }

    @JsonProperty("originalPath")
    public String getOriginalPath() { return originalPath.get(); }
    public StringProperty originalPathProperty() { return originalPath; }
    public void setOriginalPath(String v) { originalPath.set(v); }

    @JsonProperty("recyclePath")
    public String getRecyclePath() { return recyclePath.get(); }
    public StringProperty recyclePathProperty() { return recyclePath; }
    public void setRecyclePath(String v) { recyclePath.set(v); }

    @JsonProperty("sizeBytes")
    public long getSizeBytes() { return sizeBytes.get(); }
    public LongProperty sizeBytesProperty() { return sizeBytes; }
    public void setSizeBytes(long v) { sizeBytes.set(v); }

    @JsonProperty("deleteDate")
    public String getDeleteDate() { return deleteDate.get(); }
    public StringProperty deleteDateProperty() { return deleteDate; }
    public void setDeleteDate(String v) { deleteDate.set(v); }

    public String getSizeFormatted() {
        long bytes = sizeBytes.get();
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
