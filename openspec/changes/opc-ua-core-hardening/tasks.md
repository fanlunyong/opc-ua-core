# 任务清单：opc-ua-core-hardening

> 后续 change，承接 `opc-ua-core` 中延期的集成测试与代码评审建议。

## 1. 依赖与测试基础设施

- [ ] 1.1 在 `pom.xml` 添加 `org.eclipse.milo:sdk-server` 测试 scope 依赖
- [ ] 1.2 实现 `MiloServerRunner`：以 Eclipse Milo Example Server 启动可控的本地 OPC UA 服务端，提供启停 API 与节点准备
- [ ] 1.3 在 surefire 配置中区分单元测试与集成测试（按命名约定 `*IT.java` 或独立 profile）

## 2. 集成测试（承接 OpenSpec 10.2）

- [ ] 2.1 编写 `OpcUaIntegrationTest`：连接 Milo Example Server，订阅一组节点，断言通过 `OpcUaService.registerListener` 收到 `OpcUaDeviceData` 内含批次内多节点
- [ ] 2.2 集成测试覆盖写入路径：`OpcUaService.writeValue` 写入后再读取，断言值变化
- [ ] 2.3 集成测试覆盖 health 路径：`/actuator/health` 返回 UP，`devices.{deviceId}.state == CONNECTED`

## 3. 压力测试（承接 OpenSpec 10.3）

- [ ] 3.1 编写参数化测试：100 设备配置 × 5 节点/设备启动，校验连接池建立成功率 ≥ 95%、内存占用稳定
- [ ] 3.2 校验 `DataDispatchEngine.dropAt`/`droppedCount` 在持续高吞吐下的语义与日志频率（关联 I4）

## 4. 断线重连测试（承接 OpenSpec 10.4）

- [ ] 4.1 启动 Milo Server → 客户端连接 → kill server → 等待重连退避后重启 server → 断言客户端在 60s 内恢复 CONNECTED
- [ ] 4.2 断言重连后订阅自动重建：重连后再触发节点变更，listener 仍能收到数据
- [ ] 4.3 断言 `recreateAll` 路径下旧 `UaSubscription` 通过 `deleteSubscription` 释放（无 listener 泄漏）

## 5. 代码评审延期项 — 鲁棒性改进

- [ ] 5.1 **I4** `DataDispatchEngine.dispatch`：将 drop-oldest WARN/计数改为单次 dispatch 调用累积 + 单次 WARN（避免日志爆炸；明确 `droppedCount = 实际丢弃数据条数`）
- [ ] 5.2 **I5** `SubscriptionManager.onBatchReceived`：改为以 `MonitoredItem.getClientHandle()` 索引 `NodeConfig`，移除对实例引用复用的假设
- [ ] 5.3 **I6** `ReadWriteHandler` + `OpcUaCoreAutoConfiguration`：让 scheduler thread pool size 从 `OpcUaProperties.DispatchConfig.threadPoolSize` 配置读取
- [ ] 5.4 验证 5.1-5.3 不破坏现有 132+ 单元测试

## 6. 健康指标完善（承接 OpenSpec 9.2）

- [ ] 6.1 在 `MiloClientWrapper` 中追踪：`connectedSince`（最后一次成功连接时间）、`reconnectCount`（累计重连尝试次数）、`lastDataReceived`（最后一次接收数据的时间，由 SubscriptionManager 在 `onBatchReceived` 中回调更新）
- [ ] 6.2 在 `ConnectionManager.aggregateState` 中填充 `DeviceState.connectedSince` / `lastDataReceived` / `message`
- [ ] 6.3 `OpcUaHealthIndicator` 在 per-device 详情中暴露 `reconnectCount`，按 spec health-check 9.2 完整呈现

## 7. 最终验证

- [ ] 7.1 `mvn clean verify` 含集成测试全部通过
- [ ] 7.2 在 `opc-ua-core` 的 tasks.md 上勾选 9.2、10.2、10.3、10.4，并交叉引用本 change
- [ ] 7.3 归档 `opc-ua-core` change（如尚未归档）
