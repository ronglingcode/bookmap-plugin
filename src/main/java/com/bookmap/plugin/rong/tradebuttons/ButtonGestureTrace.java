package com.bookmap.plugin.rong.tradebuttons;

import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/** Records physical input before the order handler; never submits or retries an order. */
final class ButtonGestureTrace extends MouseAdapter {
    private final String name;
    private final Consumer<String> log;
    private boolean pending, activated;
    private long pressTime;

    private ButtonGestureTrace(JButton button, String name, Consumer<String> log) {
        this.name = name; this.log = log;
        button.addMouseListener(this);
        button.addActionListener(event -> {
            if (pending) { activated = true; log.accept("Button activated " + name + " press_time=" + pressTime); }
        });
    }

    static void attach(JButton button, String name, Consumer<String> log) {
        new ButtonGestureTrace(button, name, log);
    }

    @Override public void mousePressed(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event)) return;
        pending = true; activated = false; pressTime = event.getWhen();
        log.accept("Button mouse-down " + name + " event_time=" + pressTime + " shift=" + event.isShiftDown());
    }

    @Override public void mouseReleased(MouseEvent event) {
        if (!SwingUtilities.isLeftMouseButton(event) || !pending) return;
        log.accept("Button mouse-up " + name + " event_time=" + event.getWhen()
                + " press_time=" + pressTime + " activated=" + activated);
        if (!activated) log.accept("Button gesture cancelled " + name + "; no click action generated");
        pending = false;
    }
}
