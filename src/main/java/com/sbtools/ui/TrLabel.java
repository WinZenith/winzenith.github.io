package com.sbtools.ui;

import javafx.scene.control.Label;

/** Label whose caption is an English source string and follows the selected language. */
public class TrLabel extends Label {

    public TrLabel() {
        I18n.install(this, false);
    }

    public TrLabel(String text) {
        this();
        setText(text);
    }
}
