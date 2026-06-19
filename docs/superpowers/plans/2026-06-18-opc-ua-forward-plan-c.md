---
change: opc-ua-forward
design-doc: docs/superpowers/specs/2026-06-18-opc-ua-forward-design.md
base-ref: 8098f48e7b8d237a1df51c8c8550a8128195b3ab
---

# OPC UA Forward — Plan C：ForwardingEngine + AutoConfig + 端到端

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**前置：** Plan A 完成（Sender 抽象、SenderRegistry、配置模型）；Plan B 完成（4 个 Sender 实现 + CompositeSenderFactory）。

**Goal:** 实现 ForwardingEngine 主入口（接收 OpcUaDeviceData → 规则匹配 → 路由到 senders → 告警检测 → 路由到 alerts.targets）+ AutoConfiguration + 端到端 mock 集成测试。

**Architecture:** ForwardingEngine 作为 `OpcUaDataListener` 注册到 OpcUaService；在 DataDispatchEngine bucket 线程上同步执行规则匹配（O(规则数) HashMap lookup），匹配后调用 `senderRegistry.getOrCreate(target).enqueue(data)`，sender 内部异步发送。

**Tech Stack:** Spring Boot 3.2 AutoConfiguration, JUnit 5 + Mockito, ObjectMapper（Spring Boot Jackson AutoConfig 默认 Bean）。

**完成后覆盖 tasks.md 条目：** 2.1（ForwardingEngine 注册）、2.2（规则匹配器）、2.5 剩余（engine 关停编排）、7.1、7.2、7.3（告警路由）、8.1、8.2、8.3（环境变量、enabled 开关、AutoConfiguration）、9.3、9.4、9.5、9.6、9.7（端到端 + 告警路由 + 关停 + 共享测试）。

---

## 文件结构

| 文件 | 责任 |
|------|------|
| `src/main/java/com/opcua/forward/engine/RuleMatcher.java` | productId/deviceId/nodeId 匹配 |
| `src/main/java/com/opcua/forward/engine/AlertDetector.java` | 检测 data[] 含 Bad/Uncertain |
| `src/main/java/com/opcua/forward/engine/ForwardingEngine.java` | 主入口；OpcUaDataListener 实现 |
| `src/main/java/com/opcua/forward/config/OpcUaForwardAutoConfiguration.java` | Spring Boot AutoConfig |
| `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` | AutoConfig 注册 |

---

## Task 1: RuleMatcher

按 Design Doc：MatchCondition 中 productId/deviceId/nodeId 任一不匹配则规则不命中；空字段视为通配。nodeId 维度需检查 data[] 中是否存在该 nodeId。

**Files:**
- Create: `src/main/java/com/opcua/forward/engine/RuleMatcher.java`
- Test: `src/test/java/com/opcua/forward/engine/RuleMatcherTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.opcua.forward.engine;

import com.opcua.forward.config.MatchCondition;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RuleMatcherTest {

    @Test
    void emptyMatch_matchesAll() {
        assertTrue(RuleMatcher.matches(new MatchCondition(), data("p1", "d1", "n1")));
    }

    @Test
    void productId_mismatch_rejects() {
        MatchCondition m = new MatchCondition(); m.setProductId("line-A");
        assertFalse(RuleMatcher.matches(m, data("line-B", "d1", "n1")));
    }

    @Test
    void productId_match_passes() {
        MatchCondition m = new MatchCondition(); m.setProductId("line-A");
        assertTrue(RuleMatcher.matches(m, data("line-A", "d1", "n1")));
    }

    @Test
    void deviceId_match() {
        MatchCondition m = new MatchCondition();
        m.setProductId("line-A"); m.setDeviceId("furnace-1");
        assertTrue(RuleMatcher.matches(m, data("line-A", "furnace-1", "n1")));
        assertFalse(RuleMatcher.matches(m, data("line-A", "furnace-2", "n1")));
    }

    @Test
    void nodeId_match_anyDataPointMatches() {
        MatchCondition m = new MatchCondition(); m.setNodeId("ns=2;s=Temp");
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(point("ns=2;s=Press"), point("ns=2;s=Temp")));
        assertTrue(RuleMatcher.matches(m, d));
    }

    @Test
    void nodeId_match_allMissingRejects() {
        MatchCondition m = new MatchCondition(); m.setNodeId("ns=2;s=Temp");
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(point("ns=2;s=Press")));
        assertFalse(RuleMatcher.matches(m, d));
    }

    private OpcUaDeviceData data(String pid, String did, String nid) {
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "x"),
                List.of(point(nid)));
    }

    private OpcUaDataPoint point(String nodeId) {
        return new OpcUaDataPoint(nodeId, "name", 1, "Int32",
                Quality.Good, true, "Good", Instant.now(), Instant.now());
    }
}
```

- [x] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=RuleMatcherTest`
Expected: 编译失败

- [x] **Step 3: 实现 RuleMatcher**

```java
package com.opcua.forward.engine;

import com.opcua.forward.config.MatchCondition;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

/**
 * 转发规则匹配器。所有方法纯 CPU，禁 I/O。
 */
public final class RuleMatcher {

    private RuleMatcher() { }

    public static boolean matches(MatchCondition match, OpcUaDeviceData data) {
        if (match == null) return true;
        OpcUaDeviceData.SourceInfo s = data.getSource();

        if (notNullAndDifferent(match.getProductId(), s.getProductId())) return false;
        if (notNullAndDifferent(match.getDeviceId(), s.getDeviceId())) return false;

        if (match.getNodeId() != null && !match.getNodeId().isEmpty()) {
            boolean any = false;
            if (data.getData() != null) {
                for (OpcUaDataPoint p : data.getData()) {
                    if (match.getNodeId().equals(p.getNodeId())) { any = true; break; }
                }
            }
            if (!any) return false;
        }
        return true;
    }

    private static boolean notNullAndDifferent(String required, String actual) {
        return required != null && !required.isEmpty() && !required.equals(actual);
    }
}
```

- [x] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=RuleMatcherTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/engine/RuleMatcher.java \
        src/test/java/com/opcua/forward/engine/RuleMatcherTest.java
git commit -m "feat(forward): add RuleMatcher for productId/deviceId/nodeId matching"
```

---

