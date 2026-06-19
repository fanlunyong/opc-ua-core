# tsdb-forward Specification

## Purpose
TBD - created by archiving change opc-ua-forward. Update Purpose after archive.
## Requirements
### Requirement: InfluxDB 数据写入
系统 SHALL 将 `OpcUaDeviceData` 的 data[] 中每条数据点写入 InfluxDB。

#### Scenario: 单批次写入
- **WHEN** 收到设备数据且转发规则匹配到 InfluxDB target
- **THEN** data[] 中每条记录逐条转为 InfluxDB Point 写入指定 bucket

#### Scenario: InfluxDB Point 字段映射
- **WHEN** 写入 InfluxDB
- **THEN** Point 的 measurement 为设备 displayName，tags 包含 {productId, deviceId, nodeId, quality}，field 为 value，timestamp 为 sourceTimestamp

#### Scenario: InfluxDB 不可达
- **WHEN** InfluxDB 服务不可达
- **THEN** 系统记录 ERROR 日志，跳过当前批次，不阻塞后续数据

#### Scenario: 批量写入优化
- **WHEN** data[] 包含多条记录（如 10 条）
- **THEN** 系统通过 InfluxDB WriteApi 一次性批量提交，减少网络往返

### Requirement: InfluxDB 连接配置
系统 SHALL 支持通过 YAML 配置 InfluxDB 连接参数。

#### Scenario: InfluxDB 连接配置
- **WHEN** YAML 中配置了 InfluxDB url、bucket、org、token
- **THEN** 系统使用这些参数初始化 InfluxDB Client

