## ADDED Requirements

### Requirement: 告警路由
系统 SHALL 基于 `data[].quality` 字段，将包含 Bad/Uncertain 质量标记的设备数据路由到 Kafka 告警 topic。

#### Scenario: Bad 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Bad"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到数据 topic 和告警 topic（`opcua-alert-{productId}`）

#### Scenario: Uncertain 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Uncertain"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到数据 topic 和告警 topic

#### Scenario: Good 数据不触发告警
- **WHEN** data[] 中所有记录的 quality 均为 "Good"
- **THEN** 系统仅推送到数据 topic，不推送到告警 topic

#### Scenario: 告警消息格式
- **WHEN** 触发告警推送
- **THEN** 告警 topic 中的消息格式与数据 topic 完全一致（同一 `OpcUaDeviceData` JSON），下游消费者通过 `data[].quality != "Good"` 定位异常点

### Requirement: 告警开关
系统 SHALL 支持按规则配置是否启用告警路由（`alerts.enabled`）。

#### Scenario: 告警启用
- **WHEN** 规则配置 `alerts.enabled: true`
- **THEN** Bad/Uncertain 数据推送到告警 topic

#### Scenario: 告警禁用
- **WHEN** 规则配置 `alerts.enabled: false` 或未配置告警子段
- **THEN** Bad/Uncertain 数据仅推送到数据 topic，不做告警推送
