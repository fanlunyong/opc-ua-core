## Context

当前项目从零开始，无现有代码。需要构建一个 Java 后端服务的 OPC UA 核心采集层，封装 Eclipse Milo 客户端库，为上层数据转发引擎（Change 2）提供统一 JSON 数据输出。

**约束条件**：
- Java 17 + Spring Boot 3.x + Maven 构建
- 纯后端服务，无 UI 组件
- 本期通过 YAML 配置文件静态配置（Change 3 追加 REST API 动态配置）
- 第一期支持 OPC UA 标准安全（证书、用户名/密码、加密），第二期追加 REST API 认证

## Goals / Non-Goals

**Goals:**
- 封装 Eclipse Milo，提供设备发现、连接池、会话管理
- 支持订阅（Subscription）、轮询（Read）、写入（Write）三种数据交互模式
- 基于 OPC UA StatusCode 标记每条数据质量（Good/Bad/Uncertain）
- NodeId → 业务名称 → 统一 JSON 的映射
- 健康检查端点 + 断线自动重连
- 支持 100+ 设备并发连接

**Non-Goals:**
- 不做 Alarms & Events 订阅
- 不做数据转发到下游系统（Kafka、数据库等）
- 不做 REST API 动态配置
- 不做告警触发逻辑（质量标记提供原始数据，告警决策在 Change 2）
- 不做集群/分布式部署（Change 3）

## Decisions

### D1: 核心架构 — 三层模块结构

```
┌─────────────────────────────────────────────────────────┐
│                    opc-ua-core                           │
├─────────────────────────────────────────────────────────┤
│  API Layer (Public Interface)                           │
│  • OpcUaService — 统一入口                              │
│  • DataListener — 数据回调接口                           │
├─────────────────────────────────────────────────────────┤
│  Core Layer                                             │
│  • ConnectionManager — 连接池、会话管理                   │
│  • SubscriptionManager — 订阅生命周期管理                 │
│  • ReadWriteHandler — 轮询读取、写入控制                  │
│  • DataMapper — NodeId → JSON 映射                      │
│  • QualityEvaluator — StatusCode → 质量标记              │
│  • HealthIndicator — 连接健康状态                         │
├─────────────────────────────────────────────────────────┤
│  Client Wrapper Layer                                   │
│  • MiloClientWrapper — Eclipse Milo 封装                 │
│  • 处理连接、断线重连、证书管理                            │
└─────────────────────────────────────────────────────────┘
```

**理由**：三层分离使数据转发模块（Change 2）只需依赖 API Layer 的 `DataListener` 接口即可消费统一 JSON，无需关心底层 OPC UA 协议细节。Core Layer 可独立单测。

### D2: 连接池 — 有界连接池 + 会话复用

每个 OPC UA 端点维护一个可配置的有界连接池（默认 2-5 个连接），通过订阅复用同一个会话。设备数量超过连接池承载能力时排队等待。

**备选方案**：
- 每设备单连接：简单但 100+ 设备时可能资源吃紧
- 无限连接池：可能耗尽系统资源

**选择理由**：有界连接池提供背压控制，适合 100+ 设备规模的初始版本，后续可通过配置调优。

### D3: 数据模型 — 设备级批量数组 JSON Schema

每次数据采集以设备为粒度，一次上报该设备本次变更的全部属性值，数据部分为数组结构：

```json
{
  "timestamp": "2026-06-17T10:30:00.000Z",
  "source": {
    "productId": "production-line-A",
    "deviceId": "furnace-01",
    "endpointUrl": "opc.tcp://192.168.1.100:4840"
  },
  "data": [
    {
      "nodeId": "ns=2;s=Temperature",
      "displayName": "temperature",
      "value": 25.5,
      "dataType": "Double",
      "quality": "Good",
      "statusCode": "0x00000000",
      "sourceTimestamp": "2026-06-17T10:30:00.000Z",
      "serverTimestamp": "2026-06-17T10:30:00.050Z"
    },
    {
      "nodeId": "ns=2;s=Pressure",
      "displayName": "pressure",
      "value": 101.3,
      "dataType": "Double",
      "quality": "Good",
      "statusCode": "0x00000000",
      "sourceTimestamp": "2026-06-17T10:30:00.000Z",
      "serverTimestamp": "2026-06-17T10:30:00.050Z"
    }
  ]
}
```

