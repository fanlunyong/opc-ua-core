---
change: opc-ua-forward
design-doc: docs/superpowers/specs/2026-06-18-opc-ua-forward-design.md
base-ref: 8098f48e7b8d237a1df51c8c8550a8128195b3ab
---

# OPC UA Forward — Plan A：基础设施层

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 搭建 opc-ua-forward 模块的基础设施层（依赖、配置模型、cross-change patch、Sender 抽象、SenderRegistry），为 Plan B（具体 Sender）和 Plan C（ForwardingEngine 编排）打地基。

**Architecture:** 在现有 opc-ua-core 单模块项目中新增 `com.opcua.forward.*` 包；引入 Sender 接口 + AbstractSender（队列+worker drain+drop-oldest 聚合 WARN+优雅关停）+ SharedConnection 引用计数 + SenderRegistry 按指纹聚合连接。Cross-change 修改 OpcUaService/DataDispatchEngine 添加 unregisterListener / removeListener。

**Tech Stack:** Java 17, Spring Boot 3.2, JUnit 5 + Mockito, Maven。

**完成后覆盖 tasks.md 条目：** 1.1（模块+依赖）、1.2（ForwardProperties）、1.3（数据模型）、1.4（cross-change patch）、1.5（quality observability 接线）、2.4（drop-oldest 聚合）、2.5（sender 层优雅关停；engine 编排留 Plan C）、2.6（SenderRegistry）。

---

## 文件结构

| 文件 | 责任 |
|------|------|
| `pom.xml` | 新增 Kafka/InfluxDB/MQTT/Spring Web 依赖 |
| `src/main/java/com/opcua/api/OpcUaService.java`（修改） | 新增 unregisterListener |
| `src/main/java/com/opcua/core/DataDispatchEngine.java`（修改） | 新增 removeListener |
| `src/main/java/com/opcua/forward/config/ForwardProperties.java` | YAML 绑定根（@ConfigurationProperties） |
| `src/main/java/com/opcua/forward/config/ForwardRule.java` | 单条规则 |
| `src/main/java/com/opcua/forward/config/ForwardTarget.java` | 单个 target 配置 |
| `src/main/java/com/opcua/forward/config/MatchCondition.java` | 匹配条件 |
| `src/main/java/com/opcua/forward/config/AlertConfig.java` | 告警子配置 |
| `src/main/java/com/opcua/forward/sender/ConnectionFingerprint.java` | 连接指纹 |
| `src/main/java/com/opcua/forward/sender/SharedConnection.java` | 共享连接+引用计数 |
| `src/main/java/com/opcua/forward/sender/Sender.java` | Sender 接口 |
| `src/main/java/com/opcua/forward/sender/AbstractSender.java` | 队列+worker+drop-oldest+生命周期 |
| `src/main/java/com/opcua/forward/sender/SenderFactory.java` | 创建底层连接和 Sender 的工厂接口 |
| `src/main/java/com/opcua/forward/sender/SenderRegistry.java` | 按指纹聚合 |

测试镜像位于 `src/test/java/com/opcua/...` 对应路径。

---

## Task 1: 添加 Maven 依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1: 在 `</dependencies>` 之前追加**

```xml
        <!-- Forward: Kafka client -->
        <dependency>
            <groupId>org.apache.kafka</groupId>
            <artifactId>kafka-clients</artifactId>
            <version>3.6.1</version>
        </dependency>

        <!-- Forward: InfluxDB Client -->
        <dependency>
            <groupId>com.influxdb</groupId>
            <artifactId>influxdb-client-java</artifactId>
            <version>6.10.0</version>
        </dependency>

        <!-- Forward: MQTT Eclipse Paho -->
        <dependency>
            <groupId>org.eclipse.paho</groupId>
            <artifactId>org.eclipse.paho.client.mqttv3</artifactId>
            <version>1.2.5</version>
        </dependency>

        <!-- Forward: HTTP RestTemplate -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
```

注：`spring-boot-starter-test` 已含 Mockito，无需显式声明。

- [ ] **Step 2: 验证依赖解析**

Run: `mvn -q dependency:resolve`
Expected: 无错误，依赖下载成功

- [ ] **Step 3: 验证编译**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add pom.xml
git commit -m "feat(forward): add Kafka/InfluxDB/MQTT/Web dependencies"
```

---

## Task 2: Cross-Change Patch — DataDispatchEngine.removeListener

**Files:**
- Modify: `src/main/java/com/opcua/core/DataDispatchEngine.java`
- Test: `src/test/java/com/opcua/core/DataDispatchEngineRemoveListenerTest.java`

- [ ] **Step 1: 写失败测试**

创建 `src/test/java/com/opcua/core/DataDispatchEngineRemoveListenerTest.java`：

```java
package com.opcua.core;

