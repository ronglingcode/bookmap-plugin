package com.bookmap.plugin.common;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;

import velox.gui.StrategyPanel;

/**
 * Settings panel for managing predefined key price levels.
 *
 * <p>Displays all current key levels (from both the config file and session additions)
 * in a scrollable list. File-loaded levels are shown as read-only, while session levels
 * can be removed. New levels can be added via input fields at the bottom.</p>
 *
 * <p>Layout:</p>
 * <pre>
 * ┌─────────────────────────────────────┐
 * │  Key Price Levels                    │
 * │ ┌──────────────────────────────────┐ │
 * │ │ NVDA @ 180.00 (major support) [file] │
 * │ │ NVDA @ 200.00 (round number) [file]  │
 * │ │ AAPL @ 230.00  [Remove]             │
 * │ └──────────────────────────────────┘ │
 * │ Instrument: [____] Price: [____]     │
 * │ Label (optional): [________________] │
 * │ [Add Level]                          │
 * └─────────────────────────────────────┘
 * </pre>
 */
public class KeyLevelSettingsPanel extends StrategyPanel {

    private final KeyLevelConfig config;
    private final JPanel levelsListPanel;

    public KeyLevelSettingsPanel(KeyLevelConfig config) {
        super("Key Price Levels");
        this.config = config;
        setLayout(new BorderLayout(4, 4));

        // --- Scrollable list of current levels ---
        levelsListPanel = new JPanel();
        levelsListPanel.setLayout(new BoxLayout(levelsListPanel, BoxLayout.Y_AXIS));
        JScrollPane scrollPane = new JScrollPane(levelsListPanel);
        scrollPane.setPreferredSize(new Dimension(400, 150));
        scrollPane.setBorder(BorderFactory.createTitledBorder("Current Levels"));
        add(scrollPane, BorderLayout.CENTER);

        // --- Add new level form ---
        JPanel addPanel = new JPanel(new GridBagLayout());
        addPanel.setBorder(BorderFactory.createTitledBorder("Add Level"));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(2, 4, 2, 4);
        gbc.anchor = GridBagConstraints.WEST;

        // Row 1: Instrument + Price
        gbc.gridx = 0; gbc.gridy = 0;
        addPanel.add(new JLabel("Instrument:"), gbc);

        gbc.gridx = 1;
        JTextField instrumentField = new JTextField(8);
        instrumentField.setToolTipText("Bookmap instrument alias (e.g., NVDA, AAPL, ESM5)");
        addPanel.add(instrumentField, gbc);

        gbc.gridx = 2;
        addPanel.add(new JLabel("Price:"), gbc);

        gbc.gridx = 3;
        JTextField priceField = new JTextField(8);
        priceField.setToolTipText("Real price (e.g., 180.00)");
        addPanel.add(priceField, gbc);

        // Row 2: Label
        gbc.gridx = 0; gbc.gridy = 1;
        addPanel.add(new JLabel("Label:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 3; gbc.fill = GridBagConstraints.HORIZONTAL;
        JTextField labelField = new JTextField(20);
        labelField.setToolTipText("Optional descriptive text (e.g., \"major support\", \"daily resistance\")");
        addPanel.add(labelField, gbc);
        gbc.gridwidth = 1; gbc.fill = GridBagConstraints.NONE;

        // Row 3: Add button
        gbc.gridx = 0; gbc.gridy = 2; gbc.gridwidth = 4; gbc.anchor = GridBagConstraints.CENTER;
        JButton addButton = new JButton("Add Level");
        addButton.addActionListener(e -> {
            String instrument = instrumentField.getText().trim();
            String priceText = priceField.getText().trim();
            String label = labelField.getText().trim();

            if (instrument.isEmpty() || priceText.isEmpty()) {
                JOptionPane.showMessageDialog(this,
                        "Instrument and Price are required.",
                        "Missing Fields", JOptionPane.WARNING_MESSAGE);
                return;
            }

            double price;
            try {
                price = Double.parseDouble(priceText);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this,
                        "Price must be a valid number (e.g., 180.00).",
                        "Invalid Price", JOptionPane.WARNING_MESSAGE);
                return;
            }

            // Create and add the session level
            KeyLevelDefinition def = new KeyLevelDefinition(
                    instrument, price, label.isEmpty() ? null : label);
            config.addSessionLevel(def);

            // Clear input fields
            instrumentField.setText("");
            priceField.setText("");
            labelField.setText("");

            // Refresh the list
            refreshLevelsList();
        });
        addPanel.add(addButton, gbc);

        add(addPanel, BorderLayout.SOUTH);

        // Initial population
        refreshLevelsList();
    }

    /**
     * Rebuilds the scrollable list of current key levels.
     * File levels are shown as read-only; session levels have a Remove button.
     */
    private void refreshLevelsList() {
        levelsListPanel.removeAll();

        // Show file-loaded levels (read-only)
        List<KeyLevelDefinition> fileLevels = config.getFileLevels();
        for (KeyLevelDefinition def : fileLevels) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            String text = def.getInstrument() + " @ " + String.format("%.2f", def.getPrice());
            if (def.getLabel() != null) text += " (" + def.getLabel() + ")";
            row.add(new JLabel(text));
            JLabel sourceLabel = new JLabel("[file]");
            sourceLabel.setForeground(java.awt.Color.GRAY);
            sourceLabel.setToolTipText("Loaded from key-levels.json — edit the file to modify");
            row.add(sourceLabel);
            levelsListPanel.add(row);
        }

        // Show session levels (removable)
        List<KeyLevelDefinition> sessionLevels = config.getSessionLevels();
        for (KeyLevelDefinition def : sessionLevels) {
            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            String text = def.getInstrument() + " @ " + String.format("%.2f", def.getPrice());
            if (def.getLabel() != null) text += " (" + def.getLabel() + ")";
            row.add(new JLabel(text));

            JButton removeBtn = new JButton("Remove");
            removeBtn.setMargin(new Insets(0, 4, 0, 4));
            removeBtn.addActionListener(e -> {
                config.removeSessionLevel(def);
                refreshLevelsList();
            });
            row.add(removeBtn);
            levelsListPanel.add(row);
        }

        if (fileLevels.isEmpty() && sessionLevels.isEmpty()) {
            JLabel emptyLabel = new JLabel("No key levels defined.");
            emptyLabel.setForeground(java.awt.Color.GRAY);
            JPanel emptyRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
            emptyRow.add(emptyLabel);
            levelsListPanel.add(emptyRow);
        }

        levelsListPanel.revalidate();
        levelsListPanel.repaint();
    }
}
