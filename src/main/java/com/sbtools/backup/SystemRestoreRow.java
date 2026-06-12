package com.sbtools.backup;

import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class SystemRestoreRow {

    private final int sequenceNumber;
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final StringProperty description = new SimpleStringProperty();
    private final StringProperty creationTime = new SimpleStringProperty();
    private final StringProperty eventType = new SimpleStringProperty();

    public SystemRestoreRow(int sequenceNumber, String description, String creationTime, int eventTypeCode) {
        this.sequenceNumber = sequenceNumber;
        this.description.set(description != null ? description : "");
        this.creationTime.set(creationTime != null ? creationTime : "");
        this.eventType.set(formatEventType(eventTypeCode));
    }

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public StringProperty descriptionProperty() {
        return description;
    }

    public StringProperty creationTimeProperty() {
        return creationTime;
    }

    public StringProperty eventTypeProperty() {
        return eventType;
    }

    public int sequenceNumber() {
        return sequenceNumber;
    }

    static String formatEventType(int code) {
        return switch (code) {
            case 0 -> "Application Install";
            case 1 -> "Application Uninstall";
            case 10 -> "Driver Install";
            case 12 -> "Modify Settings";
            case 13 -> "Cancelled Operation";
            case 100 -> "System Checkpoint";
            case 101 -> "Manual";
            case 102 -> "Restore Operation";
            default -> "Unknown (" + code + ")";
        };
    }
}
