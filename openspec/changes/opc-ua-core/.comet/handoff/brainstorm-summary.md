# Brainstorm Summary

- Change: opc-ua-core
- Date: 2026-06-17

## 确认的技术方案

| # | 决策点 | 选择 | 说明 |
|---|--------|------|------|
| 回调线程模型 | 异步分发（方案 B） | OpcUaService 内部独立线程池分发 DataListener 回调，保护 OPC UA 采集线程不受下游阻塞影响 |
| 线程池组织 | 共享线程池 + 按设备分桶（方案 C） | 固定大小线程池 + deviceId hash 到独立队列，实现同设备数据有序送达 + 设备间故障隔离 |
| 轮询调度 | ScheduledExecutorService 集中调度（方案 A） | 全局 ScheduledThreadPoolExecutor 管理所有设备轮询任务，按配置间隔触发 |
| 故障隔离 | 静默隔离（方案 A） | 设备故障 → DISCONNECTED → 指数退避重连（1s→60s），不影响其他设备 |
| 连接池 | 有界连接池 | 每设备 max-connections 可配置，默认 3，空闲超时回收；订阅复用同一会话 |
| 数据模型 | 设备级批量数组 | OpcUaDeviceData（timestamp + source{productId,deviceId,endpointUrl} + data[]） |
| 架构 | 三层模块 | API Layer（OpcUaService/DataListener）→ Core Layer（ConnectionManager/SubscriptionManager/ReadWriteHandler/DataMapper/QualityEvaluator）→ MiloClientWrapper |
| 健康检查 | Spring Boot Actuator + 自定义 | OpcUaHealthIndicator 聚合所有设备连接状态 |

## 关键取舍与风险

- **取舍**：异步分发增加内存队列开销，但保护采集链路稳定性；队列 backpressure 策略为 drop-oldest
- **取舍**：静默隔离简单可靠，运维可观测性依赖日志，后续 Change 3 可追加监控指标
- **风险**：100+ 设备下共享线程池 + 按设备分桶的队列积压需压测验证
- **风险**：不同 OPC UA 服务器兼容性差异，需覆盖至少 Prosys 和 Milo Example Server

## 测试策略

- **单元测试**：QualityEvaluator、DataMapper、连接池逻辑（Mock Milo Client）
- **集成测试**：Eclipse Milo Example Server 作为模拟设备，验证端到端（连接→订阅→JSON 输出）
- **压力测试**：100+ 设备配置下验证连接池和线程池行为
- **故障恢复测试**：模拟网络中断→重连→订阅恢复

## Spec Patch

无。现有 delta spec 已完整覆盖验收场景（device-connection、data-collection、data-quality、data-mapping、health-check 共 5 个 capability）。
