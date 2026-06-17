# Comet Design Handoff

- Change: opc-ua-core
- Phase: design
- Mode: compact
- Context hash: 282017f365eb31843f48aed512b6f1258e92587dd3989337211c141188b78da9

Generated-by: comet-handoff.sh

OpenSpec remains the canonical capability spec. This handoff is a deterministic, source-traceable context pack, not an agent-authored summary.

## openspec/changes/opc-ua-core/proposal.md

- Source: openspec/changes/opc-ua-core/proposal.md
- Lines: 1-34
- SHA256: 5c47b58dd20d0e3a63e5003d76579bba94500f48eae1500f1a704212cdc09577

```md
## Why

工业物联网场景中，开发者在接入 OPC UA 设备时面临高学习成本和重复性工作：需要理解 OPC UA 协议细节、管理连接生命周期、处理复杂的数据交互模式。本项目构建一个开箱即用的 OPC UA 核心采集层，将协议复杂性封装为简洁的配置驱动抽象，让开发者聚焦在数据消费而非协议集成上。

## What Changes

- **NEW** OPC UA 设备连接管理：基于 YAML 配置驱动的设备发现、会话管理、连接池，支持 100+ 设备规模
- **NEW** 多模式数据采集：支持订阅（Subscription）、轮询读取、写入控制三种数据交互模式
- **NEW** 数据质量标记：自动读取 OPC UA StatusCode，标记每条数据质量（Good/Bad/Uncertain）到统一 JSON
- **NEW** 数据映射引擎：NodeId → 业务友好名称 → 统一 JSON 结构的可配置映射
- **NEW** 安全认证：支持证书、用户名/密码、加密模式等 OPC UA 标准安全机制
- **NEW** 健康检查与自动恢复：提供 `/health` 端点，连接断开自动重连，支持连接池监控

## Capabilities

### New Capabilities

- `device-connection`: OPC UA 设备连接管理，包括设备发现、会话管理、连接池、安全认证
- `data-collection`: 多模式数据采集，包括订阅推送、轮询读取、写入控制
- `data-quality`: 数据质量标记，基于 OPC UA StatusCode 评估每条数据质量
- `data-mapping`: NodeId 到业务友好名称的映射及统一 JSON 格式化输出
- `health-check`: 健康检查与自动恢复机制

### Modified Capabilities

<!-- 无现有 capability 需要修改 -->

## Impact

- **依赖**：Eclipse Milo（OPC UA 客户端库）
- **框架**：Spring Boot 3.x, Java 17
- **配置系统**：新增 YAML 配置结构定义 OPC UA 端点、节点、采集策略
- **API 接口**：提供内部 Java API 供下游模块消费统一 JSON 数据
- **部署**：作为 Spring Boot 服务模块运行，后续由 Change 3 包装为 Docker 容器
```

## openspec/changes/opc-ua-core/design.md

- Source: openspec/changes/opc-ua-core/design.md
- Lines: 1-179
- SHA256: 8aca82e5ff86555eb4c45c94a14abdfd5a663fe36c42452ffd3840a5be9ed3bb

[TRUNCATED]

```md
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
```

Full source: openspec/changes/opc-ua-core/design.md

## openspec/changes/opc-ua-core/tasks.md

- Source: openspec/changes/opc-ua-core/tasks.md
- Lines: 1-59
- SHA256: 3e9c5390b08c3ecdb40fdc0901372303d734e569a709f550359c2e1985d67c05

