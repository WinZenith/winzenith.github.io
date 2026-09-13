package com.sbtools.ui;

import com.sbtools.util.UiText;
import javafx.scene.control.TableColumn;

public final class UiColumn {

    private UiColumn() {
    }

    public static <S, T> TableColumn<S, T> of(String title) {
        return new TableColumn<>(UiText.label(title));
    }
}
