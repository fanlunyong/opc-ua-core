## ADDED Requirements

### Requirement: 健康检查端点
系统 SHALL 提供 `/health` 端点，通过 Spring Boot Actuator 暴露连接池和 OPC UA 设备的健康状态。

#### Scenario: 所有设备正常
- **WHEN** 所有配置的 OPC UA 设备均处于连接状态
- **THEN** `/health` 端点返回 `{"status": "UP", "components": {"opcua": {"status": "UP", "devices": {...}}}}`

#### Scenario: 部分设备异常
- **WHEN** 至少一台设备断开连接或会话失效
- **THEN** `/health` 端点返回 `{"status": "DOWN"}` 并列出异常设备及其错误原因

### Requirement: 断线自动重连
系统 SHALL 在检测到 OPC UA 连接断开后自动尝试重连，采用指数退避策略。

#### Scenario: 首次重连
- **WHEN** 设备连接意外断开
- **THEN** 系统在 1 秒后发起第一次重连尝试

#### Scenario: 连续重连失败
- **WHEN** 重连连续失败
- **THEN** 系统采用指数退避策略（1s → 2s → 4s → 8s → 16s → 32s → 60s），最大退避时间 60 秒

#### Scenario: 重连成功
- **WHEN** 重连成功建立
- **THEN** 系统重建所有订阅组和会话，重置退避计时器为初始值，更新健康状态为 UP

### Requirement: 连接状态监控
系统 SHALL 实时监控每个设备连接的运行状态并记录关键事件。

#### Scenario: 连接状态变更日志
- **WHEN** 设备连接状态发生变更（连接、断开、重连成功、重连失败）
- **THEN** 系统记录 INFO 或 ERROR 级别日志，包含设备 ID、时间戳和状态变更详情

#### Scenario: 连接指标暴露
- **WHEN** 系统运行中
- **THEN** 系统暴露每个设备的连接指标：当前连接数、重连次数、最后连接时间
