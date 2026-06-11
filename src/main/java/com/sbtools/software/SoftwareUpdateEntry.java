package com.sbtools.software;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class SoftwareUpdateEntry {

    private final String id;
    private final String source;
    private final String updateId;
    private final long sizeBytes;
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty currentVersion = new SimpleStringProperty();
    private final StringProperty availableVersion = new SimpleStringProperty();
    private final BooleanProperty selected = new SimpleBooleanProperty(false);

    public SoftwareUpdateEntry(String id, String name, String currentVersion, String availableVersion) {
        this(id, name, currentVersion, availableVersion, "winget", null, 0);
    }

    public SoftwareUpdateEntry(String id, String name, String currentVersion, String availableVersion,
                               String source, String updateId, long sizeBytes) {
        this.id = id;
        this.source = source == null ? "winget" : source;
        this.updateId = updateId;
        this.sizeBytes = sizeBytes;
        this.name.set(name == null ? "" : name);
        this.currentVersion.set(currentVersion == null ? "" : currentVersion);
        this.availableVersion.set(availableVersion == null ? "" : availableVersion);
    }

    public String id() {
        return id;
    }

    public String source() {
        return source;
    }

    public String updateId() {
        return updateId;
    }

    public long sizeBytes() {
        return sizeBytes;
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

    public StringProperty sourceProperty() {
        return new SimpleStringProperty(source);
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

    public boolean isSelected() {
        return selected.get();
    }

    public void setSelected(boolean value) {
        selected.set(value);
    }
}
