## 1. 项目骨架搭建

- [x] 1.1 创建 Spring Boot 3 + Java 17 + Maven 项目结构，添加 Eclipse Milo 依赖
- [x] 1.2 配置 YAML 属性绑定类（`OpcUaProperties`），支持 `opcua.devices` 配置结构解析
- [x] 1.3 创建核心数据模型类（`OpcUaDeviceData`、`OpcUaDataPoint`、`DeviceConfig`、`NodeConfig`）

## 2. MiloClientWrapper — Eclipse Milo 封装层

- [x] 2.1 实现 `MiloClientWrapper`：封装 Milo `OpcUaClient` 的创建、连接、断开
- [x] 2.2 实现安全认证：证书加载、用户名/密码认证、加密模式配置
- [x] 2.3 实现断线重连逻辑：指数退避策略（1s → 60s），重连成功后重建会话
- [x] 2.4 实现连接状态回调接口，状态变更时通知上层

## 3. 连接池与会话管理

- [x] 3.1 实现 `ConnectionManager`：有界连接池（`max-connections` 可配置），连接复用与空闲回收
- [x] 3.2 实现设备发现与批量连接：启动时遍历配置设备列表，依次建立连接
- [x] 3.3 实现会话生命周期管理：创建、keep-alive、超时处理、销毁

## 4. 数据采集 — 订阅

- [x] 4.1 实现 `SubscriptionManager`：根据配置创建 OPC UA Subscription，管理采样间隔
- [x] 4.2 实现订阅数据回调聚合：将 Milo 数据变更批次内的所有节点聚合为 `OpcUaDeviceData`（含 `source` + `data[]`）
- [x] 4.3 实现多订阅组管理：同一设备支持多个订阅组独立运行
- [x] 4.4 实现重连后订阅自动重建

<!-- Task 4 spec review (round 1) accepted minor deviations:
     - MINOR #5: commit message of d774090 differs from plan text ("DataDispatchEngine and SubscriptionManager" vs "SubscriptionManager and bucket-based async dispatch engine"). Cosmetic; semantically equivalent. Accepted; will be resolved at squash time if applicable.
     - MINOR #6: OpcUaDataListener interface created in Task 4 commit d774090 belongs to Task 6 / OpenSpec 8.1 by plan boundary. Signature matches Task 6 spec exactly (@FunctionalInterface void onDataReceived(OpcUaDeviceData)). Accepted; will be acknowledged at Task 6 review without re-creation. -->

## 5. 数据采集 — 轮询与写入

- [x] 5.1 实现 `ReadWriteHandler`：按配置间隔定期轮询读取节点值，同设备轮询结果聚合为 `OpcUaDeviceData`
- [x] 5.2 实现写入控制：接收写入请求，校验数据类型，执行写入操作
- [x] 5.3 实现轮询异常容错：单节点失败不中断其他节点轮询

## 6. 数据质量标记

- [x] 6.1 实现 `QualityEvaluator`：解析 OPC UA StatusCode，判断 Good / Bad / Uncertain
- [x] 6.2 实现 `qualityCheck` 开关：开启时 Bad/Uncertain 触发 WARN 日志，关闭时仅标记

## 7. 数据映射与 JSON 输出

- [x] 7.1 实现 `DataMapper`：NodeId → displayName 映射，未配置时自动生成 displayName
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
