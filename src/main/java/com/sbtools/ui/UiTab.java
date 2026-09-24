package com.sbtools.ui;

import com.sbtools.util.UiText;
import javafx.scene.Node;
import javafx.scene.control.Tab;

public final class UiTab {

    private UiTab() {
    }

    public static Tab tab(String title) {
        Tab tab = new Tab();
        I18n.tab(tab, UiText.label(title));
        return tab;
    }

    public static Tab tab(String title, Node content) {
        Tab tab = new Tab();
        tab.setContent(content);
        I18n.tab(tab, UiText.label(title));
        return tab;
    }
}
