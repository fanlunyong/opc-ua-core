# Comet Design Handoff

- Change: opc-ua-forward
- Phase: design
- Mode: compact
- Context hash: 748bc305c9af5b4ddd55cac61c8133effbd12bd8e8d3c3b9bfe73e4913073c79

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/opc-ua-forward/proposal.md

- Source: openspec/changes/opc-ua-forward/proposal.md
- Lines: 1-34
- SHA256: 45e4f4dfc38d02f4f5f6b8864b829b3f0eee2d59072d1f22b8e33a90ff17796d

```md
## Why

OPC UA 设备数据采集后需要流转到多种下游系统（消息队列、数据库、消息推送、Webhook）才能产生业务价值。Change 1 解决了"怎么采"的问题，Change 2 解决"采完往哪发"的问题。同时基于数据质量标记将异常数据路由到告警通道，形成采集→分发→告警的完整数据链路。

## What Changes

- **NEW** Kafka 数据推送：将设备级 JSON 数据推送到 Kafka，支持按 productId/deviceId 路由到不同 topic
- **NEW** 时序数据库写入：将 data[] 中每条记录按时间戳写入 InfluxDB（或 TimescaleDB），支持配置 measurement 映射
- **NEW** MQTT 转发：将指定节点的数据实时推送到 MQTT broker 的指定 topic
- **NEW** HTTP Webhook：数据变更触发 HTTP POST 回调配置的 URL
- **NEW** 告警路由：基于 `quality` 字段，将 Bad/Uncertain 的数据推送到 Kafka 告警专用 topic
- **NEW** 转发规则配置：YAML 配置驱动的转发路由规则，按产品/设备/节点粒度控制数据流向

## Capabilities

### New Capabilities

- `kafka-forward`: Kafka 数据推送引擎，支持 topic 路由策略和连接池管理
- `tsdb-forward`: 时序数据库写入（InfluxDB）
- `mqtt-forward`: MQTT 协议数据转发
- `http-forward`: HTTP Webhook 回调
- `alert-routing`: 基于数据质量的告警路由，Bad/Uncertain 数据推送到 Kafka 告警 topic
- `forward-config`: 转发规则配置管理，支持按 productId/deviceId/节点粒度控制输出

### Modified Capabilities

<!-- 不修改 Change 1 的 capability，只消费其 DataListener 接口 -->

## Impact

- **依赖 Change 1**：通过 `OpcUaDataListener` 接口消费 `OpcUaDeviceData` 数据
- **新增依赖**：Spring Kafka、InfluxDB Client、Eclipse Paho（MQTT）、Spring Web（HTTP）
- **配置系统**：扩展 YAML 配置，新增 `forward` 转发规则配置段
- **数据模型**：引入 `ForwardRule`、`ForwardTarget` 等转发配置模型
```

## openspec/changes/opc-ua-forward/design.md

- Source: openspec/changes/opc-ua-forward/design.md
- Lines: 1-152
- SHA256: bcd1576b79bd528240263ae915e63d6aa276a819eab6a443dbd10b710e8d2bf4

[TRUNCATED]

```md
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
```

Full source: openspec/changes/opc-ua-forward/design.md

## openspec/changes/opc-ua-forward/tasks.md

- Source: openspec/changes/opc-ua-forward/tasks.md
- Lines: 1-51
- SHA256: be9013bce3f7db74e0dfe575dce2fd557e37ba7b2e91d6b9f39c3ab679745bdc

