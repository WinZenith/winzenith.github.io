package com.sbtools.cleaner;

import com.sbtools.i18n.Messages;
import javafx.application.Platform;
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
        public String getDisplayText() { return Messages.get(displayText); }
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
        applyLanguage();
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

    /** Rewrites app-authored captions after a language change. Sizes stay as scanned. */
    public void applyLanguage() {
        categoryName.set(category.getDisplayName());
        description.set(category.getDescription());
        if (errorMessage == null || errorMessage.isBlank()) {
            statusText.set(scanStatus.getDisplayText());
        }
        if ("Pending...".equals(sizeOrCountText.get())) {
            sizeOrCountText.set(Messages.get("Pending..."));
        }
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
        if (isFxThread()) {
            this.sizeOrCountText.set(text);
        } else if (!runLaterSafe(() -> this.sizeOrCountText.set(text))) {
            this.sizeOrCountText.set(text);
        }
    }

    public StringProperty statusTextProperty() {
        return statusText;
    }

    public void setStatusText(String text) {
        if (isFxThread()) {
            this.statusText.set(text);
        } else if (!runLaterSafe(() -> this.statusText.set(text))) {
            this.statusText.set(text);
        }
    }

    public LongProperty scanDurationMsProperty() {
        return scanDurationMs;
    }

    public long getScanDurationMs() {
        return scanDurationMs.get();
    }

    public void setScanDurationMs(long ms) {
        if (isFxThread()) {
            this.scanDurationMs.set(ms);
        } else if (!runLaterSafe(() -> this.scanDurationMs.set(ms))) {
            this.scanDurationMs.set(ms);
        }
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
        Runnable update = () -> this.statusText.set(status.getDisplayText());
        if (isFxThread()) {
            update.run();
        } else if (!runLaterSafe(update)) {
            update.run();
        }
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    /**
     * Toolkit-safe FX-thread check. {@code Platform.isFxApplicationThread()}
     * may report false (or throw) when the toolkit is not initialized
     * (headless/service/test use) — callers then fall back to direct sets.
     */
    private static boolean isFxThread() {
        try {
            return Platform.isFxApplicationThread();
        } catch (IllegalStateException notStarted) {
            return false;
        }
    }

    /**
     * Enqueues {@code update} on the FX thread. Returns false when the
     * toolkit is not initialized so callers can apply the update directly
     * instead of failing the whole scan/clean operation.
     */
    private static boolean runLaterSafe(Runnable update) {
        try {
            Platform.runLater(update);
            return true;
        } catch (IllegalStateException notStarted) {
            return false;
        }
    }
}
