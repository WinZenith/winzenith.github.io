package com.sbtools.browserext;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

public class BrowserExtensionRow {

    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final StringProperty browser = new SimpleStringProperty();
    private final StringProperty extensionId = new SimpleStringProperty();
    private final StringProperty name = new SimpleStringProperty();
    private final StringProperty version = new SimpleStringProperty();
    private final StringProperty description = new SimpleStringProperty();
    private final BooleanProperty enabled = new SimpleBooleanProperty();
    private final BooleanProperty ignored = new SimpleBooleanProperty(false);
    private final StringProperty path = new SimpleStringProperty();
    private final StringProperty installDate = new SimpleStringProperty();
    private final StringProperty permissions = new SimpleStringProperty();

    public BrowserExtensionRow(String browser, String extensionId, String name, String version,
                                String description, boolean enabled, String path,
                                String installDate, String permissions) {
        this.browser.set(browser);
        this.extensionId.set(extensionId);
        this.name.set(name);
        this.version.set(version);
        this.description.set(description);
        this.enabled.set(enabled);
        this.path.set(path);
        this.installDate.set(installDate);
        this.permissions.set(permissions);
    }

    public BooleanProperty selectedProperty() { return selected; }
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean selected) { this.selected.set(selected); }

    public StringProperty browserProperty() { return browser; }
    public String getBrowser() { return browser.get(); }

    public StringProperty extensionIdProperty() { return extensionId; }
    public String getExtensionId() { return extensionId.get(); }

    public StringProperty nameProperty() { return name; }
    public String getName() { return name.get(); }

    public StringProperty versionProperty() { return version; }
    public String getVersion() { return version.get(); }

    public StringProperty descriptionProperty() { return description; }
    public String getDescription() { return description.get(); }

    public BooleanProperty enabledProperty() { return enabled; }
    public boolean isEnabled() { return enabled.get(); }
    public void setEnabled(boolean enabled) { this.enabled.set(enabled); }

    public BooleanProperty ignoredProperty() { return ignored; }
    public boolean isIgnored() { return ignored.get(); }
    public void setIgnored(boolean ignored) { this.ignored.set(ignored); }

    public StringProperty pathProperty() { return path; }
    public String getPath() { return path.get(); }

    public StringProperty installDateProperty() { return installDate; }
    public String getInstallDate() { return installDate.get(); }

    public StringProperty permissionsProperty() { return permissions; }
    public String getPermissions() { return permissions.get(); }
}
