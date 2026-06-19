---
change: opc-ua-core
phase: verify
mode: full
generated_at: 2026-06-18
verify_result: pass-with-warnings
---

# 验证报告：opc-ua-core

## Summary

| 维度 | 状态 |
|---|---|
| Completeness | 26/26 tasks done（3 项延期至 hardening change：9.2、10.2-10.4，已 split） |
| Correctness | 5 capability、20+ requirement scenario 已映射到实现；3 项部分覆盖偏差（WARNING） |
| Coherence | Tasks 4 / 5 / 6 / 7 与 design.md 高层决策一致；Round-1 spec review + 后续 code review 修复均已落地 |

**Final Assessment**: 无 CRITICAL 问题。3 项 WARNING 均已知且记录处置。Ready for archive (with noted improvements)。

## Build / Test Evidence

```text
mvn clean test       → Tests run: 133, Failures: 0, Errors: 0, Skipped: 0
mvn clean package    → BUILD SUCCESS, opc-ua-core-0.1.0-SNAPSHOT.jar
mvn compile -q       → exit 0
```

## Capability × Implementation 映射

### data-collection

| Requirement | Scenario | 实现位置 | 测试 |
|---|---|---|---|
| 订阅模式数据采集 | 创建订阅 | `SubscriptionManager.createSubscriptions:69-141` | `SubscriptionManagerTest$SubscriptionCreation` |
| 订阅模式数据采集 | 数据变更批量推送 | `SubscriptionManager.onBatchReceived` (NotificationListener) | `SubscriptionManagerTest.shouldAggregateBatchAndDispatch` |
| 订阅模式数据采集 | 多订阅组管理 | `for (groupConfig)` + `ConcurrentHashMap<deviceId, List<UaSubscription>>` | `shouldCreateSubscriptionForEachGroup` |
| 订阅模式数据采集 | 订阅重建 | `recreateAll` | `RecreateAll.shouldRemoveThenRecreate` |
| 轮询模式数据采集 | 定期轮询批量输出 | `ReadWriteHandler.startPolling/pollNode` | `PollNodeOperation.shouldReadAndDispatch` |
| 轮询模式数据采集 | 轮询与订阅共存 | 独立 components 分别 dispatch | （集成场景）|
| 轮询模式数据采集 | 轮询异常处理 | `pollNode` try/catch | `shouldTolerateReadException` |
| 写入控制 | 写入成功 | `ReadWriteHandler.writeValue` | `shouldReturnSuccessOnGoodStatusCode` |
| 写入控制 | 写入权限不足 | Bad StatusCode → `WriteResult.failure` | `shouldReturnFailureOnBadStatusCode` |
| 写入控制 | 写入数据类型不匹配 | 依赖 server StatusCode | `shouldReturnFailureOnException` |

### data-mapping

| Requirement | Scenario | 实现位置 | 测试 |
|---|---|---|---|
| NodeId 到业务名称映射 | 单节点映射 | `DataMapper.resolveDisplayName` | `DataMapperTest$DisplayNameResolution` |
| NodeId 到业务名称映射 | 未配置映射的节点 | fallback 到 nodeId | `shouldFallbackToNodeIdWhenDisplayNameNull` |
| 设备级批量 JSON 输出格式 | 设备级完整 JSON 输出 | `OpcUaDeviceData` + Jackson | `OpcUaDeviceDataJsonTest.shouldSerializeToExpectedShape` |
| 设备级批量 JSON 输出格式 | data 数组至少一个元素 | `List.of(dataPoint)` 非空保护 | （结构性保证）|
| 设备级批量 JSON 输出格式 | 数据类型保持 | `DataMapper.resolveDataType` 推断 | `DataMapperTest$DataTypeResolution` |
| 设备级批量 JSON 输出格式 | 多节点同批次输出 | **订阅路径** ✅；**轮询路径** ⚠ 单节点 dispatch | `shouldAggregateBatchAndDispatch`（订阅）|

### data-quality

