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

    // Safe default: nothing is selected for deletion until the user explicitly ticks it.
    // Mass-delete-by-default caused one-misclick data loss (see DuplicateFilesTabView).
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty fullPath = new SimpleStringProperty();
    private final LongProperty fileSize = new SimpleLongProperty();
    private final StringProperty checksumSha256 = new SimpleStringProperty();
    private final IntegerProperty totalDuplicates = new SimpleIntegerProperty(1);
    private List<String> deletablePaths;
    // Full group membership (keeper + all deletables) used to recompute the
    // keeper when the user changes keeper strategy without rescanning.
    // Null for rows built by legacy constructors — derived on demand.
    private List<String> allMemberPaths;

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumSha256) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumSha256.set(checksumSha256);
        this.deletablePaths = new ArrayList<>();
    }

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumSha256,
                            int totalDuplicates, List<String> deletablePaths) {
        this(fileName, fullPath, fileSize, checksumSha256, totalDuplicates, deletablePaths, null);
    }

    public DuplicateFileRow(String fileName, String fullPath, long fileSize, String checksumSha256,
                            int totalDuplicates, List<String> deletablePaths, List<String> allMemberPaths) {
        this.fileName.set(fileName);
        this.fullPath.set(fullPath);
        this.fileSize.set(fileSize);
        this.checksumSha256.set(checksumSha256);
        this.totalDuplicates.set(totalDuplicates);
        this.deletablePaths = deletablePaths;
        this.allMemberPaths = allMemberPaths;
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
    public void setTotalDuplicates(int v) { this.totalDuplicates.set(v); }

    public List<String> getDeletablePaths() { return deletablePaths; }
    public void setDeletablePaths(List<String> deletablePaths) { this.deletablePaths = deletablePaths; }

    /**
     * Full group membership (keeper + deletables). Falls back to deriving
     * from keeper + deletables for rows built before this field existed.
     */
    public List<String> getAllMemberPaths() {
        if (allMemberPaths != null && !allMemberPaths.isEmpty()) return allMemberPaths;
        List<String> derived = new ArrayList<>();
        if (getFullPath() != null) derived.add(getFullPath());
        if (deletablePaths != null) {
            for (String p : deletablePaths) {
                if (p != null && !derived.contains(p)) derived.add(p);
            }
        }
        return derived;
    }

    public void setAllMemberPaths(List<String> allMemberPaths) { this.allMemberPaths = allMemberPaths; }

    public void setFullPath(String v) { this.fullPath.set(v); }
    public void setFileName(String v) { this.fileName.set(v); }
}
