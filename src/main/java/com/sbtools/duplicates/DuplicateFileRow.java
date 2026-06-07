package com.sbtools.duplicates;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.util.List;

public class DuplicateFileRow {

    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty fullPath = new SimpleStringProperty();
    private final LongProperty fileSize = new SimpleLongProperty();
    private final StringProperty checksumMd5 = new SimpleStringProperty();
    private final IntegerProperty totalDuplicates = new SimpleIntegerProperty(1);
    private List<String> deletablePaths;

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumMd5) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumMd5.set(checksumMd5);
    }

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumMd5,
                            int totalDuplicates, List<String> deletablePaths) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumMd5.set(checksumMd5);
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

    public StringProperty checksumMd5Property() { return checksumMd5; }
    public String getChecksumMd5() { return checksumMd5.get(); }

    public IntegerProperty totalDuplicatesProperty() { return totalDuplicates; }
    public int getTotalDuplicates() { return totalDuplicates.get(); }

    public List<String> getDeletablePaths() { return deletablePaths; }
}
