package com.bookmap.plugin.common;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JTextField;

import velox.gui.StrategyPanel;

/**
 * Settings panel for configuring key bindings for price line types.
 * Each LineType gets a text field where the user can set the trigger key.
 */
public class KeyBindingSettingsPanel extends StrategyPanel {

    public KeyBindingSettingsPanel(PriceLineConfig config, PriceLineStore store) {
        super("Price Line Key Bindings");
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;

        int row = 0;
        for (PriceLine.LineType type : PriceLine.LineType.values()) {
            if (!type.isManual()) continue; // skip auto-drawn indicator types
            gbc.gridx = 0;
            gbc.gridy = row;
            gbc.fill = GridBagConstraints.NONE;
            gbc.weightx = 0;

            JLabel label = new JLabel(type.label + " key:");
            add(label, gbc);

            gbc.gridx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.weightx = 1.0;

            String currentKey = config.getKeyForType(type);
            JTextField keyField = new JTextField(currentKey != null ? currentKey : "", 5);
            keyField.addFocusListener(new FocusAdapter() {
                @Override
                public void focusLost(FocusEvent e) {
                    String newKey = keyField.getText().trim().toLowerCase();
                    config.setBinding(newKey, type);
                }
            });
            add(keyField, gbc);

            row++;
        }

        // Clear all lines button
        gbc.gridx = 0;
        gbc.gridy = row;
        gbc.gridwidth = 2;
        gbc.fill = GridBagConstraints.NONE;
        gbc.anchor = GridBagConstraints.CENTER;
        gbc.insets = new Insets(12, 8, 4, 8);

        JButton clearButton = new JButton("Clear All Price Lines");
        clearButton.addActionListener(e -> store.clearAll());
        add(clearButton, gbc);
    }
}
