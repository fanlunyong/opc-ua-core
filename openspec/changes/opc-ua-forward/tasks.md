## 1. 转发模块骨架

- [x] 1.1 创建 `forward` 模块结构，添加依赖（Spring Kafka、InfluxDB Client、Eclipse Paho MQTT、Spring Web）
- [x] 1.2 实现 YAML 转发配置绑定类（`ForwardProperties`），解析 `forward.rules` 配置；支持 `forward.shutdownTimeout`（默认 PT5S）
- [x] 1.3 创建转发数据模型（`ForwardRule`、`ForwardTarget`、`MatchCondition`、`AlertConfig`）
- [x] 1.4 实现 Change 1 `OpcUaService.unregisterListener` API（cross-change patch；与本 change 同分支提交）
- [x] 1.5 在 `SubscriptionManager.onBatchReceived` 中调用 `QualityEvaluator.logIfNeeded(quality, qualityCheck, nodeId)` 接入 quality observability（对接 design.md「P0 数据质量过滤」决策；调用已存在的 `QualityEvaluator` 工具，不引入新过滤逻辑）

## 2. 转发引擎核心

- [x] 2.1 实现 `ForwardingEngine`：`@PostConstruct` 注册为 Change 1 的 `OpcUaDataListener`，接收 `OpcUaDeviceData`；`@PreDestroy` 触发优雅关停；空 rules 跳过注册
- [x] 2.2 实现规则匹配器：根据 `MatchCondition`（productId/deviceId/nodeId）筛选匹配的规则；纯 CPU，禁 I/O
- [x] 2.3 实现 Sender 异步调度：独立线程池，队列解耦，drop-oldest 背压策略
- [x] 2.4 实现 Drop-oldest 计数聚合：`ConcurrentHashMap<deviceId, AtomicLong>` + 1s/100 条双触发 flush，按 (deviceId, senderId) 维度 WARN
- [x] 2.5 实现优雅关停流程：`unregisterListener` → `stopAccepting` → `awaitDrain(shutdownTimeout)` → 超时 WARN（含未发数）→ 强制 `close`
- [x] 2.6 实现 `SenderRegistry`：按连接指纹聚合共享 Producer/Client（Kafka by bootstrap+security、MQTT by brokerUrl+clientId、InfluxDB by url+org+token、HTTP type 单例）+ 引用计数

## 3. Kafka Sender

- [x] 3.1 实现 `KafkaSender`：通过 `SenderRegistry` 获取共享 KafkaProducer
- [x] 3.2 实现 topic 模板解析：`opcua-data-{productId}` → 运行时替换为实际值
- [x] 3.3 实现 `OpcUaDeviceData` JSON 序列化（复用 Change 1 的 ObjectMapper Bean）发送到 Kafka

## 4. InfluxDB Sender

- [x] 4.1 实现 `InfluxDBSender`：通过 `SenderRegistry` 获取共享 InfluxDB Client
- [x] 4.2 实现 `OpcUaDataPoint` → InfluxDB Point 转换：measurement=displayName，tags={productId, deviceId, nodeId, quality}，field=value，timestamp=sourceTimestamp
- [x] 4.3 实现 data[] 批量写入：一条 `WriteApi.writePoints()` 写入整个 data 数组

## 5. MQTT Sender

- [x] 5.1 实现 `MqttSender`：通过 `SenderRegistry` 获取共享 Eclipse Paho MQTT Client
- [x] 5.2 实现 JSON 发布到配置的 MQTT topic（支持 QoS 配置）

## 6. HTTP Sender

- [x] 6.1 实现 `HttpSender`：Spring RestTemplate HTTP POST 发送（type 单例）
- [x] 6.2 实现超时控制（默认 5s）与错误日志记录

## 7. 告警路由

- [x] 7.1 实现告警检测：遍历 data[] 中 quality 字段，识别 Bad/Uncertain
- [x] 7.2 实现告警推送：Bad/Uncertain → 复用 sender 抽象路由到 `alerts.targets` 的所有 sender 类型
- [x] 7.3 实现 `alerts.enabled` 开关控制

## 8. 配置管理

- [x] 8.1 实现 `${ENV_VAR}` 环境变量占位符替换（Kafka token、InfluxDB token 等）
- [x] 8.2 实现 Target `enabled` 开关：disabled 的 target 跳过初始化与发送
- [x] 8.3 实现 `@ConditionalOnProperty("opcua.forward.enabled", matchIfMissing=true)` 总开关与 `OpcUaForwardAutoConfiguration`

## 9. 测试

- [x] 9.1 编写 Kafka Sender 单元测试：Mock `KafkaProducer.send`，验证 ProducerRecord 的 topic + key + JSON value
- [x] 9.2 编写 InfluxDB Sender 单元测试：Mock `WriteApi.writePoints`，验证 Point 的 measurement + tags + field + timestamp
- [x] 9.3 编写告警路由逻辑测试：Good/Bad/Uncertain 三种 case 分别验证主 + alerts.targets 路由
- [x] 9.4 编写端到端测试：构造 `OpcUaDeviceData` → `ForwardingEngine` → 验证 mock senders 收到正确 JSON 与路由
- [x] 9.5 编写 Drop-oldest 聚合测试：模拟队列溢出 → 验证 (deviceId, senderId) WARN 聚合 + 1s/100 条双触发
- [x] 9.6 编写优雅关停测试：mock SlowSender → 验证 5s 超时 + WARN 含未发数
- [x] 9.7 编写 `SenderRegistry` 共享测试：相同连接指纹 → 单实例；不同指纹 → 多实例；引用计数正确
