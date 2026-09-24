package com.sbtools.ui;

import com.sbtools.util.UiText;
import javafx.scene.control.TableColumn;

public final class UiColumn {

    private UiColumn() {
    }

    public static <S, T> TableColumn<S, T> of(String title) {
        TableColumn<S, T> column = new TableColumn<>();
        I18n.column(column, UiText.label(title));
        return column;
    }
}
