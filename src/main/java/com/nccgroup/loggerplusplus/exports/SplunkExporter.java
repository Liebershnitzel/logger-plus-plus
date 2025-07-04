package com.nccgroup.loggerplusplus.exports;

import burp.api.montoya.http.message.HttpHeader;
import com.coreyd97.BurpExtenderUtilities.Preferences;
import com.google.gson.Gson;
import com.nccgroup.loggerplusplus.LoggerPlusPlus;
import com.nccgroup.loggerplusplus.filter.logfilter.LogTableFilter;
import com.nccgroup.loggerplusplus.filter.parser.ParseException;
import com.nccgroup.loggerplusplus.logentry.LogEntry;
import com.nccgroup.loggerplusplus.logentry.LogEntryField;
import com.nccgroup.loggerplusplus.logentry.Status;
import com.nccgroup.loggerplusplus.util.Globals;
import lombok.extern.log4j.Log4j2;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import javax.swing.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.Map;

@Log4j2
public class SplunkExporter extends AutomaticLogExporter implements ExportPanelProvider {

    private final SplunkExporterControlPanel controlPanel;
    private final Gson gson;
    private ScheduledExecutorService executorService;
    private ScheduledFuture<?> indexTask;
    private List<LogEntry> pendingEntries;
    private LogTableFilter logFilter;

    protected SplunkExporter(ExportController exportController, Preferences preferences) {
        super(exportController, preferences);
        this.gson = LoggerPlusPlus.gsonProvider.getGson();
        this.controlPanel = new SplunkExporterControlPanel(this);

        if ((boolean) preferences.getSetting(Globals.PREF_SPLUNK_AUTOSTART_GLOBAL)
                || (boolean) preferences.getSetting(Globals.PREF_SPLUNK_AUTOSTART_PROJECT)) {
            try {
                this.exportController.enableExporter(this);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(LoggerPlusPlus.instance.getLoggerFrame(), "Could not start Splunk exporter: " +
                        e.getMessage() + "\nSee the logs for more information.", "Splunk Exporter", JOptionPane.ERROR_MESSAGE);
                log.error("Could not automatically start Splunk exporter:", e);
            }
        }
    }

    @Override
    void setup() throws Exception {
        String filterString = preferences.getSetting(Globals.PREF_SPLUNK_FILTER);
        if (filterString != null && !filterString.isEmpty()) {
            try {
                logFilter = new LogTableFilter(filterString);
            } catch (ParseException ex) {
                log.error("The log filter configured for the Splunk exporter is invalid!", ex);
            }
        }

        pendingEntries = Collections.synchronizedList(new ArrayList<>());
        executorService = Executors.newSingleThreadScheduledExecutor();
        startScheduledTask();
    }

    private void startScheduledTask() {
        // Cancel existing task if it exists
        if (indexTask != null) {
            indexTask.cancel(true);
        }
        
        // Start new task with current delay setting
        int delay = preferences.getSetting(Globals.PREF_SPLUNK_DELAY);
        indexTask = executorService.scheduleAtFixedRate(this::sendPendingEntries, delay, delay, TimeUnit.SECONDS);
        log.info("Splunk exporter scheduled task started with " + delay + " second interval");
    }

    public void updateConfiguration() {
        // Update the log filter if it has changed
        String filterString = preferences.getSetting(Globals.PREF_SPLUNK_FILTER);
        if (filterString != null && !filterString.isEmpty()) {
            try {
                logFilter = new LogTableFilter(filterString);
                log.info("Splunk exporter filter updated: " + filterString);
            } catch (ParseException ex) {
                log.error("The log filter configured for the Splunk exporter is invalid!", ex);
            }
        } else {
            logFilter = null;
        }
        
        // Restart the scheduled task with new settings
        if (executorService != null && !executorService.isShutdown()) {
            startScheduledTask();
        }
    }

    @Override
    void exportNewEntry(LogEntry logEntry) {
        if (logEntry.getStatus() == Status.PROCESSED) {
            if (logFilter != null && !logFilter.getFilterExpression().matches(logEntry)) return;
            pendingEntries.add(logEntry);
        }
    }

    @Override
    void exportUpdatedEntry(LogEntry logEntry) {
        // For now, we only care about new entries
    }

    @Override
    void shutdown() throws Exception {
        if (indexTask != null) {
            indexTask.cancel(true);
        }
        if (executorService != null) {
            executorService.shutdown();
        }
        this.pendingEntries = null;
    }

