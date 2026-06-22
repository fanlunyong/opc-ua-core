## ADDED Requirements

### Requirement: Redis Session 共享
系统 SHALL 使用 Redis 共享 HTTP Session，支持多实例部署时 Session 一致性。

#### Scenario: Session 跨实例共享
- **WHEN** 多实例通过 Docker Compose 部署且共享 Redis
- **THEN** 在实例 A 创建的 Session 可在实例 B 读取

### Requirement: 集群健康聚合
系统 SHALL 提供 `/api/status` 端点返回系统级运行摘要。

#### Scenario: 系统状态查询
- **WHEN** GET /api/status
- **THEN** 响应包含：已配置设备总数、当前连接数、已配置转发规则数、各 Sender 状态（Kafka/InfluxDB/MQTT 是否连通）

#### Scenario: 健康聚合
- **WHEN** 多个设备部分断开
- **THEN** `/api/health` 返回包含各设备健康详情的聚合状态
