## ADDED Requirements

### Requirement: 转发规则配置
系统 SHALL 通过 YAML 配置转发规则，每条规则定义匹配条件和转发目标。

#### Scenario: 按 productId 匹配
- **WHEN** 规则配置 `match.productId: "production-line-A"`
- **THEN** 仅 source.productId 匹配的设备数据通过该规则转发

#### Scenario: 全匹配
- **WHEN** 规则未配置 match 条件
- **THEN** 所有设备数据均匹配该规则

#### Scenario: 多 target 转发
- **WHEN** 一条规则配置了多个 targets（如 Kafka + InfluxDB）
- **THEN** 匹配的数据同时发送到所有 enabled: true 的 targets

#### Scenario: Target 禁用
- **WHEN** target 配置 `enabled: false`
- **THEN** 该转发目标不参与数据发送

### Requirement: 环境变量注入
系统 SHALL 支持在转发配置中使用 `${ENV_VAR}` 占位符引用环境变量（如 Kafka token、InfluxDB token 等敏感信息）。

#### Scenario: 环境变量替换
- **WHEN** 配置中包含 `${INFLUX_TOKEN}`
- **THEN** 系统在加载配置时将其替换为实际环境变量值

### Requirement: 优雅关停超时
系统 SHALL 支持通过 `forward.shutdownTimeout` 配置应用关停时 sender 队列的最大 drain 等待时间。

#### Scenario: 默认超时
- **WHEN** 未配置 `forward.shutdownTimeout`
- **THEN** 系统使用默认值 PT5S（5 秒）

#### Scenario: 关停在超时内 drain 完成
- **WHEN** Spring context 关闭且各 sender 在 shutdownTimeout 内 drain 完队列
- **THEN** 系统正常关闭所有 sender 与共享连接，不丢失数据

#### Scenario: 关停超时
- **WHEN** Spring context 关闭且某 sender 在 shutdownTimeout 内仍未 drain 完
- **THEN** 系统记录 WARN（含未发送消息数）后强制关闭，不阻塞 JVM 退出

### Requirement: Sender 实例共享
系统 SHALL 按连接参数指纹聚合 sender 的底层连接（KafkaProducer / MqttClient / InfluxDB Client），相同连接参数的多个 target 共享同一连接实例。

#### Scenario: 同 broker 共享 KafkaProducer
- **WHEN** 多条规则配置同一 `bootstrap-servers + securityProtocol` 但不同 topic
- **THEN** 系统仅创建一个 KafkaProducer 实例，多 target 共用

#### Scenario: 不同 broker 独立 KafkaProducer
- **WHEN** 不同规则配置不同 `bootstrap-servers`
- **THEN** 系统为每个独立指纹创建独立 KafkaProducer 实例
