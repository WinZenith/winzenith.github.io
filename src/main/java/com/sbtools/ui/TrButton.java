package com.sbtools.ui;

import javafx.scene.control.Button;

/** Button whose caption is an English source string and follows the selected language. */
public class TrButton extends Button {

    public TrButton() {
        I18n.install(this, false);
    }

    public TrButton(String text) {
        this();
        setText(text);
    }
}
