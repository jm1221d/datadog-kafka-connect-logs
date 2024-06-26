/*
Unless explicitly stated otherwise all files in this repository are licensed under the Apache-2.0 License.
This product includes software developed at Datadog (https://www.datadoghq.com/). Copyright 2020 Datadog, Inc.
 */

package com.datadoghq.connect.logs.sink;

import com.datadoghq.connect.logs.util.Project;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.apache.kafka.connect.header.Header;
import org.apache.kafka.connect.json.JsonConverter;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

import static java.util.stream.Collectors.toMap;
import static java.util.stream.StreamSupport.stream;

public class DatadogLogsApiWriter {
    private static final Logger log = LoggerFactory.getLogger(DatadogLogsApiWriter.class);
    private final DatadogLogsSinkConnectorConfig config;
    private final Map<String, List<SinkRecord>> batches;
    private final JsonConverter jsonConverter;

    public DatadogLogsApiWriter(DatadogLogsSinkConnectorConfig config) {
        this.config = config;
        this.batches = new HashMap<>();
        this.jsonConverter = new JsonConverter();

        Map<String, String> jsonConverterConfig = new HashMap<>();
        jsonConverterConfig.put("schemas.enable", "false");
        jsonConverterConfig.put("decimal.format", "NUMERIC");

        jsonConverter.configure(jsonConverterConfig, false);
    }

    /**
     * Writes records to the Datadog Logs API.
     *
     * @param records to be written from the Source Broker to the Datadog Logs API.
     * @throws IOException may be thrown if the connection to the API fails.
     */
    public void write(Collection<SinkRecord> records) throws IOException {
        for (SinkRecord record : records) {
            if (!batches.containsKey(record.topic())) {
                batches.put(record.topic(), new ArrayList<>(Collections.singletonList(record)));
            } else {
                batches.get(record.topic()).add(record);
            }
            if (batches.get(record.topic()).size() >= config.ddMaxBatchLength) {
                sendBatch(record.topic());
                batches.remove(record.topic());
            }
        }

        // Flush remaining records
        flushBatches();
    }

    private void flushBatches() throws IOException {
        // send any outstanding batches
        for (Map.Entry<String, List<SinkRecord>> entry : batches.entrySet()) {
            sendBatch(entry.getKey());
        }

        batches.clear();
    }

    private void sendBatch(String topic) throws IOException {
        JsonArray content = formatBatch(topic);
        if (content.isEmpty()) {
            log.debug("Nothing to send; Skipping the HTTP request.");
            return;
        }

        URL url = config.getURL();

        sendRequest(content, url);
    }

    private JsonArray formatBatch(String topic) {
        List<SinkRecord> sinkRecords = batches.get(topic);
        JsonArray batchRecords = new JsonArray();

        for (SinkRecord record : sinkRecords) {
            if (record == null) {
                continue;
            }

            if (record.value() == null) {
                continue;
            }

            JsonElement recordJSON = recordToJSON(record);
            JsonObject message = populateMetadata(topic, recordJSON, record.timestamp(), () -> kafkaHeadersToJsonElement(record));
            batchRecords.add(message);
        }

        return batchRecords;
    }

    private JsonElement kafkaHeadersToJsonElement(SinkRecord sinkRecord) {
        Map<String, Object> headerMap = stream(sinkRecord.headers().spliterator(), false)
                .collect(toMap(Header::key, Header::value));

        Gson gson = new Gson();

        String jsonString = gson.toJson(headerMap);

        return gson.fromJson(jsonString, JsonElement.class);
    }

    private JsonElement recordToJSON(SinkRecord record) {
        byte[] rawJSONPayload = jsonConverter.fromConnectData(record.topic(), record.valueSchema(), record.value());
        String jsonPayload = new String(rawJSONPayload, StandardCharsets.UTF_8);
        return new Gson().fromJson(jsonPayload, JsonElement.class);
    }

    private JsonObject populateMetadata(String topic, JsonElement message, Long timestamp, Supplier<JsonElement> kafkaHeaders) {
        JsonObject content = new JsonObject();
        String tags = "topic:" + topic;
        content.add("message", message);
        content.add("ddsource", new JsonPrimitive(config.ddSource));
        if (config.addPublishedDate && timestamp != null) {
            content.add("published_date", new JsonPrimitive(timestamp));
        }

        if (config.parseRecordHeaders) {
            content.add("kafkaheaders", kafkaHeaders.get());
        }

        if (config.ddTags != null) {
            tags += "," + config.ddTags;
        }
        content.add("ddtags", new JsonPrimitive(tags));

        if (config.ddHostname != null) {
            content.add("hostname", new JsonPrimitive(config.ddHostname));
        }

        if (config.ddService != null) {
            content.add("service", new JsonPrimitive(config.ddService));
        }

        return content;
    }

    private void sendRequest(JsonArray content, URL url) throws IOException {
        String requestContent = content.toString();
        byte[] compressedPayload = compress(requestContent);

        HttpURLConnection con;
        if (config.proxyURL != null) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(config.proxyURL, config.proxyPort));
            con = (HttpURLConnection) url.openConnection(proxy);
        } else {
            con = (HttpURLConnection) url.openConnection();
        }
        con.setDoOutput(true);
        con.setRequestMethod("POST");
        setRequestProperties(con);

        log.trace("Submitting HTTP request to {} with body {}", con.getURL(), requestContent);
        DataOutputStream output = new DataOutputStream(con.getOutputStream());
        output.write(compressedPayload);
        output.close();
        log.trace("HTTP request submitted");

        // get response
        int status = con.getResponseCode();
        if (Response.Status.Family.familyOf(status) != Response.Status.Family.SUCCESSFUL) {
            InputStream stream = con.getErrorStream();
            String error = "";
            if (stream != null) {
                error = getOutput(stream);
            }
            con.disconnect();
            throw new IOException("HTTP Response code: " + status
                    + ", " + con.getResponseMessage() + ", " + error
                    + ", Submitted payload: " + content);
        }

        log.trace("Received HTTP response {} {} with body {}", status, con.getResponseMessage(), getOutput(con.getInputStream()));
    }

    private void setRequestProperties(HttpURLConnection con) {
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Content-Encoding", "gzip");
        con.setRequestProperty("DD-API-KEY", config.ddApiKey);
        con.setRequestProperty("DD-EVP-ORIGIN", Project.getName());
        con.setRequestProperty("DD-EVP-ORIGIN-VERSION", Project.getVersion());
        con.setRequestProperty("User-Agent", Project.getName() + "/" + Project.getVersion());
    }

    private byte[] compress(String str) throws IOException {
        ByteArrayOutputStream os = new ByteArrayOutputStream(str.length());
        GZIPOutputStream gos = new GZIPOutputStream(os);
        gos.write(str.getBytes());
        os.close();
        gos.close();
        return os.toByteArray();
    }

    private String getOutput(InputStream input) throws IOException {
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = input.read(buffer)) != -1) {
            errorOutput.write(buffer, 0, length);
        }

        return errorOutput.toString(StandardCharsets.UTF_8.name());
    }
}