```md
## 1. 项目骨架搭建

- [ ] 1.1 创建 Spring Boot 3 + Java 17 + Maven 项目结构，添加 Eclipse Milo 依赖
- [ ] 1.2 配置 YAML 属性绑定类（`OpcUaProperties`），支持 `opcua.devices` 配置结构解析
- [ ] 1.3 创建核心数据模型类（`OpcUaDeviceData`、`OpcUaDataPoint`、`DeviceConfig`、`NodeConfig`）

## 2. MiloClientWrapper — Eclipse Milo 封装层

- [ ] 2.1 实现 `MiloClientWrapper`：封装 Milo `OpcUaClient` 的创建、连接、断开
- [ ] 2.2 实现安全认证：证书加载、用户名/密码认证、加密模式配置
- [ ] 2.3 实现断线重连逻辑：指数退避策略（1s → 60s），重连成功后重建会话
- [ ] 2.4 实现连接状态回调接口，状态变更时通知上层

## 3. 连接池与会话管理

- [ ] 3.1 实现 `ConnectionManager`：有界连接池（`max-connections` 可配置），连接复用与空闲回收
- [ ] 3.2 实现设备发现与批量连接：启动时遍历配置设备列表，依次建立连接
- [ ] 3.3 实现会话生命周期管理：创建、keep-alive、超时处理、销毁

## 4. 数据采集 — 订阅

- [ ] 4.1 实现 `SubscriptionManager`：根据配置创建 OPC UA Subscription，管理采样间隔
- [ ] 4.2 实现订阅数据回调聚合：将 Milo 数据变更批次内的所有节点聚合为 `OpcUaDeviceData`（含 `source` + `data[]`）
- [ ] 4.3 实现多订阅组管理：同一设备支持多个订阅组独立运行
- [ ] 4.4 实现重连后订阅自动重建

## 5. 数据采集 — 轮询与写入

- [ ] 5.1 实现 `ReadWriteHandler`：按配置间隔定期轮询读取节点值，同设备轮询结果聚合为 `OpcUaDeviceData`
- [ ] 5.2 实现写入控制：接收写入请求，校验数据类型，执行写入操作
- [ ] 5.3 实现轮询异常容错：单节点失败不中断其他节点轮询

## 6. 数据质量标记

- [ ] 6.1 实现 `QualityEvaluator`：解析 OPC UA StatusCode，判断 Good / Bad / Uncertain
- [ ] 6.2 实现 `qualityCheck` 开关：开启时 Bad/Uncertain 触发 WARN 日志，关闭时仅标记

## 7. 数据映射与 JSON 输出

- [ ] 7.1 实现 `DataMapper`：NodeId → displayName 映射，未配置时自动生成 displayName
- [ ] 7.2 实现设备级 JSON 构建器：将 `OpcUaDeviceData` 序列化为包含 `timestamp`、`source`、`data[]` 的完整 JSON

## 8. DataListener 回调机制

- [ ] 8.1 定义 `OpcUaDataListener` 接口（`onDataReceived(OpcUaDeviceData)` — 设备级批量回调）
- [ ] 8.2 实现 Listener 注册与多播：支持多个 Listener 同时消费同一 `OpcUaDeviceData` 数据流
- [ ] 8.3 实现 `OpcUaService` — 统一对外 API 入口，组合 ConnectionManager + SubscriptionManager + ReadWriteHandler

## 9. 健康检查与监控

- [ ] 9.1 实现 `OpcUaHealthIndicator`：注册到 Spring Boot Actuator，检查所有设备连接状态
- [ ] 9.2 暴露连接指标：当前连接数、重连次数、最后连接时间

## 10. 集成测试与验证

- [ ] 10.1 编写单元测试：QualityEvaluator、DataMapper、连接池逻辑
- [ ] 10.2 编写集成测试：使用 Eclipse Milo Example Server 作为模拟设备，验证全链路（连接→订阅→JSON 输出）
- [ ] 10.3 验证 100+ 设备配置下的连接池行为（压力测试）
- [ ] 10.4 验证断线重连流程：模拟网络中断 → 重连 → 订阅恢复
```

## openspec/changes/opc-ua-core/specs/data-collection/spec.md

- Source: openspec/changes/opc-ua-core/specs/data-collection/spec.md
- Lines: 1-50
- SHA256: 2081a9d6f4fa778f216d16e2c2f1ee9ab183118af2896fb59b77172ced49513f

