# data-collection Specification

## Purpose
TBD - created by archiving change opc-ua-core. Update Purpose after archive.
## Requirements
### Requirement: 订阅模式数据采集
系统 SHALL 支持基于 OPC UA Subscription 机制的数据采集，将订阅节点的数据变更推送至注册的 DataListener。

#### Scenario: 创建订阅
- **WHEN** 配置中定义了订阅组（含节点列表和采样间隔）
- **THEN** 系统在对应设备上创建 OPC UA Subscription，按指定间隔采集数据

#### Scenario: 数据变更批量推送
- **WHEN** 订阅节点数据发生变更（同一回调批次可能包含多个节点）
- **THEN** 系统将该批次所有变更节点聚合为设备级数据数组，通过 DataListener 一次性推送完整 `OpcUaDeviceData`（含 `source` 和 `data[]`）

#### Scenario: 多订阅组管理
- **WHEN** 同一设备配置了多个订阅组（如温度组、压力组），各有不同采样间隔
- **THEN** 系统独立管理每个订阅组，各自按配置间隔推送该组变更的设备级数组数据

#### Scenario: 订阅重建
- **WHEN** 设备连接断开后重新恢复
- **THEN** 系统自动重建所有订阅，确保数据采集不中断

### Requirement: 轮询模式数据采集
系统 SHALL 支持按配置的固定间隔定期读取指定节点的值。

#### Scenario: 定期轮询批量输出
- **WHEN** 配置中定义了轮询节点及其间隔
- **THEN** 系统按指定间隔发起 Read 请求，将该设备本次读取的所有轮询节点结果聚合为设备级数组，推送至 DataListener

#### Scenario: 轮询与订阅共存
- **WHEN** 同一设备同时配置了订阅组和轮询节点
- **THEN** 订阅组按变更推送，轮询节点按间隔读取，互不干扰

#### Scenario: 轮询异常处理
- **WHEN** 单次轮询读取失败（如节点不存在或权限不足）
- **THEN** 系统记录错误日志，跳过该节点继续下一轮轮询，不中断其他节点的正常轮询

### Requirement: 写入控制
系统 SHALL 支持向指定 OPC UA 节点写入值。

#### Scenario: 写入成功
- **WHEN** 调用写入接口时提供了有效的 nodeId 和新值
- **THEN** 系统向 OPC UA 服务器写入该值并返回写入成功确认

#### Scenario: 写入权限不足
- **WHEN** 写入操作因权限不足被服务器拒绝
- **THEN** 系统返回写入失败错误，包含具体原因（权限不足）

#### Scenario: 写入数据类型不匹配
- **WHEN** 写入的值类型与目标节点数据类型不匹配
- **THEN** 系统返回数据类型错误，不执行写入

