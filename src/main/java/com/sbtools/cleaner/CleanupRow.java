package com.sbtools.cleaner;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class CleanupRow {

    private final CleanupCategory category;
    private final BooleanProperty selected = new SimpleBooleanProperty(true);
    private final StringProperty categoryName = new SimpleStringProperty();
    private final StringProperty sizeOrCountText = new SimpleStringProperty("Pending...");
    private long totalBytes;
    private int itemCount;

    public CleanupRow(CleanupCategory category) {
        this.category = category;
        this.categoryName.set(category.getDisplayName());
    }

    public CleanupCategory getCategory() {
        return category;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean selected) {
        this.selected.set(selected);
    }

    public StringProperty categoryNameProperty() {
        return categoryName;
    }

    public StringProperty sizeOrCountTextProperty() {
        return sizeOrCountText;
    }

    public void setSizeOrCountText(String text) {
        this.sizeOrCountText.set(text);
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public int getItemCount() {
        return itemCount;
    }

    public void setItemCount(int itemCount) {
        this.itemCount = itemCount;
    }
}