```md
## ADDED Requirements

### Requirement: 订阅模式数据采集
系统 SHALL 支持基于 OPC UA Subscription 机制的数据采集，将订阅节点的数据变更推送至注册的 DataListener。

#### Scenario: 创建订阅
- **WHEN** 配置中定义了订阅组（含节点列表和采样间隔）
- **THEN** 系统在对应设备上创建 OPC UA Subscription，按指定间隔采集数据

#### Scenario: 数据变更批量推送
- **WHEN** 订阅节点数据发生变更（同一回调批次可能包含多个节点）
- **THEN** 系统将该批次所有变更节点聚合为设备级数据数组，通过 DataListener 一次性推送完整 `OpcUaDeviceData`（含 `source` 和 `data[]`）

#### Scenario: 多订阅组管理
- **WHEN** 同一设备配置了多个订阅组（如温度组、压力组），各有不同采样间隔
- **THEN** 系统独立管理每个订阅组，各自按配置间隔推送该组变更的设备级数组数据

#### Scenario: 订阅重建
- **WHEN** 设备连接断开后重新恢复
- **THEN** 系统自动重建所有订阅，确保数据采集不中断

### Requirement: 轮询模式数据采集
系统 SHALL 支持按配置的固定间隔定期读取指定节点的值。

#### Scenario: 定期轮询批量输出
- **WHEN** 配置中定义了轮询节点及其间隔
- **THEN** 系统按指定间隔发起 Read 请求，将该设备本次读取的所有轮询节点结果聚合为设备级数组，推送至 DataListener

#### Scenario: 轮询与订阅共存
- **WHEN** 同一设备同时配置了订阅组和轮询节点
- **THEN** 订阅组按变更推送，轮询节点按间隔读取，互不干扰

#### Scenario: 轮询异常处理
- **WHEN** 单次轮询读取失败（如节点不存在或权限不足）
- **THEN** 系统记录错误日志，跳过该节点继续下一轮轮询，不中断其他节点的正常轮询

### Requirement: 写入控制
系统 SHALL 支持向指定 OPC UA 节点写入值。

#### Scenario: 写入成功
- **WHEN** 调用写入接口时提供了有效的 nodeId 和新值
- **THEN** 系统向 OPC UA 服务器写入该值并返回写入成功确认

#### Scenario: 写入权限不足
- **WHEN** 写入操作因权限不足被服务器拒绝
- **THEN** 系统返回写入失败错误，包含具体原因（权限不足）

#### Scenario: 写入数据类型不匹配
- **WHEN** 写入的值类型与目标节点数据类型不匹配
- **THEN** 系统返回数据类型错误，不执行写入
```

## openspec/changes/opc-ua-core/specs/data-mapping/spec.md

- Source: openspec/changes/opc-ua-core/specs/data-mapping/spec.md
- Lines: 1-31
- SHA256: 64a9df841082cea7b593d34e6717db84e69241b12251b42a9f949d4b98c0bfff

```md
## ADDED Requirements

### Requirement: NodeId 到业务名称映射
系统 SHALL 支持将 OPC UA NodeId 映射为人类可读的业务友好名称。

#### Scenario: 单节点映射
- **WHEN** 配置 `nodeId: "ns=2;s=Temperature", displayName: "temperature"`
- **THEN** 输出的 JSON 中 `displayName` 字段为 `"temperature"`，同时保留原始 `nodeId` 为 `"ns=2;s=Temperature"`

#### Scenario: 未配置映射的节点
- **WHEN** 节点未配置 displayName
- **THEN** 系统使用 NodeId 的 Identifier 部分自动生成 displayName（如 `"Temperature"`）

### Requirement: 设备级批量 JSON 输出格式
系统 SHALL 将每次数据采集事件以设备为粒度输出，`data` 数组包含该设备本次变更的全部属性值。

#### Scenario: 设备级完整 JSON 输出
- **WHEN** 数据采集事件发生
- **THEN** 输出 JSON 顶层包含 `timestamp` 和 `source`（`productId` + `deviceId` + `endpointUrl`），`data` 为数组，数组中每条记录包含：`nodeId`, `displayName`, `value`, `dataType`, `quality`, `statusCode`, `sourceTimestamp`, `serverTimestamp`

#### Scenario: data 数组至少一个元素
- **WHEN** 采集事件发生（单条或多条变更）
- **THEN** `data` 数组始终至少包含 1 个元素

#### Scenario: 数据类型保持
- **WHEN** 采集到的值为 Double 类型
- **THEN** JSON 中 `value` 字段为数值类型 `25.5`（非字符串），`dataType` 为 `"Double"`

#### Scenario: 多节点同批次输出
- **WHEN** 同一设备同一采集批次内多个节点同时变更
- **THEN** 所有变更节点合并到同一个 `data` 数组中，形成一次设备级批量上报
```

