package com.nccgroup.loggerplusplus.exports;

import com.coreyd97.BurpExtenderUtilities.Alignment;
import com.coreyd97.BurpExtenderUtilities.ComponentGroup;
import com.coreyd97.BurpExtenderUtilities.PanelBuilder;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.logfilter.LogTableFilter;
import com.nccgroup.loggerplusplus.filter.parser.ParseException;
import com.nccgroup.loggerplusplus.util.Globals;
import org.apache.commons.lang3.StringUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

import static com.nccgroup.loggerplusplus.util.Globals.*;

public class SplunkExporterConfigDialog extends JDialog {

    private final Preferences preferences;
    private final SplunkExporter splunkExporter;
    private final JTextField splunkUrlField;
    private final JPasswordField hecTokenField;
    private final JTextField indexNameField;
    private final JSpinner splunkDelaySpinner;
    private final JTextField filterField;
    private final JCheckBox autostartGlobal;
    private final JCheckBox autostartProject;

    SplunkExporterConfigDialog(Frame owner, SplunkExporter splunkExporter) {
        super(owner, "Splunk Exporter Configuration", true);

        this.preferences = splunkExporter.getPreferences();
        this.splunkExporter = splunkExporter;
        this.setLayout(new BorderLayout());

        // Create regular text fields instead of preference-bound ones
        splunkUrlField = new JTextField((String) preferences.getSetting(PREF_SPLUNK_URL));
        hecTokenField = new JPasswordField((String) preferences.getSetting(PREF_SPLUNK_HEC_TOKEN));
        indexNameField = new JTextField((String) preferences.getSetting(PREF_SPLUNK_INDEX));
        
        splunkDelaySpinner = new JSpinner(new SpinnerNumberModel((Integer) preferences.getSetting(PREF_SPLUNK_DELAY), Integer.valueOf(10), Integer.valueOf(99999), Integer.valueOf(10)));
        ((SpinnerNumberModel) splunkDelaySpinner.getModel()).setMaximum(99999);
        ((SpinnerNumberModel) splunkDelaySpinner.getModel()).setMinimum(10);
        ((SpinnerNumberModel) splunkDelaySpinner.getModel()).setStepSize(10);

        String projectPreviousFilterString = preferences.getSetting(Globals.PREF_SPLUNK_FILTER_PROJECT_PREVIOUS);
        String filterString = preferences.getSetting(Globals.PREF_SPLUNK_FILTER);
        if (projectPreviousFilterString != null && !Objects.equals(projectPreviousFilterString, filterString)) {
            int res = JOptionPane.showConfirmDialog(LoggerPlusPlus.instance.getLoggerFrame(),
                    "Looks like the log filter has been changed since you last used this Burp project.\n" +
                            "Do you want to restore the previous filter used by the project?\n" +
                            "\n" +
                            "Previously used filter: " + projectPreviousFilterString + "\n" +
                            "Current filter: " + filterString, "Splunk Exporter Log Filter",
                    JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (res == JOptionPane.YES_OPTION) {
                filterString = projectPreviousFilterString;
            }
        }

        filterField = new JTextField(filterString);
        filterField.setMinimumSize(new Dimension(600, 0));

        autostartGlobal = new JCheckBox("Autostart Exporter (All Projects)", (Boolean) preferences.getSetting(PREF_SPLUNK_AUTOSTART_GLOBAL));
        autostartProject = new JCheckBox("Autostart Exporter (This Project)", (Boolean) preferences.getSetting(PREF_SPLUNK_AUTOSTART_PROJECT));

        autostartProject.setEnabled(!(boolean) preferences.getSetting(PREF_SPLUNK_AUTOSTART_GLOBAL));
        autostartGlobal.addActionListener(e -> {
            autostartProject.setEnabled(!autostartGlobal.isSelected());
            if (autostartGlobal.isSelected()) {
                autostartProject.setSelected(true);
            }
        });

        ComponentGroup connectionGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Connection");
        connectionGroup.addComponentWithLabel("HEC URL: ", splunkUrlField);
        connectionGroup.addComponentWithLabel("HEC Token: ", hecTokenField);
        connectionGroup.addComponentWithLabel("Index: ", indexNameField);

        ComponentGroup miscGroup = new ComponentGroup(ComponentGroup.Orientation.VERTICAL, "Misc");
        miscGroup.add(PanelBuilder.build(new Component[][]{
                new JComponent[]{new JLabel("Upload Frequency (Seconds): "), splunkDelaySpinner},
                new JComponent[]{new JLabel("Log Filter: "), filterField},
                new JComponent[]{autostartGlobal},
                new JComponent[]{autostartProject},
        }, new int[][]{
                new int[]{0, 1},
                new int[]{0, 1},
                new int[]{1},
                new int[]{1}
        }, Alignment.FILL, 1, 1));

        // Create button panel
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("Save");
        JButton cancelButton = new JButton("Cancel");
        
        saveButton.addActionListener(e -> saveSettings());
        cancelButton.addActionListener(e -> dispose());

        buttonPanel.add(cancelButton);
        buttonPanel.add(saveButton);

        PanelBuilder panelBuilder = new PanelBuilder();
        panelBuilder.setComponentGrid(new JComponent[][]{
                new JComponent[]{connectionGroup},
                new JComponent[]{miscGroup},
                new JComponent[]{buttonPanel}
        });
        int[][] weights = new int[][]{
                new int[]{1},
                new int[]{1},
                new int[]{1},
        };
        panelBuilder.setGridWeightsY(weights)
                .setGridWeightsX(weights)
                .setAlignment(Alignment.CENTER)
                .setInsetsX(5)
                .setInsetsY(5);

        this.add(panelBuilder.build(), BorderLayout.CENTER);

        this.setMinimumSize(new Dimension(600, 250));

        this.pack();
        this.setResizable(true);
        this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // Make escape key close the dialog
        getRootPane().registerKeyboardAction(
            e -> dispose(),
            "Cancel",
            KeyStroke.getKeyStroke("ESCAPE"),
            JComponent.WHEN_IN_FOCUSED_WINDOW
        );
    }

    private void saveSettings() {
        String logFilter = filterField.getText();

        if (!StringUtils.isBlank(logFilter)) {
            try {
                new LogTableFilter(logFilter);
            } catch (ParseException ex) {
                JOptionPane.showMessageDialog(this,
                        "Cannot save Splunk Exporter configuration. The chosen log filter is invalid: \n" +
                                ex.getMessage(), "Invalid Splunk Exporter Configuration", JOptionPane.ERROR_MESSAGE);
                return;
            }
        }

        // Save all settings
        preferences.setSetting(PREF_SPLUNK_URL, splunkUrlField.getText());
        preferences.setSetting(PREF_SPLUNK_HEC_TOKEN, new String(hecTokenField.getPassword()));
        preferences.setSetting(PREF_SPLUNK_INDEX, indexNameField.getText());
        preferences.setSetting(PREF_SPLUNK_DELAY, splunkDelaySpinner.getValue());
        preferences.setSetting(PREF_SPLUNK_FILTER, logFilter);
        preferences.setSetting(PREF_SPLUNK_AUTOSTART_GLOBAL, autostartGlobal.isSelected());
        preferences.setSetting(PREF_SPLUNK_AUTOSTART_PROJECT, autostartProject.isSelected());
        
        // Update project previous filter
        preferences.setSetting(PREF_SPLUNK_FILTER_PROJECT_PREVIOUS, logFilter);

        splunkExporter.updateConfiguration();

        dispose();
    }
} 