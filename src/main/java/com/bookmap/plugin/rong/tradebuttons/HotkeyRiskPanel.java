package com.bookmap.plugin.rong.tradebuttons;

import java.awt.FlowLayout;
import java.awt.Font;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

/** Selecting a risk changes local hotkey preferences only; it never submits a trade. */
final class HotkeyRiskPanel extends JPanel {
    private final HotkeyRiskSelection selection;
    private final JLabel label = new JLabel();
    private final Map<String, JRadioButton> buttons = new LinkedHashMap<>();
    private final PropertyChangeListener listener = event -> refresh();
    private volatile boolean disposed;

    HotkeyRiskPanel(HotkeyRiskSelection selection) {
        super(new FlowLayout(FlowLayout.LEFT, 8, 0));
        this.selection = selection;
        setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        add(label);
        ButtonGroup group = new ButtonGroup();
        for (String method : HotkeyRiskSelection.ENTRY_METHODS) {
            JRadioButton button = new JRadioButton(method);
            button.setFocusable(false);
            button.setToolTipText("Set risk for chart B/S entries across all symbols; no order is placed");
            button.addActionListener(event -> selection.setEntryMethod(method));
            group.add(button);
            buttons.put(method, button);
            add(button);
        }
        selection.addListener(listener);
        refresh();
    }

    private void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        if (disposed) return;
        String method = selection.getEntryMethod();
        label.setText("Hotkey risk: " + method);
        buttons.get(method).setSelected(true);
    }

    void dispose() {
        disposed = true;
        selection.removeListener(listener);
    }
}
