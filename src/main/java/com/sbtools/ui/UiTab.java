package com.sbtools.ui;

import com.sbtools.util.UiText;
import javafx.scene.Node;
import javafx.scene.control.Tab;

public final class UiTab {

    private UiTab() {
    }

    public static Tab tab(String title) {
        return new Tab(UiText.label(title));
    }

    public static Tab tab(String title, Node content) {
        return new Tab(UiText.label(title), content);
    }
}
