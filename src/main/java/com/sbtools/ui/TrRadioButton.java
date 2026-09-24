package com.sbtools.ui;

import javafx.scene.control.RadioButton;

/** Radio button whose caption is an English source string and follows the selected language. */
public class TrRadioButton extends RadioButton {

    public TrRadioButton() {
        I18n.install(this, false);
    }

    public TrRadioButton(String text) {
        this();
        setText(text);
    }
}
