package com.sbtools.software;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class SoftwareUpdateEntry {

    private final String id;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty currentVersion = new SimpleStringProperty();
    private final StringProperty availableVersion = new SimpleStringProperty();
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public SoftwareUpdateEntry(String id, String name, String currentVersion, String availableVersion) {
        this.id = id;
        this.name.set(name == null ? "" : name);
        this.currentVersion.set(currentVersion == null ? "" : currentVersion);
        this.availableVersion.set(availableVersion == null ? "" : availableVersion);
    }

    public String id() {
        return id;
    }

    public StringProperty nameProperty() {
        return name;
    }

    public StringProperty currentVersionProperty() {
        return currentVersion;
    }

    public StringProperty availableVersionProperty() {
        return availableVersion;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public String getName() {
        return name.get();
    }

    public String getCurrentVersion() {
        return currentVersion.get();
    }

    public String getAvailableVersion() {
        return availableVersion.get();
    }
}
