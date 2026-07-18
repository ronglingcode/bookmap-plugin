package com.bookmap.plugin.rong;

import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Dimension;

import javax.swing.JCheckBox;
import javax.swing.JFormattedTextField;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import velox.gui.StrategyPanel;

/**
 * Settings panel for enabling/disabling automatic indicators.
 */
public class IndicatorSettingsPanel extends StrategyPanel {

    public IndicatorSettingsPanel(IndicatorConfig config, WallThresholdConfig wallThresholdConfig) {
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
        JCheckBox patternSignalsCheckbox = new JCheckBox(
                "Bookmap Pattern Automation (display-only)",
                config.isEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS));
        patternSignalsCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.BOOKMAP_PATTERN_SIGNALS, patternSignalsCheckbox.isSelected()));
        add(patternSignalsCheckbox, gbc);

        gbc.gridy++;
        JCheckBox wallChangeSoundCheckbox = new JCheckBox(
                "Order Wall Change Sound",
                config.isEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND));
        wallChangeSoundCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.ORDER_WALL_CHANGE_SOUND, wallChangeSoundCheckbox.isSelected()));
        add(wallChangeSoundCheckbox, gbc);

        gbc.gridy++;
        JPanel wallThresholdPanel = new JPanel(new GridBagLayout());
        GridBagConstraints thresholdGbc = new GridBagConstraints();
        thresholdGbc.insets = new Insets(0, 0, 0, 8);
        thresholdGbc.anchor = GridBagConstraints.WEST;
        thresholdGbc.gridx = 0;
        thresholdGbc.gridy = 0;
        wallThresholdPanel.add(new JLabel("Wall threshold floor"), thresholdGbc);

        thresholdGbc.gridx++;
        SpinnerNumberModel thresholdModel = new SpinnerNumberModel(
                wallThresholdConfig.getThresholdFloor(),
                0,
                WallThresholdConfig.MAX_THRESHOLD_FLOOR,
                500);
        JSpinner thresholdSpinner = new JSpinner(thresholdModel);
        thresholdSpinner.setPreferredSize(new Dimension(96, thresholdSpinner.getPreferredSize().height));
        if (thresholdSpinner.getEditor() instanceof JSpinner.DefaultEditor) {
            JFormattedTextField textField =
                    ((JSpinner.DefaultEditor) thresholdSpinner.getEditor()).getTextField();
            textField.setColumns(7);
        }
        thresholdSpinner.addChangeListener(e ->
                wallThresholdConfig.setThresholdFloor(((Number) thresholdSpinner.getValue()).intValue()));
        wallThresholdPanel.add(thresholdSpinner, thresholdGbc);
        add(wallThresholdPanel, gbc);

        gbc.gridy++;
        JCheckBox filledExecutionMarkersCheckbox = new JCheckBox(
                "Filled Execution Markers",
                config.isEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS));
        filledExecutionMarkersCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.FILLED_EXECUTION_MARKERS, filledExecutionMarkersCheckbox.isSelected()));
        add(filledExecutionMarkersCheckbox, gbc);

        gbc.gridy++;
        JCheckBox fireKeyboardEventCheckbox = new JCheckBox(
                "Fire Keyboard Hotkey Events (A/G/T/W, 0-9)",
                config.isEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT));
        fireKeyboardEventCheckbox.addActionListener(e ->
                config.setEnabled(IndicatorConfig.FIRE_KEYBOARD_EVENT, fireKeyboardEventCheckbox.isSelected()));
        add(fireKeyboardEventCheckbox, gbc);
    }
}
