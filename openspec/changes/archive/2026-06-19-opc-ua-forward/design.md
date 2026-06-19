## Context

Change 1 (`opc-ua-core`) 提供 `OpcUaDataListener` 接口和 `OpcUaDeviceData` 统一 JSON 输出。Change 2 作为数据消费者注册到 Change 1 的 Listener，负责将数据路由到多种下游系统。

**约束条件**：
- Java 17 + Spring Boot 3.x
- 与 Change 1 同进程运行（同 JVM），通过 `OpcUaDataListener` 接口直连
- 本期为 YAML 配置驱动（Change 3 追加 REST API 动态配置）
- 告警消息仅推送到 Kafka 告警 topic，不做其他通知渠道

## Goals / Non-Goals

**Goals:**
- 实现 `OpcUaDataListener`，接收设备级批量 JSON 数据
- Kafka 推送：统一 JSON 入 Kafka，按 productId/deviceId 路由 topic
- 时序数据库写入：InfluxDB，data[] 逐条按时间戳写入
- MQTT 转发、HTTP Webhook
- 告警路由：quality=Bad/Uncertain → Kafka alert topic

**Non-Goals:**
- 不做 OPC UA 协议连接（Change 1）
- 不做 Kafka Schema Registry 集成
- 不做消息去重/幂等保证（下游消费者负责）
- 不做告警分级/聚合/去重
- 不做自定义下游插件（首期）

## Decisions

### D1: 转发管道架构 — Chain of Responsibility + Fan-out

```
┌──────────────────────────────────────────────────────────────┐
│                  ForwardingEngine                            │
├──────────────────────────────────────────────────────────────┤
│                              │                               │
│    OpcUaDataListener.onDataReceived(OpcUaDeviceData)         │
│                              │                               │
│              ┌───────────────┼───────────────┐               │
│              ▼               ▼               ▼               │
│        ┌──────────┐   ┌──────────┐   ┌──────────┐           │
│        │ Router   │   │ Router   │   │ Router   │           │
│        │ (rules)  │   │ (rules)  │   │ (rules)  │           │
│        └────┬─────┘   └────┬─────┘   └────┬─────┘           │
│             ▼              ▼              ▼                  │
│        ┌──────────┐   ┌──────────┐   ┌──────────┐           │
│        │ Kafka    │   │ InfluxDB │   │ MQTT/HTTP│           │
│        │ Sender   │   │ Writer   │   │ Sender   │           │
│        └──────────┘   └──────────┘   └──────────┘           │
└──────────────────────────────────────────────────────────────┘
```

**流程**：
1. `ForwardingEngine` 实现 `OpcUaDataListener`，接收 `OpcUaDeviceData`
2. 将 JSON 通过每个配置的 `ForwardRouter` 传递
3. 每个 Router 根据规则判断：该数据是否匹配（productId/deviceId/quality 等）
4. 匹配后将 JSON 交给对应的 Sender 发送
5. 各 Sender 异步执行（独立线程池），不阻塞 DataListener 回调线程

**理由**：Router 过滤规则 + Sender 发送逻辑分离，新增下游系统只需实现新 Sender。异步发送确保 OPC UA 数据采集不被慢速下游拖慢。

### D2: Kafka Topic 路由策略

默认路由策略（可配置覆盖）：

```
productId → topic-name 映射：
  opcua-data-{productId}        # 数据 topic（Good/Uncertain）
  opcua-alert-{productId}       # 告警 topic（Bad）
```

**备选方案**：
- 单 topic 全量：简单但下游消息混杂，不符合"按产品订阅不同 topic"需求
- 按 deviceId 路由 topic：粒度更细但 topic 过多

**选择理由**：按 productId 路由是产品级隔离的合适粒度，balance 了 topic 数量和消费灵活性。

### D3: InfluxDB 写入模型

```java
// 每条 OPC UA 数据点写入一条 InfluxDB point
// measurement = 设备 displayName
// tags = { productId, deviceId, nodeId, quality }
// fields = { value: 25.5 }
// timestamp = sourceTimestamp
```

data[] 中每条记录逐条转为 InfluxDB Point 写入，保留完整标签索引。

### D4: 告警路由

告警路由复用 Kafka Sender，但 target topic 固定为 `opcua-alert-{productId}`：

```java
// 在 ForwardingEngine 中：
if ("Bad".equals(point.getQuality()) || "Uncertain".equals(point.getQuality())) {
    // 同时路由到数据 topic 和告警 topic
    kafkaSender.send(alertTopic, deviceData);
}
```

**理由**：告警消息格式与数据消息完全一致（同一个 `OpcUaDeviceData` JSON），无需额外解析逻辑。下游告警消费者筛选 `data[].quality != "Good"` 即可定位异常数据点。

### D5: YAML 配置结构

```yaml
forward:
  rules:
    - name: "line-A-to-kafka"
      match:
        productId: "production-line-A"
      targets:
        - type: kafka
          enabled: true
          bootstrap-servers: "kafka:9092"
          topic: "opcua-data-{productId}"
        - type: influxdb
          enabled: true
          url: "http://influxdb:8086"
          bucket: "opcua-data"
          org: "my-org"
          token: "${INFLUX_TOKEN}"
      alerts:
        enabled: true
        targets:
          - type: kafka
            topic: "opcua-alert-{productId}"
    - name: "all-to-http"
      match:
        # 不指定 productId，匹配全部设备
      targets:
        - type: http
          url: "http://webhook.internal/opcua"
```

**理由**：规则驱动转发，每规则独立配置 match 条件（productId/deviceId/nodeId）和 targets 列表。alerts 子段独立配置告警目标，且支持 `${ENV}` 环境变量注入敏感信息。

### D6: 异步发送与容错

- 每个 Sender 使用独立的线程池（默认 4 线程），通过内存队列解耦
- 发送失败时记录 ERROR 日志，不重试（避免积压拖累上游）
- 队列满时丢弃最老消息（drop-oldest），防止 OOM

## Risks / Trade-offs

- **[R] Kafka broker 不可达导致消息丢失** → 异步队列 + drop-oldest 策略控制背压；Kafka 持久化能力由运维保障
- **[R] InfluxDB 写入性能瓶颈（100+ 设备高频率推送）** → 批量写入（batch write），data[] 中多条记录一次 `WriteApi.writePoints()` 提交
- **[T] 下游故障隔离** → 每个 Sender 独立线程池，一个下游故障不影响其他通道

## Open Questions

- InfluxDB vs TimescaleDB 最终选型：优先 InfluxDB（时序场景最佳匹配），第二期评估 TimescaleDB
- 告警是否需要降噪（同设备连续 Bad 只报一次）：本期不做，Change 3 或后续迭代补充
