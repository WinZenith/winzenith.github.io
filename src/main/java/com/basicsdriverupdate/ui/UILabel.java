package com.basicsdriverupdate.ui;

import javafx.scene.control.Label;

/**
 * Modern styled label component with predefined styles.
 */
public class UILabel extends Label {

    public enum LabelStyle {
        BODY,           // Regular body text
        SECONDARY,      // Secondary/muted text
        HEADER,         // Large header text
        SECTION_TITLE,  // Section title
        STATUS_SUCCESS, // Success status
        STATUS_WARNING, // Warning status
        STATUS_DANGER,  // Error/danger status
        STATUS_INFO     // Info status
    }

    public UILabel(String text) {
        this(text, LabelStyle.BODY);
    }

    public UILabel(String text, LabelStyle style) {
        super(text);
        applyStyle(style);
    }

    private void applyStyle(LabelStyle style) {
        switch (style) {
            case BODY -> getStyleClass().add("label");
            case SECONDARY -> getStyleClass().addAll("label", "secondary");
            case HEADER -> getStyleClass().addAll("label", "header");
            case SECTION_TITLE -> getStyleClass().addAll("label", "section-title");
            case STATUS_SUCCESS -> getStyleClass().addAll("label", "status-success");
            case STATUS_WARNING -> getStyleClass().addAll("label", "status-warning");
            case STATUS_DANGER -> getStyleClass().addAll("label", "status-danger");
            case STATUS_INFO -> getStyleClass().addAll("label", "status-info");
        }
    }

    public static UILabel header(String text) {
        return new UILabel(text, LabelStyle.HEADER);
    }

    public static UILabel sectionTitle(String text) {
        return new UILabel(text, LabelStyle.SECTION_TITLE);
    }

    public static UILabel secondary(String text) {
        return new UILabel(text, LabelStyle.SECONDARY);
    }

    public static UILabel statusSuccess(String text) {
        return new UILabel(text, LabelStyle.STATUS_SUCCESS);
    }

    public static UILabel statusWarning(String text) {
        return new UILabel(text, LabelStyle.STATUS_WARNING);
    }

    public static UILabel statusDanger(String text) {
        return new UILabel(text, LabelStyle.STATUS_DANGER);
    }

    public static UILabel statusInfo(String text) {
        return new UILabel(text, LabelStyle.STATUS_INFO);
    }
}