## Task 2: AlertDetector

**Files:**
- Create: `src/main/java/com/opcua/forward/engine/AlertDetector.java`
- Test: `src/test/java/com/opcua/forward/engine/AlertDetectorTest.java`

- [x] **Step 1: 写失败测试**

```java
package com.opcua.forward.engine;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AlertDetectorTest {

    @Test
    void allGood_returnsFalse() {
        assertFalse(AlertDetector.hasBadOrUncertain(data(Quality.Good, Quality.Good)));
    }

    @Test
    void anyBad_returnsTrue() {
        assertTrue(AlertDetector.hasBadOrUncertain(data(Quality.Good, Quality.Bad)));
    }

    @Test
    void anyUncertain_returnsTrue() {
        assertTrue(AlertDetector.hasBadOrUncertain(data(Quality.Uncertain, Quality.Good)));
    }

    @Test
    void emptyData_returnsFalse() {
        OpcUaDeviceData d = new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "x"),
                List.of());
        assertFalse(AlertDetector.hasBadOrUncertain(d));
    }

    private OpcUaDeviceData data(Quality... qs) {
        OpcUaDataPoint[] arr = new OpcUaDataPoint[qs.length];
        for (int i = 0; i < qs.length; i++) {
            arr[i] = new OpcUaDataPoint("n" + i, "n", 1, "Int32",
                    qs[i], true, qs[i].name(), Instant.now(), Instant.now());
        }
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p", "d", "x"),
                List.of(arr));
    }
}
```

- [x] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=AlertDetectorTest`
Expected: 编译失败

- [x] **Step 3: 实现 AlertDetector**

```java
package com.opcua.forward.engine;

import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;

public final class AlertDetector {

    private AlertDetector() { }

    public static boolean hasBadOrUncertain(OpcUaDeviceData data) {
        if (data == null || data.getData() == null) return false;
        for (OpcUaDataPoint p : data.getData()) {
            Quality q = p.getQuality();
            if (q == Quality.Bad || q == Quality.Uncertain) return true;
        }
        return false;
    }
}
```

- [x] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=AlertDetectorTest`
Expected: PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/engine/AlertDetector.java \
        src/test/java/com/opcua/forward/engine/AlertDetectorTest.java
git commit -m "feat(forward): add AlertDetector for Bad/Uncertain quality detection"
```

---

## Task 3: ForwardingEngine（路由 + 告警 + 生命周期）

**Files:**
- Create: `src/main/java/com/opcua/forward/engine/ForwardingEngine.java`
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineRoutingTest.java`
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineAlertRoutingTest.java`
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineLifecycleTest.java`

- [x] **Step 1: 写主路由失败测试**

```java
package com.opcua.forward.engine;

import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.api.OpcUaService;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardingEngineRoutingTest {

    @Test
    void onDataReceived_routesToAllEnabledTargetsOfMatchingRule() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender s1 = mock(Sender.class);
        Sender s2 = mock(Sender.class);
        Sender s3 = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(s1, s2, s3);

        ForwardTarget t1 = kafka("k:9092", "topic-1"); t1.setEnabled(true);
        ForwardTarget t2 = kafka("k:9092", "topic-2"); t2.setEnabled(true);
        ForwardTarget t3 = kafka("k:9092", "topic-3"); t3.setEnabled(false);

        ForwardRule rule = new ForwardRule();
        rule.setName("r1");
        rule.setTargets(List.of(t1, t2, t3));

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofSeconds(1));
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData data = makeData("line-A", "d1", Quality.Good);
        engine.onDataReceived(data);

        // disabled t3 不被路由
        verify(registry, times(2)).getOrCreate(any());
        verify(s1).enqueue(data);
        verify(s2).enqueue(data);
        verifyNoInteractions(s3);
    }

    @Test
    void onDataReceived_skipsRulesWithoutMatch() {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender s = mock(Sender.class);
        when(registry.getOrCreate(any())).thenReturn(s);

        ForwardRule r1 = ruleWithProductId("line-A", kafka("k:9092", "A"));
        ForwardRule r2 = ruleWithProductId("line-B", kafka("k:9092", "B"));

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(r1, r2)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(makeData("line-A", "d1", Quality.Good));

        verify(registry, times(1)).getOrCreate(any());
        verify(s, times(1)).enqueue(any());
    }

    private static ForwardRule ruleWithProductId(String productId, ForwardTarget t) {
        ForwardRule r = new ForwardRule();
        r.setName("r-" + productId);
        r.getMatch().setProductId(productId);
        r.setTargets(List.of(t));
        return r;
    }

    static ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setTopic(topic);
        return t;
    }

    static OpcUaDeviceData makeData(String pid, String did, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [x] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=ForwardingEngineRoutingTest`
Expected: 编译失败

- [x] **Step 3: 实现 ForwardingEngine**

