## Why

`opc-ua-core` 完成后，仍有以下事项未在该 change 范围内交付，分两类：

1. **集成测试与压测**（OpenSpec 10.2-10.4）：当时未引入 `org.eclipse.milo:sdk-server` 依赖，无法基于 Eclipse Milo Example Server 验证全链路、断线重连、100+ 设备压测。这些场景能够发现单元测试覆盖不到的真实运行问题（订阅 listener 生命周期、批次聚合时序、连接池在高并发下的行为）。

2. **代码评审延期项**（来自 `opc-ua-core` round-1 code review）：
   - **I3 完整化**：`SubscriptionManager.removeSubscriptions` 已能在 `recreateAll` 路径调用 `deleteSubscription`，但仍需验证 client 已断开场景下不出错的边界。
   - **I4**：`DataDispatchEngine.dispatch` 队列满时 WARN 日志按每次 `poll` 触发，多生产者高并发下日志可能爆炸；`droppedCount` 自增语义不严格 = "实际丢弃数据条数"。需改为单次 dispatch 累积 + 单次 WARN。
   - **I5**：`SubscriptionManager` 使用 `IdentityHashMap<UaMonitoredItem, NodeConfig>` 假设了 Milo 不复用 MonitoredItem 实例。若 Milo 在重连后重建实例，回调会静默丢数据。改为以 `ClientHandle (UInteger)` 为 key 更稳健。
   - **I6**：`ReadWriteHandler.scheduler` 线程池硬编码为 size=2。`OpcUaProperties.DispatchConfig.threadPoolSize` 字段已存在但未被读取。100 设备 × 5 节点 = 500 ScheduledFuture 在 2 线程上轮转会严重退化轮询频率。
   - **9.2**：`/health` 端点 spec 要求暴露"重连次数、最后连接时间"等连接指标。当前 `ConnectionManager.aggregateState` 给 `DeviceState` 的 `connectedSince` / `lastDataReceived` / `message` 字段全部传 null，需要在 ConnectionManager + MiloClientWrapper 中增加状态变更追踪。

## What Changes

- **NEW** 引入 `org.eclipse.milo:sdk-server` 测试依赖与 `MiloServerRunner`，以 Eclipse Milo Example Server 为模拟设备
- **NEW** 集成测试 `OpcUaIntegrationTest`：连接 → 订阅 → 数据接收 → 写入 → 状态查询全链路
- **NEW** 压力测试：100+ 设备配置下连接池行为
- **NEW** 断线重连测试：模拟网络中断 → 重连 → 订阅自动重建
- **MODIFY** `DataDispatchEngine.dispatch`：将 drop-oldest WARN 与 droppedCount 改为单次累积，避免日志爆炸
- **MODIFY** `SubscriptionManager`：批次回调用 `ClientHandle` 索引 `NodeConfig`，避免 IdentityHashMap 实例引用假设
- **MODIFY** `ReadWriteHandler` + `OpcUaCoreAutoConfiguration`：从 `OpcUaProperties.DispatchConfig.threadPoolSize` 读取调度器大小
- **MODIFY** `ConnectionManager` + `MiloClientWrapper`：追踪 `connectedSince`、`reconnectCount`、`lastDataReceived`，填充 `DeviceState` 字段
- **MODIFY** `OpcUaHealthIndicator`：在 `/health` 详情中暴露连接指标（reconnectCount、lastDataReceived 等）

## Capabilities

### Modified Capabilities

- `data-collection`：批次聚合的 NodeConfig 索引方式与轮询线程池规模发生变化（行为不变，鲁棒性增强）
- `health-check`：`/health` 端点新增连接指标详情（OpenSpec 9.2 兑现）

### No New Capabilities

本 change 不引入新能力，仅强化既有能力的可观测性与生产稳健性。

## Impact

- **依赖**：新增测试依赖 `org.eclipse.milo:sdk-server`（仅 test scope）
- **配置**：`opcua.dispatch.threadPoolSize` 默认值开始被实际使用（之前是 dead config）
- **Health 端点**：详情字段扩展（兼容性保留：UP/DOWN 判定不变，新增字段不影响现有消费者）
- **关联**：完成本 change 后，`opc-ua-core` 的所有 OpenSpec 任务（含 10.2-10.4、9.2）才算完整闭环
