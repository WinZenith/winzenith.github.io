package com.basicsdriverupdate.ui;

import javafx.geometry.Insets;
import javafx.scene.layout.VBox;

/**
 * Consistent container component for sections with modern spacing.
 */
public class UISectionContainer extends VBox {

    public UISectionContainer(double spacing) {
        super(spacing);
        setPadding(new Insets(12, 16, 12, 16));
    }

    public UISectionContainer() {
        this(8);
    }

    public static UISectionContainer create() {
        return new UISectionContainer();
    }

    public static UISectionContainer compact() {
        return new UISectionContainer(4);
    }

    public static UISectionContainer spacious() {
        return new UISectionContainer(16);
    }
}
