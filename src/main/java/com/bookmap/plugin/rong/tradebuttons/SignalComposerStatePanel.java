package com.bookmap.plugin.rong.tradebuttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import java.awt.Point;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/** Persistent, selectable inspection text. Refresh is owned by the window's EDT timer. */
final class SignalComposerStatePanel extends JPanel {
    private final Supplier<String> supplier;
    private final JTextArea text = new JTextArea(11, 40);
    private final JScrollPane scroll = new JScrollPane(text);

    SignalComposerStatePanel(Supplier<String> supplier) {
        super(new BorderLayout());
        this.supplier = supplier;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Signal Composer state (advisory)"),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font("SansSerif", Font.PLAIN, 12));
        text.setBackground(new Color(24, 35, 46));
        text.setForeground(Color.WHITE);
        text.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        text.setToolTipText("Refreshes every second. Timers use recording market time in replay. Source mode is the Cairo configuration label.");
        add(scroll, BorderLayout.CENTER);
        refresh();
    }

    void refresh() {
        String value = supplier.get();
        if (value.equals(text.getText())) return;
        Point position = scroll.getViewport().getViewPosition();
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        text.setText(value);
        text.select(Math.min(start, value.length()), Math.min(end, value.length()));
        scroll.getViewport().setViewPosition(position);
    }
}
