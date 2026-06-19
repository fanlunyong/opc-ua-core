---
comet_change: opc-ua-forward
role: technical-design
canonical_spec: openspec
---

# OPC UA 转发引擎技术设计（Change 2）

## Context

Change 1 (`opc-ua-core`) 提供 `OpcUaDataListener.onDataReceived(OpcUaDeviceData)` 设备级批量数据接口。Change 2 作为消费者注册到 Change 1 的 listener，负责将数据路由到多种下游系统（Kafka / InfluxDB / MQTT / HTTP）和告警通道。

**约束：**
- Java 17 + Spring Boot 3.2 + Maven
- 与 Change 1 同进程（同 JVM），通过 `OpcUaService.registerListener` 注册
- YAML 配置驱动；Change 3 再追加 REST API 动态配置
- 全 Mock 测试策略，不引入 Testcontainers / EmbeddedKafka

## Goals / Non-Goals

**Goals：**
- 实现 `OpcUaDataListener`，接收设备级批量 JSON
- Kafka / InfluxDB / MQTT / HTTP 4 类 sender 的统一抽象
- 告警路由：`data[].quality` 含 Bad/Uncertain → 整批 OpcUaDeviceData 推送到 alerts.targets（任意 sender 类型）
- YAML 规则驱动 + `${ENV}` 环境变量注入 + 优雅关停

**Non-Goals：**
- OPC UA 协议连接（Change 1）
- 消息去重 / 幂等保证（下游负责）
- 告警分级 / 聚合 / 去重（开放问题，本期不做）
- 自定义下游插件（首期不做）
- 动态配置（Change 3）
- 嵌入式 broker 集成测试（hardening change 后续考虑）

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│                  Change 1: opc-ua-core                           │
│              OpcUaService.registerListener(...)                  │
│              DataDispatchEngine bucket-thread                    │
└────────────────────────────────┬─────────────────────────────────┘
                                 │ onDataReceived(OpcUaDeviceData)
                                 ▼
┌──────────────────────────────────────────────────────────────────┐
│                  Change 2: opc-ua-forward                        │
│  ┌─────────────────────────────────────────────────────────────┐ │
│  │  ForwardingEngine                                           │ │
│  │  (bucket thread, sync rule matching)                        │ │
│  │   for rule in rules:                                        │ │
│  │     if matcher(rule.match, data) :                          │ │
│  │       for target in rule.targets:                           │ │
│  │         senderRegistry.get(target).enqueue(data)            │ │
│  │       if rule.alerts.enabled and hasBadOrUncertain(data):   │ │
│  │         for alertTarget in rule.alerts.targets:             │ │
│  │           senderRegistry.get(alertTarget).enqueue(data)     │ │
│  └────────────────────┬────────────────────────────────────────┘ │
│                       │ enqueue (O(1) offer + drop-oldest)        │
│         ┌─────────────┼──────────────┬──────────────┐            │
│         ▼             ▼              ▼              ▼            │
│  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐    │
│  │ KafkaSender│ │InfluxDBSdr │ │ MqttSender │ │ HttpSender │    │
│  │ queue+wkr  │ │ queue+wkr  │ │ queue+wkr  │ │ queue+wkr  │    │
│  └─────┬──────┘ └─────┬──────┘ └─────┬──────┘ └─────┬──────┘    │
│        │              │              │              │            │
│        ▼              ▼              ▼              ▼            │
│   shared Kafka   shared Influx  shared MQTT    HTTP per-call    │
│   Producer       Client         Client         (no shared state)│
│   (by bootstrap  (by url+org+   (by brokerUrl                   │
│    +securityCfg) token)         +clientId)                      │
└──────────────────────────────────────────────────────────────────┘
```

## Decisions

### D1: 异步边界 — 入口同步匹配，Sender 异步

**方案：** `ForwardingEngine.onDataReceived` 在 Change 1 DataDispatchEngine bucket 线程上**同步**执行规则匹配。匹配后调用 `sender.enqueue(data)`，Sender 内部独立队列 + worker drain。

**理由：**
- 单层队列模型，链路清晰
- 规则数 ≤ 10、target 数 ≤ 30 时性能可控
- bucket 线程只承担 O(规则数) HashMap lookup + 字符串比较，纯 CPU

**约束：**
- 规则匹配代码必须纯 CPU：禁 I/O、禁 spam log、禁 lock contention
- `enqueue()` 必须 O(1)（offer + drop-oldest 局部计数）

**风险：** 规则数大幅增长（> 50）时需重构为 Engine 入口立即异步（双层队列）。当前不预先优化。

### D2: Sender 共享粒度 — 按连接参数指纹聚合

**方案：** `SenderRegistry` 维护按连接指纹聚合的 sender 实例：

| Sender | 连接指纹 key |
|---|---|
| Kafka | `bootstrap-servers + securityConfig` |
| MQTT | `brokerUrl + clientId` |
| InfluxDB | `url + org + token` |
| HTTP | type 单例（无连接状态） |

```java
public class SenderRegistry {
    private final Map<ConnectionFingerprint, SharedConnection> connections = new ConcurrentHashMap<>();
    private final Map<TargetKey, Sender> senders = new ConcurrentHashMap<>();

