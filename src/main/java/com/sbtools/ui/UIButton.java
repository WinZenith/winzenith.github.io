package com.sbtools.ui;

import javafx.scene.control.Button;

public class UIButton extends Button {

    public enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        SUCCESS,
        DANGER,
        SMALL
    }

    public UIButton(String text) {
        this(text, ButtonStyle.PRIMARY);
    }

    public UIButton(String text, ButtonStyle style) {
        super(text);
        getStyleClass().add("button");
        applyStyle(style);
    }

    private void applyStyle(ButtonStyle style) {
        switch (style) {
            case PRIMARY -> getStyleClass().add("accent");
            case SECONDARY -> getStyleClass().add("button-outlined");
            case SUCCESS -> getStyleClass().add("success");
            case DANGER -> getStyleClass().add("danger");
            case SMALL -> getStyleClass().add("small");
        }
    }

    public static UIButton primary(String text) {
        return new UIButton(text, ButtonStyle.PRIMARY);
    }

    public static UIButton secondary(String text) {
        return new UIButton(text, ButtonStyle.SECONDARY);
    }

    public static UIButton success(String text) {
        return new UIButton(text, ButtonStyle.SUCCESS);
    }

    public static UIButton danger(String text) {
        return new UIButton(text, ButtonStyle.DANGER);
    }

    public static UIButton small(String text) {
        return new UIButton(text, ButtonStyle.SMALL);
    }

    public void setStyleType(ButtonStyle style) {
        getStyleClass().removeAll("button", "accent", "button-outlined", "success", "danger", "small");
        getStyleClass().add("button");
        applyStyle(style);
    }
}
