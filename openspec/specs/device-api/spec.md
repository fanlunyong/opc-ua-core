# device-api Specification

## Purpose
TBD - created by archiving change opc-ua-config-management. Update Purpose after archive.
## Requirements
### Requirement: 设备连接 CRUD
系统 SHALL 通过 REST API 提供设备连接的增删改查操作。

#### Scenario: 新增设备
- **WHEN** POST /api/devices 携带有效设备配置 JSON
- **THEN** 系统持久化配置，立即启动该设备的连接和采集，返回 201 及设备信息

#### Scenario: 删除设备
- **WHEN** DELETE /api/devices/{id}
- **THEN** 系统断开该设备连接、清除订阅、关闭会话，移除配置，返回 204

#### Scenario: 修改设备配置
- **WHEN** PUT /api/devices/{id} 携带更新后的配置
- **THEN** 系统断开旧连接，以新配置重新建立连接和订阅，返回 200 及更新后信息

#### Scenario: 查询所有设备
- **WHEN** GET /api/devices
- **THEN** 系统返回所有已配置设备的列表及当前连接状态

#### Scenario: 查询单个设备
- **WHEN** GET /api/devices/{id}
- **THEN** 系统返回该设备详细配置及当前连接状态、订阅状态

### Requirement: 设备状态查询
系统 SHALL 在设备列表中返回实时连接状态。

#### Scenario: 连接状态字段
- **WHEN** 查询设备列表或详情
- **THEN** 响应中每个设备包含 `status` 字段（CONNECTED/DISCONNECTED/RECONNECTING）及 `lastConnectedAt` 时间戳

