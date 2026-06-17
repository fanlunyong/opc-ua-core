## ADDED Requirements

### Requirement: HTTP Webhook 回调
系统 SHALL 将 `OpcUaDeviceData` JSON 通过 HTTP POST 回调配置的 Webhook URL。

#### Scenario: HTTP POST 推送
- **WHEN** 收到设备数据且转发规则匹配到 HTTP target
- **THEN** 系统向配置的 URL 发送 HTTP POST 请求，Content-Type 为 application/json，body 为 `OpcUaDeviceData` JSON

#### Scenario: HTTP 回调失败
- **WHEN** HTTP POST 返回非 2xx 状态码或连接超时
- **THEN** 系统记录 WARN 日志（含状态码），丢弃该消息，不重试

#### Scenario: HTTP 超时控制
- **WHEN** HTTP 请求超过配置的超时时间（默认 5s）
- **THEN** 系统取消请求，记录 WARN 日志