    private void sendPendingEntries() {
        if (pendingEntries == null || pendingEntries.isEmpty()) {
            return;
        }

        List<LogEntry> entriesToSend;
        synchronized (pendingEntries) {
            entriesToSend = new ArrayList<>(pendingEntries);
            pendingEntries.clear();
        }

        String splunkUrl = preferences.getSetting(Globals.PREF_SPLUNK_URL);
        String hecToken = preferences.getSetting(Globals.PREF_SPLUNK_HEC_TOKEN);
        String index = preferences.getSetting(Globals.PREF_SPLUNK_INDEX);

        if (splunkUrl == null || splunkUrl.isEmpty() || hecToken == null || hecToken.isEmpty()) {
            log.warn("Splunk HEC URL or Token is not configured. Skipping export.");
            return;
        }

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            for (LogEntry entry : entriesToSend) {
                // Create flattened Splunk HEC event structure
                com.google.gson.JsonObject splunkEvent = new com.google.gson.JsonObject();
                
                // Automatically serialize the LogEntry object
                com.google.gson.JsonObject entryJson = gson.toJsonTree(entry).getAsJsonObject();
                for (Map.Entry<String, com.google.gson.JsonElement> field : entryJson.entrySet()) {
                    splunkEvent.add(field.getKey(), field.getValue());
                }
                
                // Add unique request ID
                splunkEvent.addProperty("requestId", entry.getIdentifier() + "_" + System.currentTimeMillis() + "_" + System.nanoTime());
                
                // Add project name
                String projectName = LoggerPlusPlus.montoya.persistence().preferences().getString("project_name");
                if (projectName != null && !projectName.isEmpty()) {
                    splunkEvent.addProperty("projectName", projectName);
                } else {
                    splunkEvent.addProperty("projectName", "default");
                }
                
                // Add microsecond timestamps
                if (entry.getRequestDateTime() != null) {
                    splunkEvent.addProperty("requestTimestamp", entry.getRequestDateTime().getTime());
                    splunkEvent.addProperty("requestTimestampMicros", entry.getRequestDateTime().getTime() * 1000 + (System.nanoTime() % 1000000) / 1000);
                }
                if (entry.getResponseDateTime() != null) {
                    splunkEvent.addProperty("responseTimestamp", entry.getResponseDateTime().getTime());
                    splunkEvent.addProperty("responseTimestampMicros", entry.getResponseDateTime().getTime() * 1000 + (System.nanoTime() % 1000000) / 1000);
                }
                
                // Add scope information
                splunkEvent.addProperty("inscope", LoggerPlusPlus.isUrlInScope(entry.getUrlString()));
                
                // Parse headers into individual searchable fields
                if (entry.getRequestHeaders() != null) {
                    com.google.gson.JsonObject requestHeaders = new com.google.gson.JsonObject();
                    for (HttpHeader header : entry.getRequestHeaders()) {
                        String headerName = header.name().toLowerCase().replaceAll("[^a-z0-9]", "_");
                        String headerValue = header.value();
                        requestHeaders.addProperty(headerName, headerValue);
                    }
                    splunkEvent.add("requestHeaders", requestHeaders);
                }
                
                if (entry.getResponseHeaders() != null) {
                    com.google.gson.JsonObject responseHeaders = new com.google.gson.JsonObject();
                    for (HttpHeader header : entry.getResponseHeaders()) {
                        String headerName = header.name().toLowerCase().replaceAll("[^a-z0-9]", "_");
                        String headerValue = header.value();
                        responseHeaders.addProperty(headerName, headerValue);
                    }
                    splunkEvent.add("responseHeaders", responseHeaders);
                }
                
                // Add Splunk-specific fields
                splunkEvent.addProperty("sourcetype", "burp:log");
                splunkEvent.addProperty("index", index);
                splunkEvent.addProperty("host", entry.getHostname() != null ? entry.getHostname() : "unknown");
                
                String jsonPayload = gson.toJson(splunkEvent);
                
                HttpPost postRequest = new HttpPost(splunkUrl);
                postRequest.setHeader("Authorization", "Splunk " + hecToken);
                postRequest.setHeader("Content-Type", "application/json");
                postRequest.setEntity(new StringEntity(jsonPayload, "UTF-8"));

                httpClient.execute(postRequest);
            }
            log.info("Sent " + entriesToSend.size() + " entries to Splunk.");
        } catch (IOException e) {
            log.error("Failed to send data to Splunk: ", e);
        }
    }

    @Override
    public JComponent getExportPanel() {
        return controlPanel;
    }

    public ExportController getExportController() {
        return exportController;
    }
} 