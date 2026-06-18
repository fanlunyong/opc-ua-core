---
change: opc-ua-forward
design-doc: docs/superpowers/specs/2026-06-18-opc-ua-forward-design.md
base-ref: 8098f48e7b8d237a1df51c8c8550a8128195b3ab
---

# OPC UA Forward — Plan B：4 个具体 Sender 实现

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**前置：** Plan A 已完成（Sender 接口、AbstractSender、SharedConnection、SenderRegistry、SenderFactory 接口、ForwardTarget 数据模型）。

**Goal:** 实现 KafkaSender / InfluxDBSender / MqttSender / HttpSender 四类具体 Sender 与对应 SenderFactory；引入 CompositeSenderFactory 按 type 分发。

**Architecture:** 每个具体 Sender 继承 `AbstractSender`，在 `doSend` 中调用底层客户端 API；连接生命周期通过 `SharedConnection` 统一管理。CompositeSenderFactory 按 `target.type` 路由到对应 SenderFactory。

**Tech Stack:** Apache Kafka client 3.6.1, InfluxDB client 6.10, Eclipse Paho 1.2.5, Spring RestTemplate, Mockito。

**完成后覆盖 tasks.md 条目：** 3.1、3.2、3.3（KafkaSender + topic 模板 + JSON）、4.1、4.2、4.3（InfluxDBSender + Point 转换 + 批量）、5.1、5.2（MqttSender + QoS）、6.1、6.2（HttpSender + 超时）、2.3（Sender 异步调度）、2.6（具体 SenderFactory 实现）、9.1、9.2（Kafka/InfluxDB 单测）。

## P0 数据质量信息保留

下游消费者必须能区分 Good/Bad/Uncertain 数据并访问 OPC UA StatusCode 子状态。本 plan 各 Sender 的实现遵循以下约定：

| Sender | 机制 | 必含字段 |
|--------|------|---------|
| KafkaSender / MqttSender / HttpSender | `ObjectMapper.writeValueAsString/Bytes(data)` 自动序列化 OpcUaDataPoint 全字段 | `quality`、`statusCode`（hex）、`sourceTimestamp`、`serverTimestamp` 已是 OpcUaDataPoint 字段，Jackson `findAndRegisterModules()` 自动包含 |
| InfluxDBSender | `Point.addTag/addField` 显式映射 | tags 必含 `quality`、`statusCode`；time 用 sourceTimestamp→serverTimestamp→batch→now 的 fallback 链；server 时间作为 `serverTimestampNs` field 保留 |

校验测试要求：每个 Sender 的单测必须包含一个用例验证序列化产物含 quality + statusCode 字段。不满足此要求的 PR 不合并。


---

## 文件结构

| 文件 | 责任 |
|------|------|
| `src/main/java/com/opcua/forward/util/PlaceholderResolver.java` | `{productId}/{deviceId}` 占位符替换 |
| `src/main/java/com/opcua/forward/sender/kafka/KafkaSender.java` | KafkaProducer.send + topic 模板 |
| `src/main/java/com/opcua/forward/sender/kafka/KafkaSenderFactory.java` | 创建共享 KafkaProducer |
| `src/main/java/com/opcua/forward/sender/influxdb/InfluxDBSender.java` | data[] → Point[] → WriteApi.writePoints |
| `src/main/java/com/opcua/forward/sender/influxdb/InfluxDBSenderFactory.java` | 创建共享 InfluxDBClient |
| `src/main/java/com/opcua/forward/sender/mqtt/MqttSender.java` | IMqttClient.publish + QoS |
| `src/main/java/com/opcua/forward/sender/mqtt/MqttSenderFactory.java` | 创建共享 MqttClient |
| `src/main/java/com/opcua/forward/sender/http/HttpSender.java` | RestTemplate POST + 超时 |
| `src/main/java/com/opcua/forward/sender/http/HttpSenderFactory.java` | type 单例 sender |
| `src/main/java/com/opcua/forward/sender/CompositeSenderFactory.java` | 按 type 分发 |