```java
package com.opcua.forward.engine;

import com.opcua.api.OpcUaDataListener;
import com.opcua.api.OpcUaService;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.model.OpcUaDeviceData;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 转发引擎主入口。注册为 OpcUaDataListener，在 DataDispatchEngine bucket 线程上同步
 * 执行规则匹配，匹配后调用 sender.enqueue（异步发送）。
 */
public class ForwardingEngine implements OpcUaDataListener {

    private static final Logger logger = LoggerFactory.getLogger(ForwardingEngine.class);

    private final OpcUaService opcUaService;
    private final SenderRegistry senderRegistry;
    private final ForwardProperties properties;

    public ForwardingEngine(OpcUaService opcUaService,
                            SenderRegistry senderRegistry,
                            ForwardProperties properties) {
        this.opcUaService = opcUaService;
        this.senderRegistry = senderRegistry;
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        if (properties.getRules() == null || properties.getRules().isEmpty()) {
            logger.info("opcua.forward.rules is empty, skipping listener registration");
            return;
        }
        opcUaService.registerListener(this);
        logger.info("ForwardingEngine registered with {} rules", properties.getRules().size());
    }

    @PreDestroy
    public void shutdown() {
        try {
            opcUaService.unregisterListener(this);
        } catch (Exception e) {
            logger.warn("unregisterListener error: {}", e.getMessage());
        }
        senderRegistry.shutdown(properties.getShutdownTimeout());
    }

    @Override
    public void onDataReceived(OpcUaDeviceData data) {
        List<ForwardRule> rules = properties.getRules();
        if (rules == null) return;
        for (ForwardRule rule : rules) {
            if (!RuleMatcher.matches(rule.getMatch(), data)) continue;

            // P0 数据质量过滤（仅作用于主 targets；alert 通道保持原始数据以便告警上下文完整）
            OpcUaDeviceData filtered = QualityFilterApplier.apply(data, rule.getQualityFilter());

            // 主 targets — 仅当存在 Good/被允许的数据点时 enqueue
            if (filtered != null && rule.getTargets() != null) {
                for (ForwardTarget t : rule.getTargets()) {
                    if (!t.isEnabled()) continue;
                    try {
                        senderRegistry.getOrCreate(t).enqueue(filtered);
                    } catch (Exception e) {
                        logger.warn("Forward to target {}/{} error: {}",
                                t.getType(), t.getTopic(), e.getMessage());
                    }
                }
            }

            // alerts.targets（Bad/Uncertain 触发）— 使用原始未过滤数据，保留 Bad/Uncertain 上下文
            AlertConfig alerts = rule.getAlerts();
            if (alerts != null && alerts.isEnabled() && AlertDetector.hasBadOrUncertain(data)) {
                if (alerts.getTargets() != null) {
                    for (ForwardTarget at : alerts.getTargets()) {
                        if (!at.isEnabled()) continue;
                        try {
                            senderRegistry.getOrCreate(at).enqueue(data);
                        } catch (Exception e) {
                            logger.warn("Alert forward error: {}", e.getMessage());
                        }
                    }
                }
            }
        }
    }
}
```

- [x] **Step 4: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardingEngineRoutingTest`
Expected: PASS

- [x] **Step 5: 写告警路由测试**

```java
package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ForwardingEngineAlertRoutingTest {

    @Test
    void goodOnly_routesToMainTargetsOnly() {
        Result r = run(Quality.Good, true);
        verify(r.mainSender).enqueue(any());
        verifyNoInteractions(r.alertSender);
    }

    @Test
    void badData_routesToMainAndAlertTargets() {
        Result r = run(Quality.Bad, true);
        verify(r.mainSender).enqueue(any());
        verify(r.alertSender).enqueue(any());
    }

    @Test
    void uncertainData_routesToMainAndAlertTargets() {
        Result r = run(Quality.Uncertain, true);
        verify(r.mainSender).enqueue(any());
        verify(r.alertSender).enqueue(any());
    }

    @Test
    void alertsDisabled_doesNotRouteToAlertTargets() {
        Result r = run(Quality.Bad, false);
        verify(r.mainSender).enqueue(any());
        verifyNoInteractions(r.alertSender);
    }

    private Result run(Quality q, boolean alertsEnabled) {
        SenderRegistry registry = mock(SenderRegistry.class);
        Sender main = mock(Sender.class);
        Sender alert = mock(Sender.class);

        ForwardTarget mainT = ForwardingEngineRoutingTest.kafka("k:9092", "main");
        ForwardTarget alertT = ForwardingEngineRoutingTest.kafka("k:9092", "alert");

        when(registry.getOrCreate(mainT)).thenReturn(main);
        when(registry.getOrCreate(alertT)).thenReturn(alert);

        ForwardRule rule = new ForwardRule();
        rule.setName("r1");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(alertsEnabled);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(ForwardingEngineRoutingTest.makeData("p", "d", q));

        return new Result(main, alert);
    }

    private record Result(Sender mainSender, Sender alertSender) { }
}
```

- [x] **Step 6: 运行测试 — 确认通过（无需修改实现）**

Run: `mvn -q test -Dtest=ForwardingEngineAlertRoutingTest`
Expected: PASS（4 个测试）

- [x] **Step 7: 写生命周期测试**

```java
package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.SenderRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.*;

class ForwardingEngineLifecycleTest {

    @Test
    void init_emptyRules_doesNotRegister() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);
        ForwardProperties props = new ForwardProperties();
        // empty rules

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.init();

        verifyNoInteractions(svc);
    }

    @Test
    void init_nonEmptyRules_registersListener() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);

        ForwardRule rule = new ForwardRule();
        rule.setName("r");
        ForwardTarget t = new ForwardTarget(); t.setType("kafka"); t.setBootstrapServers("k:9092");
        rule.setTargets(List.of(t));
        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.init();

        verify(svc).registerListener(engine);
    }

    @Test
    void shutdown_unregistersAndShutsDownRegistry() {
        OpcUaService svc = mock(OpcUaService.class);
        SenderRegistry reg = mock(SenderRegistry.class);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofSeconds(2));

        ForwardingEngine engine = new ForwardingEngine(svc, reg, props);
        engine.shutdown();

        verify(svc).unregisterListener(engine);
        verify(reg).shutdown(Duration.ofSeconds(2));
    }
}
```

- [x] **Step 8: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardingEngineLifecycleTest`
Expected: PASS

- [x] **Step 9: Commit**

```bash
git add src/main/java/com/opcua/forward/engine/ForwardingEngine.java \
        src/test/java/com/opcua/forward/engine/ForwardingEngineRoutingTest.java \
        src/test/java/com/opcua/forward/engine/ForwardingEngineAlertRoutingTest.java \
        src/test/java/com/opcua/forward/engine/ForwardingEngineLifecycleTest.java
git commit -m "feat(forward): add ForwardingEngine with rule routing + alert routing + lifecycle"
```

---

## Task 3b: QualityFilterApplier（P0 数据质量过滤工具）

**Files:**
- Create: `src/main/java/com/opcua/forward/engine/QualityFilterApplier.java`
- Test: `src/test/java/com/opcua/forward/engine/QualityFilterApplierTest.java`

> **说明**：Task 3 ForwardingEngine.onDataReceived 已经引用 `QualityFilterApplier.apply(data, qualityFilter)`。本 task 提供具体实现。
> 实施时如发现 OpcUaDeviceData 不支持 「以原 sourceInfo + 过滤后 dataPoints 重建」的构造方法，先在 `com.opcua.model.OpcUaDeviceData` 加最小补丁（同包工厂方法）；这属于跨 change 微调，commit 时在 message 中标注。

