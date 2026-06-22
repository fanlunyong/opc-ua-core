# runtime-config Specification

## Purpose
TBD - created by archiving change opc-ua-config-management. Update Purpose after archive.
## Requirements
### Requirement: 运行时配置热加载
系统 SHALL 支持 API 修改配置后实时生效，无需重启服务。

#### Scenario: 新增设备立即采集
- **WHEN** 通过 API 新增设备后
- **THEN** ConnectionManager 在 3 秒内启动该设备的连接和数据采集，不中断其他已有设备

#### Scenario: 新增规则立即转发
- **WHEN** 通过 API 新增转发规则后
- **THEN** ForwardingEngine 在 3 秒内加载新规则，匹配的数据立即按规则转发

#### Scenario: 删除设备不影响其他设备
- **WHEN** 通过 API 删除某设备
- **THEN** 仅该设备断开连接，其他设备正常运行不受影响

### Requirement: 配置持久化
系统 SHALL 在 API 修改后将配置持久化，确保服务重启后配置不丢失。

#### Scenario: 配置回写 YAML
- **WHEN** 通过 API 增删改设备或规则
- **THEN** 系统将变更持久化到 YAML 配置文件

#### Scenario: 启动恢复
- **WHEN** 服务重启
- **THEN** 系统从持久化的 YAML 文件加载上次运行时保存的完整配置

