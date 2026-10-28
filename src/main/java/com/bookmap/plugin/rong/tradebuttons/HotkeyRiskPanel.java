package com.bookmap.plugin.rong.tradebuttons;

import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.beans.PropertyChangeListener;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;

/** Selecting a size changes local hotkey preferences only; it never submits a trade. */
final class HotkeyRiskPanel extends JPanel {
    private final HotkeyRiskSelection selection;
    private final String symbol;
    private final JLabel label = new JLabel();
    private final Map<String, JRadioButton> buttons = new LinkedHashMap<>();
    private final PropertyChangeListener listener = event -> refresh();
    private volatile boolean disposed;

    HotkeyRiskPanel(HotkeyRiskSelection selection) {
        this(selection, "");
    }

    HotkeyRiskPanel(HotkeyRiskSelection selection, String symbol) {
        super(new GridBagLayout());
        this.selection = selection;
        this.symbol = symbol;
        setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        selection.addListener(listener);
        refresh();
    }

    private void rebuildButtons() {
        removeAll();
        buttons.clear();
        GridBagConstraints cell = new GridBagConstraints();
        cell.gridx = 0;
        cell.gridy = 0;
        cell.gridwidth = 2;
        cell.weightx = 1;
        cell.anchor = GridBagConstraints.WEST;
        cell.fill = GridBagConstraints.HORIZONTAL;
        cell.insets = new Insets(2, 4, 2, 4);
        add(label, cell);
        cell.gridwidth = 1;
        ButtonGroup group = new ButtonGroup();
        int index = 0;
        for (String method : selection.getEntryMethods(symbol)) {
            JRadioButton button = new JRadioButton(method);
            button.setFocusable(false);
            button.setToolTipText("Set size for chart B/S entries; no order is placed");
            button.addActionListener(event -> selection.setEntryMethod(symbol, method));
            group.add(button);
            buttons.put(method, button);
            cell.gridx = index % 2;
            cell.gridy = 1 + index / 2;
            add(button, cell);
            index++;
        }
        revalidate();
        repaint();
    }

    private void refresh() {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(this::refresh);
            return;
        }
        if (disposed) return;
        if (!new java.util.ArrayList<>(buttons.keySet()).equals(selection.getEntryMethods(symbol))) rebuildButtons();
        String method = selection.getEntryMethod(symbol);
        label.setText("Hotkey size: " + method);
        buttons.get(method).setSelected(true);
    }

    void dispose() {
        disposed = true;
        selection.removeListener(listener);
    }
}
