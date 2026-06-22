# forward-api Specification

## Purpose
TBD - created by archiving change opc-ua-config-management. Update Purpose after archive.
## Requirements
### Requirement: 转发规则 CRUD
系统 SHALL 通过 REST API 提供转发规则的增删改查操作。

#### Scenario: 新增转发规则
- **WHEN** POST /api/forward/rules 携带有效规则 JSON
- **THEN** 系统持久化规则，立即加载到 ForwardingEngine，匹配的数据开始按规则转发，返回 201

#### Scenario: 修改转发规则
- **WHEN** PUT /api/forward/rules/{name} 携带更新后的规则
- **THEN** 系统更新规则，ForwardingEngine 实时使用新规则匹配，返回 200

#### Scenario: 删除转发规则
- **WHEN** DELETE /api/forward/rules/{name}
- **THEN** 系统移除规则，停止对应转发，返回 204

#### Scenario: 查询所有规则
- **WHEN** GET /api/forward/rules
- **THEN** 系统返回所有转发规则列表及每条规则的 enabled 状态

### Requirement: 规则启停
系统 SHALL 支持通过 API 单独启停转发规则。

#### Scenario: 启用规则
- **WHEN** PATCH /api/forward/rules/{name}/enable
- **THEN** 规则立即生效，匹配的数据开始转发

#### Scenario: 停用规则
- **WHEN** PATCH /api/forward/rules/{name}/disable
- **THEN** 规则立即停止，匹配的数据不再转发，但规则配置保留

