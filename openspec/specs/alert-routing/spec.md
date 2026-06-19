# alert-routing Specification

## Purpose
TBD - created by archiving change opc-ua-forward. Update Purpose after archive.
## Requirements
### Requirement: 告警路由
系统 SHALL 基于 `data[].quality` 字段，将包含 Bad/Uncertain 质量标记的设备数据路由到配置的告警目标（任意 sender 类型）。

#### Scenario: Bad 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Bad"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到主 targets 与所有 `alerts.targets`

#### Scenario: Uncertain 数据触发告警
- **WHEN** data[] 中任意一条记录的 quality 为 "Uncertain"
- **THEN** 系统将该完整 `OpcUaDeviceData` 同时推送到主 targets 与所有 `alerts.targets`

#### Scenario: Good 数据不触发告警
- **WHEN** data[] 中所有记录的 quality 均为 "Good"
- **THEN** 系统仅推送到主 targets，不推送到 `alerts.targets`

#### Scenario: 告警消息格式
- **WHEN** 触发告警推送
- **THEN** 告警目标接收的消息与主目标完全一致（同一 `OpcUaDeviceData` JSON），下游消费者通过 `data[].quality != "Good"` 定位异常点

#### Scenario: 告警支持任意 sender 类型
- **WHEN** `alerts.targets` 配置 `type: kafka`、`type: mqtt`、`type: http` 或 `type: influxdb`
- **THEN** 系统使用相同的 sender 抽象将告警 payload 发送到对应下游

### Requirement: 告警开关
系统 SHALL 支持按规则配置是否启用告警路由（`alerts.enabled`）。

#### Scenario: 告警启用
- **WHEN** 规则配置 `alerts.enabled: true`
- **THEN** Bad/Uncertain 数据推送到 `alerts.targets`

#### Scenario: 告警禁用
- **WHEN** 规则配置 `alerts.enabled: false` 或未配置告警子段
- **THEN** Bad/Uncertain 数据仅推送到主 targets，不做告警推送

