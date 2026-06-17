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