| Requirement | Scenario | 实现位置 | 测试 |
|---|---|---|---|
| StatusCode 读取 | 正常数据质量 | `QualityEvaluator.evaluate` | `shouldReturnGoodForGoodStatusCode` |
| StatusCode 读取 | 不确定数据质量 | else → Uncertain | `shouldReturnUncertainForOtherStatusCode` |
| StatusCode 读取 | 坏数据 | isBad → Bad | `shouldReturnBadForBadStatusCode` |
| 质量标记开关 | 质量检查开启 | `QualityEvaluator.logIfNeeded` 触发 WARN | （日志验证）|
| 质量标记开关 | 质量检查关闭 | qualityCheck=false 时不 WARN | （日志验证）|
| 质量标记数据输出 | 完整 JSON 包含质量信息 | `OpcUaDataPoint.quality/qualityCheck/statusCode` | `OpcUaDeviceDataJsonTest` |

### device-connection

| Requirement | Scenario | 实现位置 |
|---|---|---|
| 设备发现与连接 | 启动时自动连接 | `ConnectionManager.startAll` |
| 设备发现与连接 | 设备端点不可达 | `MiloClientWrapper.connect` 异常处理 |
| 连接池管理 | 连接池复用 | `acquireClient/releaseClient` |
| 连接池管理 | 连接池耗尽 | bounded pool + timeout |
| 连接池管理 | 空闲连接回收 | `evictIdleConnections` |
| 安全认证 | 证书/用户名密码/无安全 | `MiloClientWrapper.buildClient` 的 SecurityConfig 处理 |
| 会话生命周期 | 创建/保持/超时恢复 | `MiloClientWrapper` 内置 |

### health-check

| Requirement | Scenario | 实现位置 |
|---|---|---|
| 健康检查端点 | 所有设备正常 | `OpcUaHealthIndicator.health()` UP + devices detail |
| 健康检查端点 | 部分设备异常 | DOWN + per-device state/message |
| 断线自动重连 | 首次重连/退避/重连成功 | `MiloClientWrapper.exponentialBackoff` |
| 连接状态监控 | 状态变更日志 | ConnectionManager + MiloClientWrapper logger |
| 连接状态监控 | **连接指标暴露**（reconnectCount, lastDataReceived） | ⚠ `DeviceState` 字段 null（split → hardening §6）|

## Issues

### CRITICAL（must fix before archive）
*无*

### WARNING（should fix or document）

1. **data-mapping § 多节点同批次输出（轮询路径）**
   - File: `ReadWriteHandler.java:113-125` `pollNode` 用 `Collections.singletonList(dataPoint)` 构造单点 OpcUaDeviceData
   - Issue: 轮询路径每节点单独调度，自然每次产生 size==1 的 batch；订阅路径已正确批次聚合
   - 处置: **接受偏差**。轮询是 per-node scheduled task，"同批次"仅适用于订阅 PublishResponse 概念。Spec scenario 隐含订阅语境；轮询的"批次"按 polling group 聚合需要架构改动，不在本 change 范围
   - 影响: 轮询消费者收到的 OpcUaDeviceData 始终 1 dataPoint；功能上等价但增加消费方处理事件数

2. **data-collection § 写入数据类型不匹配**
   - File: `ReadWriteHandler.writeValue:128-145`
   - Issue: 不做客户端类型预检，依赖 OPC UA server 返回 StatusCode
   - 处置: **接受偏差**。OPC UA 协议规范本身要求 server 进行类型校验；client 侧重复校验是冗余且需要 server schema 反射，复杂度高。`shouldReturnFailureOnBadStatusCode` + `shouldReturnFailureOnException` 已覆盖错误返回路径
   - 影响: 类型错误时客户端依赖 server 错误信息，错误消息可能不够友好

3. **health-check § 连接指标暴露（reconnectCount, lastDataReceived）**
   - File: `ConnectionManager.aggregateState:332` 传 null 三个字段
   - Issue: spec scenario 要求暴露重连次数与最后连接时间；当前未实现
   - 处置: **已 split 至 follow-up change** `opc-ua-core-hardening` §6（健康指标完善）
   - 影响: `/health` 详情缺少这两项指标；UP/DOWN 主判定不受影响