    public Sender getOrCreate(ForwardTarget target) {
        ConnectionFingerprint fp = target.fingerprint();
        SharedConnection conn = connections.computeIfAbsent(fp,
            k -> SharedConnection.create(target));
        return senders.computeIfAbsent(targetKey(target),
            k -> Sender.forTarget(target, conn));
    }
}
```

**理由：**
- KafkaProducer / InfluxDB Client / MqttClient 都是线程安全且设计为共享
- 多 rule 配置同 broker → 单连接节省资源
- 队列 + worker 仍 per-target：topic/url 不同的 target 互相隔离，慢 target 不拖慢同 broker 内的快 target
- 满足 spec scenario "多 Kafka 集群独立 Producer"（不同 bootstrap 不同连接）

**实现要点：**
- `ConnectionFingerprint` 为 immutable record，equals/hashCode 基于稳定字段
- 引用计数：每个 sender 持有一个 `SharedConnection.acquire()`，`shutdown()` 时 `release()`，最后一个释放才 close 底层连接

### D3: 告警路由 — 通用 targets

**方案：** `alerts.targets` 支持任意 sender 类型。触发条件：`data[]` 中**任意一条** quality=Bad 或 Uncertain → 整批 OpcUaDeviceData 推送到所有 `alerts.targets`。

```java
private boolean hasBadOrUncertain(OpcUaDeviceData data) {
    for (OpcUaDataPoint p : data.getData()) {
        Quality q = p.getQuality();
        if (q == Quality.Bad || q == Quality.Uncertain) return true;
    }
    return false;
}
```

**告警 payload：** 与主 topic 完全一致的 OpcUaDeviceData JSON（不过滤 Good 点）。下游消费者用 `data[].quality != "Good"` 定位异常。

**告警禁用：** `alerts.enabled=false` 或无 `alerts` 节 → 跳过告警推送。

### D4: 测试策略 — 全 Mock

| Sender | Mock 对象 | 验证点 |
|---|---|---|
| Kafka | `KafkaProducer.send` | ProducerRecord 的 topic + key + JSON value |
| MQTT | `IMqttClient.publish` | topic + qos + payload |
| InfluxDB | `WriteApi.writePoints` | Point 列表的 measurement + tags + field + ts |
| HTTP | `RestTemplate.exchange` | URI + method=POST + Content-Type + JSON body |

**端到端测试：** 构造 fake `OpcUaDeviceData` → 注入 `ForwardingEngine` → 验证多个 mock senders 收到正确路由的消息。

**舍弃：** EmbeddedKafkaBroker / Moquette / Testcontainers。归档后建议以 `opc-ua-forward-hardening` change 形式补 Testcontainers 真实写入测试。

### D5: 优雅关停（5s 超时）

**流程：**

```
Spring context close → @PreDestroy on ForwardingEngine
  1. opcUaService.unregisterListener(this)        // 停止接收新数据
  2. for sender : senders : sender.stopAccepting()  // 拒绝新 enqueue
  3. for sender : senders : sender.awaitDrain(5s)   // 等待队列清空
  4. 超时未 drain：log.warn("sender {} shutdown timeout, {} messages dropped", sender.id, queueSize)
  5. for sender : senders : sender.close()          // 关闭 worker 线程
  6. senderRegistry.releaseAllConnections()         // close 共享连接（按引用计数归零）
```

**配置：** `forward.shutdownTimeout`（默认 PT5S，ISO-8601 Duration）

**理由：**
- 数据不丢优先于关停延迟
- 5s 是 Spring Boot 默认 SmartLifecycle phase 之间的合理 budget
- 超时也要继续，避免 JVM 卡死

### D6: Drop-oldest WARN — (deviceId, senderId) 聚合

**实现：**

```java
public abstract class AbstractSender {
    private final ConcurrentHashMap<String, AtomicLong> dropsByDevice = new ConcurrentHashMap<>();
    private final AtomicLong totalDropsSinceLastFlush = new AtomicLong();
    private volatile long lastFlushNanos = System.nanoTime();
    private static final long FLUSH_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long FLUSH_COUNT_THRESHOLD = 100;

    public void enqueue(OpcUaDeviceData data) {
        if (!accepting.get()) return;
        while (!queue.offer(data)) {
            queue.poll();
            dropsByDevice
                .computeIfAbsent(data.getDeviceId(), k -> new AtomicLong())
                .incrementAndGet();
            totalDropsSinceLastFlush.incrementAndGet();
        }
        maybeFlushDropWarnings();
    }