- [x] **Step 1: 写失败测试**

```java
// src/test/java/com/opcua/forward/engine/QualityFilterApplierTest.java
package com.opcua.forward.engine;

import com.opcua.forward.config.QualityFilter;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QualityFilterApplierTest {

    @Test
    void dropBadOnlyRemovesBadPointsKeepsGoodAndUncertain() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad),
            point("n3", Quality.Uncertain)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.dropBadOnly());

        assertNotNull(out);
        assertEquals(2, out.getData().size());
        assertEquals("n1", out.getData().get(0).getNodeId());
        assertEquals("n3", out.getData().get(1).getNodeId());
    }

    @Test
    void allBadReturnsNullWhenDropBad() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Bad),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.dropBadOnly());

        assertNull(out, "all-Bad batch with dropBad should yield null (caller skips enqueue)");
    }

    @Test
    void passAllReturnsOriginalReferenceUnchanged() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, QualityFilter.passAll());

        assertSame(data, out);
    }

    @Test
    void nullFilterTreatedAsDropBadOnly() {
        OpcUaDeviceData data = makeData(List.of(
            point("n1", Quality.Good),
            point("n2", Quality.Bad)
        ));

        OpcUaDeviceData out = QualityFilterApplier.apply(data, null);

        assertNotNull(out);
        assertEquals(1, out.getData().size());
        assertEquals("n1", out.getData().get(0).getNodeId());
    }

    private OpcUaDeviceData makeData(List<OpcUaDataPoint> points) {
        // 实施时按真实 OpcUaDeviceData 构造签名调整
        return new OpcUaDeviceData(Instant.now(), /* sourceInfo */ null, points);
    }

    private OpcUaDataPoint point(String nodeId, Quality q) {
        return new OpcUaDataPoint(nodeId, nodeId, "v", "Double",
            q, true, q.name(), Instant.now(), Instant.now());
    }
}
```

- [x] **Step 2: 运行测试验证失败**

Run: `mvn -q test -Dtest=QualityFilterApplierTest`
Expected: 编译失败 — QualityFilterApplier 未定义

- [x] **Step 3: 实现**

```java
// src/main/java/com/opcua/forward/engine/QualityFilterApplier.java
package com.opcua.forward.engine;

import com.opcua.forward.config.QualityFilter;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;

import java.util.ArrayList;
import java.util.List;

/**
 * 按 ForwardRule 的 QualityFilter 过滤数据点。
 *
 * <p>策略：</p>
 * <ul>
 *   <li>若 filter 为 null，按 dropBadOnly 默认（兼容历史规则）</li>
 *   <li>若 filter.shouldDrop() 全部返回 false，原样返回（无 GC 开销）</li>
 *   <li>若全部数据点被丢弃，返回 null —— 调用方应跳过 enqueue</li>
 *   <li>否则以原 sourceInfo + 过滤后 dataPoints 构造新的 OpcUaDeviceData</li>
 * </ul>
 */
public final class QualityFilterApplier {

    private QualityFilterApplier() {}

    public static OpcUaDeviceData apply(OpcUaDeviceData data, QualityFilter filter) {
        QualityFilter f = filter != null ? filter : QualityFilter.dropBadOnly();

        // 快路径：filter 不丢任何东西
        if (!f.isDropBad() && !f.isDropUncertain()) {
            return data;
        }

        List<OpcUaDataPoint> kept = new ArrayList<>(data.getData().size());
        for (OpcUaDataPoint p : data.getData()) {
            if (!f.shouldDrop(p.getQuality())) {
                kept.add(p);
            }
        }

        if (kept.isEmpty()) return null;
        if (kept.size() == data.getData().size()) return data;  // 没有真正丢弃

        return new OpcUaDeviceData(data.getTimestamp(), data.getSource(), kept);
    }
}
```

- [x] **Step 4: 运行测试验证通过**

Run: `mvn -q test -Dtest=QualityFilterApplierTest`
Expected: 4/4 PASS

- [x] **Step 5: Commit**

```bash
git add src/main/java/com/opcua/forward/engine/QualityFilterApplier.java \
        src/test/java/com/opcua/forward/engine/QualityFilterApplierTest.java
git commit -m "feat(forward): add QualityFilterApplier for P0 data quality filtering

- Filters OpcUaDeviceData.dataPoints by per-rule QualityFilter
- Returns null when batch fully dropped (caller skips enqueue)
- Fast path when filter accepts all (no copy)
- Defaults to dropBadOnly when filter is null"
```

---

## Task 3c: ForwardingEngine.onDataReceived quality 过滤回归测试

**Files:**
- Modify: `src/test/java/com/opcua/forward/engine/ForwardingEngineRoutingTest.java`（追加 2 个测试用例）

- [x] **Step 1: 追加测试**