```md
## 1. 转发模块骨架

- [ ] 1.1 创建 `forward` 模块结构，添加依赖（Spring Kafka、InfluxDB Client、Eclipse Paho MQTT、Spring Web）
- [ ] 1.2 实现 YAML 转发配置绑定类（`ForwardProperties`），解析 `forward.rules` 配置
- [ ] 1.3 创建转发数据模型（`ForwardRule`、`ForwardTarget`、`MatchCondition`）

## 2. 转发引擎核心

- [ ] 2.1 实现 `ForwardingEngine`：注册为 Change 1 的 `OpcUaDataListener`，接收 `OpcUaDeviceData`
- [ ] 2.2 实现规则匹配器：根据 `MatchCondition`（productId/deviceId/nodeId）筛选匹配的规则
- [ ] 2.3 实现 Sender 异步调度：独立线程池，队列解耦，drop-oldest 背压策略

## 3. Kafka Sender

- [ ] 3.1 实现 `KafkaSender`：Kafka Producer 初始化与连接管理（单例 + 参数缓存）
- [ ] 3.2 实现 topic 模板解析：`opcua-data-{productId}` → 运行时替换为实际值
- [ ] 3.3 实现 `OpcUaDeviceData` JSON 序列化发送到 Kafka

## 4. InfluxDB Sender

- [ ] 4.1 实现 `InfluxDBSender`：InfluxDB Client 初始化与连接管理
- [ ] 4.2 实现 `OpcUaDataPoint` → InfluxDB Point 转换：measurement + tags + field + timestamp
- [ ] 4.3 实现 data[] 批量写入：一条 `WriteApi.writePoints()` 写入整个 data 数组

## 5. MQTT Sender

- [ ] 5.1 实现 `MqttSender`：Eclipse Paho MQTT Client 初始化与连接
- [ ] 5.2 实现 JSON 发布到配置的 MQTT topic

## 6. HTTP Sender

- [ ] 6.1 实现 `HttpSender`：Spring RestTemplate / WebClient HTTP POST 发送
- [ ] 6.2 实现超时控制（默认 5s）与错误日志记录

## 7. 告警路由

- [ ] 7.1 实现告警检测：遍历 data[] 中 quality 字段，识别 Bad/Uncertain
- [ ] 7.2 实现告警推送：Bad/Uncertain → Kafka 告警 topic（`opcua-alert-{productId}`）
- [ ] 7.3 实现 `alerts.enabled` 开关控制

## 8. 配置管理

- [ ] 8.1 实现 `${ENV_VAR}` 环境变量占位符替换（Kafka token、InfluxDB token 等）
- [ ] 8.2 实现 Target `enabled` 开关：disabled 的 target 跳过初始化与发送

## 9. 集成测试

- [ ] 9.1 编写 Kafka Sender 单元测试 + 嵌入式 Kafka 集成测试
- [ ] 9.2 编写 InfluxDB Sender 集成测试（使用 InfluxDB 内存实例或 Testcontainers）
- [ ] 9.3 编写告警路由逻辑测试：Good/Bad/Uncertain 分别验证
- [ ] 9.4 编写端到端测试：模拟数据从 Change 1 DataListener → ForwardingEngine → Kafka/InfluxDB 全链路
```

## openspec/changes/opc-ua-forward/specs/alert-routing/spec.md

- Source: openspec/changes/opc-ua-forward/specs/alert-routing/spec.md
- Lines: 1-31
- SHA256: c961ef4fed56f1aa6d7d8a050effa566388e3dcba63575039332b47cb8793014

```md
## ADDED Requirements

### Requirement: 告警路由
系统 SHALL 基于 `data[].quality` 字段，将包含 Bad/Uncertain 质量标记的设备数据路由到 Kafka 告警 topic。

#### Scenario: Bad 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Bad"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到数据 topic 和告警 topic（`opcua-alert-{productId}`）

#### Scenario: Uncertain 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Uncertain"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到数据 topic 和告警 topic

#### Scenario: Good 数据不触发告警
- **WHEN** data[] 中所有记录的 quality 均为 "Good"
- **THEN** 系统仅推送到数据 topic，不推送到告警 topic

#### Scenario: 告警消息格式
- **WHEN** 触发告警推送
- **THEN** 告警 topic 中的消息格式与数据 topic 完全一致（同一 `OpcUaDeviceData` JSON），下游消费者通过 `data[].quality != "Good"` 定位异常点

### Requirement: 告警开关
系统 SHALL 支持按规则配置是否启用告警路由（`alerts.enabled`）。

#### Scenario: 告警启用
- **WHEN** 规则配置 `alerts.enabled: true`
- **THEN** Bad/Uncertain 数据推送到告警 topic

#### Scenario: 告警禁用
- **WHEN** 规则配置 `alerts.enabled: false` 或未配置告警子段
- **THEN** Bad/Uncertain 数据仅推送到数据 topic，不做告警推送
```