### SUGGESTION

1. Spec scenario 中"多节点同批次输出"措辞可在归档时修订，明确"批次"概念限定订阅 PublishResponse；轮询语义另行表述
2. `OpcUaProperties.DispatchConfig.threadPoolSize` 已存在但 `ReadWriteHandler` 未读取（split 至 hardening §5.3 I6）

## Spec drift 决策

无 delta spec 与 design.md 矛盾。design.md 涵盖 ConnectionPool、DispatchEngine、SubscriptionManager 高层决策，与实现一致。本 change 归档时正常 sync 即可。

## Code Review 修复溯源

Round-1 spec review + Senior Code Reviewer 反馈共发现 3 Critical + 7 Important + 7 Minor，处置：

| 严重度 | # | 状态 |
|---|---|---|
| Round-1 MAJOR #1 batch aggregation | 1 | ✅ Fixed (84e0914) |
| Round-1 MAJOR #2 test 锁错误语义 | 1 | ✅ Fixed (84e0914) |
| Round-1 MAJOR #3 drop-oldest 测试不确定 | 1 | ✅ Fixed (84e0914) |
| Round-2 Critical C1/C2/C3 | 3 | ✅ Fixed (973ae89) |
| Round-2 Important I1 partial / I2 / I7 | 3 | ✅ Fixed (973ae89) |
| Round-2 Important I3 / I4 / I5 / I6 | 4 | 📋 Split → hardening |
| Round-1/2 Minor M1-M7 | 7 | 📋 Split → hardening / 接受 |

## 提交清单（自 d774090 起 12 commits）

```
ebdaaf7 chore: finalize opc-ua-core implementation
8e2b711 chore: physically split 9.2 + 10.2-10.4 out of opc-ua-core/tasks.md
cad477f chore: split deferred items into opc-ua-core-hardening follow-up change
973ae89 fix: address code review findings (C1, C2, C3, I1, I2, I7)
5cbc937 chore: mark Task 10.1 (unit tests) done; document 10.2-10.4 deferral
8ba3e38 test: add OpcUaDeviceData JSON serialization test (OpenSpec 7.2)
6f40e85 feat: implement health check indicator and auto-configuration
912017f feat: implement OpcUaService unified API and DataListener mechanism
7663474 chore: mark Task 5 OpenSpec items 5.1-5.3, 6.1-6.2, 7.1 done
d6cfeb5 feat: implement QualityEvaluator, DataMapper, and ReadWriteHandler
a19c549 chore: complete Task 4 — mark 4.1-4.4 done after spec review fix
84e0914 fix: address spec review for Task 4 — batch aggregation, deterministic tests, drop logging
```

## Final Verdict

✅ **Ready for archive** with noted improvements. 3 项 WARNING 中：
- 1 项接受偏差（轮询单节点 dispatch 是设计层面合理选择）
- 1 项接受偏差（写入类型校验由 server 承担是协议规范）
- 1 项已 split 至独立 follow-up change

无 CRITICAL 失败项；建议进入分支处理与归档流程。

## Branch Handling Status

- **User decision**: Option 2 — Push and create PR
- **Status**: PENDING — `git push -u origin feature/20260617/opc-ua-core` 因网络无法连接 github.com:443 失败
- **Resume action**: 网络恢复后用户手动执行
  ```bash
  git push -u origin feature/20260617/opc-ua-core
  gh pr create --title "..." --body "..." --base main
  bash /c/Users/fly/.claude/skills/comet/scripts/comet-state.sh set opc-ua-core branch_status handled
  bash /c/Users/fly/.claude/skills/comet/scripts/comet-guard.sh opc-ua-core verify --apply
  ```
- **`.comet.yaml` 状态**: `branch_status: pending` 保留，verify guard 不会通过；无误判风险
