package com.sbtools.uninstaller;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;

public class LeftoverItem {
    private final BooleanProperty selected;
    private final String path;
    private final boolean registry;

    public LeftoverItem(String path, boolean registry) {
        this(path, registry, true);
    }

    public LeftoverItem(String path, boolean registry, boolean selected) {
        this.selected = new SimpleBooleanProperty(selected);
        this.path = path;
        this.registry = registry;
    }

    public BooleanProperty selectedProperty() {
        return selected;
    }

    public boolean isSelected() {
        return selected.get();
    }

    public String getPath() {
        return path;
    }

    public boolean isRegistry() {
        return registry;
    }
}