## openspec/changes/opc-ua-forward/specs/forward-config/spec.md

- Source: openspec/changes/opc-ua-forward/specs/forward-config/spec.md
- Lines: 1-27
- SHA256: 6d3b2aed544e97421806fb9d4c6d3a11f47504d7427d979738947a51ed571e99

```md
## ADDED Requirements

### Requirement: 转发规则配置
系统 SHALL 通过 YAML 配置转发规则，每条规则定义匹配条件和转发目标。

#### Scenario: 按 productId 匹配
- **WHEN** 规则配置 `match.productId: "production-line-A"`
- **THEN** 仅 source.productId 匹配的设备数据通过该规则转发

#### Scenario: 全匹配
- **WHEN** 规则未配置 match 条件
- **THEN** 所有设备数据均匹配该规则

#### Scenario: 多 target 转发
- **WHEN** 一条规则配置了多个 targets（如 Kafka + InfluxDB）
- **THEN** 匹配的数据同时发送到所有 enabled: true 的 targets

#### Scenario: Target 禁用
- **WHEN** target 配置 `enabled: false`
- **THEN** 该转发目标不参与数据发送

### Requirement: 环境变量注入
系统 SHALL 支持在转发配置中使用 `${ENV_VAR}` 占位符引用环境变量（如 Kafka token、InfluxDB token 等敏感信息）。

#### Scenario: 环境变量替换
- **WHEN** 配置中包含 `${INFLUX_TOKEN}`
- **THEN** 系统在加载配置时将其替换为实际环境变量值
```

## openspec/changes/opc-ua-forward/specs/http-forward/spec.md

- Source: openspec/changes/opc-ua-forward/specs/http-forward/spec.md
- Lines: 1-16
- SHA256: 1bcfbd0ab5bc17d9398216783fe7dd6e195cc2cbc50cef0204e52d89c388adcc

```md
## ADDED Requirements

### Requirement: HTTP Webhook 回调
系统 SHALL 将 `OpcUaDeviceData` JSON 通过 HTTP POST 回调配置的 Webhook URL。

#### Scenario: HTTP POST 推送
- **WHEN** 收到设备数据且转发规则匹配到 HTTP target
- **THEN** 系统向配置的 URL 发送 HTTP POST 请求，Content-Type 为 application/json，body 为 `OpcUaDeviceData` JSON

#### Scenario: HTTP 回调失败
- **WHEN** HTTP POST 返回非 2xx 状态码或连接超时
- **THEN** 系统记录 WARN 日志（含状态码），丢弃该消息，不重试

#### Scenario: HTTP 超时控制
- **WHEN** HTTP 请求超过配置的超时时间（默认 5s）
- **THEN** 系统取消请求，记录 WARN 日志
```

## openspec/changes/opc-ua-forward/specs/kafka-forward/spec.md

- Source: openspec/changes/opc-ua-forward/specs/kafka-forward/spec.md
- Lines: 1-31
- SHA256: 7ef8fa971c8fd078ef183805c0bc2be7e5a5017a2d61df920d4379d90f3b0477

