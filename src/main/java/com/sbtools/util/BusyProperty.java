package com.sbtools.util;

import javafx.beans.property.SimpleBooleanProperty;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Reference-counted busy flag that survives interleaved set(true)/set(false)
 * from multiple tabs sharing the same global property (B4 fix).
 * Each set(true) increments the counter, set(false) decrements; the
 * underlying boolean is true iff count > 0. This prevents one tab's finish
 * from clearing another tab's still-running busy state.
 */
public final class BusyProperty extends SimpleBooleanProperty {

    private final AtomicInteger count = new AtomicInteger(0);

    public BusyProperty() {
        super(false);
    }

    public BusyProperty(boolean initialValue) {
        super(initialValue);
        if (initialValue) count.set(1);
    }

    @Override
    public void set(boolean newValue) {
        if (newValue) {
            int c = count.incrementAndGet();
            if (c == 1) {
                super.set(true);
            } else {
                // already true — keep true, just bump count
                if (!super.get()) super.set(true);
            }
        } else {
            int c = count.decrementAndGet();
            if (c < 0) {
                count.set(0);
                c = 0;
            }
            if (c == 0) {
                super.set(false);
            }
            // else remain true
        }
    }

    @Override
    public void setValue(Boolean v) {
        set(v != null && v);
    }

    public int busyCount() {
        return count.get();
    }

    public void forceClear() {
        count.set(0);
        super.set(false);
    }
}
