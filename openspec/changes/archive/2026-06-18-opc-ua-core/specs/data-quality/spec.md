## ADDED Requirements

### Requirement: StatusCode 读取
系统 SHALL 在每次数据采集时读取 OPC UA StatusCode，并将其作为数据质量判断依据。

#### Scenario: 正常数据质量
- **WHEN** 采集到的数据 StatusCode 为 Good（0x00000000）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Good"` 并附带原始 `"statusCode": "0x00000000"`

#### Scenario: 不确定数据质量
- **WHEN** 采集到的数据 StatusCode 为 Uncertain（如传感器未校准）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Uncertain"` 并附带原始 statusCode

#### Scenario: 坏数据
- **WHEN** 采集到的数据 StatusCode 为 Bad（如传感器故障）
- **THEN** 系统在输出 JSON 中标记 `"quality": "Bad"` 并附带原始 statusCode

### Requirement: 质量标记开关
系统 SHALL 支持按节点配置是否进行质量检查（qualityCheck: true/false）。

#### Scenario: 质量检查开启
- **WHEN** 节点配置 `qualityCheck: true`
- **THEN** 系统除标记 quality 字段外，还在 Bad/Uncertain 时记录 WARN 级别日志

#### Scenario: 质量检查关闭
- **WHEN** 节点配置 `qualityCheck: false`
- **THEN** 系统仍标记 quality 字段，但 Bad/Uncertain 时不产生 WARN 日志

### Requirement: 质量标记数据输出
系统 SHALL 在统一 JSON 数据中始终携带 quality 和 statusCode 字段。

#### Scenario: 完整 JSON 包含质量信息
- **WHEN** 任何数据采集事件发生
- **THEN** 输出的 JSON 必须包含 `quality` 和 `statusCode` 两个字段，不可缺失
