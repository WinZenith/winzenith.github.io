package com.sbtools.cleaner;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class CleanupRow {

    public enum ScanStatus {
        PENDING("Pending..."),
        SCANNING("Scanning..."),
        DONE("Done"),
        ERROR("Error"),
        CLEANING("Cleaning..."),
        CLEANED("Cleaned");

        private final String displayText;
        ScanStatus(String displayText) { this.displayText = displayText; }
        public String getDisplayText() { return displayText; }
    }

    private final CleanupCategory category;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final StringProperty categoryName = new SimpleStringProperty();
    private final StringProperty description = new SimpleStringProperty();
    private final StringProperty sizeOrCountText = new SimpleStringProperty("Pending...");
    private final StringProperty statusText = new SimpleStringProperty("Pending...");
    private final LongProperty scanDurationMs = new SimpleLongProperty(0);
    private volatile long totalBytes;
    private volatile int itemCount;
    private volatile ScanStatus scanStatus = ScanStatus.PENDING;
    private volatile String errorMessage;

    public CleanupRow(CleanupCategory category) {
        this.category = category;
        this.categoryName.set(category.getDisplayName());
        this.description.set(category.getDescription());
        this.statusText.set(ScanStatus.PENDING.getDisplayText());
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

    public StringProperty descriptionProperty() {
        return description;
    }

    public StringProperty sizeOrCountTextProperty() {
        return sizeOrCountText;
    }

    public void setSizeOrCountText(String text) {
        this.sizeOrCountText.set(text);
    }

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public void setStatusText(String text) {
        this.statusText.set(text);
    }

    public LongProperty scanDurationMsProperty() {
        return scanDurationMs;
    }

    public long getScanDurationMs() {
        return scanDurationMs.get();
    }

    public void setScanDurationMs(long ms) {
        this.scanDurationMs.set(ms);
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

    public ScanStatus getScanStatus() {
        return scanStatus;
    }

    public void setScanStatus(ScanStatus status) {
        this.scanStatus = status;
        this.statusText.set(status.getDisplayText());
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