**数组聚合规则**：
- 订阅模式：一个 Subscription 回调批次内的所有变更节点合并到 `data` 数组
- 轮询模式：同设备同一轮询周期内的所有轮询节点合并到 `data` 数组
- 单次上报一定包含 `source`（设备信息），`data` 数组至少 1 个元素

**理由**：
- 设备级聚合减少下游消费次数，一条 Kafka 消息即包含设备完整快照
- `data` 数组结构便于下游逐条消费或批量入库
- `quality` 字段基于 StatusCode 评估（Good / Bad / Uncertain），下游告警模块可直接消费
- 保留 `nodeId` 与 `displayName`，兼顾可追溯性和可读性
- 带上双时间戳（source + server），支持延迟分析

### D4: 配置结构 — YAML 驱动

```yaml
opcua:
  devices:
    - id: "furnace-01"
      productId: "production-line-A"
      endpoint: "opc.tcp://192.168.1.100:4840"
      security:
        policy: "Basic256Sha256"
        mode: "SignAndEncrypt"
        certificate: "/certs/client.pfx"
        username: "admin"
        password: "secret"
      connection-pool:
        max-connections: 3
        idle-timeout: 60s
      subscriptions:
        - name: "temperature-group"
          interval: 1000ms
          nodes:
            - nodeId: "ns=2;s=Temperature"
              displayName: "temperature"
              qualityCheck: true
            - nodeId: "ns=2;s=Pressure"
              displayName: "pressure"
              qualityCheck: true
      polling:
        - nodeId: "ns=2;s=Status"
          displayName: "status"
          interval: 5000ms
          qualityCheck: false
```

**理由**：YAML 层次化表达能力强，DevOps 团队可纳入 GitOps 管理。后续 Change 3 的 REST API 将对应修改运行时配置对象。

### D5: DataListener 回调模式 — 设备级批量回调

数据变更通过事件驱动回调传播，一次回调传递一个设备的完整数据数组：

```java
public interface OpcUaDataListener {
    void onDataReceived(OpcUaDeviceData deviceData);
}
```

`OpcUaDeviceData` 包含 `timestamp`、`source`（设备信息）、`data`（`List<OpcUaDataPoint>`）。

内部订阅线程在回调批次内聚合变更节点后一次性回调，避免下游逐条消费的碎片化。

### D6: 健康检查 — Spring Boot Actuator + 自定义 HealthIndicator

复用 Spring Boot Actuator `/health` 端点，注册 `OpcUaHealthIndicator`，检查各设备连接池状态。断线自动重连由 `MiloClientWrapper` 内部处理，采用指数退避策略（初始 1s，最大 60s）。

## Risks / Trade-offs

- **[R] Eclipse Milo 100+ 设备并发连接稳定性** → 压测阶段验证，必要时分拆为多个 Client 实例或添加连接限流
- **[R] 不同 OPC UA 服务器兼容性差异** → 测试阶段至少覆盖 Prosys Simulation Server 和 Eclipse Milo Example Server 两种实现
- **[R] 订阅模式下数据丢失（断线重连期间）** → 重连后自动重建订阅，数据丢失窗口记录日志；如需保证不丢，未来考虑 OPC UA 的 `AvailableDataRetransfer` 机制
- **[T] 连接池资源占用** → 有界连接池 + 空闲超时，避免长时间占用

## Open Questions

- 时序由谁负责：当前设计中 `timestamp` 由采集层打，但发送到 Kafka/DB 时可能另有延迟，需要在 Change 2 中确定是否保留原始时间戳
- 压测环境：需要搭建至少 1 台 OPC UA 模拟器来验证连接池上限（100+ 设备模拟）
