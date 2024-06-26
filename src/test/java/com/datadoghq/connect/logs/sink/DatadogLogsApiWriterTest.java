/*
Unless explicitly stated otherwise all files in this repository are licensed under the Apache-2.0 License.
This product includes software developed at Datadog (https://www.datadoghq.com/). Copyright 2020 Datadog, Inc.
 */

package com.datadoghq.connect.logs.sink;

import com.datadoghq.connect.logs.sink.util.RequestInfo;
import com.datadoghq.connect.logs.sink.util.RestHelper;
import com.datadoghq.connect.logs.util.Project;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.connect.data.Decimal;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.header.ConnectHeaders;
import org.apache.kafka.connect.header.Headers;
import org.apache.kafka.connect.sink.SinkRecord;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DatadogLogsApiWriterTest {
    private static String apiKey = "API_KEY";
    private Map<String, String> props;
    private List<SinkRecord> records;
    private RestHelper restHelper;

    @Before
    public void setUp() throws Exception {
        records = new ArrayList<>();
        props = new HashMap<>();
        props.put(DatadogLogsSinkConnectorConfig.DD_API_KEY, apiKey);
        props.put(DatadogLogsSinkConnectorConfig.DD_URL, "localhost:8080");
        restHelper = new RestHelper();
        restHelper.start();
    }

    @After
    public void tearDown() throws Exception {
        restHelper.stop();
        restHelper.flushCapturedRequests();
    }

    @Test
    public void writer_givenConfigs_sendsPOSTToURL() throws IOException {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 500, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0));
        writer.write(records);

        Assert.assertEquals(1, restHelper.getCapturedRequests().size());
        RequestInfo request = restHelper.getCapturedRequests().get(0);
        Assert.assertEquals("POST", request.getMethod());
        Assert.assertEquals("/api/v2/logs", request.getUrl());
        Assert.assertTrue(request.getHeaders().contains("Content-Type:application/json"));
        Assert.assertTrue(request.getHeaders().contains("Content-Encoding:gzip"));
        Assert.assertTrue(request.getHeaders().contains("DD-API-KEY:" + apiKey));
        Assert.assertTrue(request.getHeaders().contains("DD-EVP-ORIGIN:datadog-kafka-connect-logs"));
        Assert.assertTrue(request.getHeaders().contains("DD-EVP-ORIGIN-VERSION:" + Project.getVersion()));
        Assert.assertTrue(request.getHeaders().contains("User-Agent:datadog-kafka-connect-logs/" + Project.getVersion()));
    }

    @Test
    public void writer_handles_bigDecimal() throws IOException {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 500, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        Schema schema = Decimal.schema(2);
        BigDecimal value = new BigDecimal(new BigInteger("156"), 2);

        records.add(new SinkRecord("someTopic", 0, null, "someKey", schema, value, 0));
        writer.write(records);

        Assert.assertEquals(1, restHelper.getCapturedRequests().size());
        RequestInfo request = restHelper.getCapturedRequests().get(0);
        Assert.assertEquals("[{\"message\":1.56,\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic\"}]", request.getBody());
    }

    @Test
    public void writer_batchAtMax_shouldSendBatched() throws IOException {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 2, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0));
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue2", 0));
        writer.write(records);

        Assert.assertEquals(1, restHelper.getCapturedRequests().size());

        RequestInfo request = restHelper.getCapturedRequests().get(0);
        Assert.assertEquals("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic\"},{\"message\":\"someValue2\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic\"}]", request.getBody());
    }

    @Test
    public void writer_batchAboveMax_shouldSendSeparate() throws IOException {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 1, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0));
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue2", 0));
        writer.write(records);

        Assert.assertEquals(2, restHelper.getCapturedRequests().size());

        RequestInfo request1 = restHelper.getCapturedRequests().get(0);
        RequestInfo request2 = restHelper.getCapturedRequests().get(1);

        Set<String> requestBodySetActual = new HashSet<>();
        requestBodySetActual.add(request1.getBody());
        requestBodySetActual.add(request2.getBody());
        Set<String> requestBodySetExpected = new HashSet<>();
        requestBodySetExpected.add("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic\"}]");
        requestBodySetExpected.add("[{\"message\":\"someValue2\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic\"}]");
        Assert.assertEquals(requestBodySetExpected, requestBodySetActual);
    }

    @Test
    public void writer_readingMultipleTopics_shouldBatchSeparate() throws IOException {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 2, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        records.add(new SinkRecord("someTopic1", 0, null, "someKey", null, "someValue1", 0));
        records.add(new SinkRecord("someTopic2", 0, null, "someKey", null, "someValue2", 0));
        writer.write(records);

        Assert.assertEquals(2, restHelper.getCapturedRequests().size());

        RequestInfo request1 = restHelper.getCapturedRequests().get(0);
        RequestInfo request2 = restHelper.getCapturedRequests().get(1);

        Set<String> requestBodySetActual = new HashSet<>();
        requestBodySetActual.add(request1.getBody());
        requestBodySetActual.add(request2.getBody());
        Set<String> requestBodySetExpected = new HashSet<>();
        requestBodySetExpected.add("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic1\"}]");
        requestBodySetExpected.add("[{\"message\":\"someValue2\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic2\"}]");
        Assert.assertEquals(requestBodySetExpected, requestBodySetActual);
    }

    @Test(expected = IOException.class)
    public void writer_IOException_for_status_429() throws Exception {
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 500, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        restHelper.setHttpStatusCode(429);
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0));
        writer.write(records);
    }

    @Test
    public void metadata_asOneBatch_shouldPopulatePerBatch() throws IOException {
        props.put(DatadogLogsSinkConnectorConfig.DD_TAGS, "team:agent-core, author:berzan");
        props.put(DatadogLogsSinkConnectorConfig.DD_HOSTNAME, "test-host");
        props.put(DatadogLogsSinkConnectorConfig.DD_SERVICE, "test-service");

        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 500, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);

        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0));
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue2", 0));
        writer.write(records);

        RequestInfo request = restHelper.getCapturedRequests().get(0);

        Assert.assertEquals("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic,team:agent-core,author:berzan\",\"hostname\":\"test-host\",\"service\":\"test-service\"},{\"message\":\"someValue2\",\"ddsource\":\"kafka-connect\",\"ddtags\":\"topic:someTopic,team:agent-core,author:berzan\",\"hostname\":\"test-host\",\"service\":\"test-service\"}]", request.getBody());
    }

    @Test
    public void writer_withUseRecordTimeStampEnabled_shouldPopulateRecordTimestamp() throws IOException {
        props.put(DatadogLogsSinkConnectorConfig.ADD_PUBLISHED_DATE, "true");
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 2, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);


        long recordTime = 1713974401224L;

        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue1", 0, recordTime, TimestampType.CREATE_TIME));
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null, "someValue2", 0, recordTime, TimestampType.CREATE_TIME));
        writer.write(records);

        Assert.assertEquals(1, restHelper.getCapturedRequests().size());

        RequestInfo request = restHelper.getCapturedRequests().get(0);
        System.out.println(request.getBody());
        Assert.assertEquals("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"published_date\":1713974401224,\"ddtags\":\"topic:someTopic\"},{\"message\":\"someValue2\",\"ddsource\":\"kafka-connect\",\"published_date\":1713974401224,\"ddtags\":\"topic:someTopic\"}]", request.getBody());
    }

    @Test
    public void writer_parse_record_headers_enabled() throws IOException {
        props.put(DatadogLogsSinkConnectorConfig.PARSE_RECORD_HEADERS, "true");
        DatadogLogsSinkConnectorConfig config = new DatadogLogsSinkConnectorConfig(false, 2, props);
        DatadogLogsApiWriter writer = new DatadogLogsApiWriter(config);


        Schema keySchema = Schema.INT32_SCHEMA;
        Schema valueSchema = SchemaBuilder.struct()
                .field("field1", Schema.STRING_SCHEMA)
                .field("field2", Schema.INT32_SCHEMA)
                .build();

        Integer key = 123;
        Struct value = new Struct(valueSchema)
                .put("field1", "value1")
                .put("field2", 456);

        Headers headers = new ConnectHeaders();
        headers.addString("headerKey", "headerValue");

        long recordTime = 1713974401224L;

        SinkRecord sinkRecord = new SinkRecord("topicName", 0, keySchema, key, valueSchema, value,
                100L, recordTime, null, headers);

        records.add(sinkRecord);
        records.add(new SinkRecord("someTopic", 0, null, "someKey", null,
                "someValue1", 0, recordTime, TimestampType.CREATE_TIME));
        writer.write(records);

        Assert.assertEquals(2, restHelper.getCapturedRequests().size());

        RequestInfo requestWithHeaders = restHelper.getCapturedRequests().get(0);
        RequestInfo requestWithoutHeaders = restHelper.getCapturedRequests().get(1);

        Set<String> requestBodySetActual = new HashSet<>();
        requestBodySetActual.add(requestWithHeaders.getBody());
        requestBodySetActual.add(requestWithoutHeaders.getBody());
        Set<String> requestBodySetExpected = new HashSet<>();
        requestBodySetExpected.add("[{\"message\":{\"field1\":\"value1\",\"field2\":456},\"ddsource\":\"kafka-connect\",\"kafkaheaders\":{\"headerKey\":\"headerValue\"},\"ddtags\":\"topic:topicName\"}]");
        requestBodySetExpected.add("[{\"message\":\"someValue1\",\"ddsource\":\"kafka-connect\",\"kafkaheaders\":{},\"ddtags\":\"topic:someTopic\"}]");
        Assert.assertEquals(requestBodySetExpected, requestBodySetActual);
        props.remove(DatadogLogsSinkConnectorConfig.PARSE_RECORD_HEADERS);
    }
}
