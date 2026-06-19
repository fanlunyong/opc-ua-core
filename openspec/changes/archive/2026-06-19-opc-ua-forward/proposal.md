## Why

OPC UA 设备数据采集后需要流转到多种下游系统（消息队列、数据库、消息推送、Webhook）才能产生业务价值。Change 1 解决了"怎么采"的问题，Change 2 解决"采完往哪发"的问题。同时基于数据质量标记将异常数据路由到告警通道，形成采集→分发→告警的完整数据链路。

## What Changes

- **NEW** Kafka 数据推送：将设备级 JSON 数据推送到 Kafka，支持按 productId/deviceId 路由到不同 topic
- **NEW** 时序数据库写入：将 data[] 中每条记录按时间戳写入 InfluxDB（或 TimescaleDB），支持配置 measurement 映射
- **NEW** MQTT 转发：将指定节点的数据实时推送到 MQTT broker 的指定 topic
- **NEW** HTTP Webhook：数据变更触发 HTTP POST 回调配置的 URL
- **NEW** 告警路由：基于 `quality` 字段，将 Bad/Uncertain 的数据推送到配置的告警目标（Kafka/MQTT/HTTP/InfluxDB 任一）
- **NEW** 转发规则配置：YAML 配置驱动的转发路由规则，按产品/设备/节点粒度控制数据流向

## Capabilities

### New Capabilities

- `kafka-forward`: Kafka 数据推送引擎，支持 topic 路由策略和连接池管理
- `tsdb-forward`: 时序数据库写入（InfluxDB）
- `mqtt-forward`: MQTT 协议数据转发
- `http-forward`: HTTP Webhook 回调
- `alert-routing`: 基于数据质量的告警路由，Bad/Uncertain 数据推送到配置的告警目标（任意 sender 类型）
- `forward-config`: 转发规则配置管理，支持按 productId/deviceId/节点粒度控制输出

### Modified Capabilities

<!-- 不修改 Change 1 的 capability，只消费其 DataListener 接口 -->

## Impact

- **依赖 Change 1**：通过 `OpcUaDataListener` 接口消费 `OpcUaDeviceData` 数据
- **新增依赖**：Spring Kafka、InfluxDB Client、Eclipse Paho（MQTT）、Spring Web（HTTP）
- **配置系统**：扩展 YAML 配置，新增 `forward` 转发规则配置段
- **数据模型**：引入 `ForwardRule`、`ForwardTarget` 等转发配置模型
