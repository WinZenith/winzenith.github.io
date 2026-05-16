package com.basicsdriverupdate.drivers.model;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class DriverRow {

    private final InstalledDriver installed;
    private final StringProperty deviceName = new SimpleStringProperty();
    private final StringProperty currentVersion = new SimpleStringProperty();
    private final StringProperty availableVersion = new SimpleStringProperty();
    private final StringProperty source = new SimpleStringProperty();
    private DriverUpdateCandidate candidate;

    public DriverRow(InstalledDriver installed) {
        this.installed = installed;
        deviceName.set(installed.friendlyName());
        currentVersion.set(installed.driverVersion() != null ? installed.driverVersion() : "—");
        availableVersion.set("—");
        source.set("—");
    }

    public InstalledDriver installed() {
        return installed;
    }

    public DriverUpdateCandidate candidate() {
        return candidate;
    }

    public void setCandidate(DriverUpdateCandidate candidate) {
        this.candidate = candidate;
        if (candidate == null) {
            availableVersion.set("—");
            source.set("—");
        } else {
            availableVersion.set(candidate.availableVersion());
            source.set(candidate.source());
        }
    }

    public boolean hasUpdate() {
        return candidate != null;
    }

    public StringProperty deviceNameProperty() {
        return deviceName;
    }

    public StringProperty currentVersionProperty() {
        return currentVersion;
    }

    public StringProperty availableVersionProperty() {
        return availableVersion;
    }

    public StringProperty sourceProperty() {
        return source;
    }
}
