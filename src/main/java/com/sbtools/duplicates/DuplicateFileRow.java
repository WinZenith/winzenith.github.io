package com.sbtools.duplicates;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.ArrayList;
import java.util.List;

public class DuplicateFileRow {

    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty fullPath = new SimpleStringProperty();
    private final LongProperty fileSize = new SimpleLongProperty();
    private final StringProperty checksumSha256 = new SimpleStringProperty();
    private final IntegerProperty totalDuplicates = new SimpleIntegerProperty(1);
    private List<String> deletablePaths;

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumSha256) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumSha256.set(checksumSha256);
        this.deletablePaths = new ArrayList<>();
    }

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumSha256,
                            int totalDuplicates, List<String> deletablePaths) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumSha256.set(checksumSha256);
        this.totalDuplicates.set(totalDuplicates);
        this.deletablePaths = deletablePaths;
    }

    public BooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean selected) { this.selected.set(selected); }

    public StringProperty fileNameProperty() { return fileName; }
    public String getFileName() { return fileName.get(); }

    public StringProperty fullPathProperty() { return fullPath; }
    public String getFullPath() { return fullPath.get(); }

    public LongProperty fileSizeProperty() { return fileSize; }
    public long getFileSize() { return fileSize.get(); }

    public StringProperty checksumSha256Property() { return checksumSha256; }
    public String getChecksumSha256() { return checksumSha256.get(); }

    public IntegerProperty totalDuplicatesProperty() { return totalDuplicates; }
    public int getTotalDuplicates() { return totalDuplicates.get(); }

    public List<String> getDeletablePaths() { return deletablePaths; }
}