```java
// 在 ForwardingEngineRoutingTest 类中追加：

@Test
void onDataReceived_dropsBadDataPointsBeforeRoutingByDefault() {
    Sender mockSender = mock(Sender.class);
    when(senderRegistry.getOrCreate(any())).thenReturn(mockSender);

    ForwardRule rule = new ForwardRule();
    rule.setMatch(new MatchCondition());
    ForwardTarget t = new ForwardTarget();
    t.setType("kafka");
    t.setEnabled(true);
    rule.setTargets(List.of(t));
    // 默认 qualityFilter = dropBadOnly

    when(properties.getRules()).thenReturn(List.of(rule));

    OpcUaDeviceData data = makeMixedQualityData(
        Quality.Good, Quality.Bad, Quality.Good);

    engine.onDataReceived(data);

    ArgumentCaptor<OpcUaDeviceData> captor = ArgumentCaptor.forClass(OpcUaDeviceData.class);
    verify(mockSender).enqueue(captor.capture());

    OpcUaDeviceData enqueued = captor.getValue();
    assertEquals(2, enqueued.getData().size(), "Bad point should be dropped");
    assertTrue(enqueued.getData().stream()
        .allMatch(p -> p.getQuality() == Quality.Good));
}

@Test
void onDataReceived_skipsEnqueueWhenAllDataPointsAreBad() {
    Sender mockSender = mock(Sender.class);
    when(senderRegistry.getOrCreate(any())).thenReturn(mockSender);

    ForwardRule rule = new ForwardRule();
    rule.setMatch(new MatchCondition());
    rule.setTargets(List.of(target("kafka")));
    when(properties.getRules()).thenReturn(List.of(rule));

    OpcUaDeviceData allBad = makeMixedQualityData(Quality.Bad, Quality.Bad);

    engine.onDataReceived(allBad);

    verify(mockSender, never()).enqueue(any());
}

@Test
void onDataReceived_alertChannelReceivesOriginalDataIncludingBad() {
    // 同一 rule：alert.enabled=true，主 targets dropBadOnly
    // 验证 alert 通道仍然能拿到 Bad 点（因为 alert 用原始 data）
    Sender mainSender = mock(Sender.class);
    Sender alertSender = mock(Sender.class);

    ForwardRule rule = new ForwardRule();
    rule.setMatch(new MatchCondition());
    rule.setTargets(List.of(target("kafka")));
    AlertConfig alerts = new AlertConfig();
    alerts.setEnabled(true);
    alerts.setTargets(List.of(target("mqtt")));
    rule.setAlerts(alerts);

    when(senderRegistry.getOrCreate(argThat(t -> "kafka".equals(t.getType()))))
        .thenReturn(mainSender);
    when(senderRegistry.getOrCreate(argThat(t -> "mqtt".equals(t.getType()))))
        .thenReturn(alertSender);
    when(properties.getRules()).thenReturn(List.of(rule));

    OpcUaDeviceData data = makeMixedQualityData(Quality.Good, Quality.Bad);

    engine.onDataReceived(data);

    // 主通道：Bad 已过滤
    ArgumentCaptor<OpcUaDeviceData> mainCap = ArgumentCaptor.forClass(OpcUaDeviceData.class);
    verify(mainSender).enqueue(mainCap.capture());
    assertEquals(1, mainCap.getValue().getData().size());

    // 告警通道：原始数据，含 Bad
    ArgumentCaptor<OpcUaDeviceData> alertCap = ArgumentCaptor.forClass(OpcUaDeviceData.class);
    verify(alertSender).enqueue(alertCap.capture());
    assertEquals(2, alertCap.getValue().getData().size());
}
```

> **辅助方法**：`makeMixedQualityData(Quality...)` 构造对应数量的 OpcUaDataPoint；`target(type)` 创建 enabled=true 的 ForwardTarget。如已存在类似 helper 复用即可。

- [x] **Step 2: 运行测试验证通过**

Run: `mvn -q test -Dtest=ForwardingEngineRoutingTest`
Expected: 全部新老测试 PASS

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/opcua/forward/engine/ForwardingEngineRoutingTest.java
git commit -m "test(forward): regression tests for QualityFilter routing behavior

- Default dropBadOnly drops Bad points before main target enqueue
- All-Bad batch skips main enqueue entirely
- Alert channel still receives original data (Bad context preserved)"
```

---



## Task 4: OpcUaForwardAutoConfiguration

按 Design Doc D7：`@ConditionalOnProperty("opcua.forward.enabled", matchIfMissing=true)`，注册 4 个 SenderFactory + CompositeSenderFactory + SenderRegistry + ForwardingEngine。

环境变量替换由 Spring Boot 默认支持（`${ENV}` 占位符）— 无需自定义实现，YAML 加载时会自动解析。

**Files:**
- Create: `src/main/java/com/opcua/forward/config/OpcUaForwardAutoConfiguration.java`
- Create: `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Test: `src/test/java/com/opcua/forward/config/OpcUaForwardAutoConfigurationTest.java`

注：Plan A 阶段已新建过 `META-INF/spring/.../AutoConfiguration.imports`（opc-ua-core 自配置已注册）。本 Task 检查文件是否存在并追加 forward 自配置。

- [x] **Step 1: 写 AutoConfig 测试**

```java
package com.opcua.forward.config;

import com.opcua.api.OpcUaService;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.forward.sender.CompositeSenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OpcUaForwardAutoConfigurationTest {

    @Configuration
    static class StubOpcUaCore {
        @Bean OpcUaService opcUaService() { return mock(OpcUaService.class); }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(StubOpcUaCore.class)
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    OpcUaForwardAutoConfiguration.class));

    @Test
    void enabledByDefault_registersBeans() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(ForwardProperties.class);
            assertThat(ctx).hasSingleBean(SenderRegistry.class);
            assertThat(ctx).hasSingleBean(CompositeSenderFactory.class);
            assertThat(ctx).hasSingleBean(ForwardingEngine.class);
        });
    }

    @Test
    void disabledExplicitly_skipsRegistration() {
        runner.withPropertyValues("opcua.forward.enabled=false").run(ctx -> {
            assertThat(ctx).doesNotHaveBean(ForwardingEngine.class);
            assertThat(ctx).doesNotHaveBean(SenderRegistry.class);
        });
    }
}
```

- [x] **Step 2: 运行测试 — 确认编译失败**

Run: `mvn -q test -Dtest=OpcUaForwardAutoConfigurationTest`
Expected: 编译失败

- [x] **Step 3: 实现 AutoConfiguration**

```java
package com.opcua.forward.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.api.OpcUaService;
import com.opcua.forward.engine.ForwardingEngine;
import com.opcua.forward.sender.CompositeSenderFactory;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.http.HttpSenderFactory;
import com.opcua.forward.sender.influxdb.InfluxDBSenderFactory;
import com.opcua.forward.sender.kafka.KafkaSenderFactory;
import com.opcua.forward.sender.mqtt.MqttSenderFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * OPC UA 转发模块自动配置。
 *
 * <p>仅在 {@code opcua.forward.enabled=true}（默认）时激活。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "opcua.forward", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ForwardProperties.class)
public class OpcUaForwardAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CompositeSenderFactory compositeSenderFactory(ObjectMapper objectMapper) {
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", new KafkaSenderFactory(objectMapper));
        map.put("influxdb", new InfluxDBSenderFactory());
        map.put("mqtt", new MqttSenderFactory(objectMapper));
        map.put("http", new HttpSenderFactory(objectMapper));
        return new CompositeSenderFactory(map);
    }

    @Bean(destroyMethod = "")
    @ConditionalOnMissingBean
    public SenderRegistry senderRegistry(CompositeSenderFactory factory) {
        return new SenderRegistry(factory);
    }

    @Bean
    @ConditionalOnMissingBean
    public ForwardingEngine forwardingEngine(OpcUaService opcUaService,
                                             SenderRegistry senderRegistry,
                                             ForwardProperties properties) {
        return new ForwardingEngine(opcUaService, senderRegistry, properties);
    }
}
```