## openspec/changes/opc-ua-core/specs/data-quality/spec.md

- Source: openspec/changes/opc-ua-core/specs/data-quality/spec.md
- Lines: 1-34
- SHA256: af20cca1db132297dd982b0d0f4eff31ec745ad80c5878964c708e6701256ba5

```md
## ADDED Requirements

### Requirement: StatusCode 读取
系统 SHALL 在每次数据采集时读取 OPC UA StatusCode，并将其作为数据质量判断依据。

#### Scenario: 正常数据质量
- **WHEN** 采集到的数据 StatusCode 为 Good（0x00000000）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Good"` 并附带原始 `"statusCode": "0x00000000"`

#### Scenario: 不确定数据质量
- **WHEN** 采集到的数据 StatusCode 为 Uncertain（如传感器未校准）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Uncertain"` 并附带原始 statusCode

#### Scenario: 坏数据
- **WHEN** 采集到的数据 StatusCode 为 Bad（如传感器故障）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Bad"` 并附带原始 statusCode

### Requirement: 质量标记开关
系统 SHALL 支持按节点配置是否进行质量检查（qualityCheck: true/false）。

#### Scenario: 质量检查开启
- **WHEN** 节点配置 `qualityCheck: true`
- **THEN** 系统除标记 quality 字段外，还在 Bad/Uncertain 时记录 WARN 级别日志

#### Scenario: 质量检查关闭
- **WHEN** 节点配置 `qualityCheck: false`
- **THEN** 系统仍标记 quality 字段，但 Bad/Uncertain 时不产生 WARN 日志

### Requirement: 质量标记数据输出
系统 SHALL 在统一 JSON 数据中始终携带 quality 和 statusCode 字段。

#### Scenario: 完整 JSON 包含质量信息
- **WHEN** 任何数据采集事件发生
- **THEN** 输出的 JSON 必须包含 `quality` 和 `statusCode` 两个字段，不可缺失
```

## openspec/changes/opc-ua-core/specs/device-connection/spec.md

- Source: openspec/changes/opc-ua-core/specs/device-connection/spec.md
- Lines: 1-57
- SHA256: 8e9b27e7f8a12ea27d2597aed9036c18712d8dbe9292f7a145897fc187ca5146

```md
## ADDED Requirements

### Requirement: 设备发现与连接
系统 SHALL 通过 YAML 配置文件定义 OPC UA 设备端点，在服务启动时自动建立连接并发现设备。

#### Scenario: 启动时自动连接
- **WHEN** 服务启动且 YAML 配置中存在有效设备端点列表
- **THEN** 系统依次连接每个端点，并记录连接成功/失败状态到日志

#### Scenario: 设备端点不可达
- **WHEN** 服务启动时某设备端点不可达
- **THEN** 系统记录连接失败日志，标记该设备为 DISCONNECTED，并启动自动重连流程

### Requirement: 连接池管理
系统 SHALL 为每个 OPC UA 端点维护一个有界连接池，最大连接数可配置，默认值为 3。

#### Scenario: 连接池复用
- **WHEN** 多个订阅或读写操作请求同时发生
- **THEN** 系统从连接池中分配可用连接，等待超时后连接释放回池复用

