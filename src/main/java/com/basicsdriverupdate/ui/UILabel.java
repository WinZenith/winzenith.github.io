package com.basicsdriverupdate.ui;

import javafx.scene.control.Label;

public class UILabel extends Label {

    public enum LabelStyle {
        BODY,
        SECONDARY,
        HEADER,
        SECTION_TITLE,
        STATUS_SUCCESS,
        STATUS_WARNING,
        STATUS_DANGER,
        STATUS_INFO
    }

    public UILabel(String text) {
        this(text, LabelStyle.BODY);
    }

    public UILabel(String text, LabelStyle style) {
        super(text);
        getStyleClass().add("label");
        applyStyle(style);
    }

    private void applyStyle(LabelStyle style) {
        switch (style) {
            case BODY -> { }
            case SECONDARY -> getStyleClass().add("text-muted");
            case HEADER -> getStyleClass().addAll("large");
            case SECTION_TITLE -> getStyleClass().addAll("large");
            case STATUS_SUCCESS -> getStyleClass().add("success");
            case STATUS_WARNING -> getStyleClass().add("warning");
            case STATUS_DANGER -> getStyleClass().add("danger");
            case STATUS_INFO -> getStyleClass().add("accent");
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