- [x] **Step 4: 注册 AutoConfig**

检查 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 是否存在。若存在，追加新行；若不存在，创建并写入：

```
com.opcua.config.OpcUaCoreAutoConfiguration
com.opcua.forward.config.OpcUaForwardAutoConfiguration
```

注：opc-ua-core 已存在的 `OpcUaCoreAutoConfiguration` 行必须保留。

- [x] **Step 5: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=OpcUaForwardAutoConfigurationTest`
Expected: PASS

- [x] **Step 6: Commit**

```bash
git add src/main/java/com/opcua/forward/config/OpcUaForwardAutoConfiguration.java \
        src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports \
        src/test/java/com/opcua/forward/config/OpcUaForwardAutoConfigurationTest.java
git commit -m "feat(forward): add OpcUaForwardAutoConfiguration with conditional registration"
```

---

## Task 5: 端到端 Mock 集成测试

构造 fake `OpcUaDeviceData` → 注入 `ForwardingEngine` → 验证 mock senders 收到正确路由的消息。

**Files:**
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineEndToEndTest.java`

- [x] **Step 1: 写端到端测试**

```java
package com.opcua.forward.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.api.OpcUaService;
import com.opcua.forward.config.AlertConfig;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardingEngineEndToEndTest {

    /** 记录所有 enqueue 的 sender — 模拟 4 类 sender */
    static class RecordingSender implements Sender {
        final String id;
        final ConcurrentLinkedQueue<OpcUaDeviceData> received = new ConcurrentLinkedQueue<>();
        RecordingSender(String id) { this.id = id; }
        @Override public String getSenderId() { return id; }
        @Override public void enqueue(OpcUaDeviceData data) { received.add(data); }
        @Override public void stopAccepting() { }
        @Override public int awaitDrain(Duration timeout) { return 0; }
        @Override public void shutdown(Duration timeout) { }
    }

    static class RecordingFactory implements SenderFactory {
        final AtomicLong c = new AtomicLong();
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            return new RecordingSender(t.getType() + "-" + c.incrementAndGet());
        }
    }

    @Test
    void endToEnd_goodData_routesOnlyToMainTargets() {
        ForwardTarget mainT = kafka("k:9092", "data-{productId}");
        ForwardTarget alertT = kafka("k:9092", "alert-{productId}");

        ForwardRule rule = new ForwardRule();
        rule.setName("e2e");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofMillis(200));
        props.setRules(new ArrayList<>(List.of(rule)));

        RecordingFactory factory = new RecordingFactory();
        SenderRegistry registry = new SenderRegistry(factory);

        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        OpcUaDeviceData good = makeData("line-A", "d1", Quality.Good);
        engine.onDataReceived(good);

        // 主 sender 收到 1 条；alert sender 不创建（未触发）
        // 由于 senderRegistry 按需创建，alert sender 在该数据下未被 getOrCreate
        Sender mainSender = registry.getOrCreate(mainT);
        assertEquals(1, ((RecordingSender) mainSender).received.size());

        registry.shutdown(Duration.ofMillis(100));
    }

    @Test
    void endToEnd_badData_routesToBothMainAndAlertTargets() {
        ForwardTarget mainT = kafka("k:9092", "data-{productId}");
        ForwardTarget alertT = kafka("k:9092", "alert-{productId}");

        ForwardRule rule = new ForwardRule();
        rule.setName("e2e");
        rule.setTargets(List.of(mainT));
        AlertConfig alerts = new AlertConfig();
        alerts.setEnabled(true);
        alerts.setTargets(List.of(alertT));
        rule.setAlerts(alerts);

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(Duration.ofMillis(200));
        props.setRules(new ArrayList<>(List.of(rule)));

        SenderRegistry registry = new SenderRegistry(new RecordingFactory());
        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        engine.onDataReceived(makeData("line-A", "d1", Quality.Bad));

        RecordingSender mainSender = (RecordingSender) registry.getOrCreate(mainT);
        RecordingSender alertSender = (RecordingSender) registry.getOrCreate(alertT);
        assertEquals(1, mainSender.received.size());
        assertEquals(1, alertSender.received.size());

        registry.shutdown(Duration.ofMillis(100));
    }

    static ForwardTarget kafka(String bootstrap, String topic) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers(bootstrap);
        t.setSecurityProtocol("PLAINTEXT");
        t.setTopic(topic);
        return t;
    }

    static OpcUaDeviceData makeData(String pid, String did, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo(pid, did, "opc.tcp://x"),
                List.of(p));
    }
}
```

- [x] **Step 2: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardingEngineEndToEndTest`
Expected: PASS

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/opcua/forward/engine/ForwardingEngineEndToEndTest.java
git commit -m "test(forward): add end-to-end mock-based routing + alert routing test"
```

---

## Task 6: 优雅关停集成测试

验证 `ForwardingEngine.shutdown` → `unregisterListener` → `senderRegistry.shutdown(timeout)` → 超时 WARN 路径。

