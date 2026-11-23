package com.bookmap.plugin.rong.tradebuttons;

import java.awt.Component;
import java.awt.Container;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.Timer;

/** EDT-owned: preserve button identity across account updates and mouse gestures. */
final class TradeButtonRefresh {
    private final BooleanSupplier pressed;
    private final Consumer<List<TradebookButtonGroup>> render;
    private final Timer retry;
    private List<TradebookButtonGroup> applied = Collections.emptyList(), pending;
    private boolean stopped;

    TradeButtonRefresh(BooleanSupplier pressed, Consumer<List<TradebookButtonGroup>> render) {
        this.pressed = pressed; this.render = render;
        retry = new Timer(50, event -> flush());
    }

    void request(List<TradebookButtonGroup> groups) {
        if (stopped) return;
        pending = groups == null ? Collections.emptyList() : List.copyOf(groups);
        flush();
    }

    void flush() {
        if (stopped || pending == null) return;
        if (applied.equals(pending)) { pending = null; retry.stop(); return; }
        if (pressed.getAsBoolean()) { retry.start(); return; }
        List<TradebookButtonGroup> next = pending;
        pending = null; retry.stop();
        render.accept(next); applied = next;
    }

    void stop() { stopped = true; pending = null; retry.stop(); }

    static boolean pressed(Component component) {
        if (component instanceof AbstractButton && ((AbstractButton) component).getModel().isPressed()) return true;
        if (component instanceof Container) for (Component child : ((Container) component).getComponents())
            if (pressed(child)) return true;
        return false;
    }
}
