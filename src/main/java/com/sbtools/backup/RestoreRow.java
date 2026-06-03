package com.sbtools.backup;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public class RestoreRow {

    private final DriverBackupEntry entry;
    private final StringProperty deviceName = new SimpleStringProperty();
    private final StringProperty version = new SimpleStringProperty();
    private final StringProperty backedUpAt = new SimpleStringProperty();

    public RestoreRow(DriverBackupEntry entry) {
        this.entry = entry;
        deviceName.set(entry.friendlyName() != null && !entry.friendlyName().isBlank()
                ? entry.friendlyName() : entry.deviceId());
        version.set(entry.version() != null ? entry.version() : "—");
        backedUpAt.set(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault())
                .format(entry.createdAt()));
    }

    public DriverBackupEntry entry() {
        return entry;
    }

    public StringProperty deviceNameProperty() {
        return deviceName;
    }

    public StringProperty versionProperty() {
        return version;
    }

    public StringProperty backedUpAtProperty() {
        return backedUpAt;
    }
}
