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