```md
## ADDED Requirements

### Requirement: Kafka 数据推送
系统 SHALL 将 `OpcUaDeviceData` JSON 推送到 Kafka，支持按配置路由到指定 topic。

#### Scenario: 数据推送到 Kafka
- **WHEN** 收到设备数据且转发规则匹配到 Kafka target
- **THEN** 系统将完整的 `OpcUaDeviceData` JSON 序列化后发送到 Kafka 对应 topic

#### Scenario: Topic 按 productId 路由
- **WHEN** 配置 topic 模板为 `opcua-data-{productId}` 且 source.productId 为 `production-line-A`
- **THEN** 数据推送到 topic `opcua-data-production-line-A`

#### Scenario: Topic 按 deviceId 路由
- **WHEN** 配置 topic 模板为 `opcua-device-{deviceId}` 且 source.deviceId 为 `furnace-01`
- **THEN** 数据推送到 topic `opcua-device-furnace-01`

#### Scenario: Kafka 不可达
- **WHEN** Kafka broker 不可达
- **THEN** 系统记录 ERROR 日志，丢弃当前消息，不阻塞后续数据处理

### Requirement: Kafka 连接管理
系统 SHALL 管理 Kafka Producer 连接池，支持配置 bootstrap-servers 和基础 producer 参数。

#### Scenario: Producer 初始化
- **WHEN** 服务启动且配置了 Kafka 转发规则
- **THEN** 系统初始化 Kafka Producer 并连接到 bootstrap-servers

#### Scenario: 多 Kafka 集群
- **WHEN** 不同转发规则配置了不同的 bootstrap-servers
- **THEN** 系统为每个独立的 bootstrap-servers 创建独立的 Producer 实例
```

## openspec/changes/opc-ua-forward/specs/mqtt-forward/spec.md

- Source: openspec/changes/opc-ua-forward/specs/mqtt-forward/spec.md
- Lines: 1-16
- SHA256: 1abad6832279dd6cce3101dc180fc6a0b94e617cd67da4dc689cfd34dcaed1fd

```md
## ADDED Requirements

### Requirement: MQTT 数据转发
系统 SHALL 将 `OpcUaDeviceData` JSON 转发到配置的 MQTT broker 指定 topic。

#### Scenario: MQTT 推送
- **WHEN** 收到设备数据且转发规则匹配到 MQTT target
- **THEN** 系统将完整的 `OpcUaDeviceData` JSON 发布到配置的 MQTT topic

#### Scenario: MQTT 连接配置
- **WHEN** YAML 中配置了 MQTT broker URL、topic、QoS
- **THEN** 系统使用这些参数连接 MQTT broker 并发布消息

#### Scenario: MQTT 断线重连
- **WHEN** MQTT broker 连接断开
- **THEN** 系统使用 MQTT 客户端自带重连机制恢复连接，断开期间消息丢弃并记录日志
```

## openspec/changes/opc-ua-forward/specs/tsdb-forward/spec.md

- Source: openspec/changes/opc-ua-forward/specs/tsdb-forward/spec.md
- Lines: 1-27
- SHA256: 7455d24150b3cfb1ee1e9e861291cabe1c3dc5ae0519b40a252c0300bfc7ca56

```md
## ADDED Requirements

### Requirement: InfluxDB 数据写入
系统 SHALL 将 `OpcUaDeviceData` 的 data[] 中每条数据点写入 InfluxDB。

#### Scenario: 单批次写入
- **WHEN** 收到设备数据且转发规则匹配到 InfluxDB target
- **THEN** data[] 中每条记录逐条转为 InfluxDB Point 写入指定 bucket

#### Scenario: InfluxDB Point 字段映射
- **WHEN** 写入 InfluxDB
- **THEN** Point 的 measurement 为设备 displayName，tags 包含 {productId, deviceId, nodeId, quality}，field 为 value，timestamp 为 sourceTimestamp

#### Scenario: InfluxDB 不可达
- **WHEN** InfluxDB 服务不可达
- **THEN** 系统记录 ERROR 日志，跳过当前批次，不阻塞后续数据

#### Scenario: 批量写入优化
- **WHEN** data[] 包含多条记录（如 10 条）
- **THEN** 系统通过 InfluxDB WriteApi 一次性批量提交，减少网络往返

### Requirement: InfluxDB 连接配置
系统 SHALL 支持通过 YAML 配置 InfluxDB 连接参数。

#### Scenario: InfluxDB 连接配置
- **WHEN** YAML 中配置了 InfluxDB url、bucket、org、token
- **THEN** 系统使用这些参数初始化 InfluxDB Client
```

