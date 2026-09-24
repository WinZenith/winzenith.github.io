package com.sbtools.ui;

import javafx.scene.control.CheckBox;

/** Check box whose caption is an English source string and follows the selected language. */
public class TrCheckBox extends CheckBox {

    public TrCheckBox() {
        I18n.install(this, false);
    }

    public TrCheckBox(String text) {
        this();
        setText(text);
    }
}