#### Scenario: 连接池耗尽
- **WHEN** 所有连接均在占用中且达到最大连接数
- **THEN** 新请求在配置的超时时间内等待，超时后返回连接不可用错误

#### Scenario: 空闲连接回收
- **WHEN** 连接空闲时间超过配置的 idle-timeout
- **THEN** 系统关闭该连接并从连接池中移除

### Requirement: OPC UA 安全认证
系统 SHALL 支持 OPC UA 标准安全机制，包括证书认证和用户名/密码认证。

#### Scenario: 证书认证
- **WHEN** 配置中指定了客户端证书路径和加密策略（如 Basic256Sha256）
- **THEN** 系统使用指定证书与 OPC UA 服务器建立安全通道和会话

#### Scenario: 用户名密码认证
- **WHEN** 配置中指定了 username 和 password
- **THEN** 系统在建立会话后执行用户身份认证

#### Scenario: 无安全模式
- **WHEN** 配置中未指定安全策略（policy: None）
- **THEN** 系统以匿名模式连接 OPC UA 服务器

### Requirement: 会话生命周期管理
系统 SHALL 管理每个 OPC UA 会话的完整生命周期，包括创建、保持活跃、超时处理和销毁。

#### Scenario: 会话创建
- **WHEN** 设备连接建立成功
- **THEN** 系统创建 OPC UA 会话并设置会话超时时间（可配置，默认 600s）

#### Scenario: 会话保持
- **WHEN** 会话处于活跃状态
- **THEN** 系统定期发送 keep-alive 请求，确保会话不过期

#### Scenario: 会话超时恢复
- **WHEN** 会话因网络中断等原因超时失效
- **THEN** 系统销毁旧会话，重新建立连接并创建新会话
```

## openspec/changes/opc-ua-core/specs/health-check/spec.md

- Source: openspec/changes/opc-ua-core/specs/health-check/spec.md
- Lines: 1-38
- SHA256: a71c7fa2c51de2685fc8341a1c180eae1ae8d90e09360102ef94aa34a4f0db31

```md
## ADDED Requirements

### Requirement: 健康检查端点
系统 SHALL 提供 `/health` 端点，通过 Spring Boot Actuator 暴露连接池和 OPC UA 设备的健康状态。

#### Scenario: 所有设备正常
- **WHEN** 所有配置的 OPC UA 设备均处于连接状态
- **THEN** `/health` 端点返回 `{"status": "UP", "components": {"opcua": {"status": "UP", "devices": {...}}}}`

#### Scenario: 部分设备异常
- **WHEN** 至少一台设备断开连接或会话失效
- **THEN** `/health` 端点返回 `{"status": "DOWN"}` 并列出异常设备及其错误原因

### Requirement: 断线自动重连
系统 SHALL 在检测到 OPC UA 连接断开后自动尝试重连，采用指数退避策略。

#### Scenario: 首次重连
- **WHEN** 设备连接意外断开
- **THEN** 系统在 1 秒后发起第一次重连尝试

#### Scenario: 连续重连失败
- **WHEN** 重连连续失败
- **THEN** 系统采用指数退避策略（1s → 2s → 4s → 8s → 16s → 32s → 60s），最大退避时间 60 秒

#### Scenario: 重连成功
- **WHEN** 重连成功建立
- **THEN** 系统重建所有订阅组和会话，重置退避计时器为初始值，更新健康状态为 UP

### Requirement: 连接状态监控
系统 SHALL 实时监控每个设备连接的运行状态并记录关键事件。

#### Scenario: 连接状态变更日志
- **WHEN** 设备连接状态发生变更（连接、断开、重连成功、重连失败）
- **THEN** 系统记录 INFO 或 ERROR 级别日志，包含设备 ID、时间戳和状态变更详情

#### Scenario: 连接指标暴露
- **WHEN** 系统运行中
- **THEN** 系统暴露每个设备的连接指标：当前连接数、重连次数、最后连接时间
```

