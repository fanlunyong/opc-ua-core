## ADDED Requirements

### Requirement: MQTT 数据转发
系统 SHALL 将 `OpcUaDeviceData` JSON 转发到配置的 MQTT broker 指定 topic。

#### Scenario: MQTT 推送
- **WHEN** 收到设备数据且转发规则匹配到 MQTT target
- **THEN** 系统将完整的 `OpcUaDeviceData` JSON 发布到配置的 MQTT topic

#### Scenario: MQTT 连接配置
- **WHEN** YAML 中配置了 MQTT broker URL、topic、QoS
- **THEN** 系统使用这些参数连接 MQTT broker 并发布消息

#### Scenario: MQTT 断线重连
- **WHEN** MQTT broker 连接断开
- **THEN** 系统使用 MQTT 客户端自带重连机制恢复连接，断开期间消息丢弃并记录日志
