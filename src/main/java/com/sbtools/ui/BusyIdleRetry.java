package com.sbtools.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.value.ChangeListener;

/** Runs an action when the shared busy flag becomes false (one-shot). */
final class BusyIdleRetry {

    private BusyIdleRetry() {
    }

    static void runWhenIdle(BooleanProperty busy, Runnable action) {
        if (busy == null || action == null) {
            return;
        }
        if (!busy.get()) {
            action.run();
            return;
        }
        ChangeListener<Boolean> listener = new ChangeListener<>() {
            @Override
            public void changed(javafx.beans.value.ObservableValue<? extends Boolean> obs, Boolean wasBusy, Boolean nowBusy) {
                if (Boolean.FALSE.equals(nowBusy)) {
                    busy.removeListener(this);
                    action.run();
                }
            }
        };
        busy.addListener(listener);
    }
}
