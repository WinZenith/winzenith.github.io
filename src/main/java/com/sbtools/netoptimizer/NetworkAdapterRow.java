package com.sbtools.netoptimizer;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class NetworkAdapterRow {

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty description = new SimpleStringProperty();
    private final StringProperty status = new SimpleStringProperty();
    private final StringProperty linkSpeed = new SimpleStringProperty();
    private final StringProperty macAddress = new SimpleStringProperty();
    private final StringProperty ipAddress = new SimpleStringProperty();
    private final BooleanProperty enabled = new SimpleBooleanProperty();

    public NetworkAdapterRow(String name, String description, String status, String linkSpeed,
                              String macAddress, String ipAddress, boolean enabled) {
        this.name.set(name);
        this.description.set(description);
        this.status.set(status);
        this.linkSpeed.set(linkSpeed);
        this.macAddress.set(macAddress);
        this.ipAddress.set(ipAddress);
        this.enabled.set(enabled);
    }

    public BooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean selected) { this.selected.set(selected); }

    public StringProperty nameProperty() { return name; }
    public String getName() { return name.get(); }

    public StringProperty descriptionProperty() { return description; }
    public String getDescription() { return description.get(); }

    public StringProperty statusProperty() { return status; }
    public String getStatus() { return status.get(); }

    public StringProperty linkSpeedProperty() { return linkSpeed; }
    public String getLinkSpeed() { return linkSpeed.get(); }

    public StringProperty macAddressProperty() { return macAddress; }
    public String getMacAddress() { return macAddress.get(); }

    public StringProperty ipAddressProperty() { return ipAddress; }
    public String getIpAddress() { return ipAddress.get(); }

    public BooleanProperty enabledProperty() { return enabled; }
    public boolean isEnabled() { return enabled.get(); }
    public void setEnabled(boolean enabled) { this.enabled.set(enabled); }
}
