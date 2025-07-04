package com.nccgroup.loggerplusplus.exports;

import com.coreyd97.BurpExtenderUtilities.Alignment;
import com.coreyd97.BurpExtenderUtilities.PanelBuilder;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.util.Globals;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.util.concurrent.ExecutionException;

public class SplunkExporterControlPanel extends JPanel {

    private final SplunkExporter splunkExporter;
    private static final String STARTING_TEXT = "Starting Splunk Exporter...";
    private static final String STOPPING_TEXT = "Stopping Splunk Exporter...";
    private static final String START_TEXT = "Start Splunk Exporter";
    private static final String STOP_TEXT = "Stop Splunk Exporter";

    Logger logger = LogManager.getLogger(this);

    public SplunkExporterControlPanel(SplunkExporter splunkExporter) {
        this.splunkExporter = splunkExporter;
        this.setLayout(new BorderLayout());

        JButton showConfigDialogButton = new JButton(new AbstractAction("Configure Splunk Exporter") {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                new SplunkExporterConfigDialog(LoggerPlusPlus.instance.getLoggerFrame(), splunkExporter)
                        .setVisible(true);

                String newFilter = splunkExporter.getPreferences().getSetting(Globals.PREF_SPLUNK_FILTER);
                splunkExporter.getPreferences().setSetting(Globals.PREF_SPLUNK_FILTER_PROJECT_PREVIOUS, newFilter);
            }
        });

        JToggleButton exportButton = new JToggleButton("Start Splunk Exporter");
        exportButton.addActionListener(new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent actionEvent) {
                boolean buttonNowActive = exportButton.isSelected();
                exportButton.setEnabled(false);
                exportButton.setText(buttonNowActive ? STARTING_TEXT : STOPPING_TEXT);
                new SwingWorker<Boolean, Void>(){
                    Exception exception;

                    @Override
                    protected Boolean doInBackground() throws Exception {
                        boolean success = false;
                        try {
                            if (exportButton.isSelected()) {
                                enableExporter();
                            } else {
                                disableExporter();
                            }
                            success = true;
                        }catch (Exception e){
                            this.exception = e;
                        }
                        return success;
                    }

                    @Override
                    protected void done() {
                        try {
                            if(exception != null) {
                                JOptionPane.showMessageDialog(exportButton, "Could not start Splunk exporter: " +
                                        exception.getMessage() + "\nSee the logs for more information.", "Splunk Exporter", JOptionPane.ERROR_MESSAGE);
                                logger.error("Could not start Splunk exporter.", exception);
                            }
                            Boolean success = get();
                            boolean isRunning = buttonNowActive ^ !success;
                            exportButton.setSelected(isRunning);
                            showConfigDialogButton.setEnabled(!isRunning);

                            exportButton.setText(isRunning ? STOP_TEXT : START_TEXT);

                        } catch (InterruptedException | ExecutionException e) {
                            e.printStackTrace();
                        }
                        exportButton.setEnabled(true);
                    }
                }.execute();
            }
        });

        if (isExporterEnabled()){
            exportButton.setSelected(true);
            exportButton.setText(STOP_TEXT);
            showConfigDialogButton.setEnabled(false);
        }


        this.add(PanelBuilder.build(new JComponent[][]{
                new JComponent[]{showConfigDialogButton},
                new JComponent[]{exportButton}
        }, new int[][]{
                new int[]{1},
                new int[]{1}
        }, Alignment.FILL, 1.0, 1.0), BorderLayout.CENTER);


        this.setBorder(BorderFactory.createTitledBorder("Splunk Exporter"));
    }

    private void enableExporter() throws Exception {
        this.splunkExporter.getExportController().enableExporter(this.splunkExporter);
    }

    private void disableExporter() throws Exception {
        this.splunkExporter.getExportController().disableExporter(this.splunkExporter);
    }

    private boolean isExporterEnabled() {
        return this.splunkExporter.getExportController().getEnabledExporters().contains(this.splunkExporter);
    }

} 