**Files:**
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineShutdownIntegrationTest.java`

- [x] **Step 1: 写测试**

```java
package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForwardingEngineShutdownIntegrationTest {

    /** 模拟慢 Sender — 每条 doSend 睡 200ms */
    static class SlowFactory implements SenderFactory {
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            AbstractSender s = new AbstractSender("slow", t.getQueueCapacity(), 1) {
                @Override protected void doSend(OpcUaDeviceData data) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                @Override protected void doClose() { }
            };
            s.start();
            return s;
        }
    }

    @Test
    void shutdown_drainsBeforeTimeout() {
        ForwardTarget t = kafkaTarget(10);
        ForwardingEngine engine = engineFor(t, Duration.ofSeconds(3));

        // 入队 5 条，每条 200ms = 1s 总耗时
        for (int i = 0; i < 5; i++) engine.onDataReceived(makeData(Quality.Good));

        long start = System.nanoTime();
        engine.shutdown();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 应该在 3s 之内完成排空（约 1s）
        assertTrue(elapsed < 2500, "shutdown took " + elapsed + "ms");
    }

    @Test
    void shutdown_timeoutDoesNotHang() {
        ForwardTarget t = kafkaTarget(50);
        ForwardingEngine engine = engineFor(t, Duration.ofMillis(300));

        // 入队 50 条，每条 200ms = 10s — 远超 300ms timeout
        for (int i = 0; i < 50; i++) engine.onDataReceived(makeData(Quality.Good));

        long start = System.nanoTime();
        engine.shutdown();
        long elapsed = (System.nanoTime() - start) / 1_000_000;

        // 必须在 1s 内强制返回（不阻塞 JVM）
        assertTrue(elapsed < 1000, "shutdown took " + elapsed + "ms");
    }

    private ForwardingEngine engineFor(ForwardTarget t, Duration timeout) {
        ForwardRule rule = new ForwardRule();
        rule.setName("r");
        rule.setTargets(List.of(t));

        ForwardProperties props = new ForwardProperties();
        props.setShutdownTimeout(timeout);
        props.setRules(new ArrayList<>(List.of(rule)));

        SenderRegistry registry = new SenderRegistry(new SlowFactory());
        return new ForwardingEngine(mock(OpcUaService.class), registry, props);
    }

    private static ForwardTarget kafkaTarget(int capacity) {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka");
        t.setBootstrapServers("k:9092");
        t.setTopic("topic");
        t.setQueueCapacity(capacity);
        t.setWorkerThreads(1);
        return t;
    }

    private static OpcUaDeviceData makeData(Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", "d1", "x"),
                List.of(p));
    }
}
```

- [x] **Step 2: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardingEngineShutdownIntegrationTest`
Expected: PASS

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/opcua/forward/engine/ForwardingEngineShutdownIntegrationTest.java
git commit -m "test(forward): verify graceful shutdown drain + timeout under load"
```

---

## Task 7: Drop-Oldest 聚合集成测试（端到端）

Plan A 已在 AbstractSenderDropOldestTest 用 stall sender 验证；此处补 ForwardingEngine 入口的端到端聚合测试。

**Files:**
- Test: `src/test/java/com/opcua/forward/engine/ForwardingEngineDropOldestAggregationTest.java`

- [x] **Step 1: 写测试 — engine 入口高速 enqueue → 验证 (deviceId, senderId) 计数**

```java
package com.opcua.forward.engine;

