package com.sbtools.backup;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class SystemRestoreRow {

    private final int sequenceNumber;
    private final StringProperty description = new SimpleStringProperty();
    private final StringProperty creationTime = new SimpleStringProperty();
    private final StringProperty eventType = new SimpleStringProperty();

    public SystemRestoreRow(int sequenceNumber, String description, String creationTime, int eventTypeCode) {
        this.sequenceNumber = sequenceNumber;
        this.description.set(description != null ? description : "");
        this.creationTime.set(creationTime != null ? creationTime : "");
        this.eventType.set(formatEventType(eventTypeCode));
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

    // WMI SystemRestore.EventType codes (BEGIN/END_SYSTEM_CHANGE family).
    // NOTE: 0/1/10/12/13 are RestorePointType codes, a different property
    // the list script does not select — mapping them here mislabeled rows
    // (e.g. every manual point showed "Restore Operation").
    static String formatEventType(int code) {
        return switch (code) {
            case 100 -> "Begin system change";
            case 101 -> "End system change";
            case 102 -> "Begin nested change";
            case 103 -> "End nested change";
            default -> "Unknown (" + code + ")";
        };
    }
}