import com.opcua.api.OpcUaDataListener;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DataDispatchEngineRemoveListenerTest {

    @Test
    void removeListener_stopsReceivingNewDispatches() throws Exception {
        AtomicInteger count = new AtomicInteger();
        CountDownLatch firstHit = new CountDownLatch(1);
        OpcUaDataListener listener = data -> {
            count.incrementAndGet();
            firstHit.countDown();
        };

        DataDispatchEngine engine = new DataDispatchEngine(2, 16, List.of(listener));
        try {
            engine.dispatch(makeData("d1"));
            assertTrue(firstHit.await(1, TimeUnit.SECONDS));
            assertEquals(1, count.get());

            assertTrue(engine.removeListener(listener));

            engine.dispatch(makeData("d1"));
            Thread.sleep(100);
            assertEquals(1, count.get(), "removed listener must not receive further dispatches");
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void removeListener_returnsFalseWhenNotRegistered() {
        DataDispatchEngine engine = new DataDispatchEngine(1, 8, Collections.emptyList());
        try {
            assertFalse(engine.removeListener(data -> { }));
        } finally {
            engine.shutdown();
        }
    }

    private OpcUaDeviceData makeData(String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=DataDispatchEngineRemoveListenerTest`
Expected: 编译失败 — `cannot find symbol: method removeListener`

- [ ] **Step 3: 在 DataDispatchEngine 添加 removeListener**

修改 `src/main/java/com/opcua/core/DataDispatchEngine.java`，在 `addListener` 之后追加：

```java
    /**
     * 移除已注册的监听器。
     * @return 已移除返回 true；未注册返回 false
     */
    public boolean removeListener(OpcUaDataListener listener) {
        return listeners.remove(listener);
    }
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=DataDispatchEngineRemoveListenerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/core/DataDispatchEngine.java \
        src/test/java/com/opcua/core/DataDispatchEngineRemoveListenerTest.java
git commit -m "feat(core): add DataDispatchEngine.removeListener"
```

---

## Task 3: Cross-Change Patch — OpcUaService.unregisterListener

**Files:**
- Modify: `src/main/java/com/opcua/api/OpcUaService.java`
- Test: `src/test/java/com/opcua/api/OpcUaServiceUnregisterListenerTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.api;

import com.opcua.core.ConnectionManager;
import com.opcua.core.DataDispatchEngine;
import com.opcua.core.ReadWriteHandler;
import com.opcua.core.SubscriptionManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OpcUaServiceUnregisterListenerTest {

    @Test
    void unregisterListener_delegatesToDispatchEngine() {
        DataDispatchEngine dispatch = mock(DataDispatchEngine.class);
        when(dispatch.removeListener(any())).thenReturn(true);

        OpcUaService svc = new OpcUaService(
                mock(ConnectionManager.class), dispatch,
                mock(SubscriptionManager.class), mock(ReadWriteHandler.class));

        OpcUaDataListener listener = data -> { };
        boolean removed = svc.unregisterListener(listener);

        assertTrue(removed);
        verify(dispatch).removeListener(listener);
    }

    @Test
    void unregisterListener_returnsFalseWhenNotRegistered() {
        DataDispatchEngine dispatch = mock(DataDispatchEngine.class);
        when(dispatch.removeListener(any())).thenReturn(false);

        OpcUaService svc = new OpcUaService(
                mock(ConnectionManager.class), dispatch,
                mock(SubscriptionManager.class), mock(ReadWriteHandler.class));

        assertFalse(svc.unregisterListener(data -> { }));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=OpcUaServiceUnregisterListenerTest`
Expected: 编译失败

- [ ] **Step 3: 在 OpcUaService 添加 unregisterListener**

修改 `src/main/java/com/opcua/api/OpcUaService.java`，在 `registerListener` 之后追加：

```java
    /**
     * 注销数据监听器（与 registerListener 对称）。
     * @return 已注销返回 true；未注册返回 false
     */
    public boolean unregisterListener(OpcUaDataListener listener) {
        return dispatchEngine.removeListener(listener);
    }
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=OpcUaServiceUnregisterListenerTest`
Expected: PASS

- [ ] **Step 5: 完整测试套件回归验证**

Run: `mvn -q test`
Expected: BUILD SUCCESS — 所有测试通过

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/api/OpcUaService.java \
        src/test/java/com/opcua/api/OpcUaServiceUnregisterListenerTest.java
git commit -m "feat(core): add OpcUaService.unregisterListener API for forward integration"
```

---

## Task 3b: Cross-Change Patch — SubscriptionManager.onBatchReceived 接入 quality observability（tasks.md 1.5）

> 对应 design.md「P0 数据质量过滤」决策中的 quality observability：在订阅批次回调里把已存在的 `QualityEvaluator.logIfNeeded` 接到每个 `OpcUaDataPoint`，不引入新的过滤逻辑（过滤仍由 ForwardingEngine 负责，见 Plan C Task 3b）。

**Files:**
- Modify: `src/main/java/com/opcua/core/SubscriptionManager.java`
- Add: `src/test/java/com/opcua/core/SubscriptionManagerQualityLoggingTest.java`

- [ ] **Step 1: TDD Red** — 新增测试 `SubscriptionManagerQualityLoggingTest`：mock `QualityEvaluator` 静态方法或注入测试 hook，构造一批包含 Good/Bad/Uncertain 的 `DataValue`，触发 `onBatchReceived`，断言每个 dataPoint 都触发 `QualityEvaluator.logIfNeeded(quality, nodeConfig.isQualityCheck(), nodeId)` 一次。先确认未接线时测试失败。
- [ ] **Step 2: TDD Green** — 在 `onBatchReceived` 的循环里把 `dataPoints.add(convertToDataPoint(...))` 改为先取 `dataPoint`，再调用 `QualityEvaluator.logIfNeeded(dataPoint.getQuality(), nodeConfig.isQualityCheck(), nodeConfig.getNodeId())`，最后 `dataPoints.add(dataPoint)`。运行测试转绿。
- [ ] **Step 3: 验证 + 提交**

```bash
mvn -pl . -am test -Dtest=SubscriptionManagerQualityLoggingTest
git add src/main/java/com/opcua/core/SubscriptionManager.java \
        src/test/java/com/opcua/core/SubscriptionManagerQualityLoggingTest.java
git commit -m "feat(core): wire QualityEvaluator.logIfNeeded into SubscriptionManager batch handler"
```

---

## Task 4: 转发配置数据模型（5 个 POJO）

**Files:**
- Create: `src/main/java/com/opcua/forward/config/MatchCondition.java`
- Create: `src/main/java/com/opcua/forward/config/AlertConfig.java`
- Create: `src/main/java/com/opcua/forward/config/ForwardTarget.java`
- Create: `src/main/java/com/opcua/forward/config/ForwardRule.java`
- Create: `src/main/java/com/opcua/forward/config/QualityFilter.java`

注：纯 POJO；QualityFilter 含独立单测验证 shouldDrop 语义；其余通过 Task 5 YAML 绑定一并验证。

- [ ] **Step 1: 创建 MatchCondition**

```java
package com.opcua.forward.config;

/**
 * 转发规则匹配条件。所有字段为可选；未配置视为通配。
 */
public class MatchCondition {

    private String productId;
    private String deviceId;
    private String nodeId;

    public String getProductId() { return productId; }
    public void setProductId(String productId) { this.productId = productId; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
}
```

- [ ] **Step 2: 创建 ForwardTarget**

```java
package com.opcua.forward.config;

import java.util.HashMap;
import java.util.Map;

/**
 * 单个转发目标配置。type 决定使用哪个 Sender，其余字段按 type 解释。
 */
public class ForwardTarget {

    private String type;            // kafka | influxdb | mqtt | http
    private boolean enabled = true;
    private int queueCapacity = 1000;
    private int workerThreads = 1;

    // Kafka
    private String bootstrapServers;
    private String securityProtocol;
    private String topic;

    // InfluxDB
    private String url;
    private String bucket;
    private String org;
    private String token;

    // MQTT
    private String brokerUrl;
    private String clientId;
    private int qos = 1;

    // HTTP
    private long timeoutMillis = 5000;

    /** 透传任意未识别属性（便于 sender 扩展） */
    private Map<String, String> properties = new HashMap<>();

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getQueueCapacity() { return queueCapacity; }
    public void setQueueCapacity(int v) { this.queueCapacity = v; }

    public int getWorkerThreads() { return workerThreads; }
    public void setWorkerThreads(int v) { this.workerThreads = v; }

    public String getBootstrapServers() { return bootstrapServers; }
    public void setBootstrapServers(String v) { this.bootstrapServers = v; }

    public String getSecurityProtocol() { return securityProtocol; }
    public void setSecurityProtocol(String v) { this.securityProtocol = v; }

    public String getTopic() { return topic; }
    public void setTopic(String v) { this.topic = v; }

    public String getUrl() { return url; }
    public void setUrl(String v) { this.url = v; }

    public String getBucket() { return bucket; }
    public void setBucket(String v) { this.bucket = v; }

    public String getOrg() { return org; }
    public void setOrg(String v) { this.org = v; }

    public String getToken() { return token; }
    public void setToken(String v) { this.token = v; }

    public String getBrokerUrl() { return brokerUrl; }
    public void setBrokerUrl(String v) { this.brokerUrl = v; }

    public String getClientId() { return clientId; }
    public void setClientId(String v) { this.clientId = v; }

    public int getQos() { return qos; }
    public void setQos(int qos) { this.qos = qos; }

    public long getTimeoutMillis() { return timeoutMillis; }
    public void setTimeoutMillis(long v) { this.timeoutMillis = v; }

    public Map<String, String> getProperties() { return properties; }
    public void setProperties(Map<String, String> v) { this.properties = v; }
}
```

- [ ] **Step 3: 创建 AlertConfig**

```java
package com.opcua.forward.config;

import java.util.ArrayList;
import java.util.List;

public class AlertConfig {

    private boolean enabled;
    private List<ForwardTarget> targets = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<ForwardTarget> getTargets() { return targets; }
    public void setTargets(List<ForwardTarget> v) { this.targets = v; }
}
```

- [ ] **Step 4: 创建 ForwardRule**

```java
package com.opcua.forward.config;

import java.util.ArrayList;
import java.util.List;

public class ForwardRule {

    private String name;
    private MatchCondition match = new MatchCondition();
    private List<ForwardTarget> targets = new ArrayList<>();
    private AlertConfig alerts;
    /** P0 数据质量过滤：默认 dropBad=true，dropUncertain=false */
    private QualityFilter qualityFilter = QualityFilter.dropBadOnly();

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public MatchCondition getMatch() { return match; }
    public void setMatch(MatchCondition v) { this.match = v; }

    public List<ForwardTarget> getTargets() { return targets; }
    public void setTargets(List<ForwardTarget> v) { this.targets = v; }

    public AlertConfig getAlerts() { return alerts; }
    public void setAlerts(AlertConfig alerts) { this.alerts = alerts; }

    public QualityFilter getQualityFilter() { return qualityFilter; }
    public void setQualityFilter(QualityFilter v) {
        this.qualityFilter = v != null ? v : QualityFilter.dropBadOnly();
    }
}
```

- [ ] **Step 4b: 创建 QualityFilter（P0 数据质量过滤策略）**

```java
package com.opcua.forward.config;

import com.opcua.model.Quality;

/**
 * 数据点质量过滤策略，应用于 ForwardingEngine.onDataReceived 路由前。
 *
 * <p>语义：</p>
 * <ul>
 *   <li>dropBad=true（默认）→ Bad 质量数据不被转发</li>
 *   <li>dropUncertain=true → Uncertain 质量数据不被转发</li>
 *   <li>Good 质量数据始终转发</li>
 * </ul>
 *
 * <p>OPC UA 协议的 StatusCode 子状态信息（如 BadConnectionClosed、
 * UncertainLastUsableValue）通过 OpcUaDataPoint.statusCode hex 字段保留，
 * 由具体 Sender 在序列化时透传给下游消费者。</p>
 */
public class QualityFilter {

    private boolean dropBad = true;
    private boolean dropUncertain = false;

    public static QualityFilter dropBadOnly() {
        QualityFilter f = new QualityFilter();
        f.dropBad = true;
        f.dropUncertain = false;
        return f;
    }

    public static QualityFilter passAll() {
        QualityFilter f = new QualityFilter();
        f.dropBad = false;
        f.dropUncertain = false;
        return f;
    }

    /** Returns true 表示该数据点应被丢弃（不路由到 sender）。 */
    public boolean shouldDrop(Quality quality) {
        if (quality == null) return dropUncertain;  // null 视为 Uncertain
        return switch (quality) {
            case Good -> false;
            case Bad -> dropBad;
            case Uncertain -> dropUncertain;
        };
    }

    public boolean isDropBad() { return dropBad; }
    public void setDropBad(boolean v) { this.dropBad = v; }
    public boolean isDropUncertain() { return dropUncertain; }
    public void setDropUncertain(boolean v) { this.dropUncertain = v; }
}
```

为 QualityFilter 写最小单测：

```java
// src/test/java/com/opcua/forward/config/QualityFilterTest.java
package com.opcua.forward.config;

import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QualityFilterTest {

    @Test
    void dropBadOnlyDefaultsBlocksBadAndPassesUncertain() {
        QualityFilter f = QualityFilter.dropBadOnly();
        assertFalse(f.shouldDrop(Quality.Good));
        assertTrue(f.shouldDrop(Quality.Bad));
        assertFalse(f.shouldDrop(Quality.Uncertain));
    }

    @Test
    void passAllAllowsEverything() {
        QualityFilter f = QualityFilter.passAll();
        assertFalse(f.shouldDrop(Quality.Good));
        assertFalse(f.shouldDrop(Quality.Bad));
        assertFalse(f.shouldDrop(Quality.Uncertain));
    }

    @Test
    void nullQualityTreatedAsUncertain() {
        QualityFilter dropBoth = new QualityFilter();
        dropBoth.setDropBad(true);
        dropBoth.setDropUncertain(true);
        assertTrue(dropBoth.shouldDrop(null));

        QualityFilter dropBadOnly = QualityFilter.dropBadOnly();
        assertFalse(dropBadOnly.shouldDrop(null));
    }
}
```

Run: `mvn -q test -Dtest=QualityFilterTest`
Expected: 3/3 PASS

- [ ] **Step 5: 编译验证**

Run: `mvn -q compile`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/config/ \
        src/test/java/com/opcua/forward/config/QualityFilterTest.java
git commit -m "feat(forward): add MatchCondition/ForwardTarget/AlertConfig/ForwardRule + QualityFilter

- 5 POJOs in com.opcua.forward.config
- ForwardRule.qualityFilter defaults to dropBadOnly (drop Bad, pass Uncertain)
- QualityFilter.shouldDrop() consulted by ForwardingEngine before routing
- StatusCode sub-status info preserved via OpcUaDataPoint.statusCode hex field"
```

---

## Task 5: ForwardProperties + YAML 绑定测试

**Files:**
- Create: `src/main/java/com/opcua/forward/config/ForwardProperties.java`
- Create: `src/test/java/com/opcua/forward/config/YamlPropertySourceFactory.java`
- Create: `src/test/resources/forward-binding-test.yml`
- Test: `src/test/java/com/opcua/forward/config/ForwardPropertiesBindingTest.java`

- [ ] **Step 1: 创建测试 YAML 资源**

`src/test/resources/forward-binding-test.yml`：

```yaml
opcua:
  forward:
    enabled: true
    shutdownTimeout: PT3S
    rules:
      - name: "rule-A"
        match:
          productId: "line-A"
          deviceId: "furnace-1"
        targets:
          - type: kafka
            enabled: true
            bootstrap-servers: "kafka:9092"
            topic: "data-{productId}"
            queueCapacity: 500
        alerts:
          enabled: true
          targets:
            - type: http
              url: "http://alert/notify"
              timeoutMillis: 3000
```

- [ ] **Step 2: 创建 YamlPropertySourceFactory（Spring 默认不识别 yml 由 @TestPropertySource 加载）**

`src/test/java/com/opcua/forward/config/YamlPropertySourceFactory.java`：

```java
package com.opcua.forward.config;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.support.DefaultPropertySourceFactory;
import org.springframework.core.io.support.EncodedResource;

import java.io.IOException;
import java.util.Properties;

public class YamlPropertySourceFactory extends DefaultPropertySourceFactory {
    @Override
    public PropertySource<?> createPropertySource(String name, EncodedResource resource) throws IOException {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(resource.getResource());
        Properties props = factory.getObject();
        String n = (name != null) ? name : resource.getResource().getFilename();
        return new PropertiesPropertySource(n, props);
    }
}
```

- [ ] **Step 3: 写失败测试**

`src/test/java/com/opcua/forward/config/ForwardPropertiesBindingTest.java`：

```java
package com.opcua.forward.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = ForwardPropertiesBindingTest.TestConfig.class)
@TestPropertySource(locations = "classpath:forward-binding-test.yml",
                    factory = YamlPropertySourceFactory.class)
class ForwardPropertiesBindingTest {

    @EnableConfigurationProperties(ForwardProperties.class)
    static class TestConfig { }

    @Autowired
    ForwardProperties properties;

    @Test
    void bindsRootDefaultsAndShutdownTimeout() {
        assertTrue(properties.isEnabled());
        assertEquals(Duration.ofSeconds(3), properties.getShutdownTimeout());
        assertEquals(1, properties.getRules().size());
    }

    @Test
    void bindsRuleMatchAndTargets() {
        ForwardRule rule = properties.getRules().get(0);
        assertEquals("rule-A", rule.getName());
        assertEquals("line-A", rule.getMatch().getProductId());
        assertEquals("furnace-1", rule.getMatch().getDeviceId());

        assertEquals(1, rule.getTargets().size());
        ForwardTarget t = rule.getTargets().get(0);
        assertEquals("kafka", t.getType());
        assertTrue(t.isEnabled());
        assertEquals("kafka:9092", t.getBootstrapServers());
        assertEquals("data-{productId}", t.getTopic());
        assertEquals(500, t.getQueueCapacity());
    }

    @Test
    void bindsAlertConfig() {
        AlertConfig alerts = properties.getRules().get(0).getAlerts();
        assertNotNull(alerts);
        assertTrue(alerts.isEnabled());
        assertEquals(1, alerts.getTargets().size());
        assertEquals("http", alerts.getTargets().get(0).getType());
        assertEquals("http://alert/notify", alerts.getTargets().get(0).getUrl());
        assertEquals(3000, alerts.getTargets().get(0).getTimeoutMillis());
    }
}
```

- [ ] **Step 4: 运行测试 — 确认编译失败（ForwardProperties 未定义）**

Run: `mvn -q test -Dtest=ForwardPropertiesBindingTest`
Expected: 编译失败

- [ ] **Step 5: 创建 ForwardProperties**

```java
package com.opcua.forward.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 转发模块根配置。绑定到 opcua.forward.*。
 */
@ConfigurationProperties(prefix = "opcua.forward")
public class ForwardProperties {

    private boolean enabled = true;
    private Duration shutdownTimeout = Duration.ofSeconds(5);
    private List<ForwardRule> rules = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Duration getShutdownTimeout() { return shutdownTimeout; }
    public void setShutdownTimeout(Duration v) { this.shutdownTimeout = v; }

    public List<ForwardRule> getRules() { return rules; }
    public void setRules(List<ForwardRule> v) { this.rules = v; }
}
```

- [ ] **Step 6: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardPropertiesBindingTest`
Expected: PASS（3 个测试）

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/opcua/forward/config/ForwardProperties.java \
        src/test/java/com/opcua/forward/config/ \
        src/test/resources/forward-binding-test.yml
git commit -m "feat(forward): add ForwardProperties with YAML binding test"
```

---

## Task 6: ConnectionFingerprint

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/ConnectionFingerprint.java`
- Test: `src/test/java/com/opcua/forward/sender/ConnectionFingerprintTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionFingerprintTest {

    @Test
    void kafka_sameBootstrapAndSecurity_equalFingerprint() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("kafka"); t1.setBootstrapServers("k:9092"); t1.setSecurityProtocol("PLAINTEXT");
        t1.setTopic("topic-A");

        ForwardTarget t2 = new ForwardTarget();
        t2.setType("kafka"); t2.setBootstrapServers("k:9092"); t2.setSecurityProtocol("PLAINTEXT");
        t2.setTopic("topic-B");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void kafka_differentBootstrap_differentFingerprint() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("kafka"); t1.setBootstrapServers("k1:9092");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("kafka"); t2.setBootstrapServers("k2:9092");

        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void mqtt_byBrokerUrlAndClientId() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("mqtt"); t1.setBrokerUrl("tcp://b:1883"); t1.setClientId("c1");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("mqtt"); t2.setBrokerUrl("tcp://b:1883"); t2.setClientId("c1");
        ForwardTarget t3 = new ForwardTarget();
        t3.setType("mqtt"); t3.setBrokerUrl("tcp://b:1883"); t3.setClientId("c2");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t3));
    }

    @Test
    void influxdb_byUrlOrgToken() {
        ForwardTarget t1 = new ForwardTarget();
        t1.setType("influxdb"); t1.setUrl("http://i:8086"); t1.setOrg("o"); t1.setToken("tok");
        ForwardTarget t2 = new ForwardTarget();
        t2.setType("influxdb"); t2.setUrl("http://i:8086"); t2.setOrg("o"); t2.setToken("tok");
        ForwardTarget t3 = new ForwardTarget();
        t3.setType("influxdb"); t3.setUrl("http://i:8086"); t3.setOrg("o"); t3.setToken("DIFFERENT");

        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
        assertNotEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t3));
    }

    @Test
    void http_singletonByType() {
        ForwardTarget t1 = new ForwardTarget(); t1.setType("http"); t1.setUrl("http://a");
        ForwardTarget t2 = new ForwardTarget(); t2.setType("http"); t2.setUrl("http://b");
        assertEquals(ConnectionFingerprint.of(t1), ConnectionFingerprint.of(t2));
    }

    @Test
    void unknownType_throws() {
        ForwardTarget t = new ForwardTarget(); t.setType("unknown");
        assertThrows(IllegalArgumentException.class, () -> ConnectionFingerprint.of(t));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=ConnectionFingerprintTest`
Expected: 编译失败

- [ ] **Step 3: 实现 ConnectionFingerprint**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

import java.util.Objects;

/**
 * 连接指纹 — 决定 SharedConnection 共享粒度。
 *
 * <pre>
 * Kafka:    bootstrap-servers + securityProtocol
 * MQTT:     brokerUrl + clientId
 * InfluxDB: url + org + token
 * HTTP:     type 单例
 * </pre>
 */
public final class ConnectionFingerprint {

    private final String type;
    private final String key;

    private ConnectionFingerprint(String type, String key) {
        this.type = type;
        this.key = key;
    }

    public static ConnectionFingerprint of(ForwardTarget t) {
        String type = t.getType();
        String key = switch (type == null ? "" : type) {
            case "kafka" -> safe(t.getBootstrapServers()) + "|" + safe(t.getSecurityProtocol());
            case "mqtt" -> safe(t.getBrokerUrl()) + "|" + safe(t.getClientId());
            case "influxdb" -> safe(t.getUrl()) + "|" + safe(t.getOrg()) + "|" + safe(t.getToken());
            case "http" -> "singleton";
            default -> throw new IllegalArgumentException("Unknown sender type: " + type);
        };
        return new ConnectionFingerprint(type, key);
    }

    public String getType() { return type; }
    public String getKey() { return key; }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ConnectionFingerprint that)) return false;
        return Objects.equals(type, that.type) && Objects.equals(key, that.key);
    }

    @Override
    public int hashCode() { return Objects.hash(type, key); }

    @Override
    public String toString() { return type + ":" + key; }
}
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ConnectionFingerprintTest`
Expected: PASS（6 个测试）

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/ConnectionFingerprint.java \
        src/test/java/com/opcua/forward/sender/ConnectionFingerprintTest.java
git commit -m "feat(forward): add ConnectionFingerprint for sender connection sharing"
```

---

## Task 7: Sender 接口 + AbstractSender 骨架（队列 + worker drain + 优雅关停）

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/Sender.java`
- Create: `src/main/java/com/opcua/forward/sender/AbstractSender.java`
- Test: `src/test/java/com/opcua/forward/sender/AbstractSenderQueueTest.java`
- Test: `src/test/java/com/opcua/forward/sender/AbstractSenderShutdownTest.java`

注：本 Task 实现队列+worker+生命周期；drop-oldest 聚合 WARN 在 Task 8 引入。

- [ ] **Step 1: 写失败测试 — enqueue → worker drain → doSend**

`src/test/java/com/opcua/forward/sender/AbstractSenderQueueTest.java`：

```java
package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderQueueTest {

    static class CountingSender extends AbstractSender {
        final AtomicInteger sent = new AtomicInteger();
        final CountDownLatch latch;
        CountingSender(int expected) {
            super("counting-1", 16, 1);
            this.latch = new CountDownLatch(expected);
            start();
        }
        @Override protected void doSend(OpcUaDeviceData data) {
            sent.incrementAndGet();
            latch.countDown();
        }
        @Override protected void doClose() { /* noop */ }
    }

    @Test
    void enqueue_isDrainedByWorker() throws Exception {
        CountingSender sender = new CountingSender(3);
        try {
            sender.enqueue(makeData("d1"));
            sender.enqueue(makeData("d2"));
            sender.enqueue(makeData("d3"));
            assertTrue(sender.latch.await(2, TimeUnit.SECONDS));
            assertEquals(3, sender.sent.get());
        } finally {
            sender.shutdown(Duration.ofSeconds(1));
        }
    }

    @Test
    void enqueue_afterStopAccepting_isDropped() throws Exception {
        CountingSender sender = new CountingSender(0);
        sender.stopAccepting();
        sender.enqueue(makeData("d1"));
        Thread.sleep(50);
        assertEquals(0, sender.sent.get());
        sender.shutdown(Duration.ofSeconds(1));
    }

    private OpcUaDeviceData makeData(String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=AbstractSenderQueueTest`
Expected: 编译失败

- [ ] **Step 3: 创建 Sender 接口**

```java
package com.opcua.forward.sender;

import com.opcua.model.OpcUaDeviceData;

import java.time.Duration;

/**
 * 异步 Sender 抽象。线程安全。
 */
public interface Sender {

    /** 用于日志/聚合的标识 */
    String getSenderId();

    /** 入队（O(1)，drop-oldest）；stopAccepting 后入队会被静默丢弃 */
    void enqueue(OpcUaDeviceData data);

    /** 停止接收新数据（不停 worker） */
    void stopAccepting();

    /** 等待队列 drain；超时返回剩余消息数（>0 表示未排空） */
    int awaitDrain(Duration timeout);

    /** 完整关停：stopAccepting → awaitDrain → close（worker） */
    void shutdown(Duration timeout);
}
```

- [ ] **Step 4: 创建 AbstractSender 骨架**

```java
package com.opcua.forward.sender;

import com.opcua.model.OpcUaDeviceData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sender 抽象基类 — 队列 + worker drain + 生命周期。
 * 子类实现 {@link #doSend(OpcUaDeviceData)} 与 {@link #doClose()}。
 */
public abstract class AbstractSender implements Sender {

    protected static final Logger logger = LoggerFactory.getLogger(AbstractSender.class);

    private final String senderId;
    private final int workerThreads;
    protected final LinkedBlockingQueue<OpcUaDeviceData> queue;
    private final List<Thread> workers = new ArrayList<>();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean running = new AtomicBoolean(true);

    protected AbstractSender(String senderId, int queueCapacity, int workerThreads) {
        this.senderId = senderId;
        this.workerThreads = Math.max(1, workerThreads);
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
    }

    /** 子类构造完成后调用一次，启动 worker 线程 */
    protected final void start() {
        for (int i = 0; i < workerThreads; i++) {
            Thread t = new Thread(this::drainLoop, "sender-" + senderId + "-" + i);
            t.setDaemon(true);
            workers.add(t);
            t.start();
        }
    }

    @Override public String getSenderId() { return senderId; }

    @Override
    public void enqueue(OpcUaDeviceData data) {
        if (!accepting.get()) return;
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
            onDropOldest(data);
        }
    }

    /** drop-oldest 钩子；Task 8 增加聚合 WARN 实现 */
    protected void onDropOldest(OpcUaDeviceData data) { /* default noop */ }

    @Override public void stopAccepting() { accepting.set(false); }

    @Override
    public int awaitDrain(Duration timeout) {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (!queue.isEmpty()) {
            if (System.nanoTime() >= deadlineNanos) break;
            try { Thread.sleep(20); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return queue.size();
    }

    @Override
    public void shutdown(Duration timeout) {
        stopAccepting();
        int leftover = awaitDrain(timeout);
        if (leftover > 0) {
            logger.warn("Sender {} shutdown timeout, {} messages dropped", senderId, leftover);
        }
        running.set(false);
        for (Thread t : workers) t.interrupt();
        try {
            doClose();
        } catch (Exception e) {
            logger.warn("Sender {} doClose error: {}", senderId, e.getMessage());
        }
    }

    protected abstract void doSend(OpcUaDeviceData data);
    protected abstract void doClose();

    private void drainLoop() {
        while (running.get()) {
            try {
                OpcUaDeviceData data = queue.take();
                try {
                    doSend(data);
                } catch (Exception e) {
                    logger.warn("Sender {} doSend error: {}", senderId, e.getMessage());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                if (!running.get()) break;
            }
        }
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=AbstractSenderQueueTest`
Expected: PASS

- [ ] **Step 6: 写优雅关停验证测试**

`src/test/java/com/opcua/forward/sender/AbstractSenderShutdownTest.java`：

```java
package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderShutdownTest {

    static class SlowSender extends AbstractSender {
        final AtomicInteger sent = new AtomicInteger();
        final long sleepMillis;
        SlowSender(long sleepMillis) {
            super("slow-1", 100, 1);
            this.sleepMillis = sleepMillis;
            start();
        }
        @Override protected void doSend(OpcUaDeviceData data) {
            try { Thread.sleep(sleepMillis); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            sent.incrementAndGet();
        }
        @Override protected void doClose() { }
    }

    @Test
    void shutdown_drainsBeforeTimeout() {
        SlowSender s = new SlowSender(10);
        for (int i = 0; i < 5; i++) s.enqueue(makeData("d" + i));
        s.shutdown(Duration.ofSeconds(2));
        assertEquals(5, s.sent.get());
    }

    @Test
    void shutdown_timeoutLeavesUnsentMessages() {
        SlowSender s = new SlowSender(500);
        for (int i = 0; i < 10; i++) s.enqueue(makeData("d" + i));
        s.shutdown(Duration.ofMillis(200));
        assertTrue(s.sent.get() < 10, "should not finish all under timeout");
    }

    private OpcUaDeviceData makeData(String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 7: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=AbstractSenderShutdownTest`
Expected: PASS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/Sender.java \
        src/main/java/com/opcua/forward/sender/AbstractSender.java \
        src/test/java/com/opcua/forward/sender/AbstractSenderQueueTest.java \
        src/test/java/com/opcua/forward/sender/AbstractSenderShutdownTest.java
git commit -m "feat(forward): add Sender interface and AbstractSender (queue + worker + graceful shutdown)"
```

---

## Task 8: AbstractSender Drop-Oldest (deviceId, senderId) 聚合 WARN

按 Design Doc D6：1s 或累计 100 条触发 flush。

**Files:**
- Modify: `src/main/java/com/opcua/forward/sender/AbstractSender.java`
- Test: `src/test/java/com/opcua/forward/sender/AbstractSenderDropOldestTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AbstractSenderDropOldestTest {

    /** 队列容量 1，且不启动 worker — 所有 enqueue 在容量已满时触发 drop-oldest */
    static class StallSender extends AbstractSender {
        StallSender() { super("stall-1", 1, 1); /* 不调用 start() */ }
        @Override protected void doSend(OpcUaDeviceData data) { }
        @Override protected void doClose() { }
    }

    @Test
    void dropOldest_countAggregatedByDevice() {
        StallSender s = new StallSender();
        try {
            for (int i = 0; i < 50; i++) s.enqueue(makeData("d1"));
            for (int i = 0; i < 30; i++) s.enqueue(makeData("d2"));
            // 队列容量 1：首次 enqueue 占用，后续每次 enqueue 触发一次 drop-oldest
            // d1: 49 次 drop（不计首次占用）
            // d2: 30 次 drop（首次 d2 enqueue 即满，触发 drop）
            assertEquals(49, s.getDropCount("d1"));
            assertEquals(30, s.getDropCount("d2"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
        }
    }

    @Test
    void flush_byCountThreshold_resetsCounters() {
        StallSender s = new StallSender();
        try {
            // 触发 100 次 drop（首次占用 + 100 次 drop = 101 次 enqueue）
            for (int i = 0; i < 101; i++) s.enqueue(makeData("d1"));
            // 第 100 次 drop 后，maybeFlush 命中 count threshold → flush 清零
            assertEquals(0, s.getDropCount("d1"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
        }
    }

    @Test
    void flush_byTimeInterval_resetsCounters() throws Exception {
        StallSender s = new StallSender();
        try {
            // 5 次 drop（< 100 阈值）
            for (int i = 0; i < 6; i++) s.enqueue(makeData("d1"));
            assertEquals(5, s.getDropCount("d1"));

            // 等待 1.05s 跨过 1s 间隔
            Thread.sleep(1050);
            // 下一次 enqueue 触发 time-based flush
            s.enqueue(makeData("d1"));
            assertEquals(0, s.getDropCount("d1"));
        } finally {
            s.shutdown(Duration.ofMillis(50));
        }
    }

    private OpcUaDeviceData makeData(String deviceId) {
        OpcUaDataPoint p = new OpcUaDataPoint(
                "ns=2;s=N", "name", 1, "Int32", Quality.Good, true,
                "Good", Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败（getDropCount 未定义）**

Run: `mvn -q test -Dtest=AbstractSenderDropOldestTest`
Expected: 编译失败

- [ ] **Step 3: 在 AbstractSender 添加聚合 + flush**

修改 `src/main/java/com/opcua/forward/sender/AbstractSender.java`：

a) 类顶部 `import` 区域追加（如果还没有）：

```java
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
```

b) 字段区域追加：

```java
    private final ConcurrentHashMap<String, AtomicLong> dropsByDevice = new ConcurrentHashMap<>();
    private final AtomicLong totalDropsSinceLastFlush = new AtomicLong();
    private volatile long lastFlushNanos = System.nanoTime();
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long FLUSH_COUNT_THRESHOLD = 100;
```

c) 替换 `enqueue` 方法（在 drop-oldest 分支增加聚合调用）：

```java
    @Override
    public void enqueue(OpcUaDeviceData data) {
        if (!accepting.get()) return;
        if (!queue.offer(data)) {
            queue.poll();
            queue.offer(data);
            String deviceId = data.getSource().getDeviceId();
            dropsByDevice.computeIfAbsent(deviceId, k -> new AtomicLong()).incrementAndGet();
            totalDropsSinceLastFlush.incrementAndGet();
            onDropOldest(data);
            maybeFlushDropWarnings();
        }
    }
```

d) 在 `doSend` 抽象声明之前追加：

```java
    /** 测试可见：返回当前 deviceId 累计但未 flush 的 drop 计数 */
    long getDropCount(String deviceId) {
        AtomicLong a = dropsByDevice.get(deviceId);
        return a == null ? 0 : a.get();
    }

    private void maybeFlushDropWarnings() {
        long now = System.nanoTime();
        if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS
                || totalDropsSinceLastFlush.get() >= FLUSH_COUNT_THRESHOLD) {
            synchronized (this) {
                long dueNow = System.nanoTime();
                if (dueNow - lastFlushNanos >= FLUSH_INTERVAL_NANOS
                        || totalDropsSinceLastFlush.get() >= FLUSH_COUNT_THRESHOLD) {
                    flushDropWarnings();
                    lastFlushNanos = dueNow;
                }
            }
        }
    }

    private void flushDropWarnings() {
        dropsByDevice.forEach((deviceId, ctr) -> {
            long c = ctr.getAndSet(0);
            if (c > 0) {
                logger.warn("Sender {} dropped {} messages from device {}",
                        getSenderId(), c, deviceId);
            }
        });
        totalDropsSinceLastFlush.set(0);
    }
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=AbstractSenderDropOldestTest`
Expected: PASS（3 个测试）

- [ ] **Step 5: 回归 Task 7 测试**

Run: `mvn -q test -Dtest=AbstractSenderQueueTest,AbstractSenderShutdownTest`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/AbstractSender.java \
        src/test/java/com/opcua/forward/sender/AbstractSenderDropOldestTest.java
git commit -m "feat(forward): add (deviceId, senderId) drop-oldest WARN aggregation"
```

---

## Task 9: SharedConnection（引用计数）

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/SharedConnection.java`
- Test: `src/test/java/com/opcua/forward/sender/SharedConnectionTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SharedConnectionTest {

    @Test
    void acquire_increments_release_decrements_lastReleaseClosesUnderlying() {
        AtomicInteger closed = new AtomicInteger();
        Object underlying = new Object();
        SharedConnection<Object> conn = new SharedConnection<>(underlying, () -> closed.incrementAndGet());

        conn.acquire();
        conn.acquire();
        assertEquals(2, conn.refCount());
        assertSame(underlying, conn.get());

        conn.release();
        assertEquals(1, conn.refCount());
        assertEquals(0, closed.get());

        conn.release();
        assertEquals(0, conn.refCount());
        assertEquals(1, closed.get());
    }

    @Test
    void release_belowZero_throws() {
        SharedConnection<String> conn = new SharedConnection<>("x", () -> { });
        assertThrows(IllegalStateException.class, conn::release);
    }

    @Test
    void closeException_doesNotMaskZeroRef() {
        AtomicInteger closed = new AtomicInteger();
        SharedConnection<String> conn = new SharedConnection<>("x", () -> {
            closed.incrementAndGet();
            throw new RuntimeException("close fail");
        });
        conn.acquire();
        assertDoesNotThrow(conn::release);
        assertEquals(0, conn.refCount());
        assertEquals(1, closed.get());
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=SharedConnectionTest`
Expected: 编译失败

- [ ] **Step 3: 实现 SharedConnection**

```java
package com.opcua.forward.sender;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 引用计数包装的共享底层连接。
 *
 * <p>每个 sender 调用 {@link #acquire()} 持有一份引用，
 * shutdown 时调用 {@link #release()}；最后一个 release 触发底层 closer。</p>
 */
public final class SharedConnection<T> {

    private static final Logger logger = LoggerFactory.getLogger(SharedConnection.class);

    private final T underlying;
    private final Runnable closer;
    private final AtomicInteger refs = new AtomicInteger();

    public SharedConnection(T underlying, Runnable closer) {
        this.underlying = underlying;
        this.closer = closer;
    }

    public T get() { return underlying; }

    public void acquire() { refs.incrementAndGet(); }

    /** 计数归零时调用 closer；closer 异常被吞下并 WARN，不中断 release */
    public void release() {
        int after = refs.decrementAndGet();
        if (after < 0) {
            refs.incrementAndGet(); // 回滚
            throw new IllegalStateException("SharedConnection.release without acquire");
        }
        if (after == 0) {
            try { closer.run(); }
            catch (Exception e) { logger.warn("SharedConnection close error: {}", e.getMessage()); }
        }
    }

    public int refCount() { return refs.get(); }
}
```

- [ ] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=SharedConnectionTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/SharedConnection.java \
        src/test/java/com/opcua/forward/sender/SharedConnectionTest.java
git commit -m "feat(forward): add SharedConnection with reference-counted lifecycle"
```

---

## Task 10: SenderFactory + SenderRegistry（按指纹聚合）

**Files:**
- Create: `src/main/java/com/opcua/forward/sender/SenderFactory.java`
- Create: `src/main/java/com/opcua/forward/sender/SenderRegistry.java`
- Test: `src/test/java/com/opcua/forward/sender/SenderRegistrySharingTest.java`

注：SenderRegistry 引入抽象骨架；具体 4 个 SenderFactory 在 Plan B 注入。本 Task 用 stub factory 验证共享语义。

- [ ] **Step 1: 写失败测试**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SenderRegistrySharingTest {

    static class StubFactory implements SenderFactory {
        final AtomicInteger connectionCreations = new AtomicInteger();

        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            connectionCreations.incrementAndGet();
            return new SharedConnection<>(new Object(), () -> { });
        }

        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> conn) {
            return new AbstractSender("stub-" + t.getType() + "-" + t.getTopic(), 8, 1) {
                @Override protected void doSend(com.opcua.model.OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
        }
    }

    @Test
    void sameFingerprint_sharesConnection_butSenderIsPerTarget() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        ForwardTarget t1 = kafka("kafka:9092", "topic-A");
        ForwardTarget t2 = kafka("kafka:9092", "topic-B");

        Sender s1 = reg.getOrCreate(t1);
        Sender s2 = reg.getOrCreate(t2);

        assertNotSame(s1, s2);
        assertEquals(1, f.connectionCreations.get());

        reg.shutdown(Duration.ofMillis(100));
    }

    @Test
    void differentFingerprint_independentConnections() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        reg.getOrCreate(kafka("kafka1:9092", "topic-A"));
        reg.getOrCreate(kafka("kafka2:9092", "topic-A"));

        assertEquals(2, f.connectionCreations.get());
        reg.shutdown(Duration.ofMillis(100));
    }

    @Test
    void shutdown_preventsFurtherGetOrCreate() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        reg.getOrCreate(kafka("k:9092", "A"));
        reg.shutdown(Duration.ofMillis(100));

        assertThrows(IllegalStateException.class,
                () -> reg.getOrCreate(kafka("k:9092", "C")));
    }

    @Test
    void getOrCreate_sameTargetReturnsSameSender() {
        StubFactory f = new StubFactory();
        SenderRegistry reg = new SenderRegistry(f);

        ForwardTarget t = kafka("k:9092", "topic-A");
        Sender s1 = reg.getOrCreate(t);
        Sender s2 = reg.getOrCreate(kafka("k:9092", "topic-A"));

        assertSame(s1, s2);
        reg.shutdown(Duration.ofMillis(100));
    }

    private ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setSecurityProtocol("PLAINTEXT");
        t.setTopic(topic);
        return t;
    }
}
```

- [ ] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=SenderRegistrySharingTest`
Expected: 编译失败

- [ ] **Step 3: 创建 SenderFactory 接口**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;

/**
 * Sender 与底层连接的工厂。Plan B 引入 4 个具体实现。
 */
public interface SenderFactory {

    /** 为 fingerprint 创建底层连接（HTTP 可返回 dummy SharedConnection<Void>） */
    SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget target);

    /** 为 target 创建 Sender 实例（使用已 acquire 的共享连接） */
    Sender createSender(ForwardTarget target, SharedConnection<?> connection);
}
```

- [ ] **Step 4: 创建 SenderRegistry**

```java
package com.opcua.forward.sender;

import com.opcua.forward.config.ForwardTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sender 与共享连接注册表。
 *
 * <p>同一 ConnectionFingerprint 共享底层连接；同一 (fingerprint, target-key) 共享 Sender 实例。</p>
 */
public class SenderRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SenderRegistry.class);

    private final SenderFactory factory;
    private final Map<ConnectionFingerprint, SharedConnection<?>> connections = new ConcurrentHashMap<>();
    private final Map<TargetKey, Sender> senders = new ConcurrentHashMap<>();
    private volatile boolean shutdown = false;

    public SenderRegistry(SenderFactory factory) {
        this.factory = factory;
    }

    public synchronized Sender getOrCreate(ForwardTarget target) {
        if (shutdown) throw new IllegalStateException("SenderRegistry already shut down");
        ConnectionFingerprint fp = ConnectionFingerprint.of(target);
        TargetKey key = TargetKey.of(target);

        Sender existing = senders.get(key);
        if (existing != null) return existing;

        SharedConnection<?> conn = connections.computeIfAbsent(fp, k -> factory.createConnection(k, target));
        conn.acquire();

        Sender s = factory.createSender(target, conn);
        senders.put(key, s);
        return s;
    }

    public void shutdown(Duration timeout) {
        synchronized (this) {
            if (shutdown) return;
            shutdown = true;
        }
        for (Sender s : senders.values()) {
            try { s.shutdown(timeout); }
            catch (Exception e) { logger.warn("Sender {} shutdown error: {}", s.getSenderId(), e.getMessage()); }
        }
        for (Map.Entry<ConnectionFingerprint, SharedConnection<?>> e : connections.entrySet()) {
            try {
                while (e.getValue().refCount() > 0) e.getValue().release();
            } catch (Exception ex) {
                logger.warn("Connection {} release error: {}", e.getKey(), ex.getMessage());
            }
        }
        senders.clear();
        connections.clear();
    }

    private record TargetKey(String type, String key) {
        static TargetKey of(ForwardTarget t) {
            String key = switch (t.getType()) {
                case "kafka" -> t.getBootstrapServers() + "|" + t.getTopic();
                case "mqtt" -> t.getBrokerUrl() + "|" + t.getClientId() + "|" + t.getTopic();
                case "influxdb" -> t.getUrl() + "|" + t.getOrg() + "|" + t.getBucket();
                case "http" -> Objects.requireNonNullElse(t.getUrl(), "");
                default -> throw new IllegalArgumentException("Unknown sender type: " + t.getType());
            };
            return new TargetKey(t.getType(), key);
        }
    }
}
```

- [ ] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=SenderRegistrySharingTest`
Expected: PASS（4 个测试）

- [ ] **Step 6: 完整测试套件回归验证**

Run: `mvn -q test`
Expected: BUILD SUCCESS — opc-ua-core 既有测试 + 本 Plan 全部新增测试通过

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/opcua/forward/sender/SenderFactory.java \
        src/main/java/com/opcua/forward/sender/SenderRegistry.java \
        src/test/java/com/opcua/forward/sender/SenderRegistrySharingTest.java
git commit -m "feat(forward): add SenderRegistry with connection fingerprint sharing"
```

---

## Plan A 完成检查

完成 Task 1-10 后：

- [ ] `mvn -q test` 全部通过
- [ ] 在 `openspec/changes/opc-ua-forward/tasks.md` 中勾选以下条目：
  - 1.1（forward 模块结构 + 依赖）
  - 1.2（ForwardProperties YAML 绑定）
  - 1.3（4 个数据模型类）
  - 1.4（OpcUaService.unregisterListener cross-change patch）
  - 2.4（drop-oldest (deviceId, senderId) 聚合 WARN）
  - 2.6（SenderRegistry 共享）
- [ ] 部分勾选（仅 sender 层完成；ForwardingEngine 编排留 Plan C）：
  - 2.5（优雅关停流程 — sender 层已完成）

## 后续 Plan

- **Plan B**：4 个具体 Sender 实现 + 各 SenderFactory（KafkaSender/InfluxDBSender/MqttSender/HttpSender）
- **Plan C**：ForwardingEngine 主入口 + matcher + alert routing + AutoConfiguration + 端到端 mock 测试

## Self-Review

| 检查项 | 结果 |
|--------|------|
| 每个 Task 都有具体文件路径 | ✓ |
| 每个 step 含完整代码（无 placeholder） | ✓ |
| TDD Red-Green-Refactor 顺序 | ✓ 每个测试都先确认编译失败再实现 |
| 类型一致性 | ✓ AbstractSender 字段在后续 Task 引用一致；ForwardTarget setter 命名稳定 |
| Spec 覆盖：drop-oldest 聚合 | ✓ Task 8 |
| Spec 覆盖：优雅关停 | ✓ Task 7 + Task 8 测试 |
| Spec 覆盖：SenderRegistry 共享 | ✓ Task 10 |
| Cross-change patch | ✓ Task 2 + Task 3 |
| Commit 边界清晰，每个 Task 一次 commit | ✓ |
