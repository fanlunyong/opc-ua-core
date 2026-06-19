## ADDED Requirements

### Requirement: NodeId 到业务名称映射
系统 SHALL 支持将 OPC UA NodeId 映射为人类可读的业务友好名称。

#### Scenario: 单节点映射
- **WHEN** 配置 `nodeId: "ns=2;s=Temperature", displayName: "temperature"`
- **THEN** 输出的 JSON 中 `displayName` 字段为 `"temperature"`，同时保留原始 `nodeId` 为 `"ns=2;s=Temperature"`

#### Scenario: 未配置映射的节点
- **WHEN** 节点未配置 displayName
- **THEN** 系统使用 NodeId 的 Identifier 部分自动生成 displayName（如 `"Temperature"`）

### Requirement: 设备级批量 JSON 输出格式
系统 SHALL 将每次数据采集事件以设备为粒度输出，`data` 数组包含该设备本次变更的全部属性值。

#### Scenario: 设备级完整 JSON 输出
- **WHEN** 数据采集事件发生
- **THEN** 输出 JSON 顶层包含 `timestamp` 和 `source`（`productId` + `deviceId` + `endpointUrl`），`data` 为数组，数组中每条记录包含：`nodeId`, `displayName`, `value`, `dataType`, `quality`, `statusCode`, `sourceTimestamp`, `serverTimestamp`

#### Scenario: data 数组至少一个元素
- **WHEN** 采集事件发生（单条或多条变更）
- **THEN** `data` 数组始终至少包含 1 个元素

#### Scenario: 数据类型保持
- **WHEN** 采集到的值为 Double 类型
- **THEN** JSON 中 `value` 字段为数值类型 `25.5`（非字符串），`dataType` 为 `"Double"`

#### Scenario: 多节点同批次输出
- **WHEN** 同一设备同一采集批次内多个节点同时变更
- **THEN** 所有变更节点合并到同一个 `data` 数组中，形成一次设备级批量上报