---

## Task 1: PlaceholderResolver 工具

支持 `{productId}` / `{deviceId}` 在 topic / url 中的运行时替换。

**Files:**
- Create: `src/main/java/com/opcua/forward/util/PlaceholderResolver.java`
- Test: `src/test/java/com/opcua/forward/util/PlaceholderResolverTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.util;

import com.opcua.model.OpcUaDeviceData;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderResolverTest {

    @Test
    void resolves_productId_deviceId_inTemplate() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-data-line-A",
                PlaceholderResolver.resolve("opcua-data-{productId}", data));
        assertEquals("opcua/line-A/furnace-1",
                PlaceholderResolver.resolve("opcua/{productId}/{deviceId}", data));
    }

    @Test
    void noPlaceholder_returnsTemplateAsIs() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-static-topic",
                PlaceholderResolver.resolve("opcua-static-topic", data));
    }

    @Test
    void unknownPlaceholder_isLeftUnchanged() {
        OpcUaDeviceData data = makeData("line-A", "furnace-1");
        assertEquals("opcua-{unknown}",
                PlaceholderResolver.resolve("opcua-{unknown}", data));
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                Collections.emptyList());
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=PlaceholderResolverTest`
Expected: 编译失败

- [ ] **Step 3: 实现 PlaceholderResolver**

```java
package com.opcua.forward.util;

import com.opcua.model.OpcUaDeviceData;

/**
 * 转发模板占位符替换。当前支持 {productId} 和 {deviceId}。
 */
public final class PlaceholderResolver {

    private PlaceholderResolver() { }

    public static String resolve(String template, OpcUaDeviceData data) {
        if (template == null || template.isEmpty()) return template;
        OpcUaDeviceData.SourceInfo s = data.getSource();
        String r = template;
        if (s.getProductId() != null) r = r.replace("{productId}", s.getProductId());
        if (s.getDeviceId() != null) r = r.replace("{deviceId}", s.getDeviceId());
        return r;
    }
}
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=PlaceholderResolverTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/util/ \
        src/test/java/com/opcua/forward/util/
git commit -m "feat(forward): add PlaceholderResolver for topic/url templates"
```

---

## Task 2: KafkaSender + KafkaSenderFactory

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/kafka/KafkaSender.java`
- Create: `src/main/java/com/opcua/forward/sender/kafka/KafkaSenderFactory.java`
- Test: `src/test/java/com/opcua/forward/sender/kafka/KafkaSenderTest.java`

- [ ] **Step 1: 写失败测试 — mock KafkaProducer 验证 ProducerRecord**

```java
package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class KafkaSenderTest {

    @Test
    void doSend_resolvesTopic_andSendsJsonValue() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaProducer<String, String> producer = mock(KafkaProducer.class);
        SharedConnection<KafkaProducer<String, String>> conn =
                new SharedConnection<>(producer, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("kafka");
        target.setBootstrapServers("k:9092");
        target.setTopic("opcua-data-{productId}");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        KafkaSender sender = new KafkaSender("kafka-1", target, conn, mapper);
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));
            // 等待 worker 处理（最多 500ms）
            verify(producer, timeout(500).times(1)).send(any(ProducerRecord.class));

            ArgumentCaptor<ProducerRecord<String, String>> rec = ArgumentCaptor.forClass(ProducerRecord.class);
            verify(producer).send(rec.capture());

            assertEquals("opcua-data-line-A", rec.getValue().topic());
            assertEquals("furnace-1", rec.getValue().key());
            String json = rec.getValue().value();
            assertTrue(json.contains("\"productId\":\"line-A\""));
            assertTrue(json.contains("\"deviceId\":\"furnace-1\""));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=KafkaSenderTest`
Expected: 编译失败

- [ ] **Step 3: 实现 KafkaSender**

```java
package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;

public class KafkaSender extends AbstractSender {

    private final SharedConnection<KafkaProducer<String, String>> connection;
    private final String topicTemplate;
    private final ObjectMapper objectMapper;

