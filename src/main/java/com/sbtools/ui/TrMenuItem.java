package com.sbtools.ui;

import javafx.scene.control.MenuItem;

/** Menu item whose caption is an English source string and follows the selected language. */
public class TrMenuItem extends MenuItem {

    public TrMenuItem() {
        I18n.installMenu(this);
    }

    public TrMenuItem(String text) {
        this();
        setText(text);
    }
}
