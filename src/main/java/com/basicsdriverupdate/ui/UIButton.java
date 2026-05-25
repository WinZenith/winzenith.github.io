package com.basicsdriverupdate.ui;

import javafx.scene.control.Button;

/**
 * Modern styled button component with predefined styles.
 */
public class UIButton extends Button {

    public enum ButtonStyle {
        PRIMARY,      // Blue primary button
        SECONDARY,    // Gray secondary button
        SUCCESS,      // Green success button
        DANGER,       // Red danger button
        SMALL         // Compact small button
    }

    public UIButton(String text) {
        this(text, ButtonStyle.PRIMARY);
    }

    public UIButton(String text, ButtonStyle style) {
        super(text);
        applyStyle(style);
    }

    private void applyStyle(ButtonStyle style) {
        switch (style) {
            case PRIMARY -> getStyleClass().add("button"); // Default from CSS
            case SECONDARY -> getStyleClass().addAll("button", "secondary");
            case SUCCESS -> getStyleClass().addAll("button", "success");
            case DANGER -> getStyleClass().addAll("button", "danger");
            case SMALL -> {
                getStyleClass().add("button");
                setStyle("-fx-padding: 6 12;");
            }
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
}
