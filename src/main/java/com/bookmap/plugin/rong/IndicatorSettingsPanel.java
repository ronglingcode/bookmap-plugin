package com.bookmap.plugin.rong;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

import javax.swing.JCheckBox;
import javax.swing.JLabel;

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

        JLabel versionLabel = new JLabel("Rong Version: " + PluginVersion.VERSION);
        add(versionLabel, gbc);

        gbc.gridy++;
        JCheckBox premarketCheckbox = new JCheckBox(
                "Premarket High / Low",
                config.isEnabled(IndicatorConfig.PREMARKET_HIGH_LOW));
        premarketCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.PREMARKET_HIGH_LOW, premarketCheckbox.isSelected()));
        add(premarketCheckbox, gbc);

        gbc.gridy++;
        JCheckBox camPivotsCheckbox = new JCheckBox(
                "Camarilla Pivots (R1-R6, S1-S6)",
                config.isEnabled(IndicatorConfig.CAM_PIVOTS));
        camPivotsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.CAM_PIVOTS, camPivotsCheckbox.isSelected()));
        add(camPivotsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallLabelsCheckbox = new JCheckBox(
                "Order Wall Size Labels",
                config.isEnabled(IndicatorConfig.ORDER_WALL_SIZE_LABELS));
        wallLabelsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_SIZE_LABELS, wallLabelsCheckbox.isSelected()));
        add(wallLabelsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallChangeAlertsCheckbox = new JCheckBox(
                "Order Wall Change Alerts",
                config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS));
        wallChangeAlertsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_ALERTS, wallChangeAlertsCheckbox.isSelected()));
        add(wallChangeAlertsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallChangeSoundCheckbox = new JCheckBox(
                "Order Wall Change Sound",
                config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));
        wallChangeSoundCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND, wallChangeSoundCheckbox.isSelected()));
        add(wallChangeSoundCheckbox, gbc);
    }
}