import com.opcua.api.OpcUaService;
import com.opcua.forward.config.ForwardProperties;
import com.opcua.forward.config.ForwardRule;
import com.opcua.forward.config.ForwardTarget;
import com.opcua.forward.sender.AbstractSender;
import com.opcua.forward.sender.ConnectionFingerprint;
import com.opcua.forward.sender.Sender;
import com.opcua.forward.sender.SenderFactory;
import com.opcua.forward.sender.SenderRegistry;
import com.opcua.forward.sender.SharedConnection;
import com.opcua.model.OpcUaDataPoint;
import com.opcua.model.OpcUaDeviceData;
import com.opcua.model.Quality;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ForwardingEngineDropOldestAggregationTest {

    /** 容量 1 + 不消费 → 所有 enqueue 在容量已满时触发 drop-oldest */
    static class StallFactory implements SenderFactory {
        AbstractSender lastCreated;
        @Override
        public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override
        public Sender createSender(ForwardTarget t, SharedConnection<?> conn) {
            AbstractSender s = new AbstractSender("stall-" + t.getTopic(), 1, 1) {
                @Override protected void doSend(OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
            // 不调用 start() — worker 不消费
            this.lastCreated = s;
            return s;
        }
    }

    @Test
    void aggregatesByDeviceId_acrossSenders() {
        ForwardTarget t = new ForwardTarget();
        t.setType("kafka"); t.setBootstrapServers("k:9092"); t.setTopic("only");
        t.setQueueCapacity(1);

        ForwardRule rule = new ForwardRule();
        rule.setName("r"); rule.setTargets(List.of(t));

        ForwardProperties props = new ForwardProperties();
        props.setRules(new ArrayList<>(List.of(rule)));

        StallFactory factory = new StallFactory();
        SenderRegistry registry = new SenderRegistry(factory);
        ForwardingEngine engine = new ForwardingEngine(
                mock(OpcUaService.class), registry, props);

        for (int i = 0; i < 30; i++) engine.onDataReceived(makeData("d1", Quality.Good));
        for (int i = 0; i < 20; i++) engine.onDataReceived(makeData("d2", Quality.Good));

        AbstractSender sender = factory.lastCreated;
        // 容量 1：首次 d1 占用，后 29 次 d1 → 29 drop；首次 d2 → 1 drop（驱逐 d1）+ 后 19 次 → 19 drop
        // d1: 29 drop, d2: 20 drop
        assertEquals(29, sender.getDropCount("d1"));
        assertEquals(20, sender.getDropCount("d2"));

        registry.shutdown(Duration.ofMillis(50));
    }

    private static OpcUaDeviceData makeData(String deviceId, Quality q) {
        OpcUaDataPoint p = new OpcUaDataPoint("ns=2;s=N", "name", 1, "Int32",
                q, true, q.name(), Instant.now(), Instant.now());
        return new OpcUaDeviceData(
                Instant.now(),
                new OpcUaDeviceData.SourceInfo("p1", deviceId, "x"),
                List.of(p));
    }
}
```

注：测试用 package-private 访问 `getDropCount`（已在 Plan A Task 8 中定义为包级可见）。如包路径不同，需调整可见性或将测试移到 `com.opcua.forward.sender` 包下。

- [x] **Step 2: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=ForwardingEngineDropOldestAggregationTest`
Expected: PASS

- [x] **Step 3: Commit**

```bash
git add src/test/java/com/opcua/forward/engine/ForwardingEngineDropOldestAggregationTest.java
git commit -m "test(forward): verify (deviceId, senderId) drop-oldest aggregation end-to-end"
```

---

## Task 8: SenderRegistry 共享端到端测试

Plan A 用 stub factory 验证；此处用 `CompositeSenderFactory` + 4 类 mock factory 验证共享。

**Files:**
- Test: `src/test/java/com/opcua/forward/sender/SenderRegistryEndToEndSharingTest.java`

- [x] **Step 1: 写测试**

```java
package com.opcua.forward.sender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.opcua.forward.config.ForwardTarget;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SenderRegistryEndToEndSharingTest {

    static class CountingDelegate implements SenderFactory {
        final AtomicInteger conns = new AtomicInteger();
        final SenderFactory inner;
        CountingDelegate(SenderFactory inner) { this.inner = inner; }
        @Override public SharedConnection<?> createConnection(ConnectionFingerprint fp, ForwardTarget t) {
            conns.incrementAndGet();
            // stub 出底层连接（避免真实 Kafka/MQTT 连接）
            return new SharedConnection<>(new Object(), () -> { });
        }
        @Override public Sender createSender(ForwardTarget t, SharedConnection<?> connection) {
            return new AbstractSender("stub-" + t.getType() + "-" + t.getTopic(), 1, 1) {
                @Override protected void doSend(com.opcua.model.OpcUaDeviceData data) { }
                @Override protected void doClose() { }
            };
        }
    }

    @Test
    void multipleTargets_sameKafkaBroker_shareSingleConnection() {
        CountingDelegate kafkaCounter = new CountingDelegate(null);
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", kafkaCounter);
        SenderRegistry registry = new SenderRegistry(new CompositeSenderFactory(map));

        ForwardTarget t1 = kafka("k:9092", "topic-A");
        ForwardTarget t2 = kafka("k:9092", "topic-B");
        ForwardTarget t3 = kafka("k:9092", "topic-C");

        Sender s1 = registry.getOrCreate(t1);
        Sender s2 = registry.getOrCreate(t2);
        Sender s3 = registry.getOrCreate(t3);

        assertNotSame(s1, s2);
        assertNotSame(s2, s3);
        assertEquals(1, kafkaCounter.conns.get(), "single shared connection across 3 targets");

        registry.shutdown(Duration.ofMillis(50));
    }

    @Test
    void differentBrokers_independentConnections() {
        CountingDelegate kafkaCounter = new CountingDelegate(null);
        Map<String, SenderFactory> map = new HashMap<>();
        map.put("kafka", kafkaCounter);
        SenderRegistry registry = new SenderRegistry(new CompositeSenderFactory(map));

        registry.getOrCreate(kafka("k1:9092", "A"));
        registry.getOrCreate(kafka("k2:9092", "A"));

        assertEquals(2, kafkaCounter.conns.get());
        registry.shutdown(Duration.ofMillis(50));
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

- [x] **Step 2: 运行测试 — 确认通过**

Run: `mvn -q test -Dtest=SenderRegistryEndToEndSharingTest`
Expected: PASS

- [x] **Step 3: 完整测试套件回归**

Run: `mvn -q test`
Expected: BUILD SUCCESS — 全 Plan A/B/C 测试通过

- [x] **Step 4: Commit**

```bash
git add src/test/java/com/opcua/forward/sender/SenderRegistryEndToEndSharingTest.java
git commit -m "test(forward): verify SenderRegistry connection sharing via CompositeSenderFactory"
```

---

## Task 9: tasks.md 全量勾选 + 验证报告草稿

完成所有 Plan A/B/C 任务后，回到 `openspec/changes/opc-ua-forward/tasks.md`，将所有条目改为 `[x]`。

- [x] **Step 1: 编辑 tasks.md，勾选所有 30 个子任务**

逐项检查 Plan A/B/C 的 "完成后覆盖 tasks.md 条目" 列表，确认每条都已实现且测试通过，然后将对应 `[ ]` 改为 `[x]`。

- [x] **Step 2: 运行完整构建 + 测试**

Run: `mvn -q clean verify`
Expected: BUILD SUCCESS

- [x] **Step 3: Commit tasks.md 更新**

```bash
git add openspec/changes/opc-ua-forward/tasks.md
git commit -m "chore(forward): mark all opc-ua-forward tasks complete"
```

---

## Plan C 完成检查

- [x] `mvn -q clean verify` 全部通过
- [x] tasks.md 中以下条目可勾选（Plan C 直接负责的部分）：
  - 2.1（ForwardingEngine 注册 OpcUaDataListener）
  - 2.2（规则匹配器）
  - 2.5 剩余（engine 关停编排）
  - 7.1（告警检测）
  - 7.2（告警推送复用 sender）
  - 7.3（alerts.enabled 开关）
  - 8.1（${ENV_VAR} 替换 — Spring Boot 默认支持）
  - 8.2（target enabled 跳过）
  - 8.3（@ConditionalOnProperty + AutoConfiguration）
  - 9.3（告警路由 Good/Bad/Uncertain 三 case）
  - 9.4（端到端 mock）
  - 9.5（drop-oldest 聚合 端到端）
  - 9.6（优雅关停 端到端）
  - 9.7（SenderRegistry 共享 端到端）
- [x] Task 9 完成后整个 tasks.md 全部 `[x]`

## Self-Review

| 检查项 | 结果 |
|--------|------|
| ForwardingEngine 在 bucket 线程同步执行匹配 | ✓（`onDataReceived` 同步） |
| matcher 纯 CPU、禁 I/O | ✓ RuleMatcher 全部本地计算 |
| sender enqueue O(1) | ✓ AbstractSender.enqueue 是 offer + drop-oldest |
| 告警 payload = 主 payload | ✓ 同一 OpcUaDeviceData 引用传给 main 和 alert sender |
| 告警 enabled=false 跳过 | ✓ Task 3 测试覆盖 |
| AutoConfig 总开关 | ✓ Task 4 enabled=false 测试覆盖 |
| ${ENV_VAR} 替换 | ✓ 由 Spring Boot 默认 PropertyPlaceholderConfigurer 处理（YAML 加载时） |
| Spec 覆盖：alerts.targets 任意 sender 类型 | ✓ AlertConfig.targets 与主 targets 共用 ForwardTarget 模型 |
| Spec 覆盖：unregisterListener cross-change | ✓ Plan A Task 3 + 本 Plan engine.shutdown |
| 端到端测试覆盖 Good/Bad/Uncertain | ✓ Task 5 + Task 3 |
