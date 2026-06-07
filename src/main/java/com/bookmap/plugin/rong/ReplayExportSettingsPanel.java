package com.bookmap.plugin.rong;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JCheckBox;
import javax.swing.JLabel;

import com.bookmap.plugin.rong.exporter.BookmapExportWriter;

import velox.gui.StrategyPanel;

/**
 * Settings panel for replay export from the live Rong plugin.
 */
public class ReplayExportSettingsPanel extends StrategyPanel {

    public ReplayExportSettingsPanel(ReplayExportConfig config) {
        super("Replay Export");
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        JCheckBox enabledCheckbox = new JCheckBox(
                "Export replay data from Rong",
                config.isEnabled());
        enabledCheckbox.addActionListener(e -> config.setEnabled(enabledCheckbox.isSelected()));
        add(enabledCheckbox, gbc);

        gbc.gridy++;
        JLabel outputLabel = new JLabel("Output: " + BookmapExportWriter.resolveOutputRoot());
        add(outputLabel, gbc);
    }
}