    private void maybeFlushDropWarnings() {
        long now = System.nanoTime();
        if (now - lastFlushNanos >= FLUSH_INTERVAL_NANOS
                || totalDropsSinceLastFlush.get() >= FLUSH_COUNT_THRESHOLD) {
            // double-checked under volatile flag
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
            if (c > 0) logger.warn("Sender {} dropped {} messages from device {}",
                                   senderId, c, deviceId);
        });
        totalDropsSinceLastFlush.set(0);
    }
}
```

**语义：**
- 同 (deviceId, senderId) 1s 内或累积 100 条合并为 1 条 WARN
- 不同 deviceId 不合并（便于定位异常设备）
- 不同 senderId 不合并（不同 sender 通常是不同根因）
- 双触发：保证低吞吐时不会 1 小时不报，高吞吐时不会日志爆炸

### D7: Bean 生命周期

**结构：**

```java
@Service
@ConditionalOnProperty(prefix = "opcua.forward", name = "enabled",
                       havingValue = "true", matchIfMissing = true)
public class ForwardingEngine implements OpcUaDataListener {

    private final OpcUaService opcUaService;
    private final SenderRegistry senderRegistry;
    private final ForwardProperties forwardProperties;
    private final ObjectMapper objectMapper;       // 复用 Change 1 的 Bean

    @PostConstruct
    public void init() {
        if (forwardProperties.getRules().isEmpty()) {
            log.info("opcua.forward.rules is empty, skipping listener registration");
            return;
        }
        senderRegistry.initialize(forwardProperties);
        opcUaService.registerListener(this);
        log.info("ForwardingEngine registered with {} rules", forwardProperties.getRules().size());
    }

    @PreDestroy
    public void shutdown() {
        opcUaService.unregisterListener(this);
        senderRegistry.shutdown(forwardProperties.getShutdownTimeout());
    }

    @Override
    public void onDataReceived(OpcUaDeviceData data) {
        for (ForwardRule rule : forwardProperties.getRules()) {
            if (!matcher.matches(rule.getMatch(), data)) continue;
            for (ForwardTarget target : rule.getTargets()) {
                if (target.isEnabled()) senderRegistry.getOrCreate(target).enqueue(data);
            }
            if (rule.getAlerts() != null && rule.getAlerts().isEnabled()
                    && hasBadOrUncertain(data)) {
                for (ForwardTarget alertTarget : rule.getAlerts().getTargets()) {
                    senderRegistry.getOrCreate(alertTarget).enqueue(data);
                }
            }
        }
    }
}
```

**`OpcUaService.unregisterListener` 缺口：** Change 1 当前只有 `registerListener`，需要补一个 `unregisterListener`。这属于 Change 1 的 API 增量；策略：

- 在 Change 2 的 build 阶段，向 Change 1 已归档的 `data-collection` capability 提交一个补丁（OpenSpec 增量 spec），明确 `unregisterListener` 的 scenario
- 实现层在 `opc-ua-forward` 实施时同步修改 `com.opcua.api.OpcUaService` 添加方法（与 Change 2 commits 同分支）
- 这是合规的 cross-change 修改，因为 Change 2 实施时 Change 1 已归档稳定，新增 API 不破坏已归档语义

## Configuration Schema

```yaml
opcua:
  forward:
    enabled: true                    # 总开关，默认 true
    shutdownTimeout: PT5S            # 优雅关停超时（ISO-8601 Duration）
    rules:
      - name: "line-A-to-kafka"
        match:
          productId: "production-line-A"   # 不指定则全匹配
          deviceId: "furnace-*"            # 通配符可选
          # nodeId: "ns=2;s=...." 可选
        targets:
          - type: kafka
            enabled: true                  # 默认 true，false 跳过初始化
            bootstrap-servers: "kafka:9092"
            securityProtocol: PLAINTEXT
            topic: "opcua-data-{productId}"   # 占位符 productId/deviceId 运行时替换
            queueCapacity: 1000              # drop-oldest 阈值
            workerThreads: 2                 # sender worker 线程数
          - type: influxdb
            enabled: true
            url: "http://influxdb:8086"
            bucket: "opcua-data"
            org: "my-org"
            token: "${INFLUX_TOKEN}"         # 环境变量替换
            queueCapacity: 1000
        alerts:
          enabled: true
          targets:
            - type: kafka
              bootstrap-servers: "kafka:9092"  # 通常复用主 broker
              topic: "opcua-alert-{productId}"
            - type: http
              url: "http://alert-webhook/notify"
              timeoutMillis: 5000
      - name: "all-to-mqtt"
        match: {}                         # 全匹配
        targets:
          - type: mqtt
            brokerUrl: "tcp://mqtt:1883"
            clientId: "opc-ua-forwarder-1"
            topic: "opcua/{productId}/{deviceId}"
            qos: 1