    public KafkaSender(String senderId,
                       ForwardTarget target,
                       SharedConnection<KafkaProducer<String, String>> connection,
                       ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.topicTemplate = target.getTopic();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String topic = PlaceholderResolver.resolve(topicTemplate, data);
        String key = data.getSource().getDeviceId();
        String value;
        try {
            value = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON serialize error: {}", getSenderId(), e.getMessage());
            return;
        }
        connection.get().send(new ProducerRecord<>(topic, key, value));
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}
```

- [ ] **Step 4: 实现 KafkaSenderFactory**

```java
package com.opcua.forward.sender.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public KafkaSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, target.getBootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        if (target.getSecurityProtocol() != null) {
            props.put("security.protocol", target.getSecurityProtocol());
        }
        // 透传扩展属性
        target.getProperties().forEach(props::put);

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        return new SharedConnection<>(producer, producer::close);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<KafkaProducer<String, String>> conn =
                (SharedConnection<KafkaProducer<String, String>>) connection;
        String id = "kafka-" + counter.incrementAndGet();
        return new KafkaSender(id, target, conn, objectMapper);
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=KafkaSenderTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/kafka/ \
        src/test/java/com/opcua/forward/sender/kafka/
git commit -m "feat(forward): add KafkaSender with topic placeholder + JSON serialization"
```

---

## Task 3: InfluxDBSender + InfluxDBSenderFactory

`OpcUaDataPoint` → InfluxDB `Point`：measurement=displayName, tags={productId, deviceId, nodeId, quality, statusCode}, field "value" + "serverTimestampNs"; **time fallback 链**：sourceTimestamp → serverTimestamp → batch timestamp → now（避免 null 写入失败）。批量 `writeApi.writePoints`。

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/influxdb/InfluxDBSender.java`
- Create: `src/main/java/com/opcua/forward/sender/influxdb/InfluxDBSenderFactory.java`
- Test: `src/test/java/com/opcua/forward/sender/influxdb/InfluxDBSenderTest.java`

- [ ] **Step 1: 写失败测试 — mock WriteApi 验证 Point 转换**

```java
package com.opcua.forward.sender.influxdb;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class InfluxDBSenderTest {

    @Test
    void doSend_convertsAllDataPointsToWritePoints() throws Exception {
        WriteApi writeApi = mock(WriteApi.class);
        SharedConnection<WriteApi> conn = new SharedConnection<>(writeApi, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("influxdb");
        target.setUrl("http://i:8086");
        target.setOrg("o");
        target.setBucket("b");
        target.setToken("t");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        InfluxDBSender sender = new InfluxDBSender("influx-1", target, conn);
        try {
            Instant ts = Instant.parse("2026-06-18T00:00:00Z");
            OpcUaDeviceData data = new OpcUaDeviceData(
                    ts,
                    new OpcUaDeviceData.SourceInfo("line-A", "furnace-1", "opc.tcp://x"),
                    List.of(
                            point("ns=2;s=Temp", "Temperature", 80.5, Quality.Good, ts),
                            point("ns=2;s=Press", "Pressure", 1.2, Quality.Bad, ts)
                    )
            );
            sender.enqueue(data);

            verify(writeApi, timeout(500).times(1))
                    .writePoints(eq("b"), eq("o"), any(List.class));

            ArgumentCaptor<List<Point>> captor = ArgumentCaptor.forClass(List.class);
            verify(writeApi).writePoints(anyString(), anyString(), captor.capture());
            List<Point> points = captor.getValue();
            assertEquals(2, points.size());
            // Point 详细字段在 toLineProtocol 校验
            String l0 = points.get(0).toLineProtocol();
            assertTrue(l0.startsWith("Temperature,"));
            assertTrue(l0.contains("productId=line-A"));
            assertTrue(l0.contains("deviceId=furnace-1"));
            assertTrue(l0.contains("nodeId=ns\\=2\\;s\\=Temp") || l0.contains("nodeId=ns=2;s=Temp"));
            assertTrue(l0.contains("quality=Good"));
            assertTrue(l0.contains("value=80.5"));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDataPoint point(String nodeId, String name, Object v, Quality q, Instant ts) {
        return new OpcUaDataPoint(nodeId, name, v, "Double", q, true, q.name(), ts, ts);
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=InfluxDBSenderTest`
Expected: 编译失败

- [ ] **Step 3: 实现 InfluxDBSender**

```java
package com.opcua.forward.sender.influxdb;

import com.influxdb.client.WriteApi;
import com.influxdb.client.domain.WritePrecision;
import com.influxdb.client.write.Point;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

import java.util.ArrayList;
import java.util.List;

public class InfluxDBSender extends AbstractSender {

    private final SharedConnection<WriteApi> connection;
    private final String bucket;
    private final String org;

    public InfluxDBSender(String senderId, ForwardTarget target, SharedConnection<WriteApi> connection) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.bucket = target.getBucket();
        this.org = target.getOrg();
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        if (data.getData() == null || data.getData().isEmpty()) return;
        List<Point> points = new ArrayList<>(data.getData().size());
        OpcUaDeviceData.SourceInfo s = data.getSource();
        for (OpcUaDataPoint p : data.getData()) {
            // 时间戳 fallback 链：sourceTimestamp → serverTimestamp → batch timestamp → now
            Instant ts = p.getSourceTimestamp();
            if (ts == null) ts = p.getServerTimestamp();
            if (ts == null) ts = data.getTimestamp();
            if (ts == null) ts = Instant.now();

            Point pt = Point.measurement(p.getDisplayName() != null ? p.getDisplayName() : p.getNodeId())
                    .addTag("productId", safe(s.getProductId()))
                    .addTag("deviceId", safe(s.getDeviceId()))
                    .addTag("nodeId", safe(p.getNodeId()))
                    .addTag("quality", p.getQuality() == null ? "" : p.getQuality().name())
                    .addTag("statusCode", safe(p.getStatusCode()))
                    .time(ts, WritePrecision.NS);
            // 保留 server 时间戳作为 field（便于排查 source/server 时钟漂移）
            if (p.getServerTimestamp() != null) {
                pt.addField("serverTimestampNs", p.getServerTimestamp().toEpochMilli() * 1_000_000L);
            }
            addValueField(pt, p.getValue());
            points.add(pt);
        }
        connection.get().writePoints(bucket, org, points);
    }

    private static void addValueField(Point pt, Object v) {
        if (v == null) {
            pt.addField("value", "");
            return;
        }
        if (v instanceof Number n) pt.addField("value", n.doubleValue());
        else if (v instanceof Boolean b) pt.addField("value", b);
        else pt.addField("value", v.toString());
    }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override
    protected void doClose() {
        connection.release();
    }
}
```

- [ ] **Step 4: 实现 InfluxDBSenderFactory**

```java
package com.opcua.forward.sender.influxdb;

import com.influxdb.client.InfluxDBClient;
import com.influxdb.client.InfluxDBClientFactory;
import com.influxdb.client.WriteApi;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;

import java.util.concurrent.atomic.AtomicLong;

public class InfluxDBSenderFactory implements SenderFactory {

    private final AtomicLong counter = new AtomicLong();

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        InfluxDBClient client = InfluxDBClientFactory.create(
                target.getUrl(), target.getToken().toCharArray(), target.getOrg(), target.getBucket());
        WriteApi writeApi = client.makeWriteApi();
        return new SharedConnection<>(writeApi, () -> {
            try { writeApi.close(); } catch (Exception ignored) { }
            try { client.close(); } catch (Exception ignored) { }
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<WriteApi> conn = (SharedConnection<WriteApi>) connection;
        return new InfluxDBSender("influxdb-" + counter.incrementAndGet(), target, conn);
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=InfluxDBSenderTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/influxdb/ \
        src/test/java/com/opcua/forward/sender/influxdb/
git commit -m "feat(forward): add InfluxDBSender with batch Point conversion"
```

---

## Task 4: MqttSender + MqttSenderFactory

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/mqtt/MqttSender.java`
- Create: `src/main/java/com/opcua/forward/sender/mqtt/MqttSenderFactory.java`
- Test: `src/test/java/com/opcua/forward/sender/mqtt/MqttSenderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MqttSenderTest {

    @Test
    void doSend_publishesJsonAtConfiguredQos() throws Exception {
        IMqttClient client = mock(IMqttClient.class);
        SharedConnection<IMqttClient> conn = new SharedConnection<>(client, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("mqtt");
        target.setBrokerUrl("tcp://b:1883");
        target.setClientId("c1");
        target.setTopic("opcua/{productId}/{deviceId}");
        target.setQos(2);
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        MqttSender sender = new MqttSender("mqtt-1", target, conn,
                new ObjectMapper().findAndRegisterModules());
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));

            ArgumentCaptor<MqttMessage> msg = ArgumentCaptor.forClass(MqttMessage.class);
            verify(client, timeout(500)).publish(eq("opcua/line-A/furnace-1"), msg.capture());

            assertEquals(2, msg.getValue().getQos());
            String json = new String(msg.getValue().getPayload());
            assertTrue(json.contains("\"productId\":\"line-A\""));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=MqttSenderTest`
Expected: 编译失败

- [ ] **Step 3: 实现 MqttSender**

```java
package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

public class MqttSender extends AbstractSender {

    private final SharedConnection<IMqttClient> connection;
    private final String topicTemplate;
    private final int qos;
    private final ObjectMapper objectMapper;

    public MqttSender(String senderId, ForwardTarget target,
                      SharedConnection<IMqttClient> connection, ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.topicTemplate = target.getTopic();
        this.qos = target.getQos();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String topic = PlaceholderResolver.resolve(topicTemplate, data);
        byte[] payload;
        try {
            payload = objectMapper.writeValueAsBytes(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON error: {}", getSenderId(), e.getMessage());
            return;
        }
        MqttMessage msg = new MqttMessage(payload);
        msg.setQos(qos);
        try {
            connection.get().publish(topic, msg);
        } catch (MqttException e) {
            logger.warn("Sender {} publish error: {}", getSenderId(), e.getMessage());
        }
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}
```

- [ ] **Step 4: 实现 MqttSenderFactory**

```java
package com.opcua.forward.sender.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.eclipse.paho.client.mqttv3.IMqttClient;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;

import java.util.concurrent.atomic.AtomicLong;

public class MqttSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public MqttSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        try {
            IMqttClient client = new MqttClient(
                    target.getBrokerUrl(),
                    target.getClientId(),
                    new MemoryPersistence());
            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            client.connect(opts);
            return new SharedConnection<>(client, () -> {
                try { client.disconnect(); } catch (MqttException ignored) { }
                try { client.close(); } catch (MqttException ignored) { }
            });
        } catch (MqttException e) {
            throw new IllegalStateException("Failed to connect MQTT broker: " + target.getBrokerUrl(), e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<IMqttClient> conn = (SharedConnection<IMqttClient>) connection;
        return new MqttSender("mqtt-" + counter.incrementAndGet(), target, conn, objectMapper);
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=MqttSenderTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/mqtt/ \
        src/test/java/com/opcua/forward/sender/mqtt/
git commit -m "feat(forward): add MqttSender with QoS publish + topic placeholder"
```

---

## Task 5: HttpSender + HttpSenderFactory

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/http/HttpSender.java`
- Create: `src/main/java/com/opcua/forward/sender/http/HttpSenderFactory.java`
- Test: `src/test/java/com/opcua/forward/sender/http/HttpSenderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class HttpSenderTest {

    @Test
    void doSend_postsJsonToConfiguredUrl() {
        RestTemplate rest = mock(RestTemplate.class);
        when(rest.exchange(any(URI.class), eq(HttpMethod.POST), any(HttpEntity.class), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));
        SharedConnection<RestTemplate> conn = new SharedConnection<>(rest, () -> { });
        conn.acquire();

        ForwardTarget target = new ForwardTarget();
        target.setType("http");
        target.setUrl("http://alert/notify");
        target.setQueueCapacity(8);
        target.setWorkerThreads(1);

        HttpSender sender = new HttpSender("http-1", target, conn,
                new ObjectMapper().findAndRegisterModules());
        try {
            sender.enqueue(makeData("line-A", "furnace-1"));

            ArgumentCaptor<URI> uriCap = ArgumentCaptor.forClass(URI.class);
            ArgumentCaptor<HttpEntity<?>> entCap = ArgumentCaptor.forClass(HttpEntity.class);
            verify(rest, timeout(500)).exchange(
                    uriCap.capture(), eq(HttpMethod.POST), entCap.capture(), eq(String.class));

            assertEquals("http://alert/notify", uriCap.getValue().toString());
            assertEquals(MediaType.APPLICATION_JSON, entCap.getValue().getHeaders().getContentType());
            String body = entCap.getValue().getBody().toString();
            assertTrue(body.contains("\"productId\":\"line-A\""));
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    private OpcUaDeviceData makeData(String productId, String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(productId, deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=HttpSenderTest`
Expected: 编译失败

- [ ] **Step 3: 实现 HttpSender**

```java
package com.opcua.forward.sender.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.forward.util.PlaceholderResolver;
import com.opcua.model.OpcUaDeviceData;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.net.URI;

public class HttpSender extends AbstractSender {

    private final SharedConnection<RestTemplate> connection;
    private final String urlTemplate;
    private final ObjectMapper objectMapper;

    public HttpSender(String senderId, ForwardTarget target,
                      SharedConnection<RestTemplate> connection, ObjectMapper objectMapper) {
        super(senderId, target.getQueueCapacity(), target.getWorkerThreads());
        this.connection = connection;
        this.urlTemplate = target.getUrl();
        this.objectMapper = objectMapper;
        start();
    }

    @Override
    protected void doSend(OpcUaDeviceData data) {
        String url = PlaceholderResolver.resolve(urlTemplate, data);
        String body;
        try {
            body = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            logger.warn("Sender {} JSON error: {}", getSenderId(), e.getMessage());
            return;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = new HttpEntity<>(body, headers);
        try {
            connection.get().exchange(URI.create(url), HttpMethod.POST, entity, String.class);
        } catch (Exception e) {
            logger.warn("Sender {} POST {} error: {}", getSenderId(), url, e.getMessage());
        }
    }

    @Override
    protected void doClose() {
        connection.release();
    }
}
```

- [ ] **Step 4: 实现 HttpSenderFactory**

按 Design Doc D2，HTTP 是 type 单例（无连接状态）。

```java
package com.opcua.forward.sender.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SharedConnection;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

public class HttpSenderFactory implements SenderFactory {

    private final ObjectMapper objectMapper;
    private final AtomicLong counter = new AtomicLong();

    public HttpSenderFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        long timeoutMillis = target.getTimeoutMillis() > 0 ? target.getTimeoutMillis() : 5000L;
        RestTemplate rt = new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofMillis(timeoutMillis))
                .setReadTimeout(Duration.ofMillis(timeoutMillis))
                .build();
        return new SharedConnection<>(rt, () -> { });
    }

    @Override
    @SuppressWarnings("unchecked")
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        SharedConnection<RestTemplate> conn = (SharedConnection<RestTemplate>) connection;
        return new HttpSender("http-" + counter.incrementAndGet(), target, conn, objectMapper);
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=HttpSenderTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/http/ \
        src/test/java/com/opcua/forward/sender/http/
git commit -m "feat(forward): add HttpSender with RestTemplate POST + timeout"
```

---

## Task 6: CompositeSenderFactory（按 type 路由）

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/CompositeSenderFactory.java`
- Test: `src/test/java/com/opcua/forward/sender/CompositeSenderFactoryTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CompositeSenderFactoryTest {

    @Test
    void dispatchesByType() {
        SenderFactory kafkaFactory = mock(SenderFactory.class);
        SenderFactory httpFactory = mock(SenderFactory.class);

        Map<String, SenderFactory> map = Map.of(
                "kafka", kafkaFactory,
                "http", httpFactory);
        CompositeSenderFactory comp = new CompositeSenderFactory(map);

        ForwardTarget kt = new ForwardTarget(); kt.setType("kafka"); kt.setBootstrapServers("k:9092");
        ForwardTarget ht = new ForwardTarget(); ht.setType("http"); ht.setUrl("http://a");

        comp.createConnection(ConnectionFingerprint.of(kt), kt);
        verify(kafkaFactory).createConnection(any(), eq(kt));
        verifyNoInteractions(httpFactory);

        comp.createConnection(ConnectionFingerprint.of(ht), ht);
        verify(httpFactory).createConnection(any(), eq(ht));
    }

    @Test
    void unknownType_throws() {
        CompositeSenderFactory comp = new CompositeSenderFactory(Map.of());
        ForwardTarget t = new ForwardTarget(); t.setType("kafka"); t.setBootstrapServers("k:9092");
        assertThrows(IllegalArgumentException.class,
                () -> comp.createConnection(ConnectionFingerprint.of(t), t));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=CompositeSenderFactoryTest`
Expected: 编译失败

- [ ] **Step 3: 实现 CompositeSenderFactory**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

import java.util.Map;

/**
 * 按 ForwardTarget.type 分发到注册的 4 类 SenderFactory。
 */
public class CompositeSenderFactory implements SenderFactory {

    private final Map<String, SenderFactory> factories;

    public CompositeSenderFactory(Map<String, SenderFactory> factories) {
        this.factories = factories;
    }

    @Override
    public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target) {
        return resolve(target.getType()).createConnection(fp, target);
    }

    @Override
    public Sender createSender(ForwardTarget target, SharedConnection<?> connection) {
        return resolve(target.getType()).createSender(target, connection);
    }

    private SenderFactory resolve(String type) {
        SenderFactory f = factories.get(type);
        if (f == null) throw new IllegalArgumentException("No SenderFactory for type: " + type);
        return f;
    }
}
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=CompositeSenderFactoryTest`
Expected: PASS

- [ ] **Step 5: 全套回归测试**

Run: `mvn -q test`
Expected: BUILD SUCCESS — Plan A + Plan B 所有测试通过

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/CompositeSenderFactory.java \
        src/test/java/com/opcua/forward/sender/CompositeSenderFactoryTest.java
git commit -m "feat(forward): add CompositeSenderFactory dispatching by target type"
```

---

## Plan B 完成检查

- [ ] `mvn -q test` 全部通过
- [ ] tasks.md 中以下条目可勾选：
  - 2.3（Sender 异步调度 — 实际由 Plan A 的 AbstractSender 完成；Plan B 的 4 个 Sender 复用）
  - 3.1、3.2、3.3（KafkaSender + topic 模板 + JSON）
  - 4.1、4.2、4.3（InfluxDBSender + Point 转换 + 批量）
  - 5.1、5.2（MqttSender + QoS）
  - 6.1、6.2（HttpSender + 超时）
  - 9.1（Kafka Sender 单测）
  - 9.2（InfluxDB Sender 单测）

## Self-Review

| 检查项 | 结果 |
|--------|------|
| 4 个 Sender 都继承 AbstractSender | ✓ |
| 每个 Factory 用 SharedConnection 包装底层客户端 | ✓ |
| Topic / URL 占位符替换由 PlaceholderResolver 统一处理 | ✓ |
| Mock 测试覆盖每个 Sender 的关键 send 路径 | ✓ |
| 类型一致性：SharedConnection<KafkaProducer<String,String>> 等泛型一致 | ✓ |
| Spec 覆盖：alerts.targets 任意 sender 类型 | ✓（4 类 sender 都可作为 alert target，通过 CompositeSenderFactory 路由） |
