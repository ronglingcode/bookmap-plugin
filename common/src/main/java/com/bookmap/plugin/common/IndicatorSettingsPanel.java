package com.bookmap.plugin.common;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.JCheckBox;

import velox.gui.StrategyPanel;

/**
 * Settings panel for enabling/disabling automatic indicators.
 */
public class IndicatorSettingsPanel extends StrategyPanel {

    public IndicatorSettingsPanel(IndicatorConfig config) {
        super("Indicators");
        setLayout(new GridBagLayout());

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 8, 4, 8);
        gbc.anchor = GridBagConstraints.WEST;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;

        // Premarket High/Low toggle
        JCheckBox premarketCheckbox = new JCheckBox("Premarket High / Low",
                config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW));
        premarketCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.PREMARKET_HIGH_LOW, premarketCheckbox.isSelected()));
        add(premarketCheckbox, gbc);

        // Cam Pivots toggle
        gbc.gridy++;
        JCheckBox camPivotsCheckbox = new JCheckBox("Camarilla Pivots (R1–R6, S1–S6)",
                config.isEnabled(IndicatorConfig.CAM_PIVOTS));
        camPivotsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.CAM_PIVOTS, camPivotsCheckbox.isSelected()));
        add(camPivotsCheckbox, gbc);
    }
}