```

## Risks / Trade-offs

- **[R] 规则匹配同步阻塞 bucket 线程** → 限定规则数 ≤ 10 + 强制 O(1) 匹配；超过阈值需重构 D1
- **[R] 共享 Producer 故障影响多 target** → `SharedConnection` 暴露健康状态，sender enqueue 时 fast-fail；后续 hardening 可加 circuit breaker
- **[R] Mock-only 测试漏掉协议层 bug** → 归档前在验证报告标记，建议 hardening change 加 Testcontainers profile
- **[R] 5s 优雅关停可能不够（Kafka producer.flush 慢）** → 配置可调；超时仍强制关闭，丢失数据计入 WARN 日志
- **[T] 告警与主路由可能重复发送** → 用户配置责任：alerts.targets 与 main targets 应不同 topic；引擎不做去重

## Open Questions

无（D1-D7 已完成；告警去重保留为 Change 3 后续工作）

## P0 数据质量过滤（Plan 阶段补充）

> 在 plan 编写阶段识别 OPC UA `StatusCode` 风险后追加的决策，已落地到 Plan A/B/C。

### 风险

`SubscriptionManager.onBatchReceived` 把所有 OpcUaDataPoint（包括 `Quality.Bad` / `Uncertain`）一律分发到 ForwardingEngine。下游消费者拿到的 Bad 点 value 通常为 null 或停滞值，可能被误判为正常。OPC UA `StatusCode` 子状态信息（BadConnectionClosed、UncertainLastUsableValue 等）也容易在序列化中丢失。

### 决策

1. **新增 `QualityFilter` POJO** 与 `ForwardRule.qualityFilter` 字段（默认 `dropBadOnly`：丢 Bad、放行 Good 与 Uncertain）— Plan A Task 4
2. **`QualityFilterApplier` 工具** — Plan C Task 3b：filter 全放行返回原对象（无 GC）；全部丢弃返回 null（调用方跳过 enqueue）；部分丢弃以 `(原 sourceInfo + 子集 dataPoints)` 重建
3. **`ForwardingEngine.onDataReceived`** 在路由前应用 filter 到主 targets；**alert 通道豁免**（alert 用原始数据，保留 Bad/Uncertain 上下文供告警）— Plan C Task 3
4. **Sender payload 必含字段**：`quality`、`statusCode`（hex）、`sourceTimestamp`、`serverTimestamp` — Plan B 顶部约定 + 每个 Sender 单测验证
5. **InfluxDB 时间戳 fallback 链**：sourceTimestamp → serverTimestamp → batch timestamp → now；server 时间作为 `serverTimestampNs` field 保留 — Plan B Task 3 InfluxDBSender

### 取舍

| 决策 | 选择 | 备选 | 理由 |
|------|------|------|------|
| 默认过滤策略 | dropBadOnly | dropBoth / passAll | Bad 数据 value 不可信；Uncertain 仍有运维价值 |
| 过滤位置 | ForwardingEngine 路由前 | SubscriptionManager 回调内 | 保持 SubscriptionManager 单一职责；ForwardingEngine 已是路由决策点 |
| Alert 是否过滤 | 不过滤 | 与主通道一致 | 告警目的就是暴露 Bad/Uncertain |
| StatusCode 表示 | hex 字符串透传 | 子状态枚举 | 当前 dropBadOnly 已覆盖 80% 场景；子状态枚举体积大，留待 hardening change |

### 未覆盖（保留为新 change `opc-ua-quality-hardening`）

- Stale Value 检测（设备级 watchdog）
- `UaSubscription.NotificationListener.onStatusChanged` / `onKeepAlive` / `onPublishFailure` 回调
- StatusCode 子状态枚举化与精细化过滤
- Quality 指标暴露（Micrometer）
- 时钟漂移检测（source vs server timestamp delta）

## Spec Patches

本 change 实施时同步回写以下 OpenSpec delta：

1. `proposal.md` 措辞修订：告警目标类型从"仅 Kafka"扩展为"任意 sender 类型"
2. `tasks.md` 9.1/9.2/9.4：测试策略改为 Mock-based
3. `tasks.md` 新增 2.4：Drop-oldest (deviceId, senderId) 聚合 WARN 实现
4. `tasks.md` 新增 2.5：优雅关停 5s drain 实现
5. `specs/forward-config/spec.md`：补充 `forward.shutdownTimeout` scenario
6. `specs/alert-routing/spec.md`：scenario 措辞确认通用 sender 类型
7. **Cross-change**：在 Change 1 archived `data-collection` capability 加补丁 spec，明确 `unregisterListener` scenario（实现同步加方法）
