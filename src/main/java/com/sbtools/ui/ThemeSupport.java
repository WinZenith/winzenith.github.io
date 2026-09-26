package com.sbtools.ui;

import atlantafx.base.theme.Dracula;
import atlantafx.base.theme.NordLight;
import javafx.application.Application;
import javafx.scene.Node;

public final class ThemeSupport {

    private ThemeSupport() {
    }

    public static void apply(AppTheme theme, Node styledRoot) {
        AppTheme resolved = theme == null ? AppTheme.DRACULA : theme;
        Application.setUserAgentStylesheet(switch (resolved) {
            case DRACULA -> new Dracula().getUserAgentStylesheet();
            case NORD_LIGHT -> new NordLight().getUserAgentStylesheet();
        });
        if (styledRoot != null) {
            styledRoot.getStyleClass().removeAll("theme-dark", "theme-light");
            styledRoot.getStyleClass().add(resolved.isLight() ? "theme-light" : "theme-dark");
        }
    }
}
