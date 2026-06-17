## Why

Change 1 和 Change 2 实现了 OPC UA 数据采集和转发能力，但所有配置通过 YAML 文件静态管理，修改设备连接或转发规则必须重启服务。对于 100+ 设备规模的动态运维场景，重启带来的数据丢失窗口不可接受。Change 3 补齐 REST API 动态配置和标准化部署能力，使整个系统具备生产级运维水平。

## What Changes

- **NEW** REST API：设备连接 CRUD（增删改查）、转发规则 CRUD、系统状态查询
- **NEW** 运行时配置管理：通过 REST API 修改的配置实时反映到 Change 1 连接池和 Change 2 转发引擎，无需重启
- **NEW** 配置持久化：运行时修改持久化到配置存储（数据库或 YAML 文件）
- **NEW** Docker 部署：Dockerfile + docker compose，包含 OPC UA 服务 + Kafka + InfluxDB + Redis
- **NEW** 集群基础：Redis 共享 Session，多实例健康聚合
- **NEW**（第二期）REST API 认证：JWT 或 Basic Auth 保护管理接口

## Capabilities

### New Capabilities

- `device-api`: 设备连接 REST API（增删改查、连接状态查询）
- `forward-api`: 转发规则 REST API（增删改查、规则启停）
- `runtime-config`: 运行时配置动态热加载，API 修改实时生效
- `docker-deployment`: Docker 标准化部署（Dockerfile + docker compose）
- `cluster-support`: 集群基础支持（Redis Session 共享、健康聚合）

### Modified Capabilities

<!-- 不修改 Change 1/2 的 capability，对外暴露配置管理接口 -->

## Impact

- **依赖 Change 1 + Change 2**：通过 Spring Bean 注入动态控制 ConnectionManager 和 ForwardingEngine
- **新增依赖**：Spring Web（REST API）、Spring Session + Redis（集群）
- **部署变更**：新增 Dockerfile、docker-compose.yml
- **配置系统**：引入配置持久化层（数据库表或文件存储）
